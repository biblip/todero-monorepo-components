package com.social100.todero;

import java.util.HashMap;
import java.util.Map;

public class OAuth2Config {
  private static final Map<String, String> providerEndpoints = new HashMap<>();

  static {
    providerEndpoints.put("gmail", "https://mail.google.com/");
    providerEndpoints.put("outlook", "https://outlook.office.com/");
    providerEndpoints.put("yahoo", "https://api.login.yahoo.com/");
  }

  public static String getScope(String provider) {
    return providerEndpoints.getOrDefault(provider, "");
  }
}
