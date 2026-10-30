package io.frayground.application.port;
import io.frayground.domain.game.GameEvent;
import java.util.*;
public interface GameEventStore {
  void append(GameEvent event);
  List<GameEvent> readAfter(UUID matchId, long sequence, int limit);
}
