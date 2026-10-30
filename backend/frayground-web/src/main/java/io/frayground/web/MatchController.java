package io.frayground.web;

import io.frayground.application.query.LaboratoryQueryService;
import io.frayground.web.dto.LaboratoryDtos.*;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/matches")
@Profile("laboratory")
public class MatchController {
  private final LaboratoryQueryService queries; private final LaboratoryViewMapper mapper;
  public MatchController(LaboratoryQueryService queries,LaboratoryViewMapper mapper) { this.queries=queries; this.mapper=mapper; }
  @GetMapping("/{matchId}") public MatchView match(@PathVariable UUID matchId) { return mapper.match(queries.match(matchId)); }
  @GetMapping("/{matchId}/participants/{participantId}/observation")
  public ObservationView observation(@PathVariable UUID matchId, @PathVariable UUID participantId) {
    return mapper.observation(queries.observation(matchId, participantId));
  }
  @GetMapping("/{matchId}/events") public EventPage events(@PathVariable UUID matchId,
      @RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit) {
    var page=queries.events(matchId,after,limit);
    return new EventPage(page.items().stream().map(mapper::event).toList(),page.nextAfter(),page.hasMore());
  }
  @GetMapping("/{matchId}/decisions") public DecisionPage decisions(@PathVariable UUID matchId,
      @RequestParam(required=false) String cursor,@RequestParam(defaultValue="50") int limit) {
    var page=queries.decisions(matchId,cursor,limit);
    return new DecisionPage(page.items().stream().map(mapper::summary).toList(),page.nextCursor());
  }
}
