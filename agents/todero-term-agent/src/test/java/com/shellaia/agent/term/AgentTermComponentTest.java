package com.shellaia.agent.term;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class AgentTermComponentTest {

  @Test
  void sanitizeName_normalizesAndHyphenates() {
    assertEquals("hello-world", AgentTermComponent.sanitizeName("Hello World"));
    assertEquals("cafe-au-lait", AgentTermComponent.sanitizeName("Café au lait"));
    assertEquals("a-b-c", AgentTermComponent.sanitizeName("A__B---C"));
  }

  @Test
  void sanitizeName_rejectsEmpty() {
    assertEquals("", AgentTermComponent.sanitizeName(""));
    assertEquals("", AgentTermComponent.sanitizeName("   "));
    assertEquals("", AgentTermComponent.sanitizeName("###"));
  }

  @Test
  void composeWriteText_appendsCarriageReturnOnlyWhenSubmitting() {
    assertEquals("ls -al\r", AgentTermComponent.composeWriteText("ls -al", true));
    assertEquals("ls -al", AgentTermComponent.composeWriteText("ls -al", false));
    assertEquals("\r", AgentTermComponent.composeWriteText("", true));
  }

  @Test
  void deriveAgentCwd_usesHomeTermSubdirectory() {
    Path expected = Path.of(System.getProperty("user.home"), "term", "my-project");
    assertEquals(expected, AgentTermComponent.deriveAgentCwd("my-project"));
  }

  @Test
  void deriveAgentCwd_preservesNestedSanitizedLeafOnly() {
    Path expected = Path.of(System.getProperty("user.home"), "term", "alpha-beta");
    assertEquals(expected, AgentTermComponent.deriveAgentCwd("alpha-beta"));
  }
}
