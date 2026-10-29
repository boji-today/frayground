package io.frayground.application;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.frayground.adapter.game.RegisteredMatchFactory;
import io.frayground.adapter.model.DeepSeekModelAdapter;
import io.frayground.adapter.model.VendorHttpPool;
import io.frayground.agent.*;
import io.frayground.agent.context.StatelessContextBuilder;
import io.frayground.agent.input.DecisionInputReader;
import io.frayground.application.port.*;
import io.frayground.domain.game.GameKey;
import io.frayground.domain.action.DecisionStatus;
import io.frayground.domain.match.*;
import io.frayground.domain.strategy.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** End-to-end LLM path against a local HTTP server using the real DeepSeek adapter. */
@SpringJUnitConfig({DecisionExecutionIntegrationTest.Config.class,LlmDecisionExecutionIntegrationTest.LocalLlmConfiguration.class})
class LlmDecisionExecutionIntegrationTest {
  private static final AtomicInteger calls=new AtomicInteger();
  private static final AtomicReference<String> content=new AtomicReference<>("{\"selectedActionId\":\"c0\"}");
  private static final AtomicReference<CountDownLatch> responseReceived=new AtomicReference<>();
  private static final AtomicReference<CountDownLatch> releaseResponse=new AtomicReference<>();
  private static final HttpServer server=startServer();
  @Autowired DecisionExecutionService lifecycle; @Autowired AgentRunner runner; @Autowired RoomStore rooms;
  @Autowired MatchStore matches; @Autowired TurnStore turns; @Autowired SnapshotStore snapshots;
  @Autowired DecisionRecordStore records;
  @Autowired DecisionStore decisions; @Autowired DecisionExecutionClaimStore claims; @Autowired DecisionExecutionIntegrationTest.FaultGate faultGate;
  @Autowired PlatformTransactionManager gameTransactionManager; @Autowired io.frayground.adapter.game.GameRegistry registry;
  @Autowired JsonMapper json; @Autowired Clock clock;

  @BeforeEach void resetServer() { calls.set(0); content.set("{\"selectedActionId\":\"c0\"}"); responseReceived.set(null); releaseResponse.set(null); faultGate.target=null; }
  @AfterAll static void closeServer() { server.stop(0); }

  @Test void oneLocalModelCallAuditsAndCommitsOneMove() {
    var fixture=fixture(); var started=lifecycle.start(fixture.roomId());
    var result=runner.run(lifecycle.runtimeInput(started.decisionId()),()->false).toCompletableFuture().join();
    var record=records.find(started.decisionId()).orElseThrow();
    assertInstanceOf(DecisionOutcome.Submitted.class,result); assertEquals(1,calls.get());
    assertEquals(1,matches.find(fixture.matchId()).orElseThrow().stateVersion());
    assertNotNull(record.contextBuilderVersion()); assertTrue(record.modelInputJson().contains("selectionSchema"));
    assertTrue(record.modelOutputJson().contains("selectedActionId")); assertTrue(record.modelOutputJson().contains("\"actualModel\":\"deepseek-flash\""));
    assertNotNull(record.usage());
  }

  @Test void malformedOutputDoesNotAdvanceTheBoardAndPauses() {
    content.set("not-json"); var fixture=fixture(); var started=lifecycle.start(fixture.roomId());
    var input=lifecycle.runtimeInput(started.decisionId()); assertEquals(RuntimeType.LLM,input.hostedBindings().orElseThrow().configuration().runtimeType());
    var result=runner.run(input,()->false).toCompletableFuture().join();
    assertInstanceOf(DecisionOutcome.NotSubmitted.class,result);
    assertEquals(1,calls.get(),"call count; model record="+records.find(started.decisionId()).orElseThrow());
    assertEquals(0,matches.find(fixture.matchId()).orElseThrow().stateVersion());
    assertEquals(ExecutionStatus.PAUSED,rooms.find(fixture.roomId()).orElseThrow().executionStatus());
    assertNotNull(records.find(started.decisionId()).orElseThrow().modelOutputJson());
  }

