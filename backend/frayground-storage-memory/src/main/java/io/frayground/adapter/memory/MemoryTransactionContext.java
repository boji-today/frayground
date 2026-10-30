package io.frayground.adapter.memory;

import org.springframework.transaction.support.TransactionSynchronizationManager;

final class MemoryTransactionContext {
  final InMemoryStorage storage;
  final MemoryState working;
  boolean active = true;
  boolean rollbackOnly;
  boolean sealed;
  MemoryTransactionContext(InMemoryStorage storage, MemoryState working) { this.storage = storage; this.working = working; }
  static MemoryTransactionContext current(InMemoryStorage storage) {
    return (MemoryTransactionContext) TransactionSynchronizationManager.getResource(storage.resourceKey());
  }
}
