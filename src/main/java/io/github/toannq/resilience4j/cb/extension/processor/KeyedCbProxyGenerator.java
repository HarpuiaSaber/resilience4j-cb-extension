package io.github.toannq.resilience4j.cb.extension.processor;

import com.squareup.javapoet.*;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

class KeyedCbProxyGenerator {
  private static final ClassName SPRING_COMPONENT = ClassName.get("org.springframework.stereotype", "Component");
  private static final ClassName SPRING_PRIMARY = ClassName.get("org.springframework.context.annotation", "Primary");
  private static final ClassName SPRING_QUALIFIER = ClassName.get("org.springframework.beans.factory.annotation", "Qualifier");
  private static final ClassName CB_EXECUTOR = ClassName.get("io.github.toannq.resilience4j.cb.extension.executor", "KeyedCircuitBreakerExecutor");
  private static final String DELEGATE_FIELD = "delegate";
  private static final String EXECUTOR_FIELD = "circuitBreakerExecutor";
  private final ProcessingEnvironment processingEnv;

  KeyedCbProxyGenerator(ProcessingEnvironment processingEnv) {
    this.processingEnv = processingEnv;
  }

  void generate(TypeElement typeEl, List<CircuitBreakerMethodSpec> specs, ExecutableElement superConstructor) {
    var packageName = processingEnv.getElementUtils().getPackageOf(typeEl).getQualifiedName().toString();
    var proxyClassSpec = buildProxyClass(typeEl, specs, superConstructor);
    writeFile(packageName, proxyClassSpec);
  }

  private TypeSpec buildProxyClass(TypeElement typeEl, List<CircuitBreakerMethodSpec> specs, ExecutableElement superConstructor) {
    var cbSignatures = new HashSet<String>();
    specs.forEach(s -> {
      cbSignatures.add(signature(s.method()));
      if (s.hasKeyResolver()) cbSignatures.add(signature(s.keyResolver()));
      if (s.hasFallback()) cbSignatures.add(signature(s.fallback()));
    });
    var allMethods = new ArrayList<MethodSpec>();
    specs.forEach(cbMethodSpec -> allMethods.add(buildCbMethod(cbMethodSpec)));
    typeEl.getEnclosedElements().stream()
        .filter(ExecutableElement.class::isInstance)
        .map(e -> (ExecutableElement) e)
        .filter(e -> e.getKind() == ElementKind.METHOD)
        .filter(e -> !cbSignatures.contains(signature(e)))
        .filter(e -> !e.getModifiers().contains(Modifier.STATIC))
        .filter(e -> !e.getModifiers().contains(Modifier.PRIVATE))
        .map(this::buildPassthrough)
        .forEach(allMethods::add);
    var delegateType = TypeName.get(typeEl.asType());
    var simpleName = typeEl.getSimpleName().toString();
    var proxyName = simpleName + "KeyedCbProxy";
    var beanName = Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    var builder = TypeSpec.classBuilder(proxyName)
        .addModifiers(Modifier.PUBLIC)
        .addAnnotation(SPRING_COMPONENT)
        .addAnnotation(SPRING_PRIMARY)
        .superclass(delegateType)
        .addField(delegateType, DELEGATE_FIELD, Modifier.PRIVATE, Modifier.FINAL)
        .addField(CB_EXECUTOR, EXECUTOR_FIELD, Modifier.PRIVATE, Modifier.FINAL)
        .addMethod(buildConstructor(delegateType, beanName, superConstructor));
    typeEl.getTypeParameters().stream().map(TypeVariableName::get).forEach(builder::addTypeVariable);
    allMethods.forEach(builder::addMethod);
    return builder.build();
  }

