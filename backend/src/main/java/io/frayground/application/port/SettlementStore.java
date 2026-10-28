package io.frayground.application.port;
import io.frayground.domain.action.Settlement;
import java.util.*;
public interface SettlementStore extends SettlementRepository {
  Optional<Settlement> findBySettlementId(UUID settlementId);
  Optional<Settlement> findByTurnId(UUID turnId);
}
