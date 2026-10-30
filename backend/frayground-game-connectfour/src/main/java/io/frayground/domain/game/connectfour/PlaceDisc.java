package io.frayground.domain.game.connectfour;

import io.frayground.domain.game.GameAction;

public record PlaceDisc(String type, int column) implements GameAction {}
