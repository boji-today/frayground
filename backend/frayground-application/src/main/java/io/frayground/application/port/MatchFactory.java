package io.frayground.application.port;

import io.frayground.application.InitializedMatch;
import io.frayground.domain.game.GameKey;
import io.frayground.domain.game.Participant;
import java.util.List;
import java.util.UUID;

/** Creates the in-memory initial values for one newly created match. */
public interface MatchFactory {
  InitializedMatch create(UUID roomId, GameKey gameKey, List<Participant> participants);
}
