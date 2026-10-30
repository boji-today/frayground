package io.frayground.application.port;

import io.frayground.domain.match.CommandResult;
import java.util.Optional;
import java.util.UUID;

public interface CommandReceiptRepository {
  Optional<CommandResult> find(UUID roomId, UUID commandId);

  void save(UUID roomId, UUID commandId, byte[] requestHash, CommandResult result);
}
