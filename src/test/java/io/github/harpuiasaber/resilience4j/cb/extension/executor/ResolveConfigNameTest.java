package io.github.harpuiasaber.resilience4j.cb.extension.executor;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.ConfigurationNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResolveConfigNameTest {

  @Mock
  private CircuitBreakerRegistry registry;

  @InjectMocks
  private KeyedCircuitBreakerExecutor executor;

  @Test
  @DisplayName("configName exists → use configName")
  void instanceNameExists() {
    var circuitBreaker = CircuitBreaker.of("payment", CircuitBreakerConfig.custom().build());
    when(registry.find("payment"))
        .thenReturn(Optional.of(circuitBreaker));
    var result = executor.resolveConfig("payment", "payment:VN:PREMIUM");
    assertThat(result).isEqualTo(circuitBreaker.getCircuitBreakerConfig());
  }

  @Test
  @DisplayName("configName missing, instanceName exists → use instanceName")
  void fallbackToConfigName() {
    var circuitBreaker = CircuitBreaker.of("payment:VN:PREMIUM", CircuitBreakerConfig.custom().build());
    when(registry.find("payment"))
        .thenReturn(Optional.empty());
    when(registry.find("payment:VN:PREMIUM"))
        .thenReturn(Optional.of(circuitBreaker));
    var result = executor.resolveConfig("payment", "payment:VN:PREMIUM");
    assertThat(result).isEqualTo(circuitBreaker.getCircuitBreakerConfig());
  }

  @Test
  @DisplayName("null configName → only instanceName used")
  void nullConfigName() {
    var circuitBreaker = CircuitBreaker.of("payment:VN:PREMIUM", CircuitBreakerConfig.custom().build());
    when(registry.find("payment:VN:PREMIUM"))
        .thenReturn(Optional.of(circuitBreaker));
    var result = executor.resolveConfig(null, "payment:VN:PREMIUM");
    assertThat(result).isEqualTo(circuitBreaker.getCircuitBreakerConfig());
  }

  @Test
  @DisplayName("empty configName → only instanceName used")
  void emptyConfigName() {
    var circuitBreaker = CircuitBreaker.of("payment:VN:PREMIUM", CircuitBreakerConfig.custom().build());
    when(registry.find("payment:VN:PREMIUM"))
        .thenReturn(Optional.of(circuitBreaker));
    var result = executor.resolveConfig("", "payment:VN:PREMIUM");
    assertThat(result).isEqualTo(circuitBreaker.getCircuitBreakerConfig());
  }

  @Test
  @DisplayName("blank configName → only instanceName used")
  void blankConfigName() {
    var circuitBreaker = CircuitBreaker.of("payment:VN:PREMIUM", CircuitBreakerConfig.custom().build());
    when(registry.find("payment:VN:PREMIUM"))
        .thenReturn(Optional.of(circuitBreaker));
    var result = executor.resolveConfig("   ", "payment:VN:PREMIUM");
    assertThat(result).isEqualTo(circuitBreaker.getCircuitBreakerConfig());
  }

  @Test
  @DisplayName("no config found → throw exception")
  void notFound() {
    when(registry.find("payment:VN:PREMIUM"))
        .thenReturn(Optional.empty());
    when(registry.find("payment"))
        .thenReturn(Optional.empty());
    assertThatThrownBy(() -> executor.resolveConfig("payment", "payment:VN:PREMIUM"))
        .isInstanceOf(ConfigurationNotFoundException.class);
  }
}
