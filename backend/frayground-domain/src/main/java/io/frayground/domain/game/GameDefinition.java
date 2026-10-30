package io.frayground.domain.game;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rules for one game: initialize, observe, validate, settle a phase, and judge
 * the ending.
 *
 * <p>JSON stays outside these methods. A successful {@link #resolve} is what
 * later advances {@code stateVersion} by one for the whole phase. Passing or
 * resigning is settled here when the game accepts that action. Cancelling a
 * phase is not a rules result.
 */
public interface GameDefinition<
    S extends GameState, A extends GameAction, O extends GameObservation> {

  /** Stable game type and rules version. */
  GameKey key();

  /** Opening state for the seated participants. */
  S initialize(List<Participant> participants);

  /**
   * Next phase after {@code state}. Sequential mode lists one required seat;
   * simultaneous mode lists every seat that must act. One match still has one
   * current turn.
   */
  TurnPlan nextTurn(S state, List<Participant> participants);

  /** Seat-legal observation. It does not expose another seat's hidden state. */
  O observe(S state, UUID participantId);

  /** Actions that seat may submit from {@code state}, and how they are generated. */
  ActionSpecification<A> actions(S state, UUID participantId);

  /** Rules check for one action. Acceptance is not yet settlement. */
  ValidationResult validate(S state, UUID participantId, A action);

  /**
   * Settle every action collected for the phase. One call covers a single
   * sequential action or every simultaneous action in that phase.
   */
  Resolution<S> resolve(S state, Map<UUID, A> phaseActions);

  /** Current rules ending, including an ongoing match. */
  GameResult result(S state);

  /**
   * Events that seat may see. The input is the recorded fact log; hidden facts
   * are omitted.
   */
  List<VisibleGameEvent> visibleHistory(
      UUID participantId, List<GameEvent> authoritativeEvents);
}
