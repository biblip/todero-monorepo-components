package com.shellaia.component.term.util;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class Json {
  private Json() {
  }

  public static String quote(String s) {
    if (s == null) return "null";
    StringBuilder sb = new StringBuilder();
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
    return sb.toString();
  }

  public static String obj(Map<String, Object> map) {
    StringBuilder sb = new StringBuilder();
    sb.append('{');
    Iterator<Map.Entry<String, Object>> it = map.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, Object> e = it.next();
      sb.append(quote(e.getKey())).append(':').append(value(e.getValue()));
      if (it.hasNext()) sb.append(',');
    }
    sb.append('}');
    return sb.toString();
  }

  public static String array(List<?> list) {
    StringBuilder sb = new StringBuilder();
    sb.append('[');
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append(value(list.get(i)));
    }
    sb.append(']');
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  private static String value(Object v) {
    if (v == null) return "null";
    if (v instanceof String s) return quote(s);
    if (v instanceof Number n) return n.toString();
    if (v instanceof Boolean b) return b ? "true" : "false";
    if (v instanceof Map<?, ?> m) return obj((Map<String, Object>) m);
    if (v instanceof List<?> list) return array(list);
    return quote(String.valueOf(v));
  }
}
