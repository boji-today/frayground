package io.frayground.application.port;

import io.frayground.domain.action.Settlement;

public interface SettlementRepository {
  void save(Settlement settlement);
}
