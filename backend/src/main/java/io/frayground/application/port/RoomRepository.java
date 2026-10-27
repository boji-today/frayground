package io.frayground.application.port;

import java.util.UUID;

public interface RoomRepository {
  LockedRoom lock(UUID roomId);
}
