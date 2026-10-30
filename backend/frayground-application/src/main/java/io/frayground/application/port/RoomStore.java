package io.frayground.application.port;
import io.frayground.domain.match.Room;
import java.util.*;
public interface RoomStore extends RoomRepository {
  Optional<Room> find(UUID roomId);
  void insert(Room room);
  boolean replace(Room expected, Room replacement);
}
