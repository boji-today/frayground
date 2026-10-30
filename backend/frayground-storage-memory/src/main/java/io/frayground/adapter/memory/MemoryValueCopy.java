package io.frayground.adapter.memory;

import java.util.*;

/** Defensive copying for JSON-shaped values accepted by the game storage. */
final class MemoryValueCopy {
  private MemoryValueCopy() {}
  static Map<String,Object> map(Map<String,?> source) {
    Objects.requireNonNull(source);
    Map<String,Object> result=new LinkedHashMap<>();
    source.forEach((k,v)->result.put(Objects.requireNonNull(k),value(v)));
    return Collections.unmodifiableMap(result);
  }
  static Object value(Object v) {
    if(v==null||v instanceof String||v instanceof Boolean||v instanceof Byte||v instanceof Short||v instanceof Integer||v instanceof Long||v instanceof Float||v instanceof Double||v instanceof java.math.BigInteger||v instanceof java.math.BigDecimal) return v;
    if(v instanceof byte[] bytes) return bytes.clone();
    if(v instanceof Map<?,?> map) { Map<String,Object> out=new LinkedHashMap<>(); map.forEach((k,x)->{if(!(k instanceof String s))throw new IllegalArgumentException("JSON object keys must be strings"); out.put(s,value(x));}); return Collections.unmodifiableMap(out); }
    if(v instanceof List<?> list) { List<Object> out=new ArrayList<>(list.size()); for(Object x:list)out.add(value(x)); return Collections.unmodifiableList(out); }
    throw new IllegalArgumentException("Unsupported mutable storage value: "+v.getClass().getName());
  }
  static Object read(Object v) { return value(v); }
  static boolean equal(Object a,Object b) {
    if(a==b)return true;
    if(a==null||b==null)return false;
    if(a instanceof byte[] x&&b instanceof byte[] y)return Arrays.equals(x,y);
    if(a instanceof Map<?,?> x&&b instanceof Map<?,?> y) {
      if(!x.keySet().equals(y.keySet()))return false;
      for(Object k:x.keySet())if(!equal(x.get(k),y.get(k)))return false;
      return true;
    }
    if(a instanceof List<?> x&&b instanceof List<?> y) {
      if(x.size()!=y.size())return false;
      for(int i=0;i<x.size();i++)if(!equal(x.get(i),y.get(i)))return false;
      return true;
    }
    return a.equals(b);
  }
}
