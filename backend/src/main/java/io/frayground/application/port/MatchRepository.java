package io.frayground.application.port;

import java.util.UUID;

public interface MatchRepository {
  boolean compareAndSetVersion(UUID matchId, long expected, long next);
}
