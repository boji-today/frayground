package io.frayground.web;

import io.frayground.application.PromptPublisher;
import io.frayground.application.port.LaboratoryPromptStore;
import io.frayground.application.query.LaboratoryQueryService;
import io.frayground.application.query.LaboratoryQueryService.NotFound;
import io.frayground.web.dto.LaboratoryDtos.AgentView;
import io.frayground.web.dto.LaboratoryDtos.PromptDraftView;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Profile("laboratory")
public class AgentController {
  private final LaboratoryPromptStore prompts;
  private final PromptPublisher publisher;
  private final LaboratoryQueryService queries;
  private final LaboratoryViewMapper mapper;
  private final LaboratoryRequestDecoder decoder;
  private final Clock clock;

  public AgentController(LaboratoryPromptStore prompts, PromptPublisher publisher, LaboratoryQueryService queries,
      LaboratoryViewMapper mapper, LaboratoryRequestDecoder decoder, Clock clock) {
    this.prompts = prompts;
    this.publisher = publisher;
    this.queries = queries;
    this.mapper = mapper;
    this.decoder = decoder;
    this.clock = clock;
  }

  @GetMapping("/agents/{agentId}/configuration")
  public AgentView configuration(@PathVariable UUID agentId) {
    return mapper.agent(prompts.find(agentId).orElseThrow(() -> new NotFound("AGENT_NOT_FOUND")));
  }

  @PutMapping("/agents/{agentId}/prompt-draft")
  public PromptDraftView saveDraft(@PathVariable UUID agentId, HttpServletRequest request) throws IOException {
    var update = decoder.draft(request.getInputStream().readNBytes(65537));
    return mapper.draft(prompts.saveDraft(agentId, update.expectedDraftRevision(), update.content(), clock.instant()));
  }

  @PostMapping("/rooms/{roomId}/agents/{agentId}/prompt-versions")
  public ResponseEntity<?> publish(@PathVariable UUID roomId, @PathVariable UUID agentId, HttpServletRequest request) throws IOException {
    var command = decoder.publish(request.getInputStream().readNBytes(8193));
    queries.room(roomId);
    return ResponseEntity.status(201).body(mapper.prompt(publisher.publish(roomId, agentId, command)));
  }
}
