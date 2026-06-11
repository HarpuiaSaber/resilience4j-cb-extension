package io.github.toannq.resilience4j.cb.extension.executor;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.ConfigurationNotFoundException;
import io.github.toannq.resilience4j.cb.extension.exception.CircuitBreakerInternalException;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Runtime executor at the heart of keyed-circuit-breaker.
 *
 * <h2>Per-key CB isolation</h2>
 * Each call resolves a CB name as {@code "<baseName>:<key>"} (or just{@code "<baseName>"} if no key).
 * Since {@link CircuitBreakerRegistry} caches instances by name, each key gets an isolated CB state automatically.
 *
 * <h2>Bug fix: future always completes</h2>
 * <p>Vanilla resilience4j {@code decorateCompletionStage} calls {@code onError()} inside
 * {@code whenComplete} with no try/catch. If the configured exception predicate throws unexpectedly,
 * the downstream future is never completed. This executor wraps {@code onError()} in
 * try/catch and completes the future with {@link CircuitBreakerInternalException} instead.
 *
 * <h2>Design decisions</h2>
 * <ul>
 *   <li>Fallback is invoked only when circuit is OPEN
 *       ({@link CallNotPermittedException}). If absent, the exception is propagated.</li>
 *   <li>All business failures must be thrown as exceptions (no {@code onResult()}).</li>
 *   <li>Only {@link Exception} is recorded; {@link Error}s bypass CB state.</li>
 * </ul>
 */
public class KeyedCircuitBreakerExecutor {

  private final CircuitBreakerRegistry registry;

  public KeyedCircuitBreakerExecutor(CircuitBreakerRegistry registry) {
    this.registry = registry;
  }

  /**
   * Adapted from Resilience4j (Apache License 2.0).
   * <p>
   * Modified to address specific circuit breaker behavior.
   * <p>
   * Executes an async stage under per-key circuit breaker protection.
   *
   * @param baseName       base CB name (e.g. {@code "payment"})
   * @param configName     CB config name; blank/null = fallback to instanceName as config
   *                       (i.e. config defined inline with the same name as the CB instance)
   * @param key            runtime-resolved key; {@code null} or empty = no key suffix
   * @param executionStage async operation; must throw for all business-level failures
   * @param fallback       called only when circuit is OPEN; {@code null} = propagate directly
   * @param <T>            result type
   * @return a {@link CompletableFuture} that is <em>always</em> completed
   */
  public <T> CompletableFuture<T> execute(String baseName, String configName, String key,
                                          Supplier<CompletableFuture<T>> executionStage,
                                          Function<CallNotPermittedException, CompletableFuture<T>> fallback) {
    var resolveInstanceName = resolveInstanceName(baseName, key);
    var resolvedConfig = resolveConfig(configName, resolveInstanceName);
    var cb = registry.circuitBreaker(resolveInstanceName, resolvedConfig);
    // If circuit is OPEN (no permission):
    // - Previously: fail the future immediately with CallNotPermittedException
    // - Now: if fallback is provided, execute fallback instead of failing fast
    // This allows graceful degradation when the circuit breaker is open
    if (!cb.tryAcquirePermission()) {
      var callNotPermittedException = CallNotPermittedException.createCallNotPermittedException(cb);
      return fallback != null ? safeFallback(fallback, callNotPermittedException) : CompletableFuture.failedFuture(callNotPermittedException);
    }
    var future = new CompletableFuture<T>();
    var start = cb.getCurrentTimestamp();
    try {
      executionStage.get().whenComplete((result, throwable) -> {
        var duration = cb.getCurrentTimestamp() - start;
        if (throwable != null) {
          if (throwable instanceof Exception) {
            try {
              cb.onError(duration, cb.getTimestampUnit(), throwable);
            } catch (Throwable cbFailure) {
              // THE FIX: predicate threw → always complete the future
              future.completeExceptionally(new CircuitBreakerInternalException(resolveInstanceName, cbFailure));
              return;
            }
          }
          future.completeExceptionally(throwable);
        } else {
          cb.onSuccess(duration, cb.getTimestampUnit());
          future.complete(result);
        }
      });
    } catch (Exception syncException) {
      try {
        var duration = cb.getCurrentTimestamp() - start;
        cb.onError(duration, cb.getTimestampUnit(), syncException);
        future.completeExceptionally(syncException);
      } catch (Throwable cbFailure) {
        // THE FIX: predicate threw → always complete the future
        future.completeExceptionally(new CircuitBreakerInternalException(resolveInstanceName, cbFailure));
      }
    }
    return future;
  }

  /**
   * Resolves the CB instance name from baseName and key.
   * <p>
   * Rules:
   * <p>
   * - If both baseName and key are present → join them with {@code ":"}.
   * Example: {@code "payment" + "VN:PREMIUM" → "payment:VN:PREMIUM"}
   * <p>
   * - If key is null or blank → return baseName.
   * Example: {@code "payment" + null → "payment"}
   * <p>
   * - If baseName is null or blank → return key.
   * * Example: {@code null + "VN:PREMIUM → "VN:PREMIUM"}
   * <p>
   * - If both are null/blank → throw {@link IllegalArgumentException}.
   */
  static String resolveInstanceName(String baseName, String key) {
    var hasBase = baseName != null && !baseName.isBlank();
    var hasKey = key != null && !key.isBlank();
    if (hasBase && hasKey) {
      return baseName + ":" + key;
    }
    if (hasBase) {
      return baseName;
    }
    if (hasKey) {
      return key;
    }
    throw new IllegalArgumentException("baseName and key value must not both be blank");
  }

  /**
   * Resolves CircuitBreaker configuration.
   * <p>
   * Priority:
   * <p>
   * 1. configName (always preferred if exists)
   * <p>
   * 2. instanceName (fallback if configName not found)
   *
   * @throws ConfigurationNotFoundException if neither instanceName nor configName exists in registry
   */
  CircuitBreakerConfig resolveConfig(String configName, String instanceName) {
    CircuitBreakerConfig config = null;
    if (configName != null && !configName.isBlank()) {
      config = registry.getConfiguration(configName).orElse(null);
    }
    if (config != null) {
      return config;
    }
    return registry.getConfiguration(instanceName).orElseThrow(() -> new ConfigurationNotFoundException(instanceName));
  }

  private <T> CompletableFuture<T> safeFallback(Function<CallNotPermittedException, CompletableFuture<T>> fallback, CallNotPermittedException t) {
    try {
      var fallbackFuture = fallback.apply(t);
      if (fallbackFuture != null) {
        return fallbackFuture;
      }
      throw new IllegalArgumentException("fallback must not return null");
    } catch (Throwable e) {
      return CompletableFuture.failedFuture(e);
    }
  }
}
