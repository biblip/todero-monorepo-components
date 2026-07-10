package com.shellaia.agent.context.classify;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextMessageClassifierTest {
  private final ContextMessageClassifier classifier = new ContextMessageClassifier();

  @Test
  void classifiesMessageKindsAndBranchSignalsSeparately() {
    ContextMessageClassification classification = classifier.classify(
        "I think we should open a branch to investigate this new subject.",
        "alpha"
    );

    assertEquals(ContextMessageKind.OPINION, classification.kind());
    assertEquals(BranchSignalKind.OPEN, classification.branchSignalKind());
    assertTrue(classification.confirmationRequired());
    assertTrue(classification.confidence() >= 0.85d);
  }

  @Test
  void classifiesQuestionsAndSuggestsSubjectSwitchesWhenRequested() {
    ContextMessageClassification classification = classifier.classify(
        "Should we switch to the beta context?",
        "alpha"
    );

    assertEquals(ContextMessageKind.QUESTION, classification.kind());
    assertTrue(classification.subjectSwitchSuggested());
    assertTrue(classification.confirmationRequired());
    assertEquals("beta context", classification.candidateSubject());
  }

  @Test
  void keepsGeneralFactsAsFactWhenNoSpecialSignalsExist() {
    ContextMessageClassification classification = classifier.classify(
        "The user confirmed the subject stayed focused.",
        "alpha"
    );

    assertEquals(ContextMessageKind.FACT, classification.kind());
    assertEquals(BranchSignalKind.NONE, classification.branchSignalKind());
    assertFalse(classification.subjectSwitchSuggested());
    assertFalse(classification.confirmationRequired());
  }
}
