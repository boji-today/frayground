package io.frayground.web;

import io.frayground.application.LaboratoryRoomInitializer;
import io.frayground.application.query.LaboratoryQueryService;
import io.frayground.config.LaboratoryProperties;
import io.frayground.engine.TurnScheduler;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/rooms")
@Profile("laboratory")
public class RoomController {
  private final LaboratoryRoomInitializer initializer; private final LaboratoryQueryService queries;
  private final LaboratoryProperties properties; private final LaboratoryViewMapper mapper;
  private final LaboratoryRequestDecoder decoder; private final TurnScheduler scheduler; private final RoomEventStreamService streams;
  public RoomController(LaboratoryRoomInitializer initializer,LaboratoryQueryService queries,
      LaboratoryProperties properties,LaboratoryViewMapper mapper,LaboratoryRequestDecoder decoder,TurnScheduler scheduler,RoomEventStreamService streams) {
    this.initializer=initializer; this.queries=queries; this.properties=properties; this.mapper=mapper;
    this.decoder=decoder; this.scheduler=scheduler; this.streams=streams;
  }
  @PostMapping("/current") public ResponseEntity<?> initialize() {
    var result=initializer.initialize();
    return ResponseEntity.status(result.created()?201:200).body(mapper.room(queries.room(properties.roomId())));
  }
  @GetMapping("/current") public Object current() { return mapper.room(queries.room(properties.roomId())); }
  @GetMapping("/{roomId}") public Object room(@PathVariable UUID roomId) { return mapper.room(queries.room(roomId)); }
  @GetMapping(value="/{roomId}/events",produces="text/event-stream")
  public SseEmitter events(@PathVariable UUID roomId,@RequestHeader(value="Last-Event-ID",required=false) String last,
      @RequestParam(required=false) String after) {
    String cursor=last!=null?last:after;
    if(cursor!=null && !cursor.matches("0|[1-9][0-9]{0,15}"))throw new IllegalArgumentException("cursor");
    long value=cursor==null?0:Long.parseLong(cursor);
    if(value>9007199254740991L)throw new IllegalArgumentException("cursor");
    return streams.subscribe(roomId,value);
  }
  @PostMapping("/{roomId}/commands") public ResponseEntity<?> command(@PathVariable UUID roomId,HttpServletRequest request) throws IOException {
    var command=decoder.command(request.getInputStream().readNBytes(8193));
    queries.room(roomId);
    var result=scheduler.handleCommand(roomId,command);
    return result.errorCode()==null?ResponseEntity.ok(mapper.ack(result)):LaboratoryErrors.command(result.errorCode(),command.commandId());
  }
}
