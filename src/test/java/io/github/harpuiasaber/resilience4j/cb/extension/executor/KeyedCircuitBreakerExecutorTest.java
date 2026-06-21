package io.github.harpuiasaber.resilience4j.cb.extension.executor;

import io.github.harpuiasaber.resilience4j.cb.extension.exception.CircuitBreakerInternalException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class KeyedCircuitBreakerExecutorTest {

  private static final String BASE_NAME = "payment";
  private static final String CONFIG_NAME = "payment";
  private static final int SLIDING_WINDOW_SIZE = 10;
  private static final int MINIMUM_NUMBER_OF_CALLS = 5;
  private static final int FAILURE_RATE_THRESHOLD = 50;
  private static final int DURATION_IN_OPEN_STATE_MILLIS = 500;
  private static final int PERMITTED_NUMBER_OF_CALLS_IN_HALF_OPEN_STATE = 4;
  private static final CircuitBreakerConfig CONFIG = CircuitBreakerConfig.custom()
      .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
      .slidingWindowSize(SLIDING_WINDOW_SIZE)
      .minimumNumberOfCalls(MINIMUM_NUMBER_OF_CALLS)
      .failureRateThreshold(FAILURE_RATE_THRESHOLD)
      .waitDurationInOpenState(Duration.ofMillis(DURATION_IN_OPEN_STATE_MILLIS))
      .permittedNumberOfCallsInHalfOpenState(PERMITTED_NUMBER_OF_CALLS_IN_HALF_OPEN_STATE)
      .build();

  private CircuitBreakerRegistry registry;
  private KeyedCircuitBreakerExecutor executor;

  @BeforeEach
  void setUp() {
    registry = CircuitBreakerRegistry.ofDefaults();
    registry.circuitBreaker(BASE_NAME, CONFIG);
    executor = new KeyedCircuitBreakerExecutor(registry);
  }

  @Nested
  @DisplayName("Circuit breaker CLOSED: normal flow")
  class SuccessPathTest {

    @Test
    @DisplayName("async process: returns result success")
    void returnsAsyncResultSuccess() {
      var key = "someKey";
      var cb = cb(key);
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
      var future = executor.execute(BASE_NAME, CONFIG_NAME, key, () -> CompletableFuture.supplyAsync(
          () -> "result",
          CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS)
      ), null);
      assertThat(future).succeedsWithin(1, TimeUnit.SECONDS)
          .isEqualTo("result");
    }

    @Test
    @DisplayName("sync process: returns result success")
    void returnsSyncResultSuccess() {
      var key = "someKey";
      var cb = cb(key);
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
      var result = executor.execute(BASE_NAME, CONFIG_NAME, key, () -> CompletableFuture.completedFuture("result"), null).join();
      assertThat(result).isEqualTo("result");
    }

    @Test
    @DisplayName("async process: result throws exception")
    void asyncFailure_recordsAndCompletes() {
      var key = "someKey";
      var cause = new RuntimeException("downstream");
      var future = executor.execute(BASE_NAME, CONFIG_NAME, key, () -> CompletableFuture.failedFuture(cause), null);
      assertThat(future).failsWithin(1, TimeUnit.SECONDS);
      assertThatThrownBy(future::join).hasCause(cause);
    }

    @Test
    @DisplayName("sync process: result throws exception")
    void syncThrow_recordsAndCompletes() {
      var key = "someKey";
      var cause = new IllegalStateException("sync");
      var future = executor.execute(BASE_NAME, CONFIG_NAME, key, () -> {
        throw cause;
      }, null);
      assertThat(future).failsWithin(1, TimeUnit.SECONDS);
      assertThatThrownBy(future::join).hasCause(cause);
    }
  }

  @Nested
  @DisplayName("Per-key Circuit breaker isolation")
  class PerKeyIsolationTest {

    @Test
    @DisplayName("different keys -> independent CB state")
    void differentKeys_independentState() {
      var keyVn = "VN";
      resetCbState(keyVn, CircuitBreaker.State.OPEN);
      var keyUs = "US";
      var cbUs = cb(keyUs);
      assertThat(cbUs.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
      var result = executor.execute(BASE_NAME, CONFIG_NAME, keyUs, () -> CompletableFuture.completedFuture("ok"), null).join();
      assertThat(result).isEqualTo("ok");
      assertThat(cbUs.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
      assertThat(cbUs.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("same key -> shares CB state across calls")
    void sameKey_sharesCbState() {
      var key1 = "VN";
      var key2 = "VN";
      resetCbState(key1, CircuitBreaker.State.OPEN);
      var future = executor.execute(BASE_NAME, CONFIG_NAME, key2, () -> CompletableFuture.completedFuture("not run"), null);
      assertThat(future).failsWithin(1, TimeUnit.SECONDS);
      assertThatThrownBy(future::join)
          .isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(CallNotPermittedException.class)
          .cause()
          .hasMessageContaining(KeyedCircuitBreakerExecutor.resolveInstanceName(BASE_NAME, key2));
    }

    @Test
    @DisplayName("null or empty or blank key -> same CB (baseName)")
    void nullAndBlankKey_sameInstance() {
      String key = null;
      resetCbState(key, CircuitBreaker.State.OPEN);
      var future = executor.execute(BASE_NAME, CONFIG_NAME, "", () -> CompletableFuture.completedFuture("ok"), null);
      assertFailsWith(future, CallNotPermittedException.class);
      future = executor.execute(BASE_NAME, CONFIG_NAME, "   ", () -> CompletableFuture.completedFuture("ok"), null);
      assertFailsWith(future, CallNotPermittedException.class);
    }
  }

  @Nested
  @DisplayName("Circuit breaker OPEN: Fallback behaviour")
  class FallbackTest {

    @Test
    @DisplayName("fallback called -> future complete with fallback result")
    void openCircuit_fallbackCalledWithCorrectException() {
      var key = "someKey";
      resetCbState(key, CircuitBreaker.State.OPEN);
      boolean[] called = {false};
      Throwable[] captured = {null};
      var future = executor.execute(BASE_NAME, CONFIG_NAME, key, () -> {
        called[0] = true;
        return CompletableFuture.completedFuture("not run");
      }, t -> {
        captured[0] = t;
        return CompletableFuture.completedFuture("fallback");
      });
      assertThat(future).succeedsWithin(1, TimeUnit.SECONDS)
          .isEqualTo("fallback");
      assertThat(called[0]).isFalse();
      assertThat(captured[0]).isInstanceOf(CallNotPermittedException.class);
    }

    @Test
    @DisplayName("fallback throws -> future fails with fallback exception")
    void fallbackThrows_propagatesFallbackException() {
      var key = "someKey";
      resetCbState(key, CircuitBreaker.State.OPEN);
      var fbEx = new RuntimeException("fallback failed");
      var future = executor.execute(BASE_NAME, CONFIG_NAME, key, () -> CompletableFuture.completedFuture("no run"), t -> {
        throw fbEx;
      });
      assertThat(future).failsWithin(1, TimeUnit.SECONDS);
      assertThatThrownBy(future::join)
          .isInstanceOf(CompletionException.class)
          .hasCause(fbEx);
    }

    @Test
    @DisplayName("no fallback (null) -> future fails with CallNotPermittedException")
    void noFallback_openCircuit_propagates() {
      var key = "someKey";
      resetCbState(key, CircuitBreaker.State.OPEN);
      var future = executor.execute(BASE_NAME, CONFIG_NAME, key, () -> CompletableFuture.completedFuture("not run"), null);
      assertFailsWith(future, CallNotPermittedException.class);
    }

    @Test
    @DisplayName("fallback return null -> future fails with IllegalArgumentException")
    void fallbackReturnsNull_npe() {
      var key = "someKey";
      resetCbState(key, CircuitBreaker.State.OPEN);
      var future = executor.execute(BASE_NAME, CONFIG_NAME, key, () -> CompletableFuture.completedFuture("no run"), t -> null);
      assertFailsWith(future, IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("CB state-machine: CLOSED -> OPEN -> HALF_OPEN -> CLOSED/OPEN")
  class StateMachineTest {

    @Test
    @DisplayName("CLOSED -> OPEN: failure threshold reached")
    void closedToOpen() {
      var key = "someKey";
      var cb = cb(key);
      assertThat(cb.getMetrics().getNumberOfFailedCalls()).isZero();
      assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isZero();
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
      for (var i = 0; i < MINIMUM_NUMBER_OF_CALLS; i++) {
        executeFail(key);
      }
      assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(Math.min(MINIMUM_NUMBER_OF_CALLS, SLIDING_WINDOW_SIZE));
      assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isZero();
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("OPEN -> HALF_OPEN: waitDuration expires (probe success)")
    void openToHalfOpen_afterWait_probeSuccess() {
      var key = "someKey";
      resetCbState(key, CircuitBreaker.State.OPEN);
      await().pollDelay(DURATION_IN_OPEN_STATE_MILLIS, TimeUnit.MILLISECONDS)
          .atMost(DURATION_IN_OPEN_STATE_MILLIS + 500, TimeUnit.MILLISECONDS)
          .untilAsserted(() -> executor.execute(BASE_NAME, CONFIG_NAME, key, () -> CompletableFuture.completedFuture("probe"), null),
              future -> assertThat(future).succeedsWithin(1, TimeUnit.SECONDS).isEqualTo("probe"));
      var cb = cb(key);
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
      assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
      assertThat(cb.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("OPEN -> HALF_OPEN: waitDuration expires (probe fail)")
    void openToHalfOpen_afterWait_probeFail() {
      var key = "someKey";
      resetCbState(key, CircuitBreaker.State.OPEN);
      await().pollDelay(DURATION_IN_OPEN_STATE_MILLIS, TimeUnit.MILLISECONDS)
          .atMost(DURATION_IN_OPEN_STATE_MILLIS + 500, TimeUnit.MILLISECONDS)
          .untilAsserted(() -> executor.execute(BASE_NAME, CONFIG_NAME, key, () -> CompletableFuture.failedFuture(new RuntimeException("probe fail")), null),
              future -> assertFailsWith(future, RuntimeException.class));
      var cb = cb(key);
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
      assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
      assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isZero();
    }

    @Test
    @DisplayName("HALF_OPEN -> CLOSED: probe success rate > failureRateThreshold")
    void halfOpenToClosed_whenFailureRateIsBelowThreshold() {
      var key = "someKey";
      resetCbState(key, CircuitBreaker.State.HALF_OPEN);
      var numberOfSuccessCalls = (PERMITTED_NUMBER_OF_CALLS_IN_HALF_OPEN_STATE * FAILURE_RATE_THRESHOLD) / 100 + 1;
      for (var i = 0; i < numberOfSuccessCalls; i++) {
        executeSuccess(key);
      }
      var cb = cb(key);
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
      assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(numberOfSuccessCalls);
      var numberOfFailedCalls = PERMITTED_NUMBER_OF_CALLS_IN_HALF_OPEN_STATE - numberOfSuccessCalls;
      for (var i = 0; i < numberOfFailedCalls; i++) {
        executeFail(key);
      }
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
      assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isZero();
      assertThat(cb.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("HALF_OPEN -> OPEN: probe failure rate = failureRateThreshold")
    void halfOpenToOpen_whenFailureRateIsEqualsThreshold() {
      var key = "someKey";
      resetCbState(key, CircuitBreaker.State.HALF_OPEN);
      var numberOfFailedCalls = (PERMITTED_NUMBER_OF_CALLS_IN_HALF_OPEN_STATE * FAILURE_RATE_THRESHOLD) / 100;
      for (int i = 0; i < numberOfFailedCalls; i++) {
        executeFail(key);
      }
      var cb = cb(key);
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
      assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(numberOfFailedCalls);
      var numberOfSuccessCalls = PERMITTED_NUMBER_OF_CALLS_IN_HALF_OPEN_STATE - numberOfFailedCalls;
      assertThat(numberOfSuccessCalls).isEqualTo(numberOfFailedCalls);
      for (var i = 0; i < numberOfSuccessCalls; i++) {
        executeSuccess(key);
      }
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
      assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(numberOfSuccessCalls);
      assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(numberOfFailedCalls);
    }

    @Test
    @DisplayName("HALF_OPEN -> OPEN: probe failure rate > failureRateThreshold")
    void halfOpenToOpen_whenFailureRateIsExceedsThreshold() {
      var key = "someKey";
      resetCbState(key, CircuitBreaker.State.HALF_OPEN);
      var numberOfFailedCalls = (PERMITTED_NUMBER_OF_CALLS_IN_HALF_OPEN_STATE * FAILURE_RATE_THRESHOLD) / 100 + 1;
      for (int i = 0; i < numberOfFailedCalls; i++) {
        executeFail(key);
      }
      var cb = cb(key);
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
      assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(numberOfFailedCalls);
      var numberOfSuccessCalls = PERMITTED_NUMBER_OF_CALLS_IN_HALF_OPEN_STATE - numberOfFailedCalls;
      for (var i = 0; i < numberOfSuccessCalls; i++) {
        executeSuccess(key);
      }
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
      assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(numberOfSuccessCalls);
      assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(numberOfFailedCalls);
    }

    @Test
    @DisplayName("full recovery cycle: CLOSED -> OPEN -> HAFL_OPEN -> CLOED")
    void fullRecoveryCycle_successOnFirstHalfOpen() {
      var key = "someKey";
      var cb = cb(key);
      assertThat(cb.getMetrics().getNumberOfFailedCalls()).isZero();
      assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isZero();
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
      for (var i = 0; i < MINIMUM_NUMBER_OF_CALLS; i++) {
        executeFail(key);
      }
      assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(Math.min(MINIMUM_NUMBER_OF_CALLS, SLIDING_WINDOW_SIZE));
      assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isZero();
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
      await().pollDelay(DURATION_IN_OPEN_STATE_MILLIS, TimeUnit.MILLISECONDS)
          .atMost(DURATION_IN_OPEN_STATE_MILLIS + 500, TimeUnit.MILLISECONDS)
          .untilAsserted(() -> executor.execute(BASE_NAME, CONFIG_NAME, key, () -> CompletableFuture.completedFuture("probe"), null),
              future -> assertThat(future).succeedsWithin(1, TimeUnit.SECONDS).isEqualTo("probe"));
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
      assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
      assertThat(cb.getMetrics().getNumberOfFailedCalls()).isZero();
      var numberOfSuccessCalls = (PERMITTED_NUMBER_OF_CALLS_IN_HALF_OPEN_STATE * FAILURE_RATE_THRESHOLD) / 100;
      for (var i = 0; i < numberOfSuccessCalls; i++) {
        executeSuccess(key);
      }
      ++numberOfSuccessCalls; // + 1 probe success at await check
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
      assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(numberOfSuccessCalls);
      var numberOfFailedCalls = PERMITTED_NUMBER_OF_CALLS_IN_HALF_OPEN_STATE - numberOfSuccessCalls;
      for (var i = 0; i < numberOfFailedCalls; i++) {
        executeFail(key);
      }
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
      assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isZero();
      assertThat(cb.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("full recovery cycle: CLOSED -> OPEN -> HAFL_OPEN -> OPEN -> HAFL_OPEN -> CLOED")
    void fullRecoveryCycle_requiresMultipleHalfOpenRetriesBeforeClosing() {
      var key = "someKey";
      var cb = cb(key);
      assertThat(cb.getMetrics().getNumberOfFailedCalls()).isZero();
      assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isZero();
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
      for (var i = 0; i < MINIMUM_NUMBER_OF_CALLS; i++) {
        executeFail(key);
      }
      assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(Math.min(MINIMUM_NUMBER_OF_CALLS, SLIDING_WINDOW_SIZE));
      assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isZero();
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
      await().pollDelay(DURATION_IN_OPEN_STATE_MILLIS, TimeUnit.MILLISECONDS)
          .atMost(DURATION_IN_OPEN_STATE_MILLIS + 500, TimeUnit.MILLISECONDS)
          .untilAsserted(() -> executor.execute(BASE_NAME, CONFIG_NAME, key, () -> CompletableFuture.failedFuture(new RuntimeException("probe fail")), null),
              future -> assertFailsWith(future, RuntimeException.class));
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
      assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
      assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isZero();
      var numberOfFailedCalls = (PERMITTED_NUMBER_OF_CALLS_IN_HALF_OPEN_STATE * FAILURE_RATE_THRESHOLD) / 100;
      for (int i = 0; i < numberOfFailedCalls; i++) {
        executeFail(key);
      }
      ++numberOfFailedCalls; // + 1 probe fail at await check
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
      assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(numberOfFailedCalls);
      var numberOfSuccessCalls = PERMITTED_NUMBER_OF_CALLS_IN_HALF_OPEN_STATE - numberOfFailedCalls;
      for (var i = 0; i < numberOfSuccessCalls; i++) {
        executeSuccess(key);
      }
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
      assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(numberOfSuccessCalls);
      assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(numberOfFailedCalls);
      await().pollDelay(DURATION_IN_OPEN_STATE_MILLIS, TimeUnit.MILLISECONDS)
          .atMost(DURATION_IN_OPEN_STATE_MILLIS + 500, TimeUnit.MILLISECONDS)
          .untilAsserted(() -> executor.execute(BASE_NAME, CONFIG_NAME, key, () -> CompletableFuture.completedFuture("probe"), null),
              future -> assertThat(future).succeedsWithin(1, TimeUnit.SECONDS).isEqualTo("probe"));
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
      assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
      assertThat(cb.getMetrics().getNumberOfFailedCalls()).isZero();
      numberOfSuccessCalls = (PERMITTED_NUMBER_OF_CALLS_IN_HALF_OPEN_STATE * FAILURE_RATE_THRESHOLD) / 100;
      for (var i = 0; i < numberOfSuccessCalls; i++) {
        executeSuccess(key);
      }
      ++numberOfSuccessCalls; // + 1 probe success at await check
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
      assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(numberOfSuccessCalls);
      numberOfFailedCalls = PERMITTED_NUMBER_OF_CALLS_IN_HALF_OPEN_STATE - numberOfSuccessCalls;
      for (var i = 0; i < numberOfFailedCalls; i++) {
        executeFail(key);
      }
      assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
      assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isZero();
      assertThat(cb.getMetrics().getNumberOfFailedCalls()).isZero();
    }
  }

  @Nested
  @DisplayName("Bug fix: future always completes")
  class BugFixTest {

    private static final String ERROR_CONFIG_NAME = "payment-error-predicate";

    @BeforeEach
    void setUp() {
      var errorConfig = CircuitBreakerConfig.custom()
          .slidingWindowSize(1).minimumNumberOfCalls(1)
          .recordException(e -> {
            throw new RuntimeException("predicate error");
          })
          .build();
      registry = CircuitBreakerRegistry.ofDefaults();
      registry.circuitBreaker(BASE_NAME, errorConfig);
      executor = new KeyedCircuitBreakerExecutor(registry);
    }

    @Test
    @DisplayName("async process: onError throws -> future fails with CircuitBreakerInternalException")
    void asyncPath_predicateThrows() {
      var key = "someKey";
      var future = executor.execute(BASE_NAME, ERROR_CONFIG_NAME, key, () -> CompletableFuture.failedFuture(new RuntimeException("async error")), null);
      assertThat(future).failsWithin(1, TimeUnit.SECONDS);
      assertThatThrownBy(future::join)
          .isInstanceOf(CompletionException.class)
          .cause()
          .isInstanceOf(CircuitBreakerInternalException.class)
          .hasMessageContaining(KeyedCircuitBreakerExecutor.resolveInstanceName(BASE_NAME, key));
      assertThat(cb("K").getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("sync process: onError throws -> future fails with CircuitBreakerInternalException")
    void syncPath_predicateThrows() {
      var key = "someKey";
      var future = executor.execute(BASE_NAME, ERROR_CONFIG_NAME, key, () -> {
        throw new RuntimeException("sync error");
      }, null);
      assertThat(future).failsWithin(1, TimeUnit.SECONDS);
      assertThatThrownBy(future::join)
          .isInstanceOf(CompletionException.class)
          .cause()
          .isInstanceOf(CircuitBreakerInternalException.class)
          .hasMessageContaining(KeyedCircuitBreakerExecutor.resolveInstanceName(BASE_NAME, key));
      assertThat(cb("K").getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("Error bypasses onError -> future fails with Error")
    void errorSubclass_bypassesOnError_futureCompletes() {
      var key = "someKey";
      var oom = new OutOfMemoryError("oom");
      var future = executor.execute(BASE_NAME, ERROR_CONFIG_NAME, key, () -> CompletableFuture.failedFuture(oom), null);
      assertThat(future).failsWithin(1, TimeUnit.SECONDS);
      assertThatThrownBy(future::join).hasCause(oom);
      assertThat(cb(key).getMetrics().getNumberOfFailedCalls()).isZero();
    }
  }

  private CircuitBreaker cb(String key) {
    var resolveInstanceName = KeyedCircuitBreakerExecutor.resolveInstanceName(BASE_NAME, key);
    var resolvedConfig = executor.resolveConfig(CONFIG_NAME, resolveInstanceName);
    return registry.circuitBreaker(resolveInstanceName, resolvedConfig);
  }

  private void resetCbState(String key, CircuitBreaker.State state) {
    var cb = cb(key);
    cb.reset();
    switch (state) {
      case OPEN -> cb.transitionToOpenState();
      case HALF_OPEN -> {
        cb.transitionToOpenState();
        cb.transitionToHalfOpenState();
      }
      case CLOSED -> cb.transitionToClosedState();
      default -> throw new IllegalStateException("Unexpected value: " + state);
    }
    assertThat(cb.getState()).isEqualTo(state);
  }

  private void assertFailsWith(CompletableFuture<?> future, Class<? extends Throwable> type) {
    assertThat(future).failsWithin(1, TimeUnit.SECONDS);
    assertThatThrownBy(future::join)
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(type);
  }

  private void executeSuccess(String key) {
    var future = executor.execute(BASE_NAME, CONFIG_NAME, key, () -> CompletableFuture.completedFuture("probe"), null);
    assertThat(future).succeedsWithin(1, TimeUnit.SECONDS).isEqualTo("probe");
  }

  private void executeFail(String key) {
    var future = executor.execute(BASE_NAME, CONFIG_NAME, key, () -> CompletableFuture.failedFuture(new RuntimeException("probe fail")), null);
    assertFailsWith(future, RuntimeException.class);
  }
}
