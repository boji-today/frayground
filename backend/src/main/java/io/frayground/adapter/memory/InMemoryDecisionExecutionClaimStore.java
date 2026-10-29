package io.frayground.adapter.memory;

import io.frayground.application.port.*;
import io.frayground.domain.action.ActionEnvelope;
import java.util.*;

public final class InMemoryDecisionExecutionClaimStore implements DecisionExecutionClaimStore {
  private final InMemoryStorage storage;
  public InMemoryDecisionExecutionClaimStore(InMemoryStorage storage) { this.storage=storage; }
  public boolean claim(UUID id) {
    var state=storage.writeState();
    if (!state.decisions.containsKey(id)) throw new StorageConflictException("Decision does not exist");
    return state.executionClaims.add(id);
  }
  public void saveEnvelope(ActionEnvelope e) {
    var state=storage.writeState();
    if (!state.executionClaims.contains(e.decisionId())) throw new StorageConflictException("Decision is not claimed");
    var value=copy(e); var old=state.executionEnvelopes.putIfAbsent(e.decisionId(),value);
    if (old!=null && !old.equals(value)) throw new StorageConflictException("Execution envelope already fixed");
  }
  public Optional<ActionEnvelope> findEnvelope(UUID id) {
    return Optional.ofNullable(storage.readState().executionEnvelopes.get(id)).map(InMemoryDecisionExecutionClaimStore::copy);
  }
  private static ActionEnvelope copy(ActionEnvelope e) {
    return new ActionEnvelope(e.protocolVersion(),e.matchId(),e.participantId(),e.turnId(),e.decisionId(),e.actionId(),e.stateVersion(),MemoryValueCopy.map(e.action()));
  }
}
