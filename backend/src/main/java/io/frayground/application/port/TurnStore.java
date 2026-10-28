package io.frayground.application.port;
import io.frayground.domain.match.TurnContext;
import java.util.*;
public interface TurnStore {
  void insert(UUID matchId, TurnContext turn);
  Optional<TurnContext> find(UUID turnId);
  boolean replace(TurnContext expected, TurnContext replacement);
}
