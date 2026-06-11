package io.github.toannq.resilience4j.cb.extension.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

@DisplayName("KeyedCircuitBreakerProcessor — compile-time tests")
class KeyedCircuitBreakerProcessorTest {

  private Compilation compile(String... sources) {
    var fileObjects = Arrays.stream(sources)
        .map(src -> {
          int nl = src.indexOf('\n');
          String path = src.substring(0, nl).trim();
          String body = src.substring(nl + 1);
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
    @DisplayName("generates KeyedCbProxy file")
    void generatesProxyFile() {
      var c = compile(orderClientWithKeyResolver());

      assertThat(c).succeeded();
      assertThat(c).generatedSourceFile("com.example.OrderClientKeyedCbProxy");
    }

    @Test
    @DisplayName("delegate calls cbExecutor.execute with _cbKey variable from resolver")
    void delegateCallsExecuteWithKeyVariable() {
      var c = compile(orderClientWithKeyResolver());

      assertThat(c).succeeded();
      var contents = assertThat(c)
          .generatedSourceFile("com.example.OrderClientKeyedCbProxy")
          .contentsAsUtf8String();
      contents.contains("var cbKey = this.delegate.resolveOrderKey(");
      contents.contains("this.circuitBreakerExecutor.execute(");
      contents.contains("cbKey");
    }

    @Test
    @DisplayName("no keyResolverMethod → passes null as key argument")
    void noKeyResolver_passesNullKey() {
      var c = compile("""
          com.example.SimpleClient
          package com.example;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
          import org.springframework.stereotype.Component;
          import java.util.concurrent.CompletableFuture;
          
          @KeyedCircuitBreakerClient
          @Component
          public class SimpleClient {
              @KeyedCircuitBreaker(name = "simple")
              public CompletableFuture<String> fetch(String id) {
                  return CompletableFuture.completedFuture("ok");
              }
          }
          """);

      assertThat(c).succeeded();
      var src = assertThat(c)
          .generatedSourceFile("com.example.SimpleClientKeyedCbProxy")
          .contentsAsUtf8String();
      src.contains("null");
      src.doesNotContain("cbKey");
    }

    @Test
    @DisplayName("fallbackMethod set → delegate contains fallback lambda, not null")
    void withFallback_generatesFallbackLambda() {
      var c = compile(orderClientWithKeyResolverAndFallback());

      assertThat(c).succeeded();
      assertThat(c)
          .generatedSourceFile("com.example.OrderClientKeyedCbProxy")
          .contentsAsUtf8String()
          .contains("fetchOrderFallback");
    }

    @Test
    @DisplayName("no fallbackMethod → execute call ends with , null)")
    void noFallback_passesNullFallback() {
      var c = compile("""
          com.example.NoFallbackClient
          package com.example;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
          import org.springframework.stereotype.Component;
          import java.util.concurrent.CompletableFuture;
          
          @KeyedCircuitBreakerClient
          @Component
          public class NoFallbackClient {
              @KeyedCircuitBreaker(name = "cb")
              public CompletableFuture<String> fetch(String id) {
                  return CompletableFuture.completedFuture("ok");
              }
          }
          """);

      assertThat(c).succeeded();
      assertThat(c)
          .generatedSourceFile("com.example.NoFallbackClientKeyedCbProxy")
          .contentsAsUtf8String()
          .contains("null)");
    }

    @Test
    @DisplayName("configuration contains @Component, @Bean, @Primary, @Qualifier")
    void configurationAnnotations() {
      var c = compile(orderClientWithKeyResolver());

      assertThat(c).succeeded();
      var contents = assertThat(c)
          .generatedSourceFile("com.example.OrderClientKeyedCbProxy")
          .contentsAsUtf8String();
      contents.contains("@Component");
      contents.contains("@Primary");
      contents.contains("@Qualifier(");
      contents.contains("this.delegate");
      contents.contains("this.circuitBreakerExecutor");
    }

    @Test
    @DisplayName("non-annotated method generates passthrough delegation (no cbExecutor call)")
    void passthroughForNonAnnotatedMethod() {
      var c = compile("""
          com.example.MixedClient
          package com.example;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
          import org.springframework.stereotype.Component;
          import java.util.concurrent.CompletableFuture;
          
          @KeyedCircuitBreakerClient
          @Component
          public class MixedClient {
              @KeyedCircuitBreaker(name = "mixed")
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
          .generatedSourceFile("com.example.MixedClientKeyedCbProxy")
          .contentsAsUtf8String()
          .contains("delegate.untracked(id)");
    }

    @Test
    @DisplayName("configName defaults to empty (blank) in generated code")
    void configNameDefaultsToBlankInGeneratedCode() {
      var c = compile("""
          com.example.DefaultConfigClient
          package com.example;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
          import org.springframework.stereotype.Component;
          import java.util.concurrent.CompletableFuture;
          
          @KeyedCircuitBreakerClient
          @Component
          public class DefaultConfigClient {
              @KeyedCircuitBreaker(name = "myCb")
              public CompletableFuture<String> fetch(String id) {
                  return CompletableFuture.completedFuture("ok");
              }
          }
          """);

      assertThat(c).succeeded();
      var contents = assertThat(c)
          .generatedSourceFile("com.example.DefaultConfigClientKeyedCbProxy")
          .contentsAsUtf8String();
      contents.contains("\"myCb\"");
      contents.contains("\"\"");
    }
  }

  @Nested
  @DisplayName("Compile-time validation errors")
  class ValidationErrors {

    @Test
    @DisplayName("error: @KeyedCircuitBreaker on non-CompletableFuture return type")
    void errorOnNonFutureReturnType() {
      var c = compile("""
          com.example.BadClient
          package com.example;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
          import org.springframework.stereotype.Component;
          
          @KeyedCircuitBreakerClient
          @Component
          public class BadClient {
              @KeyedCircuitBreaker(name = "bad")
              public String notAFuture(String id) { return "bad"; }
          }
          """);

      assertThat(c).failed();
      assertThat(c).hadErrorContaining("CompletableFuture");
    }

    @Test
    @DisplayName("error: keyResolverMethod does not exist on type")
    void errorWhenKeyResolverNotFound() {
      var c = compile("""
          com.example.MissingResolverClient
          package com.example;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
          import org.springframework.stereotype.Component;
          import java.util.concurrent.CompletableFuture;
          
          @KeyedCircuitBreakerClient
          @Component
          public class MissingResolverClient {
              @KeyedCircuitBreaker(name = "cb", keyResolverMethod = "doesNotExist")
              public CompletableFuture<String> fetch(String id) { return CompletableFuture.completedFuture("ok"); }
          }
          """);

      assertThat(c).failed();
      assertThat(c).hadErrorContaining("doesNotExist");
    }

    @Test
    @DisplayName("error: keyResolverMethod returns non-String type")
    void errorWhenKeyResolverReturnsWrongType() {
      var c = compile("""
          com.example.WrongReturnClient
          package com.example;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
          import org.springframework.stereotype.Component;
          import java.util.concurrent.CompletableFuture;
          
          @KeyedCircuitBreakerClient
          @Component
          public class WrongReturnClient {
              @KeyedCircuitBreaker(name = "cb", keyResolverMethod = "resolveKey")
              public CompletableFuture<String> fetch(String id) { return CompletableFuture.completedFuture("ok"); }

              public int resolveKey(String id) { return 1; }
          }
          """);

      assertThat(c).failed();
      assertThat(c).hadErrorContaining("resolveKey");
    }

    @Test
    @DisplayName("error: keyResolverMethod params don't match original method params")
    void errorWhenKeyResolverParamsMismatch() {
      var c = compile("""
          com.example.MismatchClient
          package com.example;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
          import org.springframework.stereotype.Component;
          import java.util.concurrent.CompletableFuture;
          
          @KeyedCircuitBreakerClient
          @Component
          public class MismatchClient {
              @KeyedCircuitBreaker(name = "cb", keyResolverMethod = "resolveKey")
              public CompletableFuture<String> fetch(String id) { return CompletableFuture.completedFuture("ok"); }

              public String resolveKey(String id, String extra) { return id + extra; }
          }
          """);

      assertThat(c).failed();
      assertThat(c).hadErrorContaining("resolveKey");
    }

    @Test
    @DisplayName("error: fallbackMethod does not exist on type")
    void errorWhenFallbackNotFound() {
      var c = compile("""
          com.example.NoFallbackClient
          package com.example;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
          import org.springframework.stereotype.Component;
          import java.util.concurrent.CompletableFuture;
          
          @KeyedCircuitBreakerClient
          @Component
          public class NoFallbackClient {
              @KeyedCircuitBreaker(name = "cb", fallbackMethod = "missingFallback")
              public CompletableFuture<String> fetch(String id) { return CompletableFuture.completedFuture("ok"); }
          }
          """);

      assertThat(c).failed();
      assertThat(c).hadErrorContaining("missingFallback");
    }

    @Test
    @DisplayName("error: fallbackMethod missing trailing Throwable param")
    void errorWhenFallbackMissingThrowable() {
      var c = compile("""
          com.example.BadFallbackClient
          package com.example;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
          import org.springframework.stereotype.Component;
          import java.util.concurrent.CompletableFuture;
          
          @KeyedCircuitBreakerClient
          @Component
          public class BadFallbackClient {
              @KeyedCircuitBreaker(name = "cb", fallbackMethod = "fetchFallback")
              public CompletableFuture<String> fetch(String id) { return CompletableFuture.completedFuture("ok"); }
          
              public CompletableFuture<String> fetchFallback(String id) { return CompletableFuture.completedFuture("fb"); }
          }
          """);

      assertThat(c).failed();
      assertThat(c).hadErrorContaining("fetchFallback");
    }

    @Test
    @DisplayName("warning (not error): @KeyedCircuitBreakerClient with no annotated methods")
    void warningWhenNoAnnotatedMethods() {
      var c = compile("""
          com.example.EmptyClient
          package com.example;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
          import org.springframework.stereotype.Component;
          import java.util.concurrent.CompletableFuture;
          
          @KeyedCircuitBreakerClient
          @Component
          public class EmptyClient {
              public CompletableFuture<String> plain(String id) { return CompletableFuture.completedFuture("ok"); }
          }
          """);

      assertThat(c).succeeded();
      assertThat(c).hadWarningContaining("no @KeyedCircuitBreaker methods");
    }

    @Test
    @DisplayName("error: @KeyedCircuitBreakerClient on final class")
    void errorOnFinalClass() {
      var c = compile("""
          com.example.FinalClient
          package com.example;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
          import org.springframework.stereotype.Component;
          import java.util.concurrent.CompletableFuture;

          @KeyedCircuitBreakerClient
          @Component
          public final class FinalClient {
              @KeyedCircuitBreaker(name = "cb")
              public CompletableFuture<String> fetch(String id) { return CompletableFuture.completedFuture("ok"); }
          }
          """);

      assertThat(c).failed();
      assertThat(c).hadErrorContaining("cannot be placed on a final class");
    }

    @Test
    @DisplayName("error: @KeyedCircuitBreakerClient requires Spring bean annotation")
    void errorWhenNotSpringBean() {
      var c = compile("""
          com.example.NoBeanClient
          package com.example;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
          import java.util.concurrent.CompletableFuture;

          @KeyedCircuitBreakerClient
          public class NoBeanClient {
              @KeyedCircuitBreaker(name = "cb")
              public CompletableFuture<String> fetch(String id) { return CompletableFuture.completedFuture("ok"); }
          }
          """);

      assertThat(c).failed();
      assertThat(c).hadErrorContaining("requires the class to be a Spring bean");
    }

    @Test
    @DisplayName("error: @KeyedCircuitBreakerClient cannot be placed on a @Primary class")
    void errorWhenPrimaryClass() {
      var c = compile("""
          com.example.PrimaryClient
          package com.example;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
          import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
          import org.springframework.stereotype.Component;
          import org.springframework.context.annotation.Primary;
          import java.util.concurrent.CompletableFuture;

          @KeyedCircuitBreakerClient
          @Component
          @Primary
          public class PrimaryClient {
              @KeyedCircuitBreaker(name = "cb")
              public CompletableFuture<String> fetch(String id) { return CompletableFuture.completedFuture("ok"); }
          }
          """);

      assertThat(c).failed();
      assertThat(c).hadErrorContaining("cannot be placed on a @Primary class");
    }
  }

  private String orderClientWithKeyResolver() {
    return """
        com.example.OrderClient
        package com.example;
        import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
        import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
        import org.springframework.stereotype.Component;
        import java.util.concurrent.CompletableFuture;
        
        @KeyedCircuitBreakerClient
        @Component
        public class OrderClient {
        
            @KeyedCircuitBreaker(name = "order", keyResolverMethod = "resolveOrderKey")
            public CompletableFuture<String> fetchOrder(String region, String tier) { return CompletableFuture.completedFuture("ok"); }
        
            public String resolveOrderKey(String region, String tier) { return region + ":" + tier; }
        
            public CompletableFuture<Void> cancelOrder(String id) { return CompletableFuture.completedFuture(null); }
        }
        """;
  }

  private String orderClientWithKeyResolverAndFallback() {
    return """
        com.example.OrderClient
        package com.example;
        import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreakerClient;
        import io.github.toannq.resilience4j.cb.extension.annotation.KeyedCircuitBreaker;
        import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
        import org.springframework.stereotype.Component;
        import java.util.concurrent.CompletableFuture;
        
        @KeyedCircuitBreakerClient
        @Component
        public class OrderClient {
        
            @KeyedCircuitBreaker(
                name = "order",
                keyResolverMethod = "resolveOrderKey",
                fallbackMethod = "fetchOrderFallback"
            )
            public CompletableFuture<String> fetchOrder(String region, String tier) { return CompletableFuture.completedFuture("ok"); }
        
            public String resolveOrderKey(String region, String tier) { return region + ":" + tier; }
        
            public CompletableFuture<String> fetchOrderFallback(String region, String tier, CallNotPermittedException t) { return CompletableFuture.completedFuture("fallback"); }
        
            public CompletableFuture<Void> cancelOrder(String id) { return CompletableFuture.completedFuture(null); }
        }
        """;
  }
}
