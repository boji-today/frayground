package io.frayground.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.frayground.agent.runtime.SelectionOutputCodec;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SelectionOutputCodecTest {
  private final SelectionOutputCodec codec=new SelectionOutputCodec();
  @Test void createsClosedSchemaFromCandidates() {
    String schema=codec.schema(List.of("c0","c1"));
    assertTrue(schema.contains("\"enum\":[\"c0\",\"c1\"]"));
    assertTrue(schema.contains("\"additionalProperties\":false"));
    assertTrue(schema.contains("\"required\":[\"selectedActionId\"]"));
  }
  @Test void parsesOnlyOneExactCandidateObject() {
    assertEquals(new RuntimeOutcome.SelectedAction("c1"),codec.parse(result("{\"selectedActionId\":\"c1\"}"),"JSON_MODE",Set.of("c0","c1")));
    for (String bad:List.of("", "```json\n{}\n```", "{\"selectedActionId\":\"c1\",\"x\":1}", "{\"selectedActionId\":1}", "{\"selectedActionId\":\"c9\"}", "{} {}", "{\"selectedActionId\":\"c0\",\"selectedActionId\":\"c1\"}"))
      assertInstanceOf(RuntimeOutcome.Failed.class,codec.parse(result(bad),"JSON_MODE",Set.of("c0","c1")),bad);
  }
  @Test void strictToolAllowsAbsentOrWhitespaceContentButRejectsText() {
    var args=List.of(new ToolArguments("submit_action","{\"selectedActionId\":\"c0\"}"));
    for (String content:java.util.Arrays.asList(null,""," \t\n")) {
      var good=new ModelInvocationResult(null,null,content,args,null,null,null,null);
      assertEquals(new RuntimeOutcome.SelectedAction("c0"),codec.parse(good,"STRICT_TOOL",Set.of("c0")),String.valueOf(content));
    }
    var wrong=new ModelInvocationResult(null,null,"some text",args,null,null,null,null);
    assertInstanceOf(RuntimeOutcome.Failed.class,codec.parse(wrong,"STRICT_TOOL",Set.of("c0")));
  }
  @Test void strictToolStillRejectsInvalidArgumentsAndToolShape() {
    var valid=List.of(new ToolArguments("submit_action","{\"selectedActionId\":\"c0\"}"));
    assertInstanceOf(RuntimeOutcome.Failed.class,codec.parse(new ModelInvocationResult(null,null,"",List.of(),null,null,null,null),"STRICT_TOOL",Set.of("c0")));
    assertInstanceOf(RuntimeOutcome.Failed.class,codec.parse(new ModelInvocationResult(null,null,"",List.of(valid.get(0),valid.get(0)),null,null,null,null),"STRICT_TOOL",Set.of("c0")));
    assertInstanceOf(RuntimeOutcome.Failed.class,codec.parse(new ModelInvocationResult(null,null,"",List.of(new ToolArguments("other","{\"selectedActionId\":\"c0\"}")),null,null,null,null),"STRICT_TOOL",Set.of("c0")));
    assertInstanceOf(RuntimeOutcome.Failed.class,codec.parse(new ModelInvocationResult(null,null,"",List.of(new ToolArguments("submit_action","not-json")),null,null,null,null),"STRICT_TOOL",Set.of("c0")));
    assertInstanceOf(RuntimeOutcome.Failed.class,codec.parse(new ModelInvocationResult(null,null,"",valid,null,null,null,null),"STRICT_TOOL",Set.of("c1")));
  }
  private static ModelInvocationResult result(String text) { return new ModelInvocationResult(null,null,text,List.of(),null,null,null,null); }
}