  @Test void lateResponseAfterCancellationIsAuditedWithoutRevivingDecision() throws Exception {
    CountDownLatch received=new CountDownLatch(1),release=new CountDownLatch(1); blockResponse(received,release);
    var fixture=fixture(); var started=lifecycle.start(fixture.roomId());
    var future=runner.run(lifecycle.runtimeInput(started.decisionId()),()->false).toCompletableFuture();
    assertTrue(received.await(3,TimeUnit.SECONDS));
    try {
      lifecycle.cancel(fixture.roomId(),UUID.randomUUID()); release.countDown();
      assertInstanceOf(DecisionOutcome.NotSubmitted.class,future.join());
      assertEquals(DecisionStatus.CANCELLED,decisions.find(started.decisionId()).orElseThrow().status());
      assertEquals(0,matches.find(fixture.matchId()).orElseThrow().stateVersion()); assertEquals(1,calls.get());
      assertNotNull(records.find(started.decisionId()).orElseThrow().modelOutputJson());
      assertTrue(records.find(started.decisionId()).orElseThrow().errorJson().contains("DECISION_CANCELLED"));
      assertTrue(claims.findEnvelope(started.decisionId()).isEmpty());
    } finally { release.countDown(); responseReceived.set(null); releaseResponse.set(null); }
  }

  @Test void lateResponseAfterDeadlineIsAuditedWithoutRevivingDecision() throws Exception {
    CountDownLatch received=new CountDownLatch(1),release=new CountDownLatch(1); blockResponse(received,release);
    var fixture=fixture(); var started=lifecycle.start(fixture.roomId());
    var future=runner.run(lifecycle.runtimeInput(started.decisionId()),()->false).toCompletableFuture();
    assertTrue(received.await(3,TimeUnit.SECONDS));
    try {
      ((DecisionExecutionIntegrationTest.MutableClock)clock).set(started.deadline()); lifecycle.expire(started.decisionId()); release.countDown();
      assertInstanceOf(DecisionOutcome.NotSubmitted.class,future.join());
      assertEquals(DecisionStatus.EXPIRED,decisions.find(started.decisionId()).orElseThrow().status());
      assertEquals(0,matches.find(fixture.matchId()).orElseThrow().stateVersion()); assertEquals(1,calls.get());
      assertNotNull(records.find(started.decisionId()).orElseThrow().modelOutputJson());
      assertTrue(records.find(started.decisionId()).orElseThrow().errorJson().contains("DECISION_EXPIRED"));
      assertTrue(claims.findEnvelope(started.decisionId()).isEmpty());
    } finally { release.countDown(); ((DecisionExecutionIntegrationTest.MutableClock)clock).set(DecisionExecutionIntegrationTest.MutableClock.INITIAL); responseReceived.set(null); releaseResponse.set(null); }
  }

  @Test void outputAuditFailureCanRecoverWithoutAnotherModelCall() throws Exception {
    content.set("not-json");
    CountDownLatch received=new CountDownLatch(1),release=new CountDownLatch(1); blockResponse(received,release);
    var fixture=fixture(); var started=lifecycle.start(fixture.roomId()); var input=lifecycle.runtimeInput(started.decisionId());
    var future=runner.run(input,()->false).toCompletableFuture(); assertTrue(received.await(3,TimeUnit.SECONDS));
    try {
      faultGate.target="record.replace"; release.countDown();
      var initial=assertThrows(CompletionException.class,future::join);
      assertInstanceOf(IllegalStateException.class,initial.getCause());
      assertNull(records.find(started.decisionId()).orElseThrow().modelOutputJson());
      assertTrue(lifecycle.pendingOutputs().contains(started.decisionId()));
      assertEquals(1,calls.get());
      faultGate.target=null;
      assertInstanceOf(DecisionOutcome.NotSubmitted.class,runner.run(input,()->false).toCompletableFuture().join());
      var saved=records.find(started.decisionId()).orElseThrow();
      assertNotNull(saved.modelOutputJson()); assertTrue(saved.modelOutputJson().contains("not-json"));
      assertEquals(DecisionStatus.FAILED,decisions.find(started.decisionId()).orElseThrow().status());
      assertTrue(claims.findEnvelope(started.decisionId()).isEmpty());
      assertEquals(0,matches.find(fixture.matchId()).orElseThrow().stateVersion()); assertEquals(1,calls.get());
    } finally { faultGate.target=null; release.countDown(); responseReceived.set(null); releaseResponse.set(null); }
  }

