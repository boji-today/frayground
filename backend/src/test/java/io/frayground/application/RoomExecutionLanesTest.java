package io.frayground.application;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class RoomExecutionLanesTest {
  static class RejectOnce extends AbstractExecutorService {
    final ExecutorService delegate=Executors.newSingleThreadExecutor(); final AtomicBoolean reject=new AtomicBoolean(true);
    public void execute(Runnable task) { if (reject.getAndSet(false)) throw new RejectedExecutionException("first"); delegate.execute(task); }
    public void shutdown() { delegate.shutdown(); } public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
    public boolean isShutdown() { return delegate.isShutdown(); } public boolean isTerminated() { return delegate.isTerminated(); }
    public boolean awaitTermination(long timeout,TimeUnit unit) throws InterruptedException { return delegate.awaitTermination(timeout,unit); }
  }
  @Test void rejectionDoesNotPoisonRoomAndCloseRejectsNewTasks() throws Exception {
    var executor=new RejectOnce(); var lanes=new RoomExecutionLanes(executor); UUID room=UUID.randomUUID();
    var count=new AtomicInteger();
    assertThrows(RejectedExecutionException.class,()->lanes.submit(room,count::incrementAndGet));
    var done=new CountDownLatch(1); lanes.submit(room,()->{ count.incrementAndGet(); done.countDown(); });
    assertTrue(done.await(5,TimeUnit.SECONDS)); assertEquals(1,count.get());
    lanes.close(); assertThrows(RejectedExecutionException.class,()->lanes.submit(room,count::incrementAndGet));
  }
  @Test void concurrentSubmissionsAfterRejectionAreExecutedExactlyOnce() throws Exception {
    var lanes=new RoomExecutionLanes(new RejectOnce()); UUID room=UUID.randomUUID();
    assertThrows(RejectedExecutionException.class,()->lanes.submit(room,()->fail("rejected task executed")));
    var workers=Executors.newFixedThreadPool(8); var start=new CountDownLatch(1); var done=new CountDownLatch(100); var count=new AtomicInteger();
    try {
      List<Future<?>> submissions=new ArrayList<>();
      for (int i=0;i<100;i++) submissions.add(workers.submit(()->{ try { start.await(); lanes.submit(room,()->{ count.incrementAndGet(); done.countDown(); }); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }));
      start.countDown(); for (Future<?> f:submissions) f.get(5,TimeUnit.SECONDS);
      assertTrue(done.await(5,TimeUnit.SECONDS)); assertEquals(100,count.get());
    } finally { workers.shutdownNow(); lanes.close(); }
  }
}
