package io.frayground.domain.game.connectfour;
import io.frayground.domain.game.ActionOption;
import io.frayground.domain.game.GameEventDraft;
import io.frayground.domain.game.GameOutcome;
import io.frayground.domain.game.Participant;
import io.frayground.domain.game.ValidationResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
class ConnectFourDefinitionTest {
  final UUID a = UUID.randomUUID();
  final UUID b = UUID.randomUUID();
  final UUID agent = UUID.randomUUID();
  final List<Participant> participants = List.of(
      new Participant(b, agent, "B"), new Participant(a, agent, "A"));
  final ConnectFourDefinition game = new ConnectFourDefinition();

  ConnectFourState play(int... columns) {
    ConnectFourState state = game.initialize(participants);
    for (int column : columns) {
      state = game.resolve(state, Map.of(state.currentParticipantId(),
          new PlaceDisc("place_disc", column))).newState();
    }
    return state;
  }
  @Test void initializationAndValidation() {
    var s = game.initialize(participants);
    assertThat(s.participantOrder()).containsExactly(a, b);
    assertThat(game.validate(s,null,null)).isEqualTo(new ValidationResult.Rejected("UNKNOWN_PARTICIPANT"));
    assertThat(game.validate(s,b,null)).isEqualTo(new ValidationResult.Rejected("NOT_YOUR_TURN"));
    assertThat(game.validate(s,a,null)).isEqualTo(new ValidationResult.Rejected("INVALID_ACTION"));
    assertThat(game.validate(s,a,new PlaceDisc("x",9))).isEqualTo(new ValidationResult.Rejected("INVALID_ACTION"));
    assertThat(game.validate(s,a,new PlaceDisc("place_disc",7))).isEqualTo(new ValidationResult.Rejected("COLUMN_OUT_OF_RANGE"));
    assertThatThrownBy(()->game.initialize(List.of(participants.get(0)))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->game.resolve(s,Map.of())).isInstanceOf(IllegalArgumentException.class);
    assertThat(game.observe(s,a).legalColumns()).containsExactly(0,1,2,3,4,5,6);
    assertThat(game.observe(s,b).legalColumns()).isEmpty();
  }
  @Test void winAndImmutability() {
    var before = play(0, 0, 1, 1, 2, 2);
    var resolution = game.resolve(before, Map.of(a, new PlaceDisc("place_disc", 3)));
    var s = resolution.newState();
    assertThat(before.board().get(5).get(3)).isEqualTo("EMPTY");
    assertThat(s.result().status()).isEqualTo(GameOutcome.WIN);
    assertThat(s.winningCells()).containsExactly(new Cell(5,0),new Cell(5,1),new Cell(5,2),new Cell(5,3));
    assertThat(resolution.events()).extracting(GameEventDraft::type).containsExactly("DiscPlaced","MatchFinished");
    assertThat(game.actions(s,a).options()).isEmpty();
    assertThat(game.nextTurn(s,participants).requiredParticipants()).isEmpty();
    assertThat(game.validate(s,a,null)).isEqualTo(new ValidationResult.Rejected("GAME_FINISHED"));
    assertThatThrownBy(()->game.resolve(s,Map.of(a,new PlaceDisc("place_disc",4)))).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void fullColumn() {
    var s = play(0, 0, 0, 0, 0, 0);
    assertThat(game.validate(s,a,new PlaceDisc("place_disc",0))).isEqualTo(new ValidationResult.Rejected("COLUMN_FULL"));
    assertThat(game.actions(s,a).options()).extracting(ActionOption::selectedActionId).containsExactly("c1","c2","c3","c4","c5","c6");
  }
  @Test void rejectsBadSeatsActionsAndTurnMappings() {
    var state = game.initialize(participants);
    assertThatThrownBy(() -> game.initialize(List.of(new Participant(a, agent, "A"), new Participant(a, agent, "B"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> game.initialize(List.of(new Participant(a, agent, "A"), new Participant(b, agent, "A"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> game.initialize(List.of(new Participant(a, agent, "A"), new Participant(b, agent, "C"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> game.nextTurn(state, List.of(new Participant(b, agent, "A"), new Participant(a, agent, "B"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> game.observe(state, UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
    assertThat(game.validate(state, a, new PlaceDisc("place_disc", -1)))
        .isEqualTo(new ValidationResult.Rejected("COLUMN_OUT_OF_RANGE"));
    assertThat(game.validate(state, UUID.randomUUID(), new PlaceDisc("bad", -1)))
        .isEqualTo(new ValidationResult.Rejected("UNKNOWN_PARTICIPANT"));
    assertThatThrownBy(() -> game.resolve(state, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> game.resolve(state, Map.of(a, new PlaceDisc("place_disc", 0), b, new PlaceDisc("place_disc", 1))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> game.resolve(state, Map.of(a, new PlaceDisc("bad", 0))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(state.moveCount()).isZero();
    var resolution = game.resolve(state, Map.of(a, new PlaceDisc("place_disc", 6)));
    assertThat(resolution.newState().board().get(5).get(6)).isEqualTo("A");
    assertThat(resolution.events().get(0).payload()).containsEntry("row", 5).containsEntry("column", 6)
        .containsEntry("seat", "A").containsEntry("participantId", a);
    assertThat(resolution.events().get(0).eventVersion()).isEqualTo(1);
    assertThat(state.board().get(5).get(6)).isEqualTo("EMPTY");
  }
}
