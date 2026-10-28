package io.frayground.agent;

import static org.junit.jupiter.api.Assertions.*;
import io.frayground.agent.input.DecisionInputReader;
import io.frayground.agent.runtime.MockAgentRuntime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import io.frayground.adapter.agent.AgentRuntimeConfiguration;

class MockAgentRuntimeTest {
  private final Instant now = Instant.parse("2030-01-01T00:00:00Z");
  private DecisionRequest request(String spec, Instant deadline) {
    return new DecisionRequest("1.0", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, 0,
        "other-game", "v1", "schema", "not schema json", spec, "rules", "not observation json", deadline, null);
  }
  private RuntimeInput input(DecisionRequest req) { return new RuntimeInput(req, Optional.empty()); }
  private String code(RuntimeOutcome result) { return ((RuntimeOutcome.Failed) result).error().code(); }
  private RuntimeOutcome run(DecisionRequest request, boolean cancelled) {
    return new MockAgentRuntime(new DecisionInputReader(), Clock.fixed(now, ZoneOffset.UTC)).decide(input(request), () -> cancelled).toCompletableFuture().join();
  }
  private String spec(String mode, String options) { return "{\"mode\":\""+mode+"\",\"actionSchemaId\":\"schema\",\"options\":"+options+",\"constraints\":{}}"; }

  @Test void returnsRequiredStableFailuresAndHonorsDeadlineBoundary() {
    assertEquals("DECISION_CANCELLED", code(run(request(spec("ACTION_SELECTION", "[]"), now.plusSeconds(2)), true)));
    assertEquals("DECISION_EXPIRED", code(run(request(spec("ACTION_SELECTION", "[]"), now), false)));
    assertEquals("INVALID_DECISION_INPUT", code(run(request("{bad}", now.plusSeconds(2)), false)));
    assertEquals("NO_ACTION_OPTIONS", code(run(request(spec("ACTION_SELECTION", "[]"), now.plusSeconds(2)), false)));
    assertEquals("ACTION_MODE_NOT_SUPPORTED", code(run(request(spec("STRUCTURED_ACTION", "[]"), now.plusSeconds(2)), false)));
  }

  @Test void choosesFirstSuppliedNonConnectFourIdEvenWhenIdsAreUnsortedAndContextJsonIsBroken() {
    var runtime = new MockAgentRuntime(new DecisionInputReader(), Clock.fixed(now, ZoneOffset.UTC));
    var req = request("{\"mode\":\"ACTION_SELECTION\",\"options\":[{\"selectedActionId\":\"zeta\",\"action\":{}},{\"selectedActionId\":\"alpha\",\"action\":{}}],\"constraints\":{}}",
        now.plusSeconds(5));
    RuntimeOutcome outcome = runtime.decide(input(req), () -> false).toCompletableFuture().join();
    assertEquals("zeta", ((RuntimeOutcome.SelectedAction) outcome).selectedActionId());
  }

  @Test void cancellationAndDeadlineAreCheckedAgainImmediatelyBeforeReturning() {
    var request = request("{\"mode\":\"ACTION_SELECTION\",\"options\":[{\"selectedActionId\":\"first\",\"action\":{}}],\"constraints\":{}}",
        now.plusSeconds(5));
    var checks = new AtomicInteger();
    var runtime = new MockAgentRuntime(new DecisionInputReader(), Clock.fixed(now, ZoneOffset.UTC));
    RuntimeOutcome cancelledAtReturn = runtime.decide(input(request), () -> checks.incrementAndGet() > 1).toCompletableFuture().join();
    assertEquals("DECISION_CANCELLED", code(cancelledAtReturn));

    Clock deadlineAtReturn = new Clock() {
      private final AtomicInteger reads = new AtomicInteger();
      @Override public ZoneId getZone() { return ZoneOffset.UTC; }
      @Override public Clock withZone(ZoneId zone) { return this; }
      @Override public Instant instant() { return reads.incrementAndGet() == 1 ? now : now.plusSeconds(5); }
    };
    RuntimeOutcome expiredAtReturn = new MockAgentRuntime(new DecisionInputReader(), deadlineAtReturn)
        .decide(input(request), () -> false).toCompletableFuture().join();
    assertEquals("DECISION_EXPIRED", code(expiredAtReturn));
  }
  @Test void lightweightSpringConfigurationNeedsNoStorageOrModelBeans() {
    try (var context = new AnnotationConfigApplicationContext(AgentRuntimeConfiguration.class)) {
      assertNotNull(context.getBean(DecisionInputReader.class));
      assertNotNull(context.getBean(AgentContextBuilder.class));
      assertNotNull(context.getBean(MockAgentRuntime.class));
      assertSame(context.getBean(MockAgentRuntime.class), context.getBean(AgentRuntime.class));
      assertEquals(3, context.getBeanNamesForType(DecisionInputReader.class).length
          + context.getBeanNamesForType(AgentContextBuilder.class).length
          + context.getBeanNamesForType(MockAgentRuntime.class).length);
    }
  }
}
