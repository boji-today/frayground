package io.frayground.domain.game.blackjack;

import io.frayground.domain.game.ActionGenerationMode;
import io.frayground.domain.game.ActionOption;
import io.frayground.domain.game.ActionSpecification;
import io.frayground.domain.game.GameDefinition;
import io.frayground.domain.game.GameEvent;
import io.frayground.domain.game.GameKey;
import io.frayground.domain.game.GameResult;
import io.frayground.domain.game.Participant;
import io.frayground.domain.game.Resolution;
import io.frayground.domain.game.SchedulingMode;
import io.frayground.domain.game.TurnPlan;
import io.frayground.domain.game.ValidationResult;
import io.frayground.domain.game.VisibleGameEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class BlackjackDefinition implements GameDefinition<BlackjackState, BlackjackAction, BlackjackObservation> {
  public static final String SCHEMA_ID = "blackjack/action/1";
  public static final GameKey KEY = new GameKey("blackjack", "1");
  private static final List<String> SEATS = List.of("P1", "P2", "P3");
  private final ShoeShuffler shuffler;

  public BlackjackDefinition(ShoeShuffler shuffler) {
    this.shuffler = shuffler;
  }

  @Override
  public GameKey key() {
    return KEY;
  }

  @Override
  public BlackjackState initialize(List<Participant> participants) {
    if (participants == null || participants.isEmpty() || participants.size() > 3) {
      throw new IllegalArgumentException("participants");
    }
    Map<String, UUID> seats = new LinkedHashMap<>();
    for (Participant participant : participants) {
      if (participant == null || !SEATS.contains(participant.seatKey())
          || seats.putIfAbsent(participant.seatKey(), participant.participantId()) != null) {
        throw new IllegalArgumentException("participants");
      }
    }
    List<String> order = SEATS.stream().filter(seats::containsKey).toList();
    List<UUID> ids = order.stream().map(seats::get).toList();
    List<PlayerSeat> players = order.stream()
        .map(seat -> new PlayerSeat(seats.get(seat), seat, List.of(), PlayerStatus.PLAYING)).toList();
    return new BlackjackState(BlackjackPhase.READY, ids, order, null, 0, "", List.of(), 0, players,
        List.of(), true, List.of(), BlackjackRules.pending());
  }

  @Override
  public TurnPlan nextTurn(BlackjackState state, List<Participant> participants) {
    if (!state.participantOrder().equals(order(participants))) {
      throw new IllegalArgumentException("participants");
    }
    List<UUID> required = state.currentParticipantId() == null ? List.of() : List.of(state.currentParticipantId());
    return new TurnPlan(SchedulingMode.TURN_BASED_SEQUENTIAL, required);
  }

  public Optional<Resolution<BlackjackState>> resolveSystem(BlackjackState state) {
    return switch (state.phase()) {
      case READY -> Optional.of(BlackjackRules.deal(state, shuffler));
      case DEALER_TURN -> Optional.of(BlackjackRules.dealer(state));
      default -> Optional.empty();
    };
  }

  @Override
  public BlackjackObservation observe(BlackjackState state, UUID participantId) {
    int index = state.participantOrder().indexOf(participantId);
    if (index < 0) {
      throw new IllegalArgumentException("participant");
    }
    List<BlackjackObservation.SeatView> players = state.players().stream().map(player ->
        new BlackjackObservation.SeatView(player.seat(), visible(player.cards()), player.status(),
            BlackjackRules.value(player.cards()))).toList();
    List<Card> dealer = state.holeHidden() ? state.dealerCards().subList(0, Math.min(1, state.dealerCards().size()))
        : state.dealerCards();
    List<String> legal = participantId.equals(state.currentParticipantId()) ? List.of("hit", "stand") : List.of();
    return new BlackjackObservation(state.seats().get(index),
        state.currentParticipantId() == null ? null : state.seats().get(state.participantOrder().indexOf(state.currentParticipantId())),
        players, visible(dealer), state.holeHidden(),
        state.holeHidden() ? null : BlackjackRules.value(state.dealerCards()),
        state.actions(), legal);
  }

  @Override
  public ActionSpecification<BlackjackAction> actions(BlackjackState state, UUID participantId) {
    List<ActionOption<BlackjackAction>> options = observe(state, participantId).legalActions().stream()
        .map(type -> new ActionOption<>(type, new BlackjackAction(type))).toList();
    return new ActionSpecification<>(ActionGenerationMode.ACTION_SELECTION, SCHEMA_ID, options, Map.of());
  }

  @Override
  public ValidationResult validate(BlackjackState state, UUID participantId, BlackjackAction action) {
    String code = null;
    if (participantId == null || state.participantOrder().indexOf(participantId) < 0) {
      code = "UNKNOWN_PARTICIPANT";
    } else if (state.phase() != BlackjackPhase.PLAYER_TURNS) {
      code = "GAME_FINISHED";
    } else if (!participantId.equals(state.currentParticipantId())) {
      code = "NOT_YOUR_TURN";
    } else if (action == null || (!action.type().equals("hit") && !action.type().equals("stand"))) {
      code = "INVALID_ACTION";
    }
    return code == null ? new ValidationResult.Accepted() : new ValidationResult.Rejected(code);
  }

  @Override
  public Resolution<BlackjackState> resolve(BlackjackState state, Map<UUID, BlackjackAction> actions) {
    if (actions == null || actions.size() != 1) {
      throw new IllegalArgumentException("actions");
    }
    var entry = actions.entrySet().iterator().next();
    if (!(validate(state, entry.getKey(), entry.getValue()) instanceof ValidationResult.Accepted)) {
      throw new IllegalArgumentException("action");
    }
    return BlackjackRules.act(state, entry.getKey(), entry.getValue());
  }

  @Override
  public GameResult result(BlackjackState state) {
    return state.result();
  }

  @Override
  public List<VisibleGameEvent> visibleHistory(UUID participantId, List<GameEvent> events) {
    if (participantId == null || events == null) {
      throw new IllegalArgumentException("history");
    }
    boolean revealed = events.stream().anyMatch(event -> event.type().equals("DealerHoleRevealed"));
    List<VisibleGameEvent> visible = new ArrayList<>();
    for (GameEvent event : events) {
      if (event.type().equals("ShoeShuffled")) {
        continue;
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      if (event.type().equals("CardDealt")) {
        boolean hole = Boolean.TRUE.equals(event.payload().get("hole"));
        payload.put("seat", event.payload().get("seat"));
        if (hole && !revealed) {
          payload.put("hidden", true);
        } else {
          payload.put("suit", event.payload().get("suit"));
          payload.put("rank", event.payload().get("rank"));
          payload.put("hole", hole);
        }
      } else {
        event.payload().forEach((key, value) -> {
          if (!key.equals("deckNo")) {
            payload.put(key, value);
          }
        });
      }
      visible.add(new VisibleGameEvent(event.type(), payload));
    }
    return List.copyOf(visible);
  }

  private List<UUID> order(List<Participant> participants) {
    return initialize(participants).participantOrder();
  }

  private static List<BlackjackObservation.VisibleCard> visible(List<Card> cards) {
    return cards.stream().map(card -> new BlackjackObservation.VisibleCard(card.suit(), card.rank())).toList();
  }
}
