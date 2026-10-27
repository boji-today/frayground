package io.frayground.application.port;

import io.frayground.domain.game.StateSnapshot;
import java.util.Optional;
import java.util.UUID;

/**
 * Appends and reads one match's committed state snapshots.
 *
 * <p>Each snapshot belongs to one {@code stateVersion}. Reading it does not
 * call a model.
 */
public interface SnapshotStore {
  void append(StateSnapshot snapshot);

  Optional<StateSnapshot> find(UUID matchId, long stateVersion);
}
