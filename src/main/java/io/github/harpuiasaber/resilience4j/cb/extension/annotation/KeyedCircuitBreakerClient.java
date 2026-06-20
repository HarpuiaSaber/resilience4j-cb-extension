package io.github.harpuiasaber.resilience4j.cb.extension.annotation;

import java.lang.annotation.*;

/**
 * Marks an interface for compile-time keyed circuit breaker proxy generation.
 *
 * <p>The annotation processor scans the target interface for methods annotated with
 * {@link KeyedCircuitBreaker} and generates a proxy implementation in the same package
 * named {@code <InterfaceName>KeyedCbProxy}.
 *
 * <p>The generated proxy <b>implements</b> the annotated interface and delegates all method
 * calls to the concrete implementation bean specified by {@link #delegate()}. The delegate
 * bean is identified at runtime via Spring {@code @Qualifier} using the lower-camel-case
 * simple name of the specified class.
 *
 * <p>The generated proxy is registered as a Spring {@code @Component} and {@code @Primary}
 * bean so existing injection points for the interface continue to work transparently,
 * receiving the proxy instead of the original implementation.
 *
 * <p>{@link #delegate()} is mandatory — the processor reports a compile-time error if omitted.
 *
 * <h3>Example</h3>
 * <pre>{@code
 * @KeyedCircuitBreakerClient(delegate = ExampleServiceImpl.class)
 * public interface ExampleService {
 *
 *     @KeyedCircuitBreaker(name = "example", keyResolverMethod = "resolveKey", fallbackMethod = "fallback")
 *     CompletableFuture<String> call(String input);
 *
 *     // Key resolver: same params, returns String.
 *     // Can be a default method so implementations are not forced to override.
 *     default String resolveKey(String input) { return input; }
 *
 *     // Fallback: same params + CallNotPermittedException, returns CompletableFuture<String>.
 *     // Can be a default method so implementations are not forced to override.
 *     default CompletableFuture<String> fallback(String input, CallNotPermittedException e) {
 *         return CompletableFuture.failedFuture(e);
 *     }
 *
 *     // Not annotated — forwarded transparently to the delegate
 *     CompletableFuture<Boolean> check(String input);
 * }
 *
 * @Service
 * public class ExampleServiceImpl implements ExampleService {
 *
 *     @Override
 *     public CompletableFuture<String> call(String input) {
 *         return CompletableFuture.completedFuture("ok");
 *     }
 *
 *     @Override
 *     public CompletableFuture<Boolean> check(String input) {
 *         return CompletableFuture.completedFuture(true);
 *     }
 *
 *     // resolveKey and fallback are inherited from the interface default —
 *     // override only when custom behaviour is needed.
 * }
 * }</pre>
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface KeyedCircuitBreakerClient {
  /**
   * The concrete implementation class to use as the delegate at runtime. Mandatory —
   * the processor reports a compile-time error if not specified.
   *
   * <p>The processor derives the Spring {@code @Qualifier} bean name from this class's
   * simple name (lower-camel-case) and injects it into the generated proxy constructor.
   */
  Class<?> delegate() default Void.class;
}