package io.frayground.config;

import io.frayground.adapter.memory.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

class StorageModeConfigurationTest {
  @Test void memoryModeIsIsolatedPerApplicationContext() {
    var id=java.util.UUID.randomUUID();
    try (var first=context()) {
      var rooms=first.getBean(InMemoryRoomRepository.class);
      var tx=new TransactionTemplate(first.getBean("gameTransactionManager",PlatformTransactionManager.class));
      var room=new io.frayground.domain.match.Room(id,0,0,null,
          io.frayground.domain.match.ExecutionMode.STEP,io.frayground.domain.match.ExecutionStatus.PAUSED,
          io.frayground.domain.match.RoomHealth.OK,null);
      tx.executeWithoutResult(s->rooms.insert(room));
      assertTrue(rooms.find(room.roomId()).isPresent());
    }
    try (var second=context()) {
      assertTrue(second.getBean(InMemoryRoomRepository.class).find(id).isEmpty());
      assertSame(second.getBean("gameTransactionManager"),second.getBean(InMemoryTransactionManager.class));
    }
  }
  @Test void databaseGameManagerAliasesTheDefaultJdbcManagerAndRegistersNoMemoryStores() {
    var c=new AnnotationConfigApplicationContext();
    c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",Map.of("frayground.storage.mode","database")));
    c.register(StorageTransactionConfiguration.class,DatabaseBeans.class); c.refresh();
    try(c) {
      assertSame(c.getBean("transactionManager"),c.getBean("gameTransactionManager"));
      assertInstanceOf(org.springframework.jdbc.support.JdbcTransactionManager.class,c.getBean("transactionManager"));
      assertThrows(org.springframework.beans.factory.NoSuchBeanDefinitionException.class,()->c.getBean(InMemoryRoomRepository.class));
    }
  }
  @Test void bootDataSourceAutoConfigurationKeepsUnqualifiedAndNamedTransactionsRoutedSeparately() {
    var c=new AnnotationConfigApplicationContext();
    c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",Map.of("frayground.storage.mode","memory")));
    c.register(BootRoutingConfig.class); c.refresh();
    try(c) {
      var probe=c.getBean(RoutingProbe.class);
      assertTrue(probe.defaultTransactionHasJdbcResource());
      var id=probe.gameTransactionWritesMemory();
      assertTrue(c.getBean(InMemoryRoomRepository.class).find(id).isPresent());
    }
  }
  @Configuration(proxyBeanMethods=false) static class DatabaseBeans {
    @Bean DataSource dataSource() { return mockedDataSource(); }
  }
  @Configuration(proxyBeanMethods=false) @Import({MemoryStorageConfiguration.class,StorageTransactionConfiguration.class})
  @org.springframework.boot.autoconfigure.ImportAutoConfiguration(org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration.class)
  static class BootRoutingConfig {
    @Bean DataSource dataSource() { return mockedDataSource(); }
    @Bean RoutingProbe routingProbe(DataSource ds,InMemoryRoomRepository rooms) { return new RoutingProbe(ds,rooms); }
  }
  static class RoutingProbe {
    private final DataSource dataSource; private final InMemoryRoomRepository rooms;
    RoutingProbe(DataSource ds,InMemoryRoomRepository rooms) { this.dataSource=ds; this.rooms=rooms; }
    @org.springframework.transaction.annotation.Transactional public boolean defaultTransactionHasJdbcResource() { return org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(dataSource); }
    @org.springframework.transaction.annotation.Transactional(transactionManager="gameTransactionManager") public java.util.UUID gameTransactionWritesMemory() {
      var id=java.util.UUID.randomUUID(); rooms.insert(new io.frayground.domain.match.Room(id,0,0,null,io.frayground.domain.match.ExecutionMode.STEP,io.frayground.domain.match.ExecutionStatus.PAUSED,io.frayground.domain.match.RoomHealth.OK,null)); return id;
    }
  }
  private static DataSource mockedDataSource() {
    DataSource ds=org.mockito.Mockito.mock(DataSource.class);
    try { org.mockito.Mockito.when(ds.getConnection()).thenReturn(org.mockito.Mockito.mock(java.sql.Connection.class)); }
    catch(java.sql.SQLException e) { throw new IllegalStateException(e); }
    return ds;
  }
  private static AnnotationConfigApplicationContext context() {
    var c=new AnnotationConfigApplicationContext();
    c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",Map.of("frayground.storage.mode","memory")));
    c.register(MemoryStorageConfiguration.class,StorageTransactionConfiguration.class,DatabaseBeans.class);
    c.refresh(); return c;
  }
}
