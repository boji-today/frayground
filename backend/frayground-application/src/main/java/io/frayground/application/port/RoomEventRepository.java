package io.frayground.application.port;

import io.frayground.domain.match.RoomEvent;
import java.util.List;
import java.util.UUID;

public interface RoomEventRepository {
  void append(RoomEvent event);

  List<RoomEvent> readAfter(UUID roomId, long sequence, int limit);
}
