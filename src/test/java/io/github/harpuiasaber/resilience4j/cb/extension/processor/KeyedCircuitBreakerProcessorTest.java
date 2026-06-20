package io.github.harpuiasaber.resilience4j.cb.extension.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

@DisplayName("KeyedCircuitBreakerProcessor — compile-time tests (interface-only)")
class KeyedCircuitBreakerProcessorTest {

  private Compilation compile(String... sources) {
    var fileObjects = Arrays.stream(sources)
        .map(src -> {
          int nl = src.indexOf('\n');
          var path = src.substring(0, nl).trim();
          var body = src.substring(nl + 1);
          return JavaFileObjects.forSourceString(path, body);
        })
        .toList();

    return javac()
        .withProcessors(new KeyedCircuitBreakerProcessor())
        .compile(fileObjects);
  }

  @Nested
  @DisplayName("Successful generation")
  class SuccessfulGeneration {

    @Test
    @DisplayName("generates proxy for interface with default method")
    void generatesProxyFileWithDefaultContractMethod() {

      var c = compile(
          """
              com.example.OrderApi
              package com.example;
              
              import io.github.harpuiasaber.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
              import io.github.harpuiasaber.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
              import java.util.concurrent.CompletableFuture;
              import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
              
              @KeyedCircuitBreakerClient(delegate = OrderClientImpl.class)
              public interface OrderApi {
              
                  @KeyedCircuitBreaker(name = "order", keyResolverMethod = "resolveOrderKey", fallbackMethod = "fetchOrderFallback")
                  CompletableFuture<String> fetchOrder(String region, String tier);
              
                  default String resolveOrderKey(String region, String tier) { return region + ":" + tier; };
              
                  default CompletableFuture<String> fetchOrderFallback(String region, String tier, CallNotPermittedException e) { return CompletableFuture.failedFuture(new RuntimeException("cb error")); };
              
                  CompletableFuture<Void> cancelOrder(String id);
              }
              """,
          """
              com.example.OrderClientImpl
              package com.example;
              
              import org.springframework.stereotype.Component;
              import java.util.concurrent.CompletableFuture;
              
              @Component
              public class OrderClientImpl implements OrderApi {
              
                  public CompletableFuture<String> fetchOrder(String region, String tier) {
                      return CompletableFuture.completedFuture("ok");
                  }
              
                  public CompletableFuture<Void> cancelOrder(String id) {
                      return CompletableFuture.completedFuture(null);
                  }
              }
              """);
      assertThat(c).succeeded();
      var content = assertThat(c).generatedSourceFile("com.example.OrderApiKeyedCbProxy")
          .contentsAsUtf8String();
      content.doesNotContain("public String resolveOrderKey");
      content.doesNotContain("public CompletableFuture<String> fetchOrderFallback");
    }

    @Test
    @DisplayName("generates proxy for interface with explicit delegate")
    void generatesProxyFileForInterface() {

      var c = compile(
          """
              com.example.OrderApi
              package com.example;
              
              import io.github.harpuiasaber.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
              import io.github.harpuiasaber.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
              import java.util.concurrent.CompletableFuture;
              
              @KeyedCircuitBreakerClient(delegate = com.example.OrderClientImpl.class)
              public interface OrderApi {
              
                  @KeyedCircuitBreaker(name = "order", keyResolverMethod = "resolveOrderKey")
                  CompletableFuture<String> fetchOrder(String region, String tier);
              
                  String resolveOrderKey(String region, String tier);
              
                  CompletableFuture<Void> cancelOrder(String id);
              }
              """,
          """
              com.example.OrderClientImpl
              package com.example;
              
              import org.springframework.stereotype.Component;
              import java.util.concurrent.CompletableFuture;
              
              @Component
              public class OrderClientImpl implements OrderApi {
              
                  public CompletableFuture<String> fetchOrder(String region, String tier) {
                      return CompletableFuture.completedFuture("ok");
                  }
              
                  public String resolveOrderKey(String region, String tier) {
                      return region + ":" + tier;
                  }
              
                  public CompletableFuture<Void> cancelOrder(String id) {
                      return CompletableFuture.completedFuture(null);
                  }
              }
              """);
      assertThat(c).succeeded();
      assertThat(c).generatedSourceFile("com.example.OrderApiKeyedCbProxy");
    }

