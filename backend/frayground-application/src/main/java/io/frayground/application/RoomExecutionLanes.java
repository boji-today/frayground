package io.frayground.application;

import java.util.*;
import java.util.concurrent.*;

/** FIFO control queues, independent of model execution. */
public class RoomExecutionLanes implements AutoCloseable {
  private final ExecutorService executor;
  private final Map<UUID,ArrayDeque<Runnable>> queues=new HashMap<>();
  private boolean closed;
  public RoomExecutionLanes(ExecutorService executor) { this.executor=executor; }
  public synchronized void submit(UUID roomId,Runnable task) {
    Objects.requireNonNull(roomId); Objects.requireNonNull(task);
    if (closed) throw new RejectedExecutionException("Room lanes are closed");
    ArrayDeque<Runnable> queue=queues.computeIfAbsent(roomId,k->new ArrayDeque<>());
    queue.add(task);
    if (queue.size()==1) {
      try { executor.execute(()->drain(roomId)); }
      catch (RuntimeException | Error failure) { queue.removeLast(); queues.remove(roomId); throw failure; }
    }
  }
  private void drain(UUID roomId) {
    while (true) {
      Runnable task;
      synchronized (this) { var q=queues.get(roomId); task=q.peek(); }
      try { task.run(); } catch (Throwable e) { System.getLogger(getClass().getName()).log(System.Logger.Level.ERROR,"Room task failed: "+roomId,e); }
      synchronized (this) { var q=queues.get(roomId); q.remove(); if (q.isEmpty()) { queues.remove(roomId); return; } }
    }
  }
  @Override public synchronized void close() { closed=true; executor.shutdown(); }
}
