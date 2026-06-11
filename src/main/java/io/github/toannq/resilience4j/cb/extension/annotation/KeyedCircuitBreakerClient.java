package io.github.toannq.resilience4j.cb.extension.annotation;

import io.github.toannq.resilience4j.cb.extension.executor.KeyedCircuitBreakerExecutor;

import java.lang.annotation.*;

/**
 * Marks a class for compile-time keyed circuit breaker proxy generation.
 *
 * <p>The annotation processor scans the target class for methods annotated with
 * {@link KeyedCircuitBreaker} and generates a proxy subclass in the same package:
 *
 * <ul>
 *   <li>{@code <ClassName>KeyedCbProxy} – extends the target class and overrides
 *       methods annotated with {@link KeyedCircuitBreaker}. Annotated methods are
 *       wrapped using {@link KeyedCircuitBreakerExecutor}, while all other methods
 *       retain the original behavior. Contract methods referenced by {@link KeyedCircuitBreaker}
 *       are inherited directly from the target class and are not overridden in the generated proxy.</li>
 * </ul>
 *
 * <p>The generated proxy is registered as a Spring {@code @Component} and {@code @Primary}
 * bean so existing injection points continue to work transparently for both concrete class and interface
 * injection.
 *
 * <p>To satisfy Java inheritance rules the generator mirrors available constructor shape by emitting
 * a public constructor on the proxy that accepts two parameters: the original bean (qualified by its bean name)
 * and a {@link KeyedCircuitBreakerExecutor} instance. If the target class has no no-arg constructor, the
 * generated constructor will invoke the superclass constructor with {@code null} placeholders for parameters
 * (see {@code KeyedCbProxyGenerator.buildConstructor}).
 *
 * <p><b>Limitations:</b>
 * <ul>
 *   <li>The target class must not be {@code final}.</li>
 *   <li>Annotated methods must not be {@code final} or {@code private}.</li>
 *   <li>Dependencies declared only through constructor injection in the target class
 *       are not injected into generated proxy instances — the proxy receives the original bean
 *       instance as a delegate instead.</li>
 *   <li>The annotation processor rejects target classes that are already annotated with
 *       {@code @Primary}. The generator marks the proxy {@code @Primary} and placing
 *       {@code @KeyedCircuitBreakerClient} on a {@code @Primary} class would conflict.</li>
 * </ul>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * @Service
 * @KeyedCircuitBreakerClient
 * public class ExampleClient {
 *
 *     private final Dependency dependency;
 *
 *     public ExampleClient(Dependency dependency) {
 *         this.dependency = dependency;
 *     }
 *
 *     @KeyedCircuitBreaker(name = "example", keyResolverMethod = "resolveKey")
 *     public CompletableFuture<String> call(String input) {
 *         return CompletableFuture.completedFuture("ok");
 *     }
 *
 *     // Key resolver: same params, returns String
 *     public String resolveKey(String input) {
 *         return input;
 *     }
 *
 *     // Not annotated — keeps original behavior
 *     public CompletableFuture<Boolean> check(String input) {
 *         return CompletableFuture.completedFuture(true);
 *     }
 * }
 * }</pre>
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface KeyedCircuitBreakerClient {
}
