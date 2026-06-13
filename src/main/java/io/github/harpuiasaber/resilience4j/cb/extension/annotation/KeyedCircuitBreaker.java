package io.github.harpuiasaber.resilience4j.cb.extension.annotation;

import io.github.harpuiasaber.resilience4j.cb.extension.executor.KeyedCircuitBreakerExecutor;

import java.lang.annotation.*;

/**
 * Marks a {@link java.util.concurrent.CompletableFuture}-returning method for
 * per-key circuit breaker protection handled by the generated proxy.
 *
 * <p>Must be used on a method inside a type annotated with {@link KeyedCircuitBreakerClient}.
 * The annotation processor validates the method and generates a proxy that wraps
 * the annotated method invocation with {@link KeyedCircuitBreakerExecutor}.
 *
 * <h3>How keyed CBs work (implementation notes)</h3>
 * <p>Each unique resolved key yields a distinct {@link io.github.resilience4j.circuitbreaker.CircuitBreaker}
 * instance. The generated proxy computes the instance name as {@code "<name>:<resolvedKey>"} when a key
 * resolver is provided, otherwise the base {@code name} is used as the instance name.
 * This behavior is implemented by {@code KeyedCircuitBreakerExecutor.resolveInstanceName(...)}.
 *
 * <h3>keyResolverMethod contract</h3>
 * <p>Optional. If provided, the method must be declared on the same class with:
 * <ul>
 *   <li>Return type: {@link String}</li>
 *   <li>Parameters: exactly the same as the annotated method</li>
 * </ul>
 * The annotation processor enforces this at compile time.
 *
 * <h3>fallbackMethod contract</h3>
 * <p>Optional. If provided, the method must be declared on the same class with:
 * <ul>
 *   <li>Return type: {@code CompletableFuture<T>} — same {@code T} as the annotated method</li>
 *   <li>Parameters: same as the annotated method, followed by a final
 *       {@link io.github.resilience4j.circuitbreaker.CallNotPermittedException} parameter</li>
 * </ul>
 * The generated proxy will invoke the fallback only when the circuit is OPEN. If omitted,
 * the executor returns a failed {@code CompletableFuture} with {@code CallNotPermittedException}.
 *
 * <h3>Examples and generated shape</h3>
 * <p>For an annotated class {@code ExampleClient}, the processor generates a class
 * {@code ExampleClientKeyedCbProxy} in the same package. The proxy:
 * <ul>
 *   <li>extends the original class;</li>
 *   <li>is annotated {@code @Component} and {@code @Primary};</li>
 *   <li>has a public constructor that takes two parameters: the original bean (qualified by
 *       the original bean name) and a {@link KeyedCircuitBreakerExecutor}.</li>
 * </ul>
 * See {@code KeyedCbProxyGenerator.buildCbMethod} and {@code KeyedCbProxyGenerator.buildConstructor} for details.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface KeyedCircuitBreaker {

  /**
   * Base circuit breaker name.
   * Combined with the resolved key: {@code name + ":" + resolvedKey}.
   * Without a key resolver this is used as-is.
   */
  String name();

  /**
   * Circuit breaker config name passed to
   * {@code CircuitBreakerRegistry.circuitBreaker(instanceName, configName)}.
   *
   * <p>Defaults to {@code ""} which means <em>use the CB instance name as the config name</em>
   * (i.e. config is defined inline with the same name). Set explicitly only when the config
   * name differs from the instance name.
   */
  String configName() default "";

  /**
   * Optional method name on the same type that computes the CB instance key at runtime.
   * Signature must be: {@code String methodName(<same params as annotated method>)}.
   * If empty, the circuit breaker uses {@link #name()} directly (single shared instance).
   */
  String keyResolverMethod() default "";

  /**
   * Optional fallback method name on the same type.
   * Signature must be: {@code CompletableFuture<T> methodName(<same params>, io.github.resilience4j.circuitbreaker.CallNotPermittedException e)}.
   * Called only when the circuit is OPEN. If empty, the exception propagates to the caller.
   */
  String fallbackMethod() default "";
}
