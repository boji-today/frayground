package io.frayground.application;

import io.frayground.domain.action.ActionEnvelope;
import io.frayground.domain.action.ActionSubmissionResult;
import java.util.concurrent.CompletionStage;

/**
 * Submits one action inside the room transaction.
 *
 * <p>It checks eligibility, deadline, match, turn, decision, state version,
 * and the rules again, then returns the authoritative receipt. A repeated
 * {@code actionId} returns the original result.
 */
public interface ActionSubmitter {
  CompletionStage<ActionSubmissionResult> submit(ActionEnvelope envelope);
}
