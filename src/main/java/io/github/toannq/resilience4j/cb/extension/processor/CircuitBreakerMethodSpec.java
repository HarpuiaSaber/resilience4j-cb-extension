package io.github.toannq.resilience4j.cb.extension.processor;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import java.util.List;

record CircuitBreakerMethodSpec(ExecutableElement method,
                                String baseName,
                                String configName,
                                ExecutableElement keyResolver,
                                ExecutableElement fallback,
                                List<? extends VariableElement> params) {
  boolean hasKeyResolver() {
    return keyResolver != null;
  }

  boolean hasFallback() {
    return fallback != null;
  }
}
