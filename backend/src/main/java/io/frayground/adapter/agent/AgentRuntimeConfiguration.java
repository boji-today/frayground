package io.frayground.adapter.agent;

import io.frayground.agent.AgentContextBuilder;
import io.frayground.agent.context.StatelessContextBuilder;
import io.frayground.agent.input.DecisionInputReader;
import io.frayground.agent.runtime.MockAgentRuntime;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/** Spring wiring for the first stateless context builder and deterministic runtime. */
@Configuration(proxyBeanMethods = false)
public class AgentRuntimeConfiguration {
  @Bean DecisionInputReader decisionInputReader() {
    return new DecisionInputReader(JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
  }
  @Bean AgentContextBuilder agentContextBuilder(DecisionInputReader reader) { return new StatelessContextBuilder(reader, 32_768); }
  @Bean MockAgentRuntime mockAgentRuntime(DecisionInputReader reader) { return new MockAgentRuntime(reader, Clock.systemUTC()); }
}
