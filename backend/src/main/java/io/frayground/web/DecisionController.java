package io.frayground.web;

import io.frayground.application.query.LaboratoryQueryService;
import io.frayground.web.dto.LaboratoryDtos.DecisionView;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/decisions")
@Profile("laboratory")
public class DecisionController {
  private final LaboratoryQueryService queries; private final LaboratoryViewMapper mapper;
  public DecisionController(LaboratoryQueryService queries,LaboratoryViewMapper mapper) { this.queries=queries; this.mapper=mapper; }
  @GetMapping("/{decisionId}") public DecisionView decision(@PathVariable UUID decisionId) {
    return mapper.decision(queries.decision(decisionId));
  }
}
