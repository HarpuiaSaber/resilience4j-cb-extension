package io.github.harpuiasaber.resilience4j.cb.extension.exception;

/**
 * Thrown when resilience4j's {@code RecordExceptionPredicate} itself throws during {@code CircuitBreaker#onError()} recording.
 *
 * <p>Surfacing this as a distinct exception type ensures:
 * <ol>
 *   <li>The {@link java.util.concurrent.CompletableFuture} is <em>always</em> completed
 *       (the resilience4j bug where a throwing predicate causes the future to hang).</li>
 *   <li>Callers can distinguish CB-internal failures from normal service failures.</li>
 * </ol>
 */
public class CircuitBreakerInternalException extends RuntimeException {

  private final String circuitBreakerName;

  public CircuitBreakerInternalException(String circuitBreakerName, Throwable cause) {
    super("CircuitBreaker [" + circuitBreakerName + "] threw internally during onError recording", cause);
    this.circuitBreakerName = circuitBreakerName;
  }

  /**
   * The CB instance name (may include the resolved key).
   */
  public String getCircuitBreakerName() {
    return circuitBreakerName;
  }
}
