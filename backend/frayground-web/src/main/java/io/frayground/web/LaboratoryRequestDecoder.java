package io.frayground.web;

import io.frayground.domain.match.*;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Strict command JSON confined to the laboratory API. */
public final class LaboratoryRequestDecoder {
  private final JsonMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  public ControlCommand command(byte[] input) {
    if(input.length==0 || input.length>8192)throw new IllegalArgumentException("request");
    try {
      JsonNode root=json.readTree(input);
      if(root==null || !root.isObject())throw new IllegalArgumentException("request");
      checkDepth(root,1);
      Set<String> names=new HashSet<>(); root.properties().forEach(e->names.add(e.getKey()));
      if(!Set.of("commandId","expectedRoomRevision","expectedMatchId","type","mode").containsAll(names)
          || !names.containsAll(Set.of("commandId","expectedRoomRevision","expectedMatchId","type")))
        throw new IllegalArgumentException("request");
      UUID id=uuid(root.get("commandId")), match=uuid(root.get("expectedMatchId"));
      var revision=root.get("expectedRoomRevision");
      if(revision==null || !revision.isIntegralNumber() || !revision.canConvertToLong()
          || revision.longValue()<0 || revision.longValue()>9007199254740991L)throw new IllegalArgumentException("revision");
      JsonNode typeNode=root.get("type");
      if(typeNode==null || !typeNode.isString())throw new IllegalArgumentException("type");
      String type=typeNode.stringValue();
      if(!Set.of("start","pause","continue","step","set_mode","cancel_decision","reset").contains(type))
        throw new IllegalArgumentException("type");
      ControlCommandType kind=ControlCommandType.valueOf(type.toUpperCase(Locale.ROOT));
      ExecutionMode mode=null;
      if(root.has("mode")) {
        JsonNode value=root.get("mode");
        if(value==null || !value.isString())throw new IllegalArgumentException("mode");
        mode=ExecutionMode.valueOf(value.stringValue());
      }
      return new ControlCommand(id,revision.longValue(),match,kind,mode);
    } catch(RuntimeException ex) { throw new IllegalArgumentException("request"); }
  }
  private static void checkDepth(JsonNode node,int depth) {
    if(depth>32)throw new IllegalArgumentException("depth");
    if(node.isObject())node.properties().forEach(e->checkDepth(e.getValue(),depth+1));
    else if(node.isArray())node.forEach(child->checkDepth(child,depth+1));
  }
  private static UUID uuid(JsonNode node) {
    if(node==null || !node.isString())throw new IllegalArgumentException("uuid");
    UUID result=UUID.fromString(node.stringValue());
    if(!result.toString().equalsIgnoreCase(node.stringValue()))throw new IllegalArgumentException("uuid");
    return result;
  }
}
