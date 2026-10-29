package io.frayground.web;

import io.frayground.application.query.LaboratoryQueryService;
import io.frayground.domain.match.RoomEvent;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Bounded notification streams; network writes never run on game scheduler threads. */
public final class RoomEventStreamService implements AutoCloseable {
  public static final class LimitExceeded extends RuntimeException {}
  public static final class InvalidCursor extends RuntimeException {}
  private record EventView(long sequence,UUID roomId,UUID matchId,long roomRevision,String type,
      java.time.Instant occurredAt,Map<String,Object> payload) {}
  private static final Object HEARTBEAT=new Object();
  private final LaboratoryQueryService queries;
  private final ScheduledExecutorService polling=Executors.newSingleThreadScheduledExecutor();
  private static final long ASYNC_TIMEOUT_MS=30_000L;
  private static ThreadFactory daemonFactory(String name) {
    return task -> { Thread t=new Thread(task,name); t.setDaemon(true); return t; };
  }
  private final ExecutorService writers=new ThreadPoolExecutor(4,4,0L,TimeUnit.MILLISECONDS,
      new SynchronousQueue<>(),daemonFactory("laboratory-sse-writer"),new ThreadPoolExecutor.AbortPolicy());
  private final ExecutorService completion=new ThreadPoolExecutor(4,4,0L,TimeUnit.MILLISECONDS,
      new SynchronousQueue<>(),daemonFactory("laboratory-sse-close"),new ThreadPoolExecutor.AbortPolicy());
  private final Supplier<SseEmitter> emitters;
  private final Semaphore capacity=new Semaphore(4);
  private final Set<Session> sessions=ConcurrentHashMap.newKeySet();
  public RoomEventStreamService(LaboratoryQueryService queries) { this(queries,()->new SseEmitter(ASYNC_TIMEOUT_MS)); }
  RoomEventStreamService(LaboratoryQueryService queries,Supplier<SseEmitter> emitters) {
    this.queries=queries; this.emitters=emitters;
  }
  public SseEmitter subscribe(UUID roomId,long after) {
    var room=queries.room(roomId).room();
    if(after<0)throw new IllegalArgumentException("after");
    if(after>room.eventSeq())throw new InvalidCursor();
    if(!capacity.tryAcquire())throw new LimitExceeded();
    Session s;
    try { s=new Session(roomId,after); }
    catch(RuntimeException e) { capacity.release(); throw e; }
    sessions.add(s);
    s.emitter.onCompletion(s::close); s.emitter.onTimeout(s::close); s.emitter.onError(e->s.close());
    try {
      writers.execute(s::write);
      s.task=polling.scheduleAtFixedRate(s::poll,0,250,TimeUnit.MILLISECONDS);
      if(s.closed.get())s.task.cancel(false);
      return s.emitter;
    } catch(RejectedExecutionException e) { s.close(); throw new LimitExceeded(); }
      catch(RuntimeException e) { s.close(); throw e; }
  }
  private final class Session {
    final UUID roomId; final SseEmitter emitter=emitters.get();
    final BlockingQueue<Object> queue=new ArrayBlockingQueue<>(128);
    final AtomicBoolean closed=new AtomicBoolean();
    volatile ScheduledFuture<?> task; long cursor; long heartbeat=System.nanoTime();
    Session(UUID roomId,long cursor) { this.roomId=roomId; this.cursor=cursor; }
    void poll() {
      if(closed.get())return;
      try {
        for(RoomEvent event:queries.roomEvents(roomId,cursor,100)) {
          if(!queue.offer(project(event))) { close(); return; }
          cursor=event.sequence();
        }
        long now=System.nanoTime();
        if(now-heartbeat>=TimeUnit.SECONDS.toNanos(15)) {
          if(!queue.offer(HEARTBEAT)) { close(); return; } heartbeat=now;
        }
      } catch(RuntimeException e) { close(); }
    }
    void write() {
      try {
        while(!closed.get()) {
          Object item=queue.poll(1,TimeUnit.SECONDS);
          if(item==null)continue;
          if(item==HEARTBEAT)emitter.send(SseEmitter.event().comment("heartbeat"));
          else { EventView e=(EventView)item; emitter.send(SseEmitter.event().id(Long.toString(e.sequence())).name(e.type()).data(e)); }
        }
      } catch(IOException | InterruptedException | RuntimeException e) {
        if(e instanceof InterruptedException)Thread.currentThread().interrupt();
      } finally { close(); }
    }
    void close() {
      if(!closed.compareAndSet(false,true))return;
      if(task!=null)task.cancel(false); queue.clear(); sessions.remove(this); capacity.release();
      // complete() may wait behind a blocked send's write lock. Never do it on the polling thread.
      try { completion.execute(emitter::complete); }
      catch(RejectedExecutionException ignored) { /* Servlet async timeout also terminates the response. */ }
    }
  }
  private static EventView project(RoomEvent e) {
    String type=Set.of("ROOM_INITIALIZED","MATCH_RESET","EXECUTION_CHANGED","DECISION_STARTED",
        "DECISION_FINISHED","ACTION_RESOLVED","MATCH_FINISHED","ROOM_UNAVAILABLE",
        "SERVER_RECOVERED","PROMPT_PUBLISHED","CONFIGURATION_PUBLISHED").contains(e.type())?e.type():"STATE_CHANGED";
    List<String> keys=switch(type) {
      case "ROOM_INITIALIZED", "MATCH_RESET" -> List.of("currentMatchId");
      case "EXECUTION_CHANGED" -> List.of("executionMode","executionStatus");
      case "DECISION_STARTED" -> List.of("decisionId","participantId","promptVersionId");
      case "DECISION_FINISHED" -> List.of("decisionId","status","errorCode");
      case "ACTION_RESOLVED" -> List.of("decisionId","stateVersion");
      case "PROMPT_PUBLISHED" -> List.of("agentId","promptVersionId","versionNo");
      case "CONFIGURATION_PUBLISHED" -> List.of("agentId","configVersionId","versionNo");
      default -> List.of();
    };
    Map<String,Object> payload=new LinkedHashMap<>();
    for(String key:keys) {
      Object value=e.payload().get(key);
      if(value instanceof Number || value instanceof Boolean || value instanceof UUID ||
          (value instanceof String text && text.length()<=80 && text.matches("[A-Za-z0-9_-]+")))payload.put(key,value);
    }
    if(type.equals("ROOM_INITIALIZED") && e.matchId()!=null)payload.put("currentMatchId",e.matchId());
    return new EventView(e.sequence(),e.roomId(),e.matchId(),e.roomRevision(),type,e.occurredAt(),Map.copyOf(payload));
  }
  @PreDestroy @Override public void close() {
    sessions.forEach(Session::close); polling.shutdownNow(); writers.shutdownNow(); completion.shutdown();
  }
}
