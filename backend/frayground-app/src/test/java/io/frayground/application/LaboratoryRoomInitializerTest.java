package io.frayground.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import io.frayground.adapter.game.*;
import io.frayground.adapter.game.connectfour.*;
import io.frayground.application.port.*;
import io.frayground.config.*;
import io.frayground.domain.game.*;
import io.frayground.domain.game.connectfour.ConnectFourDefinition;
import io.frayground.domain.match.Match;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.json.JsonMapper;

class LaboratoryRoomInitializerTest {
  static final UUID ROOM=UUID.fromString("00000000-0000-4000-8000-000000000001");
  @Configuration(proxyBeanMethods=false)
  @Import({MemoryStorageConfiguration.class,StorageTransactionConfiguration.class})
  static class Config {
    @Bean DataSource dataSource() { return mock(DataSource.class); }
    @Bean FixtureFactory factory() { return new FixtureFactory(); }
    @Bean LaboratoryRoomInitializer initializer(RoomStore rooms,MatchStore matches,TurnStore turns,
        SnapshotStore snapshots,RoomEventRepository events,FixtureFactory factory) {
      return new LaboratoryRoomInitializer(rooms,matches,turns,snapshots,events,factory,Clock.systemUTC(),
          UUID::randomUUID,ROOM,UUID.fromString("00000000-0000-4000-8000-000000000002"),
          UUID.fromString("00000000-0000-4000-8000-000000000003"));
    }
  }
  static final class FixtureFactory implements MatchFactory {
    volatile boolean fail, noTurn;
    final MatchFactory delegate=new RegisteredMatchFactory(new DefaultGameRegistry(List.of(
        new GameRegistration<>(new ConnectFourDefinition(),new ConnectFourCodec(),new ConnectFourActionSchemaProvider()))),
        JsonMapper.builder().build(),Clock.systemUTC(),UUID::randomUUID);
    @Override public InitializedMatch create(UUID room,GameKey key,List<Participant> participants) {
      if(fail)throw new IllegalStateException("injected");
      var initial=delegate.create(room,key,participants);
      if(!noTurn)return initial;
      var m=initial.match();
      return new InitializedMatch(new Match(m.matchId(),m.roomId(),m.gameKey(),m.status(),m.stateVersion(),
          m.gameEventSeq(),null,m.participants(),m.result()),null,initial.initialSnapshot());
    }
  }
  private AnnotationConfigApplicationContext context() {
    var ctx=new AnnotationConfigApplicationContext();
    ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",Map.of("frayground.storage.mode","memory")));
    ctx.register(Config.class); ctx.refresh(); return ctx;
  }
  @Test void concurrentCallsCreateOneMatch() throws Exception {
    try(var ctx=context()) {
      var initializer=ctx.getBean(LaboratoryRoomInitializer.class);
      var start=new CountDownLatch(1); var executor=Executors.newFixedThreadPool(2);
      try {
        var a=executor.submit(()->{start.await(); return initializer.initialize();});
        var b=executor.submit(()->{start.await(); return initializer.initialize();});
        start.countDown(); var first=a.get(5,TimeUnit.SECONDS); var second=b.get(5,TimeUnit.SECONDS);
        assertNotEquals(first.created(),second.created());
        assertEquals(first.room().activeMatchId(),second.room().activeMatchId());
        assertEquals(1,ctx.getBean(RoomEventRepository.class).readAfter(ROOM,0,10).size());
      } finally { executor.shutdownNow(); }
    }
  }
  @Test void failureRollsBackAndNullTurnIsAccepted() {
    try(var ctx=context()) {
      var initializer=ctx.getBean(LaboratoryRoomInitializer.class); var factory=ctx.getBean(FixtureFactory.class);
      factory.fail=true; assertThrows(IllegalStateException.class,initializer::initialize);
      assertTrue(ctx.getBean(RoomStore.class).find(ROOM).isEmpty());
      assertTrue(ctx.getBean(RoomEventRepository.class).readAfter(ROOM,0,10).isEmpty());
      factory.fail=false; factory.noTurn=true;
      var created=initializer.initialize(); assertTrue(created.created());
      var match=ctx.getBean(MatchStore.class).find(created.room().activeMatchId()).orElseThrow();
      assertNull(match.currentTurnId());
      assertTrue(ctx.getBean(SnapshotStore.class).find(match.matchId(),0).isPresent());
    }
  }
}
