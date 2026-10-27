package io.frayground.agent;

import io.frayground.domain.action.ActionSubmissionResult;
import java.util.Objects;

/**
 * Result of one runner pass.
 *
 * <p>{@code Submitted} pairs the runtime candidate with the authoritative
 * receipt. {@code NotSubmitted} means no action was submitted.
 */
public sealed interface DecisionOutcome
    permits DecisionOutcome.Submitted, DecisionOutcome.NotSubmitted {

  record Submitted(RuntimeOutcome candidate, ActionSubmissionResult submission)
      implements DecisionOutcome {
    public Submitted {
      Objects.requireNonNull(candidate);
      Objects.requireNonNull(submission);
    }
  }

  record NotSubmitted(RuntimeError error) implements DecisionOutcome {
    public NotSubmitted {
      Objects.requireNonNull(error);
    }
  }
}
