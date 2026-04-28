package com.shellaia.agent.term;

import org.junit.jupiter.api.Test;

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
}

