package io.frayground.application.port;
import io.frayground.domain.match.Match;
import java.util.*;
public interface MatchStore extends MatchRepository {
  Optional<Match> find(UUID matchId);
  void insert(Match match);
  boolean replace(Match expected, Match replacement);
}
