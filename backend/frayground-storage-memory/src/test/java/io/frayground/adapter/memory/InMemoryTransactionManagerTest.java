package io.frayground.adapter.memory;

import io.frayground.config.MemoryStorageConfiguration;
import io.frayground.config.StorageTransactionConfiguration;
import io.frayground.domain.match.*;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(InMemoryTransactionManagerTest.Config.class)
class InMemoryTransactionManagerTest {
  @Autowired Service service;
  @Autowired InMemoryRoomRepository rooms;
  @Autowired InMemoryMatchRepository matches;

  @Test void proxyCommitsAndRollsBackAndRequiresTransactionForWrites() {
    Room committed = room();
    service.insert(committed);
    assertTrue(rooms.find(committed.roomId()).isPresent());
    Room rolledBack = room();
    assertThrows(IllegalStateException.class, () -> service.insertThenFail(rolledBack));
    assertTrue(rooms.find(rolledBack.roomId()).isEmpty());
    assertThrows(RuntimeException.class, () -> rooms.insert(room()));
    Room atomicRoom=room(); Match atomicMatch=match(atomicRoom);
    assertThrows(IllegalStateException.class,()->service.insertRoomAndMatchThenFail(atomicRoom,atomicMatch));
    assertTrue(rooms.find(atomicRoom.roomId()).isEmpty());
    assertTrue(matches.find(atomicMatch.matchId()).isEmpty());
    Room rollbackOnly=room();
    assertThrows(org.springframework.transaction.UnexpectedRollbackException.class,()->service.insertJoinedRollbackOnly(rollbackOnly));
    assertTrue(rooms.find(rollbackOnly.roomId()).isEmpty());
    AtomicBoolean callback=new AtomicBoolean();
    service.commitWithCallback(room(),callback);
    assertTrue(callback.get());
    Room beforeCommit=room(), afterCommit=room(), afterCompletion=room();
    AtomicBoolean afterCommitRejected=new AtomicBoolean(), afterCompletionRejected=new AtomicBoolean();
    service.tryWritesAroundCommit(beforeCommit,afterCommit,afterCompletion,afterCommitRejected,afterCompletionRejected);
    assertTrue(rooms.find(beforeCommit.roomId()).isPresent());
    assertTrue(afterCommitRejected.get()); assertTrue(afterCompletionRejected.get());
    assertTrue(rooms.find(afterCommit.roomId()).isEmpty());
    assertTrue(rooms.find(afterCompletion.roomId()).isEmpty());
    callback.set(false);
    assertThrows(IllegalStateException.class,()->service.rollbackWithCallback(room(),callback));
    assertFalse(callback.get());
  }

  private static Room room() { return new Room(UUID.randomUUID(),0,0,null,ExecutionMode.STEP,ExecutionStatus.PAUSED,RoomHealth.OK,null); }
  private static Match match(Room room) { return new Match(UUID.randomUUID(),room.roomId(),new io.frayground.domain.game.GameKey("sample","1"),MatchStatus.READY,0,0,null,List.of(),null); }

  @Configuration(proxyBeanMethods=false)
  @Import({MemoryStorageConfiguration.class,StorageTransactionConfiguration.class})
  @PropertySource("classpath:memory-test.properties")
  static class Config {
    @Bean javax.sql.DataSource dataSource() { return org.mockito.Mockito.mock(javax.sql.DataSource.class); }
    @Bean InnerService innerService() { return new InnerService(); }
    @Bean Service service(InMemoryRoomRepository rooms,InMemoryMatchRepository matches,InnerService inner) { return new Service(rooms,matches,inner); }
  }

  static class Service {
    private final InMemoryRoomRepository rooms;
    private final InMemoryMatchRepository matches;
    private final InnerService inner;
    Service(InMemoryRoomRepository rooms,InMemoryMatchRepository matches,InnerService inner) { this.rooms=rooms; this.matches=matches; this.inner=inner; }
    @Transactional(transactionManager="gameTransactionManager") public void insert(Room room) { rooms.insert(room); }
    @Transactional(transactionManager="gameTransactionManager") public void insertThenFail(Room room) { rooms.insert(room); throw new IllegalStateException("rollback"); }
    @Transactional(transactionManager="gameTransactionManager") public void insertJoinedRollbackOnly(Room room) { rooms.insert(room); inner.markRollbackOnly(); }
    @Transactional(transactionManager="gameTransactionManager") public void insertRoomAndMatchThenFail(Room r,Match m) { rooms.insert(r); matches.insert(m); throw new IllegalStateException("rollback"); }
    @Transactional(transactionManager="gameTransactionManager") public void commitWithCallback(Room r,AtomicBoolean flag) { rooms.insert(r); org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() { public void afterCommit() { flag.set(true); } }); }
    @Transactional(transactionManager="gameTransactionManager") public void rollbackWithCallback(Room r,AtomicBoolean flag) { rooms.insert(r); org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() { public void afterCommit() { flag.set(true); } }); throw new IllegalStateException("rollback"); }
    @Transactional(transactionManager="gameTransactionManager") public void tryWritesAroundCommit(Room before,Room after,Room completion,AtomicBoolean afterRejected,AtomicBoolean completionRejected) {
      org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
        public void beforeCommit(boolean readOnly) { rooms.insert(before); }
        public void afterCommit() { try { rooms.insert(after); } catch(org.springframework.transaction.IllegalTransactionStateException e) { afterRejected.set(true); } }
        public void afterCompletion(int status) { try { rooms.insert(completion); } catch(org.springframework.transaction.IllegalTransactionStateException e) { completionRejected.set(true); } }
      });
    }
  }
  static class InnerService {
    @Transactional(transactionManager="gameTransactionManager") public void markRollbackOnly() { org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly(); }
  }
}
