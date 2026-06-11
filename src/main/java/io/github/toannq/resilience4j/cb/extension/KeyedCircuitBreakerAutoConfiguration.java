package io.github.toannq.resilience4j.cb.extension;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.toannq.resilience4j.cb.extension.executor.KeyedCircuitBreakerExecutor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration.
 * Registers {@link KeyedCircuitBreakerExecutor} when resilience4j is on the classpath
 * and no custom executor bean has been defined.
 *
 * <p>Note: Spring Boot detects auto-configurations via the
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * resource when this project is packaged. The resource in this repo should list this
 * class (verify if you change the auto-config class name).
 */
@AutoConfiguration
@ConditionalOnClass(CircuitBreakerRegistry.class)
@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
public class KeyedCircuitBreakerAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public KeyedCircuitBreakerExecutor keyedCircuitBreakerExecutor(CircuitBreakerRegistry registry) {
    return new KeyedCircuitBreakerExecutor(registry);
  }
}
