package io.frayground.application.port;

import io.frayground.application.DecisionInputSnapshot;
import java.util.Optional;
import java.util.UUID;

public interface DecisionInputSnapshotStore {
  void save(DecisionInputSnapshot snapshot);
  Optional<DecisionInputSnapshot> find(UUID decisionId);
}
