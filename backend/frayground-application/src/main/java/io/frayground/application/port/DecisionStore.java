package io.frayground.application.port;
import io.frayground.domain.action.Decision;
import java.util.*;
public interface DecisionStore extends DecisionRepository {
  Optional<Decision> find(UUID decisionId);
  List<Decision> findByTurn(UUID turnId);
  void insert(Decision decision);
  boolean replace(Decision expected, Decision replacement);
}
