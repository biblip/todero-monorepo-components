package com.shellaia.component.spotify;

import java.util.LinkedHashMap;
import java.util.Map;

final class SpotifyComponentDefaults {
  static final String ENV_SPOTIFY_CLIENT_ID = "SPOTIFY_CLIENT_ID";
  static final String ENV_SPOTIFY_REDIRECT_URI_APP = "SPOTIFY_REDIRECT_URI_APP";
  static final String ENV_SPOTIFY_REDIRECT_URI_CONSOLE = "SPOTIFY_REDIRECT_URI_CONSOLE";
  static final String ENV_SPOTIFY_REDIRECT_URI_ALLOWLIST = "SPOTIFY_REDIRECT_URI_ALLOWLIST";
  static final String ENV_SPOTIFY_DEVICE_ID = "SPOTIFY_DEVICE_ID";

  static final String DEFAULT_SPOTIFY_CLIENT_ID = "6a97c2f26f4c4043aef129247f4c7426";
  static final String DEFAULT_SPOTIFY_REDIRECT_URI_APP = "https://auth.shellaia.com/component/callback";
  static final String DEFAULT_SPOTIFY_REDIRECT_URI_CONSOLE = "http://127.0.0.1:34895/spotify/callback";
  static final String DEFAULT_SPOTIFY_REDIRECT_URI_ALLOWLIST =
      DEFAULT_SPOTIFY_REDIRECT_URI_APP + "," + DEFAULT_SPOTIFY_REDIRECT_URI_CONSOLE;

  private SpotifyComponentDefaults() {
  }

  static Map<String, String> withDefaults(Map<String, String> env) {
    LinkedHashMap<String, String> merged = new LinkedHashMap<>();
    if (env != null) {
      merged.putAll(env);
    }
    putIfBlank(merged, ENV_SPOTIFY_CLIENT_ID, DEFAULT_SPOTIFY_CLIENT_ID);
    putIfBlank(merged, ENV_SPOTIFY_REDIRECT_URI_APP, DEFAULT_SPOTIFY_REDIRECT_URI_APP);
    putIfBlank(merged, ENV_SPOTIFY_REDIRECT_URI_CONSOLE, DEFAULT_SPOTIFY_REDIRECT_URI_CONSOLE);
    putIfBlank(merged, ENV_SPOTIFY_REDIRECT_URI_ALLOWLIST, DEFAULT_SPOTIFY_REDIRECT_URI_ALLOWLIST);
    putIfBlank(merged, ENV_SPOTIFY_DEVICE_ID, "");
    return merged;
  }

  private static void putIfBlank(Map<String, String> env, String key, String value) {
    String current = env.get(key);
    if (current == null || current.isBlank()) {
      env.put(key, value);
    }
  }
}
