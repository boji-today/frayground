package io.frayground.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.frayground.application.query.LaboratoryQueryService;
import io.frayground.application.query.LaboratoryReadModels.RoomData;
import io.frayground.domain.match.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class RoomEventStreamServiceTest {
  static final class BlockedEmitter extends SseEmitter {
    final CountDownLatch sending=new CountDownLatch(1), closing=new CountDownLatch(1), release=new CountDownLatch(1);
    BlockedEmitter() { super(30000L); }
    @Override public void send(SseEventBuilder event) throws IOException { sending.countDown(); awaitRelease(); }
    @Override public void complete() { closing.countDown(); awaitRelease(); }
    void awaitRelease() {
      try { release.await(5,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
  }
  private static Room room(UUID id,long seq) {
    return new Room(id,0,seq,null,ExecutionMode.STEP,ExecutionStatus.PAUSED,RoomHealth.OK,null);
  }
  @Test void overflowClosingBlockedEmitterDoesNotBlockOtherPolling() throws Exception {
    var queries=mock(LaboratoryQueryService.class); UUID first=UUID.randomUUID(), second=UUID.randomUUID();
    when(queries.room(first)).thenReturn(new RoomData(room(first,1000),null));
    when(queries.room(second)).thenReturn(new RoomData(room(second,1),null));
    var secondPolled=new CountDownLatch(1);
    when(queries.roomEvents(any(),anyLong(),anyInt())).thenAnswer(call->{
      UUID id=call.getArgument(0); long after=call.getArgument(1);
      if(id.equals(second)) { secondPolled.countDown(); return List.of(); }
      var events=new ArrayList<RoomEvent>();
      for(long i=after+1;i<=Math.min(after+100,1000);i++)
        events.add(new RoomEvent(first,i,null,0,"EXECUTION_CHANGED",Map.of(),Instant.EPOCH));
      return events;
    });
    var blocked=new BlockedEmitter(); var count=new AtomicInteger();
    try(var streams=new RoomEventStreamService(queries,()->count.getAndIncrement()==0?blocked:new SseEmitter(30000L))) {
      streams.subscribe(first,0);
      assertTrue(blocked.sending.await(5,TimeUnit.SECONDS));
      assertTrue(blocked.closing.await(5,TimeUnit.SECONDS),"overflow should start asynchronous cleanup");
      streams.subscribe(second,0);
      assertTrue(secondPolled.await(5,TimeUnit.SECONDS),"another stream must still be polled");
    } finally { blocked.release.countDown(); }
  }
  @Test void limitAndCursorAreBoundedAndTimeoutIsFinite() {
    var queries=mock(LaboratoryQueryService.class); UUID id=UUID.randomUUID();
    when(queries.room(id)).thenReturn(new RoomData(room(id,0),null));
    when(queries.roomEvents(eq(id),anyLong(),anyInt())).thenReturn(List.of());
    try(var streams=new RoomEventStreamService(queries)) {
      assertThrows(RoomEventStreamService.InvalidCursor.class,()->streams.subscribe(id,1));
      for(int i=0;i<4;i++)assertEquals(30000L,streams.subscribe(id,0).getTimeout());
      assertThrows(RoomEventStreamService.LimitExceeded.class,()->streams.subscribe(id,0));
    }
    assertEquals("1",new LaboratoryExceptionHandler().limit().getHeaders().getFirst("Retry-After"));
  }
}
