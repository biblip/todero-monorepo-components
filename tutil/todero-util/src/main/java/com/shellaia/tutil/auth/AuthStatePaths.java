package com.shellaia.tutil.auth;

import java.nio.file.Path;

/**
 * Shared local auth state layout used by the Todero auth broker and its gate.
 * <p>
 * The root path may be overridden for tests or alternate deployments with the
 * `todero.auth.root` system property.
 */
public final class AuthStatePaths {
  public static final String AUTH_ROOT_PROPERTY = "todero.auth.root";
  private static final String DEFAULT_ROOT_RELATIVE = ".todero/auth-broker";

  private AuthStatePaths() {
  }

  public static Path root() {
    String override = System.getProperty(AUTH_ROOT_PROPERTY);
    if (override != null && !override.isBlank()) {
      return Path.of(override.trim()).toAbsolutePath().normalize();
    }
    return Path.of(System.getProperty("user.home"), DEFAULT_ROOT_RELATIVE).toAbsolutePath().normalize();
  }

  public static Path settingsFile() {
    return root().resolve("settings.json");
  }

  public static Path sessionFile() {
    return root().resolve("session.json");
  }

  public static Path principalFile() {
    return root().resolve("principal.json");
  }

  public static Path challengeFile() {
    return root().resolve("challenge.json");
  }
}