    @Test
    @DisplayName("proxy implements interface and uses @Qualifier delegate")
    void proxyImplementsInterface() {
      var c = compile(
          """
              com.example.OrderApi
              package com.example;
              
              import io.github.harpuiasaber.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
              import io.github.harpuiasaber.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
              import java.util.concurrent.CompletableFuture;
              
              @KeyedCircuitBreakerClient(delegate = com.example.OrderClientImpl.class)
              public interface OrderApi {
              
                  @KeyedCircuitBreaker(name = "order", keyResolverMethod = "resolveOrderKey")
                  CompletableFuture<String> fetchOrder(String region, String tier);
              
                  String resolveOrderKey(String region, String tier);
              
                  CompletableFuture<Void> cancelOrder(String id);
              }
              """,
          """
              com.example.OrderClientImpl
              package com.example;
              
              import org.springframework.stereotype.Component;
              import java.util.concurrent.CompletableFuture;
              
              @Component
              public class OrderClientImpl implements OrderApi {
              
                  public CompletableFuture<String> fetchOrder(String region, String tier) {
                      return CompletableFuture.completedFuture("ok");
                  }
              
                  public String resolveOrderKey(String region, String tier) {
                      return region + ":" + tier;
                  }
              
                  public CompletableFuture<Void> cancelOrder(String id) {
                      return CompletableFuture.completedFuture(null);
                  }
              }
              """);
      assertThat(c).succeeded();
      var contents = assertThat(c)
          .generatedSourceFile("com.example.OrderApiKeyedCbProxy")
          .contentsAsUtf8String();
      contents.contains("implements OrderApi");
      contents.contains("@Qualifier(");
    }

    @Test
    @DisplayName("with fallback - generates fallback handler")
    void withFallbackGeneratesHandler() {
      var c = compile(
          """
              com.example.OrderApi
              package com.example;
              
              import io.github.harpuiasaber.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
              import io.github.harpuiasaber.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
              import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
              import java.util.concurrent.CompletableFuture;
              
              @KeyedCircuitBreakerClient(delegate = com.example.OrderClientImpl.class)
              public interface OrderApi {
              
                  @KeyedCircuitBreaker(
                      name = "order",
                      keyResolverMethod = "resolveOrderKey",
                      fallbackMethod = "fetchOrderFallback"
                  )
                  CompletableFuture<String> fetchOrder(String region, String tier);
              
                  String resolveOrderKey(String region, String tier);
              
                  CompletableFuture<String> fetchOrderFallback(
                      String region,
                      String tier,
                      CallNotPermittedException e
                  );
              
                  CompletableFuture<Void> cancelOrder(String id);
              }
              """,
          """
              com.example.OrderClientImpl
              package com.example;
              
              import org.springframework.stereotype.Component;
              import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
              import java.util.concurrent.CompletableFuture;
              
              @Component
              public class OrderClientImpl implements OrderApi {
              
                  public CompletableFuture<String> fetchOrder(String region, String tier) {
                      return CompletableFuture.completedFuture("ok");
                  }
              
                  public String resolveOrderKey(String region, String tier) {
                      return region + ":" + tier;
                  }
              
                  public CompletableFuture<String> fetchOrderFallback(
                      String region,
                      String tier,
                      CallNotPermittedException e
                  ) {
                      return CompletableFuture.completedFuture("fallback");
                  }
              
                  public CompletableFuture<Void> cancelOrder(String id) {
                      return CompletableFuture.completedFuture(null);
                  }
              }
              """);
      assertThat(c).succeeded();
      assertThat(c)
          .generatedSourceFile("com.example.OrderApiKeyedCbProxy")
          .contentsAsUtf8String()
          .contains("fetchOrderFallback");
    }

    @Test
    @DisplayName("non-annotated methods delegate passthrough")
    void passthroughForNonAnnotatedMethod() {
      var c = compile(
          """
              com.example.MixedApi
              package com.example;
              
              import io.github.harpuiasaber.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
              import io.github.harpuiasaber.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
              import java.util.concurrent.CompletableFuture;
              
              @KeyedCircuitBreakerClient(delegate = com.example.MixedImpl.class)
              public interface MixedApi {
              
                  @KeyedCircuitBreaker(name = "mixed")
                  CompletableFuture<String> tracked(String id);
              
                  CompletableFuture<Void> untracked(String id);
              }
              """,
          """
              com.example.MixedImpl
              package com.example;
              
              import org.springframework.stereotype.Component;
              import java.util.concurrent.CompletableFuture;
              
              @Component
              public class MixedImpl implements MixedApi {
              
                  public CompletableFuture<String> tracked(String id) {
                      return CompletableFuture.completedFuture("ok");
                  }
              
                  public CompletableFuture<Void> untracked(String id) {
                      return CompletableFuture.completedFuture(null);
                  }
              }
              """);

      assertThat(c).succeeded();
      assertThat(c)
          .generatedSourceFile("com.example.MixedApiKeyedCbProxy")
          .contentsAsUtf8String()
          .contains("delegate.untracked(id)");
    }
  }

  @Nested
  @DisplayName("Compile-time validation errors")
  class ValidationErrors {

