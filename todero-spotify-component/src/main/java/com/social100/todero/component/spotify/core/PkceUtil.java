package com.social100.todero.component.spotify.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class PkceUtil {
  private static final SecureRandom RNG = new SecureRandom();

  public static String generateCodeVerifier() {
    byte[] bytes = new byte[64]; // 43-128 chars when b64url
    RNG.nextBytes(bytes);
    return base64Url(bytes);
  }

  public static String codeChallengeS256(String codeVerifier) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
      return base64Url(digest);
    } catch (Exception e) {
      throw new RuntimeException("Unable to compute code_challenge", e);
    }
  }

  private static String base64Url(byte[] data) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
  }
}