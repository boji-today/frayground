package io.frayground.application.port;
import java.util.*;
public interface CommandReceiptStore extends CommandReceiptRepository {
  Optional<byte[]> findRequestHash(UUID roomId, UUID commandId);
}
