# AGENTS: How to work with this repository

Concise, actionable notes for coding agents: what to read first, project-specific rules, build/test commands, and common gotchas.

1) Big picture
- Small library that provides per-key circuit-breaker helpers on top of Resilience4j.
- Two responsibilities:
  - Compile-time: an annotation processor scans classes annotated with `@KeyedCircuitBreakerClient` and emits a proxy subclass `<YourClass>KeyedCbProxy` using JavaPoet.
  - Runtime: `KeyedCircuitBreakerExecutor` contains the per-key circuit-breaker logic used by generated proxies (instance naming, config resolution, permission/fallback handling).

2) Read these files first (in this order)
- `src/main/java/.../processor/KeyedCircuitBreakerProcessor.java` — processor entrypoint and validation rules (must read to know what source shapes are accepted).
- `src/main/java/.../processor/KeyedCbProxyGenerator.java` — codegen: proxy naming, constructor wiring, and generated method bodies.
- `src/main/java/.../annotation/KeyedCircuitBreaker.java` and `KeyedCircuitBreakerClient.java` — annotation contracts and examples the processor expects.
- `src/main/java/.../executor/KeyedCircuitBreakerExecutor.java` — runtime behavior and important utility methods: `resolveInstanceName`, `resolveConfig`.
- `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — Spring auto-config registration (verify when touching auto-config classes).
- `pom.xml` and `src/test/...` — build config (Java 21) and compile-testing based tests that document codegen behavior.

3) Project-specific rules you must know (precise)
- Target type requirements (processor rules):
  - `@KeyedCircuitBreakerClient` must be placed on a concrete, non-final class (not an interface).
  - The target class must be a Spring bean: one of `@Component`, `@Service`, `@Repository`, `@Controller`, or `@RestController`.
  - The processor rejects target classes annotated with `@Primary` (the generated proxy will be `@Primary`).
- Generated proxy shape (see `KeyedCbProxyGenerator`):
  - Class name: `<OriginalClassName>KeyedCbProxy`, in the same package.
  - Annotations: `@Component` and `@Primary` on the generated proxy.
  - Constructor: public constructor that accepts (1) the original bean instance qualified by bean name and (2) a `KeyedCircuitBreakerExecutor` instance. If the original class has no no-arg constructor, the generator resolves a non-private super-constructor and invokes it using null placeholders when necessary.
- Annotation contracts (processor enforces at compile time):
  - Methods annotated with `@KeyedCircuitBreaker` must return `CompletableFuture<T>`.
  - `keyResolverMethod` (optional) must be a method on the same class that returns `String` and has identical parameters to the annotated method.
  - `fallbackMethod` (optional) must be a method on the same class that returns `CompletableFuture<T>` and has the same parameters as the annotated method plus a final `io.github.resilience4j.circuitbreaker.CallNotPermittedException` parameter.

4) Runtime behavior highlights (from `KeyedCircuitBreakerExecutor`)
- Instance name: `"<baseName>:<resolvedKey>"` when key present; otherwise `baseName` (null/blank key behavior described in executor).
- Config resolution: prefer explicit `configName`, else try registry entry keyed by instanceName; throw `ConfigurationNotFoundException` if not found.
- When circuit is OPEN, the executor invokes the fallback (if present) or returns a failed `CompletableFuture` with `CallNotPermittedException`.
- Only `Exception` subclasses are recorded via `onError`; `Error` subclasses are not recorded. If resilience4j's error predicate throws, executor wraps it into `CircuitBreakerInternalException` to ensure futures always complete.

5) Build / test / dev commands
- Full build + tests (recommended):
  ```powershell
  mvn -DskipTests=false clean package
  ```
- Run tests only:
  ```powershell
  mvn test
  ```
- Fast package (skip tests):
  ```powershell
  mvn -DskipTests=true package
  ```
- Notes:
  - Project targets Java 21. Tests use google compile-testing; run tests via Maven to ensure proper JVM flags.
  - Enable annotation processing in your IDE to preview generated sources in `target/generated-sources`.

6) Common gotchas (do not assume defaults)
- Many runtime deps (Spring, Resilience4j) are `provided` in `pom.xml`. Tests create their own runtime instances — don't assume those jars are present at compile time in host apps.
- Processor enforces the class must be a Spring bean and concrete. If you change the processor to accept interfaces or non-beans, update many tests that assume the current validation.
- The processor explicitly rejects classes annotated with `@Primary` to avoid conflicts — if you change this behavior, update tests and the generated proxy wiring.
- Auto-config resource `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` may be stale — verify its contents when editing `KeyedCircuitBreakerAutoConfiguration`.

7) How to add features safely
- If you modify the annotation processor or generated shape:
  - Update/extend tests in `src/test/.../processor/KeyedCircuitBreakerProcessorTest.java` (compile-testing). These tests assert both success generation and specific validation errors.
  - Run `mvn -DskipTests=false test` locally.
- If you modify runtime behavior:
  - Add focused unit tests under `src/test/java/.../executor/` following existing StateMachineTest / BugFixTest / FallbackTest patterns. Use small deterministic durations for Awaitility/delayedExecutor.

8) Quick references (where to look in code)
- Validation & entrypoint: `KeyedCircuitBreakerProcessor` (processor).
- Codegen internals: `KeyedCbProxyGenerator.buildCbMethod` and `buildConstructor`.
- Runtime: `KeyedCircuitBreakerExecutor.resolveInstanceName`, `resolveConfig`, execute/permission/fallback handling.
- Examples & tests: `src/test/java/.../processor/KeyedCircuitBreakerProcessorTest.java` and `src/test/java/.../executor/KeyedCircuitBreakerExecutorTest.java`.

If you want, I can also:
- add a short CONTRIBUTING.md with exact Maven commands and CI hints
- update the AutoConfiguration.imports resource if you want the packaged auto-config to match the code

---
Generated/updated to match current processor rules (requires concrete Spring beans, rejects @Primary targets, fallback uses CallNotPermittedException). Review tests when changing validation or generated proxy shape.
