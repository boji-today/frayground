package io.frayground.application.port;
import io.frayground.domain.action.ActionEnvelope;
import java.util.*;
public interface ActionReceiptStore extends ActionReceiptRepository {
  Optional<ActionEnvelope> findEnvelope(UUID actionId);
}
