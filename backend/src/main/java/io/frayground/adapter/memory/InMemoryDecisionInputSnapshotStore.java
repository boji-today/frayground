package io.frayground.adapter.memory;

import io.frayground.application.DecisionInputSnapshot;
import io.frayground.application.port.DecisionInputSnapshotStore;
import io.frayground.application.port.StorageConflictException;
import java.util.Optional;
import java.util.UUID;

public final class InMemoryDecisionInputSnapshotStore implements DecisionInputSnapshotStore {
  private final InMemoryStorage storage;
  public InMemoryDecisionInputSnapshotStore(InMemoryStorage storage) { this.storage = storage; }
  @Override public void save(DecisionInputSnapshot snapshot) {
    var state = storage.writeState();
    var id = snapshot.started().decisionId();
    if (!state.decisions.containsKey(id)) throw new StorageConflictException("Decision does not exist");
    var old = state.decisionInputs.putIfAbsent(id, snapshot);
    if (old != null && !old.equals(snapshot)) throw new StorageConflictException("Decision input conflict");
  }
  @Override public Optional<DecisionInputSnapshot> find(UUID decisionId) {
    return Optional.ofNullable(storage.readState().decisionInputs.get(decisionId));
  }
}
