package com.social100.todero.component.spotify.core;

public final class SpotifyConfig {
  private final String clientId;
  private final String redirectUrlApp;
  private final String redirectUrlConsole;
  private final String redirectAllowlist;
  private final String deviceId;

  private SpotifyConfig(Builder builder) {
    this.clientId = builder.clientId;
    this.redirectUrlApp = builder.redirectUrlApp;
    this.redirectUrlConsole = builder.redirectUrlConsole;
    this.redirectAllowlist = builder.redirectAllowlist;
    this.deviceId = builder.deviceId;
  }

  // Convenience factory
  public static Builder builder() {
    return new Builder();
  }

  // Getters only (immutability)
  public String getClientId() {
    return clientId;
  }

  public String getRedirectUrlApp() {
    return redirectUrlApp;
  }

  public String getRedirectUrlConsole() {
    return redirectUrlConsole;
  }

  public String getRedirectAllowlist() {
    return redirectAllowlist;
  }

  public String getDeviceId() {
    return deviceId;
  }

  // Builder
  public static class Builder {
    private String clientId;
    private String redirectUrlApp;
    private String redirectUrlConsole;
    private String redirectAllowlist;
    private String deviceId;

    public Builder clientId(String clientId) {
      this.clientId = clientId;
      return this;
    }

    public Builder redirectUrlApp(String redirectUrlApp) {
      this.redirectUrlApp = redirectUrlApp;
      return this;
    }

    public Builder redirectUrlConsole(String redirectUrlConsole) {
      this.redirectUrlConsole = redirectUrlConsole;
      return this;
    }

    public Builder redirectAllowlist(String redirectAllowlist) {
      this.redirectAllowlist = redirectAllowlist;
      return this;
    }

    public Builder deviceId(String deviceId) {
      this.deviceId = deviceId;
      return this;
    }

    public SpotifyConfig build() {
      return new SpotifyConfig(this);
    }
  }
}
