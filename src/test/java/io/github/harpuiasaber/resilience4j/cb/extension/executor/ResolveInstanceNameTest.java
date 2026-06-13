package io.github.harpuiasaber.resilience4j.cb.extension.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ResolveInstanceNameTest {

  @Test
  @DisplayName("non-blank baseName and key → baseName:key")
  void nonBlankKey() {
    assertThat(KeyedCircuitBreakerExecutor.resolveInstanceName("payment", "VN:PREMIUM"))
        .isEqualTo("payment:VN:PREMIUM");
  }

  @Test
  @DisplayName("null baseName → return key")
  void nullBaseName() {
    assertThat(KeyedCircuitBreakerExecutor.resolveInstanceName(null, "VN:PREMIUM"))
        .isEqualTo("VN:PREMIUM");
  }

  @Test
  @DisplayName("blank baseName → return key")
  void blankBaseName() {
    assertThat(KeyedCircuitBreakerExecutor.resolveInstanceName("  ", "VN:PREMIUM"))
        .isEqualTo("VN:PREMIUM");
  }

  @Test
  @DisplayName("empty baseName → return key")
  void emptyBaseName() {
    assertThat(KeyedCircuitBreakerExecutor.resolveInstanceName("", "VN:PREMIUM"))
        .isEqualTo("VN:PREMIUM");
  }

  @Test
  @DisplayName("null key → baseName only")
  void nullKey() {
    assertThat(KeyedCircuitBreakerExecutor.resolveInstanceName("payment", null))
        .isEqualTo("payment");
  }

  @Test
  @DisplayName("blank key → baseName only")
  void blankKey() {
    assertThat(KeyedCircuitBreakerExecutor.resolveInstanceName("payment", "  "))
        .isEqualTo("payment");
  }

  @Test
  @DisplayName("empty key → baseName only")
  void emptyKey() {
    assertThat(KeyedCircuitBreakerExecutor.resolveInstanceName("payment", ""))
        .isEqualTo("payment");
  }

  @Test
  @DisplayName("both null → return null")
  void bothNull() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> KeyedCircuitBreakerExecutor.resolveInstanceName(null, null))
        .withMessageContaining("baseName and key value must not both be blank");
  }

  @Test
  @DisplayName("both blank → return blank")
  void bothBlank() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> KeyedCircuitBreakerExecutor.resolveInstanceName("  ", " "))
        .withMessageContaining("baseName and key value must not both be blank");
  }

  @Test
  @DisplayName("both empty → return empty")
  void bothEmpty() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> KeyedCircuitBreakerExecutor.resolveInstanceName("", ""))
        .withMessageContaining("baseName and key value must not both be blank");
  }
}

