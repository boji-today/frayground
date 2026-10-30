package io.frayground.application.port;
import io.frayground.domain.action.DecisionRecord;
import java.util.*;
public interface DecisionRecordStore {
  Optional<DecisionRecord> find(UUID decisionId);
  void insert(DecisionRecord record);
  boolean replace(DecisionRecord expected, DecisionRecord replacement);
}
