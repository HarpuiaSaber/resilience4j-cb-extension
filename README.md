# Resilience4j Keyed Circuit Breaker Extension

A compile-time annotation processor and runtime executor that provides **per-key circuit-breaker isolation** on top of [Resilience4j](https://resilience4j.readme.io/).

## Features

- **Keyed circuit breakers**: Each unique key gets its own independent `CircuitBreaker` instance (e.g., per region, tenant, request type).
- **Compile-time code generation**: Annotation processor generates Spring proxy subclasses using JavaPoet.
- **Spring Boot integration**: Auto-configured `KeyedCircuitBreakerExecutor` and proxy bean registration.
- **Optional fallback**: Graceful degradation when circuit is OPEN.
- **Type-safe**: Compile-time validation of method signatures, key resolvers, and fallback handlers.

## Quick Example

```java
@Service
@KeyedCircuitBreakerClient
public class PaymentClient {

    @KeyedCircuitBreaker(
        name = "payment",
        keyResolverMethod = "resolveRegion",
        fallbackMethod = "paymentFallback"
    )
    public CompletableFuture<String> pay(String region, String orderId) {
        return callExternalPaymentService(region, orderId);
    }

    // Key resolver: returns the circuit breaker key
    public String resolveRegion(String region, String orderId) {
        return region;
    }

    // Fallback: called when circuit is OPEN
    public CompletableFuture<String> paymentFallback(
            String region, String orderId, 
            CallNotPermittedException e) {
        return CompletableFuture.completedFuture("payment-pending");
    }
}
```

The processor generates `PaymentClientKeyedCbProxy` which:
- Extends the original class
- Overrides `@KeyedCircuitBreaker` methods to wrap them with the executor
- Is registered as a Spring `@Primary` bean, replacing the original

## Installation

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>io.github.toannq</groupId>
    <artifactId>resilience4j-cb-extension</artifactId>
    <version>1.0.0</version>
</dependency>
```

Requires:
- Java 21+
- Spring Framework (any currently-supported version; tested with Spring Boot 3.x)
- Resilience4j (any currently-supported version)

## Configuration

Each keyed circuit breaker requires a Resilience4j configuration. Define in `application.yaml`:

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 100
        failureRateThreshold: 50.0
        slowCallRateThreshold: 100.0
        slowCallDurationThreshold: 2s
        permittedNumberOfCallsInHalfOpenState: 3
        automaticTransitionFromOpenToHalfOpenEnabled: true
        waitDurationInOpenState: 10s
    instances:
      payment:VN:
        baseConfig: default
      payment:US:
        baseConfig: default
```

Or configure programmatically via `CircuitBreakerRegistry`.

## Build & Test

**Full build with tests:**
```powershell
mvn -DskipTests=false clean package
```

**Run tests only:**
```powershell
mvn test
```

**Fast package (skip tests):**
```powershell
mvn -DskipTests=true package
```

## Limitations

- Target class must be **not final** and a **concrete Spring bean** (`@Component`, `@Service`, `@Repository`, `@Controller`, or `@RestController`).
- Target class must not be annotated with `@Primary` (processor will reject it; the generated proxy is `@Primary`).
- Annotated methods must return `CompletableFuture<T>`.
- **Constructor-injected dependencies** in the target class are not automatically injected into the proxy. Instead, the proxy receives the original bean instance as a delegate.

## Validation Rules (Compile-Time)

The processor enforces:

1. **Target class**: concrete, not final, is a Spring bean, not `@Primary`.
2. **Annotated methods**: must return `CompletableFuture<T>`.
3. **Key resolver (optional)**: must return `String` and have the same parameters as the annotated method.
4. **Fallback (optional)**: must return `CompletableFuture<T>`, have the same parameters as the annotated method, plus a final `CallNotPermittedException` parameter.

## Documentation

For detailed developer guidance, see [AGENTS.md](./AGENTS.md).

## Architecture

- **Compile-time**: `KeyedCircuitBreakerProcessor` (annotation processor) + `KeyedCbProxyGenerator` (JavaPoet codegen).
- **Runtime**: `KeyedCircuitBreakerExecutor` (per-key CB logic, resilience4j integration).
- **Auto-config**: `KeyedCircuitBreakerAutoConfiguration` (Spring Boot auto-configuration).

See [AGENTS.md](./AGENTS.md) for detailed code structure and conventions.

## License

See [LICENSE](./LICENSE) file.

## Disclaimer

This software is provided "as is", without warranty of any kind.
The authors are not liable for any damages arising from its use.