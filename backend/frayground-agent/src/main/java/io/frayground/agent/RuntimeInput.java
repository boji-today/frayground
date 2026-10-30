package io.frayground.agent;

import java.util.Objects;
import java.util.Optional;

/**
 * Input passed to a runtime for one decision.
 *
 * <p>{@code publicRequest} is the serializable observation contract.
 * {@code hostedBindings} are loaded from the versions fixed when the decision
 * started. A third party does not receive those bindings.
 */
public record RuntimeInput(
    DecisionRequest publicRequest, Optional<HostedBindings> hostedBindings) {

  public RuntimeInput {
    Objects.requireNonNull(publicRequest);
    Objects.requireNonNull(hostedBindings);
  }
}
