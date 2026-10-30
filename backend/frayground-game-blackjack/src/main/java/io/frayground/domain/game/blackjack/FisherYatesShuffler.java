package io.frayground.domain.game.blackjack;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.LongSupplier;

public final class FisherYatesShuffler implements ShoeShuffler {
  public static final String ALGORITHM = "fisher-yates/v1";
  private final LongSupplier seeds;

  public FisherYatesShuffler(LongSupplier seeds) {
    this.seeds = seeds;
  }

  @Override
  public ShoeShuffle shuffle() {
    long seed = seeds.getAsLong();
    List<Card> cards = new ArrayList<>(BlackjackRules.standardShoe());
    Random random = new Random(seed);
    for (int i = cards.size() - 1; i > 0; i--) {
      int j = random.nextInt(i + 1);
      cards.set(i, cards.set(j, cards.get(i)));
    }
    return new ShoeShuffle(seed, cards);
  }
}
