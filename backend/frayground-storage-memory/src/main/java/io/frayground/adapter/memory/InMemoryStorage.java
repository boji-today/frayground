package io.frayground.adapter.memory;

import java.util.concurrent.locks.ReentrantLock;
import org.springframework.transaction.IllegalTransactionStateException;

public final class InMemoryStorage {
  private final ReentrantLock lock = new ReentrantLock(true);
  private final Object resourceKey = new Object();
  private volatile MemoryState committed = new MemoryState();
  public InMemoryStorage() {}
  Object resourceKey() { return resourceKey; }
  void begin() { lock.lock(); }
  MemoryState workingCopy() { return committed.copy(); }
  void commit(MemoryTransactionContext context) { context.sealed = true; committed = context.working; }
  void unlock() { lock.unlock(); }
  MemoryState readState() {
    MemoryTransactionContext tx = MemoryTransactionContext.current(this);
    return tx != null && tx.active ? tx.working : committed;
  }
  MemoryState writeState() {
    MemoryTransactionContext tx = MemoryTransactionContext.current(this);
    if (tx == null || !tx.active || tx.rollbackOnly || tx.sealed) throw new IllegalTransactionStateException("In-memory writes require an active, writable game transaction");
    return tx.working;
  }
}
