package com.shellaia.aia.parser;

import com.social100.todero.remote.RemoteCliConfig;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AIAArgumentParserTest {
  @Test
  void aiSchemeIsNormalizedToHttpsWithAgentPort() {
    RemoteCliConfig cfg = new AIAArgumentParser().parseArgs(new String[]{"ai://brumor.pbxkey.com"});

    assertNotNull(cfg);
    assertEquals(URI.create("https://brumor.pbxkey.com:41"), cfg.getBaseUri());
    assertEquals("brumor.pbxkey.com", cfg.getServerRawHost());
    assertEquals("brumor.pbxkey.com", cfg.getVhostSni());
    assertEquals(41, cfg.getPort());
    assertEquals("brumor.pbxkey.com:41", cfg.getHostHeaderAuthority());
  }

  @Test
  void aiaSchemeIsNormalizedToHttpsWithComponentPort() {
    RemoteCliConfig cfg = new AIAArgumentParser().parseArgs(new String[]{"aia://brumor.pbxkey.com"});

    assertNotNull(cfg);
    assertEquals(URI.create("https://brumor.pbxkey.com:414"), cfg.getBaseUri());
    assertEquals("brumor.pbxkey.com", cfg.getServerRawHost());
    assertEquals("brumor.pbxkey.com", cfg.getVhostSni());
    assertEquals(414, cfg.getPort());
    assertEquals("brumor.pbxkey.com:414", cfg.getHostHeaderAuthority());
  }
}
