package io.frayground.adapter.memory;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Spring transaction manager for the shared in-memory state image. */
public final class InMemoryTransactionManager extends AbstractPlatformTransactionManager {
  private final InMemoryStorage storage;
  public InMemoryTransactionManager(InMemoryStorage storage) { this.storage = storage; setNestedTransactionAllowed(false); }
  @Override protected Object doGetTransaction() {
    MemoryTransactionContext bound = MemoryTransactionContext.current(storage);
    return new TxObject(bound);
  }
  @Override protected boolean isExistingTransaction(Object transaction) { return ((TxObject) transaction).context != null; }
  @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
    TxObject tx = (TxObject) transaction;
    if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NESTED) throw new UnsupportedOperationException("Nested transactions are unsupported");
    storage.begin();
    try {
      MemoryTransactionContext context = new MemoryTransactionContext(storage, storage.workingCopy());
      TransactionSynchronizationManager.bindResource(storage.resourceKey(), context);
      tx.context = context; tx.owner = true;
    } catch (RuntimeException | Error e) { storage.unlock(); throw e; }
  }
  @Override protected void doCommit(DefaultTransactionStatus status) {
    MemoryTransactionContext context = ((TxObject) status.getTransaction()).context;
    if (context.rollbackOnly) throw new org.springframework.transaction.UnexpectedRollbackException("Game transaction marked rollback-only");
    storage.commit(context);
  }
  @Override protected void doRollback(DefaultTransactionStatus status) { var c=((TxObject) status.getTransaction()).context; c.rollbackOnly = true; c.sealed = true; }
  @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) { ((TxObject) status.getTransaction()).context.rollbackOnly = true; }
  @Override protected Object doSuspend(Object transaction) { throw new UnsupportedOperationException("Suspended game transactions are unsupported"); }
  @Override protected void doResume(Object transaction, Object suspendedResources) { throw new UnsupportedOperationException("Resumed game transactions are unsupported"); }
  @Override protected void doCleanupAfterCompletion(Object transaction) {
    TxObject tx = (TxObject) transaction;
    if (tx.owner && tx.context != null) {
      tx.context.active = false;
      tx.context.sealed = true;
      if (TransactionSynchronizationManager.hasResource(storage.resourceKey())) TransactionSynchronizationManager.unbindResource(storage.resourceKey());
      storage.unlock();
    }
  }
  private static final class TxObject implements org.springframework.transaction.support.SmartTransactionObject {
    MemoryTransactionContext context; boolean owner;
    TxObject(MemoryTransactionContext context) { this.context = context; }
    @Override public boolean isRollbackOnly() { return context != null && context.rollbackOnly; }
    @Override public void flush() {}
  }
}
