package io.frayground.application;

import io.frayground.domain.action.ActionEnvelope;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/** Semantic equality for complete envelopes and JSON payload values. */
public final class ActionEnvelopeCanonicalizer {
  public boolean equivalent(ActionEnvelope a, ActionEnvelope b) {
    return a.protocolVersion().equals(b.protocolVersion()) && a.matchId().equals(b.matchId())
        && a.participantId().equals(b.participantId()) && a.turnId().equals(b.turnId())
        && a.decisionId().equals(b.decisionId()) && a.actionId().equals(b.actionId())
        && a.stateVersion()==b.stateVersion() && canonical(a.action()).equals(canonical(b.action()));
  }
  private Object canonical(Object value) {
    if (value == null) return NullValue.INSTANCE;
    if (value instanceof Number n) {
      try { return new Numeric(n instanceof Byte || n instanceof Short || n instanceof Integer || n instanceof Long || n instanceof BigInteger,
          new BigDecimal(n.toString()).stripTrailingZeros()); }
      catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid JSON number",ex); }
    }
    if (value instanceof String || value instanceof Boolean) return value;
    if (value instanceof Map<?,?> map) {
      Map<String,Object> result=new TreeMap<>();
      map.forEach((k,v)-> { if (!(k instanceof String)) throw new IllegalArgumentException("Invalid JSON key"); result.put((String) k,canonical(v)); });
      return result;
    }
    if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
    throw new IllegalArgumentException("Invalid JSON value");
  }
  private enum NullValue { INSTANCE }
  private record Numeric(boolean integral,BigDecimal value) {}
}
