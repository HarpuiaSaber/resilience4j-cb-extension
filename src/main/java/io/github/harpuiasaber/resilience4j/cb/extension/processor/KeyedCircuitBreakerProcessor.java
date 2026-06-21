package io.github.harpuiasaber.resilience4j.cb.extension.processor;

import com.google.auto.service.AutoService;
import io.github.harpuiasaber.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
import io.github.harpuiasaber.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

@AutoService(Processor.class)
@SupportedAnnotationTypes("io.github.harpuiasaber.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class KeyedCircuitBreakerProcessor extends AbstractProcessor {

  private Types typeUtils;
  private TypeMirror stringType;
  private TypeMirror callNotPermittedExceptionType;
  private TypeMirror completableFutureType;
  private TypeMirror voidType;

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    this.typeUtils = processingEnv.getTypeUtils();
    var elementUtils = processingEnv.getElementUtils();
    this.stringType = elementUtils.getTypeElement("java.lang.String").asType();
    this.callNotPermittedExceptionType = elementUtils.getTypeElement("io.github.resilience4j.circuitbreaker.CallNotPermittedException").asType();
    this.completableFutureType = typeUtils.erasure(elementUtils.getTypeElement("java.util.concurrent.CompletableFuture").asType());
    this.voidType = elementUtils.getTypeElement("java.lang.Void").asType();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    if (roundEnv.processingOver()) return false;
    for (var element : roundEnv.getElementsAnnotatedWith(KeyedCircuitBreakerClient.class)) {
      if (element instanceof TypeElement typeElement) {
        process(typeElement);
      } else {
        error(element, "@KeyedCircuitBreakerClient can only be placed on a type");
      }
    }
    return true;
  }

  private void process(TypeElement typeElement) {
    if (!validateType(typeElement)) return;
    var delegateTypeEl = getDelegateTypeElement(typeElement);
    if (delegateTypeEl == null) return;
    var methodsByName = ElementFilter.methodsIn(typeElement.getEnclosedElements())
        .stream()
        .collect(Collectors.groupingBy(m -> m.getSimpleName().toString()));
    var circuitBreakerSpecs = new ArrayList<CircuitBreakerMethodSpec>();
    var hasError = false;
    for (var enclosed : typeElement.getEnclosedElements()) {
      if (!(enclosed instanceof ExecutableElement method) || method.getKind() != ElementKind.METHOD) continue;
      var annotation = method.getAnnotation(KeyedCircuitBreaker.class);
      if (annotation == null) continue;
      if (!isAssignable(method.getReturnType(), completableFutureType)) {
        error(method, "@KeyedCircuitBreaker requires CompletableFuture<T> return type, but '%s' returns '%s'",
            method.getSimpleName(), method.getReturnType());
        hasError = true;
        continue;
      }
      var keyResolverMethod = annotation.keyResolverMethod();
      ExecutableElement keyResolverEl = null;
      if (isNonEmpty(keyResolverMethod) && (keyResolverEl = resolveOptionalMethod(keyResolverMethod, method, methodsByName,
          (original, candidates) -> resolveKeyResolver(original, keyResolverMethod, candidates))) == null) {
        hasError = true;
        continue;
      }
      var fallbackMethod = annotation.fallbackMethod();
      ExecutableElement fallbackEl = null;
      if (isNonEmpty(fallbackMethod) && (fallbackEl = resolveOptionalMethod(fallbackMethod, method, methodsByName,
          (original, candidates) -> resolveFallback(original, fallbackMethod, candidates))) == null) {
        hasError = true;
        continue;
      }
      circuitBreakerSpecs.add(new CircuitBreakerMethodSpec(method, annotation.name(), annotation.configName(), keyResolverEl, fallbackEl, method.getParameters()));
    }
    if (hasError) return;
    if (circuitBreakerSpecs.isEmpty()) {
      warn(typeElement, "@KeyedCircuitBreakerClient on '%s' has no @KeyedCircuitBreaker methods — no code will be generated",
          typeElement.getSimpleName());
      return;
    }
    new KeyedCbProxyGenerator(processingEnv).generate(typeElement, circuitBreakerSpecs, delegateTypeEl);
  }

  private boolean validateType(TypeElement typeElement) {
    if (typeElement.getKind() != ElementKind.INTERFACE) {
      error(typeElement, "@KeyedCircuitBreakerClient can only be placed on an interface, not on '%s'.", typeElement.getSimpleName());
      return false;
    }
    return true;
  }

  private TypeElement getDelegateTypeElement(TypeElement typeElement) {
    var keyedCircuitBreakerClientAnnotation = typeElement.getAnnotation(KeyedCircuitBreakerClient.class);
    try {
      var delegateClass = keyedCircuitBreakerClientAnnotation.delegate();
      if (delegateClass != Void.class) {
        var delegateTypeEl = processingEnv.getElementUtils().getTypeElement(delegateClass.getCanonicalName());
        if (delegateTypeEl == null) {
          error(typeElement, "Delegate class '%s' specified in @KeyedCircuitBreakerClient on '%s' not found.",
              delegateClass.getCanonicalName(), typeElement.getSimpleName());
          return null;
        }
        return delegateTypeEl;
      }
    } catch (MirroredTypeException mte) {
      var delegateTypeMirror = mte.getTypeMirror();
      if (isAssignable(delegateTypeMirror, voidType)) {
        error(typeElement, "@KeyedCircuitBreakerClient on interface '%s' requires either an explicit delegate or at least one concrete Spring bean implementing it.",
            typeElement.getSimpleName());
        return null;
      }
      if (isAssignable(delegateTypeMirror, typeElement.asType())) {
        return (TypeElement) typeUtils.asElement(delegateTypeMirror);
      }
      error(typeElement, "Delegate type '%s' specified in @KeyedCircuitBreakerClient on '%s' is not assignable to the interface.",
          delegateTypeMirror.toString(), typeElement.getSimpleName());
      return null;
    }
    error(typeElement, "@KeyedCircuitBreakerClient on interface '%s' requires either an explicit delegate or at least one concrete Spring bean implementing it.",
        typeElement.getSimpleName());
    return null;
  }

  private ExecutableElement resolveOptionalMethod(String methodName, ExecutableElement original,
                                                  Map<String, List<ExecutableElement>> methodsByName,
                                                  BiFunction<ExecutableElement, List<ExecutableElement>, ExecutableElement> resolver) {
    if (!isNonEmpty(methodName)) return null;
    return resolver.apply(original, methodsByName.getOrDefault(methodName, List.of()));
  }

  private ExecutableElement resolveKeyResolver(ExecutableElement original, String resolverName, List<ExecutableElement> candidates) {
    var originalParams = original.getParameters();
    for (var candidate : candidates) {
      if (resolverName.equals(candidate.getSimpleName().toString())
          && isAssignable(candidate.getReturnType(), stringType)
          && hasSameParams(candidate.getParameters(), originalParams)) {
        if (candidate.getModifiers().contains(Modifier.STATIC)) {
          error(original, "Key resolver '%s' on '%s' is static, but must be non-static",
              resolverName, original.getEnclosingElement().getSimpleName());
          return null;
        }
        return candidate;
      }
    }
    error(original, "Could not find key resolver '%s' on '%s'. Expected: java.lang.String %s(%s)",
        resolverName, original.getEnclosingElement().getSimpleName(), resolverName, formatParams(originalParams));
    return null;
  }

  private ExecutableElement resolveFallback(ExecutableElement original, String fallbackName, List<ExecutableElement> candidates) {
    var originalParams = original.getParameters();
    for (var candidate : candidates) {
      if (!isValidFallbackCandidate(candidate, fallbackName, originalParams)) {
        continue;
      }
      if (candidate.getModifiers().contains(Modifier.STATIC)) {
        error(original, "Fallback '%s' on '%s' is static, but must be non-static",fallbackName, original.getEnclosingElement().getSimpleName());
        return null;
      }
      return candidate;
    }
    error(original, "Could not find fallback '%s' on '%s'. Expected: %s %s(%s, io.github.resilience4j.circuitbreaker.CallNotPermittedException e)",
        fallbackName, original.getEnclosingElement().getSimpleName(), original.getReturnType(), fallbackName, formatParams(originalParams));
    return null;
  }

  private boolean isValidFallbackCandidate(ExecutableElement candidate, String fallbackName, List<? extends VariableElement> originalParams) {
    if (!fallbackName.equals(candidate.getSimpleName().toString())) {
      return false;
    }
    if (!isAssignable(candidate.getReturnType(), completableFutureType)) {
      return false;
    }
    var params = candidate.getParameters();
    return params.size() == originalParams.size() + 1
        && isAssignable(params.getLast().asType(), callNotPermittedExceptionType)
        && hasSameParams(params.subList(0, params.size() - 1), originalParams);
  }

  private boolean isAssignable(TypeMirror t1, TypeMirror erasedType) {
    return typeUtils.isAssignable(typeUtils.erasure(t1), erasedType);
  }

  private boolean isNonEmpty(String s) {
    return s != null && !s.isEmpty();
  }

  private boolean hasSameParams(List<? extends VariableElement> p1, List<? extends VariableElement> p2) {
    if (p1.size() != p2.size()) return false;
    for (var i = 0; i < p1.size(); i++) {
      if (!typeUtils.isSameType(p1.get(i).asType(), p2.get(i).asType())) return false;
    }
    return true;
  }

  private String formatParams(List<? extends VariableElement> params) {
    return params.stream()
        .map(p -> p.asType() + " " + p.getSimpleName())
        .collect(Collectors.joining(", "));
  }

  private void error(Element el, String msg, Object... args) {
    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, String.format(msg, args), el);
  }

  private void warn(Element el, String msg, Object... args) {
    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, String.format(msg, args), el);
  }
}