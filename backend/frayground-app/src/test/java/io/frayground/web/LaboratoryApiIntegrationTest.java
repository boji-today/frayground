package io.frayground.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.awaitility.Awaitility.await;

import io.frayground.application.query.LaboratoryQueryService;
import io.frayground.config.LaboratoryProperties;
import javax.sql.DataSource;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("laboratory")
@Import(LaboratoryApiIntegrationTest.DatabaseStub.class)
class LaboratoryApiIntegrationTest {
  @TestConfiguration(proxyBeanMethods=false) static class DatabaseStub {
    @Bean DataSource dataSource() { return mock(DataSource.class); }
  }
  @Autowired WebApplicationContext context;
  @Autowired LaboratoryRequestFilter filter;
  @Autowired LaboratoryProperties properties;
  @Autowired LaboratoryQueryService queries;
  @Autowired DataSource dataSource;
  final JsonMapper json=JsonMapper.builder().build();
  MockMvc mvc;
  @BeforeEach void setup() { mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build(); }
  @Test void initializeCommandsAndObserveWithoutDatabase() throws Exception {
    mvc.perform(get("/api/v1/rooms/current")).andExpect(status().isNotFound());
    mvc.perform(post("/api/v1/rooms/current").header("X-Frayground-Client","laboratory"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.currentMatch.stateVersion").value(0))
        .andExpect(jsonPath("$.currentMatch.game.board.length()").value(6));
    mvc.perform(post("/api/v1/rooms/current").header("X-Frayground-Client","laboratory"))
        .andExpect(status().isOk());
    var room=queries.room(properties.roomId()).room();
    String id="10000000-0000-4000-8000-000000000001";
    String body="{\"commandId\":\""+id+"\",\"expectedRoomRevision\":"+room.revision()+
        ",\"expectedMatchId\":\""+room.activeMatchId()+"\",\"type\":\"step\"}";
    var path="/api/v1/rooms/"+room.roomId()+"/commands";
    String first=mvc.perform(post(path).header("X-Frayground-Client","laboratory")
        .contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    String replay=mvc.perform(post(path).header("X-Frayground-Client","laboratory")
        .contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertEquals(json.readTree(first),json.readTree(replay));
    await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
        assertEquals(1,queries.match(room.activeMatchId()).match().stateVersion()));
    mvc.perform(get("/api/v1/rooms/current")).andExpect(status().isOk())
        .andExpect(jsonPath("$.currentMatch.stateVersion").value(1))
        .andExpect(jsonPath("$.currentMatch.game.moveCount").value(1))
        .andExpect(jsonPath("$.currentMatch.game.lastMove.row").value(5));
    String matchBody=mvc.perform(get("/api/v1/matches/"+room.activeMatchId())).andExpect(status().isOk())
        .andExpect(jsonPath("$.game.moveCount").value(1))
        .andExpect(jsonPath("$.game.board").isArray())
        .andExpect(jsonPath("$.game.seats").isMap())
        .andExpect(jsonPath("$.game.currentParticipantId").exists())
        .andExpect(jsonPath("$.game.winningCells").isArray())
        .andExpect(jsonPath("$.game.result.status").value("ONGOING"))
        .andExpect(jsonPath("$.game.lastMove.row").value(5))
        .andExpect(jsonPath("$.game.lastMove.column").exists())
        .andReturn().getResponse().getContentAsString();
    String current=json.readTree(matchBody).path("game").path("currentParticipantId").asText();
    mvc.perform(get("/api/v1/matches/"+room.activeMatchId()+"/participants/"+current+"/observation"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.observationVersion").value(1))
        .andExpect(jsonPath("$.observation.legalColumns").isArray())
        .andExpect(jsonPath("$.observation.legalColumns[0]").exists());
    String decisionId=mvc.perform(get("/api/v1/matches/"+room.activeMatchId()+"/decisions"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
        .andReturn().getResponse().getContentAsString();
    decisionId=json.readTree(decisionId).path("items").get(0).path("decisionId").asText();
    mvc.perform(get("/api/v1/decisions/"+decisionId)).andExpect(status().isOk())
        .andExpect(jsonPath("$.submittedAction.type").value("place_disc"))
        .andExpect(jsonPath("$.submittedAction.column").exists())
        .andExpect(jsonPath("$.receipt.executedAction.type").value("place_disc"))
        .andExpect(jsonPath("$.receipt.executedAction.column").exists());
    mvc.perform(get("/api/v1/matches/"+room.activeMatchId()+"/events"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].type").value("DiscPlaced"))
        .andExpect(jsonPath("$.items[0].payload.column").exists())
        .andExpect(jsonPath("$.items[0].payload.row").exists())
        .andExpect(jsonPath("$.items[0].payload.seat").exists())
        .andExpect(jsonPath("$.items[0].payload.participantId").exists());
    mvc.perform(get("/api/v1/matches/"+room.activeMatchId()+"/events").param("after","9007199254740992"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/matches/"+room.activeMatchId()+"/decisions"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("COMPLETED"));
    var updated=queries.room(room.roomId()).room();
    String reset="{\"commandId\":\"10000000-0000-4000-8000-000000000002\",\"expectedRoomRevision\":"+
        updated.revision()+",\"expectedMatchId\":\""+updated.activeMatchId()+"\",\"type\":\"reset\"}";
    mvc.perform(post(path).header("X-Frayground-Client","laboratory").contentType("application/json").content(reset))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/matches/"+room.activeMatchId())).andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ABORTED"));
    String lateReplay=mvc.perform(post(path).header("X-Frayground-Client","laboratory")
        .contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertEquals(json.readTree(first),json.readTree(lateReplay));
    assertFalse(first.contains("requestJson")); assertFalse(first.contains("credentialRef"));
    verifyNoInteractions(dataSource);
  }
  @Test void requestBoundaryRejectsCrossOriginAndDuplicateFields() throws Exception {
    mvc.perform(post("/api/v1/rooms/current").header("Origin","https://evil.example")
        .header("X-Frayground-Client","laboratory")).andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/rooms/current")).andExpect(status().isForbidden());
    String body="{\"type\":\"step\",\"type\":\"reset\"}";
    mvc.perform(post("/api/v1/rooms/"+properties.roomId()+"/commands")
        .header("X-Frayground-Client","laboratory").contentType("application/json").content(body))
        .andExpect(status().isBadRequest());
  }
  @Test void missingResourcesAndUnsafeCursorHaveSpecificErrors() throws Exception {
    String id="99999999-0000-4000-8000-000000000001";
    mvc.perform(get("/api/v1/rooms/"+id)).andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
    mvc.perform(get("/api/v1/matches/"+id)).andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MATCH_NOT_FOUND"));
    mvc.perform(get("/api/v1/decisions/"+id)).andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("DECISION_NOT_FOUND"));
  }
}