  private MethodSpec buildCbMethod(CircuitBreakerMethodSpec spec) {
    var method = spec.method();
    var returnType = TypeName.get(method.getReturnType());
    var delegateArgs = spec.params().stream()
        .map(p -> p.getSimpleName().toString())
        .collect(Collectors.joining(", "));
    var builder = MethodSpec.methodBuilder(method.getSimpleName().toString())
        .addAnnotation(Override.class)
        .addModifiers(Modifier.PUBLIC)
        .returns(returnType);
    method.getTypeParameters().forEach(tp -> builder.addTypeVariable(TypeVariableName.get(tp)));
    spec.params().forEach(p -> builder.addParameter(TypeName.get(p.asType()), p.getSimpleName().toString()));
    CodeBlock keyArg;
    if (spec.hasKeyResolver()) {
      builder.addStatement("var cbKey = this.$L.$L($L)", DELEGATE_FIELD, spec.keyResolver().getSimpleName(), delegateArgs);
      keyArg = CodeBlock.of("cbKey");
    } else {
      keyArg = CodeBlock.of("null");
    }
    var supplierCode = CodeBlock.of("() -> this.$L.$L($L)", DELEGATE_FIELD, method.getSimpleName(), delegateArgs);
    CodeBlock fallbackCode;
    if (spec.hasFallback()) {
      var fbParams = spec.fallback().getParameters();
      var fbArgs = fbParams.subList(0, fbParams.size() - 1).stream()
          .map(p -> p.getSimpleName().toString())
          .collect(Collectors.joining(", "));
      var exParam = fbParams.getLast().getSimpleName().toString();
      if (fbArgs.isEmpty())
        fallbackCode = CodeBlock.of("$L -> this.$L.$L($L)", exParam, DELEGATE_FIELD, spec.fallback().getSimpleName(), exParam);
      else
        fallbackCode = CodeBlock.of("$L -> this.$L.$L($L, $L)", exParam, DELEGATE_FIELD, spec.fallback().getSimpleName(), fbArgs, exParam);
    } else {
      fallbackCode = CodeBlock.of("null");
    }
    builder.addStatement("return this.$L.execute($S, $S, $L, $L, $L)",
        EXECUTOR_FIELD, spec.baseName(), spec.configName(), keyArg, supplierCode, fallbackCode);
    return builder.build();
  }

  private MethodSpec buildPassthrough(ExecutableElement exe) {
    var returnType = TypeName.get(exe.getReturnType());
    var args = exe.getParameters().stream()
        .map(p -> p.getSimpleName().toString())
        .collect(Collectors.joining(", "));
    var builder = MethodSpec.methodBuilder(exe.getSimpleName().toString())
        .addAnnotation(Override.class)
        .addModifiers(Modifier.PUBLIC)
        .returns(returnType);
    exe.getTypeParameters().forEach(tp -> builder.addTypeVariable(TypeVariableName.get(tp)));
    exe.getParameters().forEach(p -> builder.addParameter(TypeName.get(p.asType()), p.getSimpleName().toString()));
    exe.getThrownTypes().forEach(t -> builder.addException(TypeName.get(t)));
    if (returnType.equals(TypeName.VOID)) {
      builder.addStatement("this.$L.$L($L)", DELEGATE_FIELD, exe.getSimpleName(), args);
    } else {
      builder.addStatement("return this.$L.$L($L)", DELEGATE_FIELD, exe.getSimpleName(), args);
    }
    return builder.build();
  }

  private MethodSpec buildConstructor(TypeName delegateType, String beanName, ExecutableElement superConstructor) {
    var constructorBuilder = MethodSpec.constructorBuilder()
        .addModifiers(Modifier.PUBLIC)
        .addParameter(ParameterSpec.builder(delegateType, DELEGATE_FIELD)
            .addAnnotation(AnnotationSpec.builder(SPRING_QUALIFIER)
                .addMember("value", "$S", beanName).build())
            .build())
        .addParameter(CB_EXECUTOR, EXECUTOR_FIELD);
    if (superConstructor != null) {
      var nullArgs = superConstructor.getParameters().stream()
          .map(p -> "null")
          .collect(Collectors.joining(", "));
      constructorBuilder.addStatement("super($L)", nullArgs);
    }
    constructorBuilder.addStatement("this.$L = $L", DELEGATE_FIELD, DELEGATE_FIELD)
        .addStatement("this.$L = $L", EXECUTOR_FIELD, EXECUTOR_FIELD);
    return constructorBuilder.build();
  }

  private static String signature(ExecutableElement e) {
    return e.getSimpleName() + "("
        + e.getParameters().stream()
        .map(v -> v.asType().toString())
        .collect(Collectors.joining(","))
        + ")";
  }

  private void writeFile(String packageName, TypeSpec spec) {
    try {
      JavaFile.builder(packageName, spec)
          .addFileComment("Auto-generated by keyed-circuit-breaker — DO NOT EDIT.")
          .skipJavaLangImports(true)
          .build()
          .writeTo(processingEnv.getFiler());
    } catch (IOException e) {
      processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed to write generated file: " + e.getMessage());
    }
  }
}