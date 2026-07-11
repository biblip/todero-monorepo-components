package com.shellaia.aia.parser;

import com.social100.todero.remote.RemoteCliConfig;
import com.social100.todero.util.ArgumentParser;

import java.net.URI;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

public class AIAArgumentParser {
  ArgumentParser parser = null;

  public RemoteCliConfig parseArgs(String commandLine) {
    if (this.parser == null) {
      this.parser = new ArgumentParser();
    }
    List<String> args = parser.tokenizeCommandLine(commandLine);
    return parseArgs(args.toArray(new String[0]));
  }

  public RemoteCliConfig parseArgs(String[] args) {
    // 1) Configure the reusable ArgumentParser
    if (this.parser == null) {
      parser = new ArgumentParser();
    }
    // host: default matches old code ("ai://127.0.0.1"); accept any non-empty string
    parser.addRule("host", v -> v == null || !v.isEmpty(), "ai://127.0.0.1");
    // sni: optional (null by default); if provided, must be non-empty
    parser.addRule("sni", v -> v == null || !v.isEmpty(), null);
    // trust anchors: optional path to PEM/DER cert(s)
    parser.addRule("trust-anchors", v -> v == null || !v.isEmpty(), null);
    // pinned SPKI SHA-256 hash: hex or base64
    parser.addRule("pinned-spki", v -> v == null || !v.isEmpty(), null);
    // client auth PKCS#12 identity
    parser.addRule("client-pkcs12", v -> v == null || !v.isEmpty(), null);
    parser.addRule("client-key-alias", v -> v == null || !v.isEmpty(), null);
    parser.addRule("client-password", v -> v == null || !v.isEmpty(), null);

    parser.addRule("name", value -> value != null && !value.trim().isEmpty(), "defaultName");

    // 2) Normalize argv to the parser’s expected format:
    //    - Convert --key=value into ["--key", "value"]
    //    - Treat the first positional token (no leading "--") as ["--host", <value>]
    List<String> normalized = new ArrayList<>();
    boolean positionalHostCaptured = false;
    for (String a : args) {
      if (a.startsWith("--") && a.contains("=")) {
        int eq = a.indexOf('=');
        String key = a.substring(0, eq);     // e.g., --host
        String val = a.substring(eq + 1);    // e.g., ai://...
        normalized.add(key);
        normalized.add(val);
      } else if (!a.startsWith("--") && !positionalHostCaptured) {
        normalized.add("--host");
        normalized.add(a);
        positionalHostCaptured = true;
      } else {
        normalized.add(a);
      }
    }

    // 3) Parse and handle errors uniformly
    if (!parser.parse(normalized.toArray(new String[0]))) {
      System.err.println("Failed to parse arguments: " + parser.errorMessage());
      return null;
    }

    String serverUrl = parser.getArgument("host");
    String sniOverride = parser.getArgument("sni");
    String trustAnchorsPath = parser.getArgument("trust-anchors");
    String pinnedSpkiSha256 = parser.getArgument("pinned-spki");
    String clientPkcs12Path = parser.getArgument("client-pkcs12");
    String clientKeyAlias = parser.getArgument("client-key-alias");
    String clientPassword = parser.getArgument("client-password");

    // 4) Preserve the original URI / scheme / port logic
    URI uri;
    try {
      System.out.printf("Connecting to: %s%n%n", serverUrl);
      uri = URI.create(serverUrl);
    } catch (IllegalArgumentException iae) {
      System.err.println("error: invalid server URL: " + serverUrl);
      return null;
    }

    String schemeString = uri.getScheme();
    String urlHost = uri.getHost();
    if (urlHost == null) {
      urlHost = uri.toString();
    }
    String rawHostForLog = urlHost;
    int port;

    if (uri.getPort() != -1) {
      port = uri.getPort();
    } else {
      if (schemeString != null) {
        port = switch (schemeString) {
          case "aia" -> 414;
          case "uaia" -> 41414;
          case "ai" -> 41;
          case "uai" -> 4141;
          default -> 0;
        };
      } else {
        port = 41;
        schemeString = "ai";
      }
    }

    boolean tlsEnabled = schemeString == null || !schemeString.startsWith("u");

    String vhost = (sniOverride != null && !sniOverride.isEmpty()) ? sniOverride : urlHost;
    if (vhost != null && vhost.startsWith("[") && vhost.endsWith("]")) {
      vhost = vhost.substring(1, vhost.length() - 1);
    }
    if (rawHostForLog == null && sniOverride != null) rawHostForLog = sniOverride;

    URI normalizedUri = normalizeBaseUri(uri);

    return new RemoteCliConfig(
        normalizedUri,
        urlHost,
        rawHostForLog,
        vhost,
        port,
        tlsEnabled,
        trustAnchorsPath,
        pinnedSpkiSha256,
        clientPkcs12Path,
        clientPassword,
        clientKeyAlias,
        java.util.Collections.<String, String>emptyMap(),
        java.util.Collections.<String>emptyList(),
        null,
        null,
        "http",
        false);
  }

  public String getArgument(String name) {
    return parser.getArgument(name);
  }

  public String errorMessage() {
    return parser.errorMessage();
  }

  private static URI normalizeBaseUri(URI uri) {
    if (uri == null) {
      return null;
    }
    String scheme = uri.getScheme();
    if (scheme == null) {
      return uri;
    }
    String normalizedScheme = scheme.trim().toLowerCase(Locale.ROOT);
    if (!"ai".equals(normalizedScheme) && !"aia".equals(normalizedScheme)) {
      return uri;
    }
    int port = uri.getPort();
    if (port == -1) {
      port = "aia".equals(normalizedScheme) ? 414 : 41;
    }
    String authority = uri.getRawAuthority();
    if (authority == null || authority.isBlank()) {
      authority = uri.getHost();
    }
    if (authority == null || authority.isBlank()) {
      authority = uri.toString();
    }
    if (!authority.contains(":")) {
      authority = authority + ":" + port;
    }
    String path = uri.getRawPath() == null ? "" : uri.getRawPath();
    String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
    String fragment = uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment();
    return URI.create("https://" + authority + path + query + fragment);
  }
}
