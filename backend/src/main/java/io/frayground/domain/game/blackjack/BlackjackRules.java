package io.frayground.domain.game.blackjack;

import io.frayground.domain.game.GameEventDraft;
import io.frayground.domain.game.GameOutcome;
import io.frayground.domain.game.GameResult;
import io.frayground.domain.game.ParticipantStanding;
import io.frayground.domain.game.Resolution;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class BlackjackRules {
  static final String DEALER = "DEALER";
  private static final String PENDING = "PENDING";
  private static final String SETTLED = "HAND_SETTLED";

  private BlackjackRules() {}

  public static List<Card> standardShoe() {
    List<Card> cards = new ArrayList<>(312);
    for (int deck = 0; deck < 6; deck++) {
      for (String suit : Card.SUITS) {
        for (String rank : Card.RANKS) {
          cards.add(new Card(deck, suit, rank));
        }
      }
    }
    return List.copyOf(cards);
  }

  static int value(List<Card> cards) {
    int total = 0;
    int aces = 0;
    for (Card card : cards) {
      total += points(card.rank());
      if (card.rank().equals("A")) {
        aces++;
      }
    }
    while (total > 21 && aces > 0) {
      total -= 10;
      aces--;
    }
    return total;
  }

  static boolean natural(List<Card> cards) {
    return cards.size() == 2 && value(cards) == 21;
  }

  static GameResult pending() {
    return new GameResult(GameOutcome.ONGOING, List.of(), PENDING);
  }

  static Resolution<BlackjackState> deal(BlackjackState ready, ShoeShuffler shuffler) {
    ShoeShuffle shuffled = Objects.requireNonNull(shuffler.shuffle());
    List<Card> shoe = shuffled.cards();
    int n = ready.players().size();
    List<List<Card>> hands = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      hands.add(new ArrayList<>());
    }
    List<Card> dealer = new ArrayList<>();
    List<GameEventDraft> events = new ArrayList<>();
    events.add(event("ShoeShuffled", Map.of("algorithm", FisherYatesShuffler.ALGORITHM, "seed", shuffled.seed())));
    int cursor = 0;
    for (int pass = 0; pass < 2; pass++) {
      for (int i = 0; i < n; i++) {
        Card card = shoe.get(cursor++);
        hands.get(i).add(card);
        events.add(dealt(card, ready.players().get(i).seat(), ready.players().get(i).participantId(), false));
      }
      Card card = shoe.get(cursor++);
      dealer.add(card);
      events.add(dealt(card, DEALER, null, pass == 1));
    }
    boolean dealerNatural = natural(dealer);
    events.add(event("DealerBlackjackChecked", Map.of("dealerNatural", dealerNatural)));
    List<PlayerSeat> players = new ArrayList<>();
    UUID current = null;
    for (int i = 0; i < n; i++) {
      PlayerSeat old = ready.players().get(i);
      boolean playerNatural = natural(hands.get(i));
      PlayerStatus status = playerNatural ? PlayerStatus.NATURAL : dealerNatural ? PlayerStatus.STAND : PlayerStatus.PLAYING;
      players.add(new PlayerSeat(old.participantId(), old.seat(), hands.get(i), status));
      if (!dealerNatural && !playerNatural && current == null) {
        current = old.participantId();
      }
    }
    boolean finished = dealerNatural || current == null;
    List<PublicAction> actions = new ArrayList<>();
    List<Card> dealerCards = dealer;
    boolean holeHidden = !finished;
    GameResult result = pending();
    if (finished) {
      holeHidden = false;
      actions.add(new PublicAction("HOLE_REVEALED", DEALER));
      events.add(revealed(dealer.get(1)));
      result = settle(players, dealerCards);
      events.add(finished(result));
    }
    BlackjackState state = new BlackjackState(finished ? BlackjackPhase.FINISHED
        : BlackjackPhase.PLAYER_TURNS, ready.participantOrder(), ready.seats(), current,
        shuffled.seed(), FisherYatesShuffler.ALGORITHM, shoe, cursor, players, dealerCards,
        holeHidden, actions, result);
    return new Resolution<>(state, events, result);
  }

  static Resolution<BlackjackState> act(BlackjackState state, UUID id, BlackjackAction action) {
    int index = state.participantOrder().indexOf(id);
    PlayerSeat player = state.players().get(index);
    List<PlayerSeat> players = new ArrayList<>(state.players());
    List<PublicAction> actions = new ArrayList<>(state.actions());
    List<GameEventDraft> events = new ArrayList<>();
    int cursor = state.cursor();
    if (action.type().equals("stand")) {
      players.set(index, new PlayerSeat(player.participantId(), player.seat(), player.cards(), PlayerStatus.STAND));
      actions.add(new PublicAction("STAND", player.seat()));
      events.add(event("PlayerStand", Map.of("seat", player.seat(), "participantId", id)));
    } else {
      Card card = state.shoe().get(cursor++);
      List<Card> cards = new ArrayList<>(player.cards());
      cards.add(card);
      int total = value(cards);
      PlayerStatus status = total > 21 ? PlayerStatus.BUST : total == 21 ? PlayerStatus.TWENTY_ONE : PlayerStatus.PLAYING;
      players.set(index, new PlayerSeat(player.participantId(), player.seat(), cards, status));
      actions.add(new PublicAction("HIT", player.seat()));
      events.add(dealt(card, player.seat(), id, false));
      events.add(event("PlayerHit", Map.of("seat", player.seat(), "participantId", id, "suit", card.suit(), "rank", card.rank())));
      if (status == PlayerStatus.BUST) {
        actions.add(new PublicAction("BUST", player.seat()));
        events.add(event("PlayerBust", Map.of("seat", player.seat(), "participantId", id)));
      } else if (status == PlayerStatus.TWENTY_ONE) {
        actions.add(new PublicAction("REACHED_21", player.seat()));
        events.add(event("PlayerReached21", Map.of("seat", player.seat(), "participantId", id)));
      }
    }
    UUID next = players.get(index).status() == PlayerStatus.PLAYING ? id : null;
    if (next == null) {
      for (int i = index + 1; i < players.size(); i++) {
        if (players.get(i).status() == PlayerStatus.PLAYING) {
          next = players.get(i).participantId();
          break;
        }
      }
    }
    BlackjackPhase phase = next == null ? BlackjackPhase.DEALER_TURN : BlackjackPhase.PLAYER_TURNS;
    BlackjackState updated = new BlackjackState(phase, state.participantOrder(), state.seats(), next,
        state.seed(), state.shuffleAlgorithm(), state.shoe(), cursor, players, state.dealerCards(),
        true, actions, pending());
    return new Resolution<>(updated, events, pending());
  }

  static Resolution<BlackjackState> dealer(BlackjackState state) {
    List<Card> dealer = new ArrayList<>(state.dealerCards());
    List<PublicAction> actions = new ArrayList<>(state.actions());
    List<GameEventDraft> events = new ArrayList<>();
    int cursor = state.cursor();
    actions.add(new PublicAction("HOLE_REVEALED", DEALER));
    events.add(revealed(dealer.get(1)));
    boolean live = state.players().stream().anyMatch(player ->
        player.status() == PlayerStatus.STAND || player.status() == PlayerStatus.TWENTY_ONE);
    if (live) {
      while (value(dealer) < 17) {
        Card card = state.shoe().get(cursor++);
        dealer.add(card);
        actions.add(new PublicAction("DEALER_HIT", DEALER));
        events.add(dealt(card, DEALER, null, false));
        events.add(event("DealerHit", Map.of("suit", card.suit(), "rank", card.rank())));
      }
      if (value(dealer) <= 21) {
        actions.add(new PublicAction("DEALER_STAND", DEALER));
        events.add(event("DealerStand", Map.of("total", value(dealer))));
      }
    }
    GameResult result = settle(state.players(), dealer);
    events.add(finished(result));
    BlackjackState updated = new BlackjackState(BlackjackPhase.FINISHED, state.participantOrder(), state.seats(),
        null, state.seed(), state.shuffleAlgorithm(), state.shoe(), cursor, state.players(), dealer,
        false, actions, result);
    return new Resolution<>(updated, events, result);
  }

  static GameResult settle(List<PlayerSeat> players, List<Card> dealer) {
    boolean dealerNatural = natural(dealer);
    boolean dealerBust = value(dealer) > 21;
    int dealerTotal = value(dealer);
    double[] scores = new double[players.size()];
    for (int i = 0; i < players.size(); i++) {
      PlayerSeat player = players.get(i);
      if (dealerNatural) {
        scores[i] = player.status() == PlayerStatus.NATURAL ? 0 : -1;
      } else if (player.status() == PlayerStatus.NATURAL) {
        scores[i] = 1.5;
      } else if (player.status() == PlayerStatus.BUST) {
        scores[i] = -1;
      } else if (dealerBust) {
        scores[i] = 1;
      } else {
        int total = value(player.cards());
        scores[i] = total > dealerTotal ? 1 : total < dealerTotal ? -1 : 0;
      }
    }
    int[] ranks = ranks(scores);
    List<ParticipantStanding> standings = new ArrayList<>();
    int winners = 0;
    double winnerScore = 0;
    for (int i = 0; i < players.size(); i++) {
      standings.add(new ParticipantStanding(players.get(i).participantId(), ranks[i], scores[i]));
      if (ranks[i] == 1) {
        winners++;
        winnerScore = scores[i];
      }
    }
    GameOutcome outcome = winners == 1 && winnerScore > 0 ? GameOutcome.WIN : GameOutcome.DRAW;
    return new GameResult(outcome, standings, SETTLED);
  }

  static void check(BlackjackPhase phase, List<UUID> participantOrder, List<String> seatKeys, UUID currentParticipantId,
      long seed, String shuffleAlgorithm, List<Card> shoe, int cursor, List<PlayerSeat> players,
      List<Card> dealerCards, boolean holeHidden, List<PublicAction> actions, GameResult result) {
    if (participantOrder.size() != seatKeys.size() || participantOrder.size() != players.size()
        || participantOrder.isEmpty() || participantOrder.size() > 3) {
      throw new IllegalArgumentException("participants");
    }
    Set<UUID> ids = new HashSet<>();
    Set<String> seenSeats = new HashSet<>();
    List<String> expectedSeats = List.of("P1", "P2", "P3").stream().filter(seatKeys::contains).toList();
    if (!seatKeys.equals(expectedSeats)) {
      throw new IllegalArgumentException("seats");
    }
    int cards = 0;
    for (int i = 0; i < players.size(); i++) {
      PlayerSeat player = players.get(i);
      if (!ids.add(player.participantId()) || !player.participantId().equals(participantOrder.get(i))
          || !seenSeats.add(player.seat()) || !player.seat().equals(seatKeys.get(i))) {
        throw new IllegalArgumentException("player");
      }
      checkStatus(player);
      cards += player.cards().size();
    }
    cards += dealerCards.size();
    if (cards != cursor || cursor < 0) {
      throw new IllegalArgumentException("cursor");
    }
    if (phase == BlackjackPhase.READY) {
      if (cursor != 0 || !shoe.isEmpty() || seed != 0 || !shuffleAlgorithm.isEmpty() || currentParticipantId != null
          || !holeHidden || !dealerCards.isEmpty() || !actions.isEmpty() || !result.equals(pending())) {
        throw new IllegalArgumentException("ready");
      }
      return;
    }
    if (!shuffleAlgorithm.equals(FisherYatesShuffler.ALGORITHM) || shoe.size() != 312
        || !new HashSet<>(shoe).equals(new HashSet<>(standardShoe()))) {
      throw new IllegalArgumentException("shoe");
    }
    int dealt = 2 * (players.size() + 1);
    if (cursor < dealt || cursor > 312) {
      throw new IllegalArgumentException("cursor");
    }
    for (int pass = 0; pass < 2; pass++) {
      for (int i = 0; i < players.size(); i++) {
        if (!players.get(i).cards().get(pass).equals(shoe.get(pass * (players.size() + 1) + i))) {
          throw new IllegalArgumentException("deal");
        }
      }
      if (!dealerCards.get(pass).equals(shoe.get(pass * (players.size() + 1) + players.size()))) {
        throw new IllegalArgumentException("deal");
      }
    }
    List<Card> dealtCards = new ArrayList<>();
    players.forEach(player -> dealtCards.addAll(player.cards()));
    dealtCards.addAll(dealerCards);
    if (!multiset(dealtCards).equals(multiset(shoe.subList(0, cursor)))) {
      throw new IllegalArgumentException("cards");
    }
    int hits = 0;
    int dealerHits = 0;
    for (PublicAction action : actions) {
      if (action.type().equals("HIT")) {
        hits++;
      } else if (action.type().equals("DEALER_HIT")) {
        dealerHits++;
      } else if (!Set.of("STAND", "REACHED_21", "BUST", "HOLE_REVEALED", "DEALER_STAND").contains(action.type())) {
        throw new IllegalArgumentException("actions");
      }
    }
    int playerHits = players.stream().mapToInt(player -> player.cards().size() - 2).sum();
    if (hits != playerHits || dealerHits != dealerCards.size() - 2) {
      throw new IllegalArgumentException("actions");
    }
    if (phase == BlackjackPhase.FINISHED) {
      if (currentParticipantId != null || holeHidden || dealerCards.size() < 2
          || players.stream().anyMatch(player -> player.status() == PlayerStatus.PLAYING)
          || !result.equals(settle(players, dealerCards))) {
        throw new IllegalArgumentException("finished");
      }
      return;
    }
    if (!result.equals(pending()) || !holeHidden || dealerCards.size() != 2) {
      throw new IllegalArgumentException("phase");
    }
    if (phase == BlackjackPhase.DEALER_TURN) {
      if (currentParticipantId != null || players.stream().anyMatch(player -> player.status() == PlayerStatus.PLAYING)) {
        throw new IllegalArgumentException("dealer");
      }
      return;
    }
    if (phase != BlackjackPhase.PLAYER_TURNS || currentParticipantId == null) {
      throw new IllegalArgumentException("turn");
    }
    int current = participantOrder.indexOf(currentParticipantId);
    if (current < 0 || players.get(current).status() != PlayerStatus.PLAYING) {
      throw new IllegalArgumentException("turn");
    }
    for (int i = 0; i < players.size(); i++) {
      PlayerStatus status = players.get(i).status();
      if (i < current && status == PlayerStatus.PLAYING) {
        throw new IllegalArgumentException("turn");
      }
      if (i > current && status == PlayerStatus.PLAYING && players.get(i).cards().size() != 2) {
        throw new IllegalArgumentException("turn");
      }
    }
  }

  private static void checkStatus(PlayerSeat player) {
    int total = value(player.cards());
    boolean valid = switch (player.status()) {
      case PLAYING -> total < 21 && !player.cards().isEmpty() || player.cards().isEmpty();
      case NATURAL -> natural(player.cards());
      case STAND -> !player.cards().isEmpty() && total < 21;
      case BUST -> total > 21;
      case TWENTY_ONE -> total == 21 && player.cards().size() > 2;
    };
    if (!valid || (player.cards().size() == 2 && total == 21 && player.status() != PlayerStatus.NATURAL)) {
      throw new IllegalArgumentException("status");
    }
  }

  private static Map<Card, Integer> multiset(List<Card> cards) {
    Map<Card, Integer> counts = new HashMap<>();
    for (Card card : cards) {
      counts.merge(card, 1, Integer::sum);
    }
    return counts;
  }

  private static int[] ranks(double[] scores) {
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < scores.length; i++) {
      order.add(i);
    }
    order.sort((left, right) -> Double.compare(scores[right], scores[left]));
    int[] ranks = new int[scores.length];
    int rank = 1;
    for (int i = 0; i < order.size(); i++) {
      if (i > 0 && Double.compare(scores[order.get(i)], scores[order.get(i - 1)]) != 0) {
        rank = i + 1;
      }
      ranks[order.get(i)] = rank;
    }
    return ranks;
  }

  private static int points(String rank) {
    return switch (rank) {
      case "A" -> 11;
      case "J", "Q", "K" -> 10;
      default -> Integer.parseInt(rank);
    };
  }

  private static GameEventDraft dealt(Card card, String seat, UUID participantId, boolean hole) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("seat", seat);
    payload.put("deckNo", card.deckNo());
    payload.put("suit", card.suit());
    payload.put("rank", card.rank());
    payload.put("hole", hole);
    if (participantId != null) {
      payload.put("participantId", participantId);
    }
    return event("CardDealt", payload);
  }

  private static GameEventDraft revealed(Card card) {
    return event("DealerHoleRevealed", Map.of("seat", DEALER, "deckNo", card.deckNo(),
        "suit", card.suit(), "rank", card.rank()));
  }

  private static GameEventDraft finished(GameResult result) {
    return event("MatchFinished", Map.of("result", result));
  }

  private static GameEventDraft event(String type, Map<String, Object> payload) {
    return new GameEventDraft(type, 1, payload);
  }
}