  private static void blockResponse(CountDownLatch received,CountDownLatch release) { responseReceived.set(received); releaseResponse.set(release); }

  private Fixture fixture() {
    UUID roomId=UUID.randomUUID(); var participants=List.of(new io.frayground.domain.game.Participant(UUID.randomUUID(),UUID.randomUUID(),"A"),new io.frayground.domain.game.Participant(UUID.randomUUID(),UUID.randomUUID(),"B"));
    var initial=new RegisteredMatchFactory(registry,json,clock,UUID::randomUUID).create(roomId,new GameKey("connect-four","1"),participants);
    var source=initial.match(); var room=new Room(roomId,0,0,source.matchId(),ExecutionMode.STEP,ExecutionStatus.RUNNING,RoomHealth.OK,null);
    var active=new io.frayground.domain.match.Match(source.matchId(),source.roomId(),source.gameKey(),MatchStatus.ACTIVE,source.stateVersion(),source.gameEventSeq(),source.currentTurnId(),source.participants(),source.result());
    new TransactionTemplate(gameTransactionManager).executeWithoutResult(tx->{ rooms.insert(room); matches.insert(active); turns.insert(active.matchId(),initial.firstTurn()); snapshots.append(initial.initialSnapshot()); });
    return new Fixture(roomId,active.matchId());
  }
  private record Fixture(UUID roomId,UUID matchId) {}

  private static HttpServer startServer() {
    try {
      HttpServer value=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
      value.createContext("/chat/completions",exchange->{ calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
        CountDownLatch received=responseReceived.get(), release=releaseResponse.get();
        if (received!=null && release!=null) {
          received.countDown();
          try { if (!release.await(3,TimeUnit.SECONDS)) throw new IllegalStateException("loopback response release timed out"); }
          catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        }
        byte[] body=("{\"id\":\"local-req\",\"model\":\"deepseek-flash\",\"choices\":[{\"message\":{\"content\":"+JsonMapper.builder().build().writeValueAsString(content.get())+"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":4}}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type","application/json"); exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close(); });
      value.start(); return value;
    } catch(Exception e) { throw new ExceptionInInitializerError(e); }
  }

  @Configuration(proxyBeanMethods=false)
  static class LocalLlmConfiguration {
    @Bean AgentContextBuilder localContextBuilder(DecisionInputReader reader) { return new StatelessContextBuilder(reader,32768); }
    @Bean(destroyMethod="close") VendorHttpPool localModelPool() { return new VendorHttpPool("local",2); }
    @Bean ModelAdapter localDeepSeekAdapter(VendorHttpPool pool,Clock clock) {
      ModelCredentialProvider credentials=new ModelCredentialProvider() {
        public boolean supports(ModelConfiguration c) { return "system:deepseek-flash".equals(c.credentialRef()); }
        public Optional<String> resolve(ModelConfiguration c) { return Optional.of("local-test-key"); }
      };
      return new DeepSeekModelAdapter(pool,credentials,java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()),clock);
    }
    @Bean @Primary AgentBindingsReader llmBindings(DecisionExecutionIntegrationTest.BindingSource source,Clock clock) {
      return new AgentBindingsReader() {
        public Optional<HostedBindings> current(UUID id) { return source.current(id).map(b->new HostedBindings(b.prompt(),configuration(b.configuration(),clock))); }
        public Optional<HostedBindings> versions(UUID id,UUID prompt,UUID config) { return current(id).filter(b->b.prompt().promptVersionId().equals(prompt)&&b.configuration().configVersionId().equals(config)); }
        private AgentConfiguration configuration(AgentConfiguration old,Clock clock) {
          return new AgentConfiguration(old.configVersionId(),old.agentId(),old.versionNo(),RuntimeType.LLM,
              new ModelConfiguration("deepseek","deepseek-flash",false,0,128,10000,"system:deepseek-flash","JSON_MODE",false),old.memoryPolicy(),clock.instant());
        }
      };
    }
  }
}
