package io.frayground.adapter.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VendorHttpPoolTest {

  @Test
  void isolatesConnectionManagersByVendor() {
    try (VendorHttpPool deepseek = new VendorHttpPool("deepseek", 2);
        VendorHttpPool other = new VendorHttpPool("other", 3)) {
      assertThat(deepseek.client()).isNotSameAs(other.client());
      assertThat(deepseek.connectionManager()).isNotSameAs(other.connectionManager());
      assertThat(deepseek.connectionManager().getMaxTotal()).isEqualTo(2);
      assertThat(deepseek.connectionManager().getDefaultMaxPerRoute()).isEqualTo(2);
      assertThat(other.connectionManager().getMaxTotal()).isEqualTo(3);
      assertThat(other.connectionManager().getDefaultMaxPerRoute()).isEqualTo(3);
    }
  }

  @Test
  void rejectsANonPositivePoolSize() {
    assertThatThrownBy(() -> new VendorHttpPool("deepseek", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
