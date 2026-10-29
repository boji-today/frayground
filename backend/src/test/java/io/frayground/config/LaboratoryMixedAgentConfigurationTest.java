package io.frayground.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.frayground.adapter.agent.FixedLaboratoryBindings;
import io.frayground.domain.strategy.RuntimeType;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("laboratory")
@TestPropertySource(properties = {
    "frayground.laboratory.agents.a.runtime=MOCK",
    "frayground.laboratory.agents.a.prompt=Mock seat prompt",
    "frayground.laboratory.agents.b.runtime=LLM",
    "frayground.laboratory.agents.b.prompt=LLM seat prompt"
})
@Import(LaboratoryMixedAgentConfigurationTest.DatabaseStub.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LaboratoryMixedAgentConfigurationTest {
  @TestConfiguration(proxyBeanMethods = false)
  static class DatabaseStub { @Bean DataSource dataSource() { return mock(DataSource.class); } }

  @Autowired LaboratoryProperties ids;
  @Autowired FixedLaboratoryBindings bindings;

  @Test void bindsDifferentPromptAndRuntimeForEachSeatAtStartup() {
    var a = bindings.current(ids.agentA()).orElseThrow();
    var b = bindings.current(ids.agentB()).orElseThrow();
    assertEquals("Mock seat prompt", a.prompt().content());
    assertEquals("LLM seat prompt", b.prompt().content());
    assertEquals(RuntimeType.MOCK, a.configuration().runtimeType());
    assertEquals(RuntimeType.LLM, b.configuration().runtimeType());
    assertEquals("system:deepseek-flash", b.configuration().model().credentialRef());
    assertEquals("STATELESS", b.configuration().memoryPolicy().mode().name());
  }
}
