package io.frayground.domain.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActionEnvelopeTest {

  @Test
  void acceptsProtocolAndCopiesAction() {
    Map<String, Object> action = new HashMap<>();
    action.put("column", 3);

    ActionEnvelope envelope = envelope("1.0", 0, action);
    action.put("column", 9);

    assertThat(envelope.action()).containsEntry("column", 3);
    assertThat(envelope.stateVersion()).isZero();
  }

  @Test
  void rejectsOtherProtocolVersions() {
    assertThatThrownBy(() -> envelope("2.0", 0, Map.of("column", 0)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> envelope(null, 0, Map.of("column", 0)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNegativeVersionOrNullAction() {
    assertThatThrownBy(() -> envelope("1.0", -1, Map.of("column", 0)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> envelope("1.0", 0, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNullActionValue() {
    Map<String, Object> action = new HashMap<>();
    action.put("column", null);

    assertThatThrownBy(() -> envelope("1.0", 0, action))
        .isInstanceOf(NullPointerException.class);
  }

  private ActionEnvelope envelope(String protocol, long version, Map<String, Object> action) {
    return new ActionEnvelope(
        protocol,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        version,
        action);
  }
}