    @Test
    @DisplayName("error: @KeyedCircuitBreakerClient only on interface, not class")
    void errorWhenPlacedOnClass() {
      var c = compile("""
          com.example.NotAllowedClient
          package com.example;
          import io.github.harpuiasaber.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
          import io.github.harpuiasaber.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
          import org.springframework.stereotype.Component;
          import java.util.concurrent.CompletableFuture;
          
          @KeyedCircuitBreakerClient
          @Component
          public class NotAllowedClient {
              @KeyedCircuitBreaker(name = "cb")
              public CompletableFuture<String> fetch(String id) { return CompletableFuture.completedFuture("ok"); }
          }
          """);

      assertThat(c).failed();
      assertThat(c).hadErrorContaining("can only be placed on an interface");
    }

    @Test
    @DisplayName("error: method must return CompletableFuture")
    void errorOnNonFutureReturnType() {
      var c = compile(
          """
              com.example.BadApi
              package com.example;
              
              import io.github.harpuiasaber.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
              import io.github.harpuiasaber.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
              
              @KeyedCircuitBreakerClient(delegate = com.example.BadImpl.class)
              public interface BadApi {
              
                  @KeyedCircuitBreaker(name = "bad")
                  String notAFuture(String id);
              }
              """,
          """
              com.example.BadImpl
              package com.example;
              
              import org.springframework.stereotype.Component;
              
              @Component
              public class BadImpl implements BadApi {
              
                  public String notAFuture(String id) {
                      return "bad";
                  }
              }
              """);

      assertThat(c).failed();
      assertThat(c).hadErrorContaining("CompletableFuture");
    }

    @Test
    @DisplayName("error: requires an explicit delegate")
    void interfaceWithMultipleImplementersErrors() {
      var c = compile(
          """
              com.example.Svc
              package com.example;
              
              import io.github.harpuiasaber.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
              import io.github.harpuiasaber.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
              import java.util.concurrent.CompletableFuture;
              
              @KeyedCircuitBreakerClient
              public interface Svc {
              
                  @KeyedCircuitBreaker(name = "cb")
                  CompletableFuture<String> call(String id);
              }
              """,
          """
              com.example.SvcImplA
              package com.example;
              
              import org.springframework.stereotype.Component;
              import java.util.concurrent.CompletableFuture;
              
              @Component
              public class SvcImplA implements Svc {
              
                  public CompletableFuture<String> call(String id) {
                      return CompletableFuture.completedFuture("a");
                  }
              }
              """,
          """
              com.example.SvcImplB
              package com.example;
              
              import org.springframework.stereotype.Component;
              import java.util.concurrent.CompletableFuture;
              
              @Component
              public class SvcImplB implements Svc {
              
                  public CompletableFuture<String> call(String id) {
                      return CompletableFuture.completedFuture("b");
                  }
              }
              """);

      assertThat(c).failed();
      assertThat(c).hadErrorContaining("requires either an explicit delegate or at least one concrete Spring bean implementing it");
    }

    @Test
    @DisplayName("error: static at contract method")
    void interfaceWithStaticContractMethod() {
      var c = compile(
          """
              com.example.OrderApi
              package com.example;
              
              import io.github.harpuiasaber.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
              import io.github.harpuiasaber.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
              import java.util.concurrent.CompletableFuture;
              import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
              
              @KeyedCircuitBreakerClient(delegate = OrderClientImpl.class)
              public interface OrderApi {
              
                  @KeyedCircuitBreaker(name = "order", keyResolverMethod = "resolveOrderKey", fallbackMethod = "fetchOrderFallback")
                  CompletableFuture<String> fetchOrder(String region, String tier);
              
                  static String resolveOrderKey(String region, String tier) { return region + ":" + tier; };
              
                  default CompletableFuture<String> fetchOrderFallback(String region, String tier, CallNotPermittedException e) { return CompletableFuture.failedFuture(new RuntimeException("cb error")); };
              
                  CompletableFuture<Void> cancelOrder(String id);
              }
              """,
          """
              com.example.OrderClientImpl
              package com.example;
              
              import org.springframework.stereotype.Component;
              import java.util.concurrent.CompletableFuture;
              
              @Component
              public class OrderClientImpl implements OrderApi {
              
                  public CompletableFuture<String> fetchOrder(String region, String tier) {
                      return CompletableFuture.completedFuture("ok");
                  }
              
                  public CompletableFuture<Void> cancelOrder(String id) {
                      return CompletableFuture.completedFuture(null);
                  }
              }
              """);

      assertThat(c).failed();
      assertThat(c).hadErrorContaining("Key resolver 'resolveOrderKey' on 'OrderApi' is static, but must be non-static");
    }
  }
}
