package io.github.harpuiasaber.resilience4j.cb.extension.processor;

import com.google.auto.service.AutoService;
import io.github.harpuiasaber.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
import io.github.harpuiasaber.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
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
  private static final List<String> SPRING_BEAN_ANNOTATIONS = List.of(
      "org.springframework.stereotype.Component",
      "org.springframework.stereotype.Service",
      "org.springframework.stereotype.Repository",
      "org.springframework.stereotype.Controller",
      "org.springframework.web.bind.annotation.RestController"
  );

  private Types typeUtils;
  private TypeMirror stringType;
  private TypeMirror callNotPermittedExceptionType;
  private TypeMirror completableFutureType;

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    this.typeUtils = processingEnv.getTypeUtils();
    var elementUtils = processingEnv.getElementUtils();
    this.stringType = elementUtils.getTypeElement("java.lang.String").asType();
    this.callNotPermittedExceptionType = elementUtils.getTypeElement("io.github.resilience4j.circuitbreaker.CallNotPermittedException").asType();
    this.completableFutureType = typeUtils.erasure(elementUtils.getTypeElement("java.util.concurrent.CompletableFuture").asType());
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
      warn(typeElement, "@KeyedCircuitBreakerClient on '%s' has no @KeyedCircuitBreaker methods — no code will be generated", typeElement.getSimpleName());
      return;
    }

    var superConstructor = resolveSuperConstructor(typeElement);
    new KeyedCbProxyGenerator(processingEnv).generate(typeElement, circuitBreakerSpecs, superConstructor);
  }

  private boolean validateType(TypeElement typeElement) {
    if (typeElement.getKind() != ElementKind.CLASS) {
      error(typeElement, "@KeyedCircuitBreakerClient can only be placed on a concrete class, not on '%s'. "
          + "Interfaces are not supported — annotate the implementing class instead.", typeElement.getSimpleName());
      return false;
    }
    var isValid = true;
    if (typeElement.getModifiers().contains(Modifier.FINAL)) {
      error(typeElement, "@KeyedCircuitBreakerClient cannot be placed on a final class '%s'. "
          + "The generated proxy must extend it.", typeElement.getSimpleName());
      isValid = false;
    }
    var annotationNames = typeElement.getAnnotationMirrors().stream()
        .map(am -> am.getAnnotationType().asElement().toString())
        .collect(Collectors.toSet());
    if (SPRING_BEAN_ANNOTATIONS.stream().noneMatch(annotationNames::contains)) {
      error(typeElement, "@KeyedCircuitBreakerClient on '%s' requires the class to be a Spring bean "
          + "(@Component, @Service, @Repository, @Controller, or @RestController).", typeElement.getSimpleName());
      isValid = false;
    }
    if (annotationNames.contains("org.springframework.context.annotation.Primary")) {
      error(typeElement, "@KeyedCircuitBreakerClient cannot be placed on a @Primary class '%s'. "
              + "The generated proxy is already @Primary — having two @Primary beans of the same type will cause a startup conflict.",
          typeElement.getSimpleName());
      isValid = false;
    }
    return isValid;
  }

  private ExecutableElement resolveSuperConstructor(TypeElement typeElement) {
    var constructors = ElementFilter.constructorsIn(typeElement.getEnclosedElements());
    var hasNoArg = constructors.stream().anyMatch(c -> c.getParameters().isEmpty());
    if (hasNoArg) return null;
    return constructors.stream()
        .filter(c -> !c.getModifiers().contains(Modifier.PRIVATE))
        .findFirst()
        .orElse(null);
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
      if (isAssignable(candidate.getReturnType(), stringType) && hasSameParams(candidate.getParameters(), originalParams)) {
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
      if (!isAssignable(candidate.getReturnType(), completableFutureType)) continue;
      var params = candidate.getParameters();
      if (params.size() != originalParams.size() + 1) continue;
      if (isAssignable(params.getLast().asType(), callNotPermittedExceptionType) && hasSameParams(params.subList(0, params.size() - 1), originalParams)) {
        return candidate;
      }
    }
    error(original, "Could not find fallback '%s' on '%s'. Expected: %s %s(%s, io.github.resilience4j.circuitbreaker.CallNotPermittedException e)",
        fallbackName, original.getEnclosingElement().getSimpleName(), original.getReturnType(), fallbackName, formatParams(originalParams));
    return null;
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