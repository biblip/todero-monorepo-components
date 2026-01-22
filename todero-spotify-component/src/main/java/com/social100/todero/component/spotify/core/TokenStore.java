package com.social100.todero.component.spotify.core;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.social100.todero.common.storage.Storage;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

public final class TokenStore {
  private static final Gson GSON = new Gson();
  private static final String DEFAULT_SECRET_KEY = "auth/token";

  private final Storage storage;

  public TokenStore(Storage storage) {
    if (storage == null) throw new IllegalArgumentException("storage is required");
    this.storage = storage;
  }

  /**
   * Returns true if the token is expired (with 30s clock skew).
   */
  public static boolean isExpired(TokenData td) {
    return Instant.now().getEpochSecond() >= td.expiresAtEpoch - 30;
  }

  /**
   * Read token data from secret storage.
   */
  public Optional<TokenData> read() {
    try {
      String json = storage.getSecret(DEFAULT_SECRET_KEY);
      if (json == null || json.isBlank()) return Optional.empty();
      TokenData td = GSON.fromJson(json, TokenData.class);
      return Optional.ofNullable(td);
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /**
   * Write token data to secret storage (overwrites existing).
   */
  public void write(TokenData td) throws IOException {
    String json = GSON.toJson(td);
    storage.putSecret(DEFAULT_SECRET_KEY, json);
  }

  /**
   * Remove any stored token. No-op if missing.
   */
  public void clear() throws IOException {
    storage.deleteSecret(DEFAULT_SECRET_KEY);
  }

  public static class TokenData {
    @SerializedName("access_token")
    public String accessToken;
    @SerializedName("refresh_token")
    public String refreshToken;
    @SerializedName("expires_at_epoch")
    public long expiresAtEpoch; // seconds
    @SerializedName("scope")
    public String scope;
  }
}
