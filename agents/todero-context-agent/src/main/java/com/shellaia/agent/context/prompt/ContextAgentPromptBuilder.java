package com.shellaia.agent.context.prompt;

import com.shellaia.agent.context.model.BackgroundMemoryCandidate;
import com.shellaia.agent.context.model.SubjectBranchOverview;
import com.shellaia.agent.context.model.SubjectLedgerEntry;
import com.shellaia.agent.context.model.SubjectWorkspaceSnapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ContextAgentPromptBuilder {
  private static final String TEMPLATE_RESOURCE = "/prompts/conversational-context-agent-system.md";
  private static final String TEMPLATE = loadTemplate();

  public ContextAgentPrompt build(AgentMode mode,
                                  SubjectWorkspaceSnapshot snapshot,
                                  List<String> recentInteractions,
                                  List<String> durableInteractions,
                                  List<String> draftCandidates) {
    AgentMode safeMode = mode == null ? AgentMode.GENERAL : mode;
    List<String> sections = new ArrayList<>();
    sections.add(TEMPLATE.trim());
    sections.add("Current mode: " + safeMode);

    String subjectId = "";
    String branchId = "";
    boolean branchContextIncluded = false;

    if (snapshot != null) {
      subjectId = snapshot.identity().subjectId();
      sections.add(renderSubject(snapshot));
      if (snapshot.branchOverview() != null) {
        sections.add(renderBranch(snapshot.branchOverview()));
        branchContextIncluded = true;
        branchId = snapshot.branchOverview().manifest().branchId();
      }
      sections.add(renderLedger(snapshot.ledgerEntry()));
      sections.add(renderMemory(snapshot));
      sections.add(renderBackground(snapshot.backgroundScanResult().candidates(), snapshot.backgroundScanResult().gitHints()));
    }

    sections.add(renderList("Recent interactions", recentInteractions));
    sections.add(renderList("Durable reminders and tasks", durableInteractions));
    sections.add(renderList("Draft subject candidates", draftCandidates));
    sections.add(outputStyle());
    return new ContextAgentPrompt(
        safeMode,
        subjectId,
        branchId,
        branchContextIncluded,
        String.join("\n\n", sections),
        List.copyOf(sections)
    );
  }

  private static String renderSubject(SubjectWorkspaceSnapshot snapshot) {
    StringBuilder out = new StringBuilder();
    out.append("Active subject:\n");
    out.append("- id: ").append(snapshot.identity().subjectId()).append('\n');
    out.append("- display name: ").append(snapshot.identity().displayName()).append('\n');
    out.append("- active branch: ").append(blankOr(snapshot.identity().activeBranchId())).append('\n');
    out.append("- created at: ").append(snapshot.identity().createdAt()).append('\n');
    out.append("- updated at: ").append(snapshot.identity().updatedAt());
    return out.toString();
  }

  private static String renderLedger(SubjectLedgerEntry entry) {
    if (entry == null) {
      return "Ledger entry:\n- none";
    }
    StringBuilder out = new StringBuilder();
    out.append("Ledger entry:\n");
    out.append("- subject id: ").append(entry.subjectId()).append('\n');
    out.append("- display name: ").append(entry.displayName()).append('\n');
    out.append("- canonical: ").append(entry.canonical()).append('\n');
    out.append("- branch goal: ").append(blankOr(entry.branchGoal())).append('\n');
    out.append("- branch status: ").append(blankOr(entry.branchStatus())).append('\n');
    out.append("- active branch: ").append(blankOr(entry.activeBranchId())).append('\n');
    out.append("- created at: ").append(entry.createdAt()).append('\n');
    out.append("- updated at: ").append(entry.updatedAt());
    return out.toString();
  }

  private static String renderBranch(SubjectBranchOverview branchOverview) {
    StringBuilder out = new StringBuilder();
    out.append("Branch context:\n");
    out.append("- branch id: ").append(branchOverview.manifest().branchId()).append('\n');
    out.append("- subject id: ").append(branchOverview.manifest().subjectId()).append('\n');
    out.append("- focus: ").append(branchOverview.manifest().focus()).append('\n');
    out.append("- status: ").append(branchOverview.manifest().status()).append('\n');
    out.append("- goal: ").append(blankOr(branchOverview.manifest().goal())).append('\n');
    out.append("- notes: ").append(blankOr(branchOverview.manifest().notes())).append('\n');
    out.append("- outcome: ").append(blankOr(branchOverview.manifest().outcome())).append('\n');
    out.append("- summary: ").append(branchOverview.summary());
    return out.toString();
  }

  private static String renderMemory(SubjectWorkspaceSnapshot snapshot) {
    StringBuilder out = new StringBuilder();
    out.append("Curated memory:\n");
    out.append(snapshot.memoryView().currentSummary(8, 1800));
    return out.toString();
  }

  private static String renderBackground(List<BackgroundMemoryCandidate> candidates, List<String> gitHints) {
    StringBuilder out = new StringBuilder();
    out.append("Background candidates:\n");
    if (candidates == null || candidates.isEmpty()) {
      out.append("none");
    } else {
      for (BackgroundMemoryCandidate candidate : candidates) {
        out.append("- [").append(candidate.source()).append("] ");
        out.append(candidate.kind()).append(" @ ").append(candidate.subjectId()).append(" / ").append(blankOr(candidate.branchId()));
        out.append(" : ").append(blankOr(candidate.excerpt())).append('\n');
      }
      if (out.charAt(out.length() - 1) == '\n') {
        out.setLength(out.length() - 1);
      }
    }
    if (gitHints != null && !gitHints.isEmpty()) {
      out.append("\nGit hints:\n");
      for (String hint : gitHints) {
        out.append("- ").append(hint).append('\n');
      }
      if (out.charAt(out.length() - 1) == '\n') {
        out.setLength(out.length() - 1);
      }
    }
    return out.toString();
  }

  private static String renderList(String title, List<String> values) {
    StringBuilder out = new StringBuilder();
    out.append(title).append(":\n");
    if (values == null || values.isEmpty()) {
      out.append("none");
      return out.toString();
    }
    for (String value : values) {
      if (value == null || value.isBlank()) {
        continue;
      }
      out.append("- ").append(value.trim()).append('\n');
    }
    if (out.charAt(out.length() - 1) == '\n') {
      out.setLength(out.length() - 1);
    }
    return out.toString();
  }

  private static String outputStyle() {
    return "Output style:\n- direct, grounded, concise\n- distinguish fact, inference, advice, and confirmed memory\n- do not repeat clarifications once the user has already answered them\n- treat short confirmations as resolving the immediately previous unresolved question\n- ask for confirmation before changing active subject or promoting uncertain inferences to confirmed truth\n- surface durable reminders and tasks proactively when they are already known\n- when you claim you changed something, verify the result first and report failure plainly if the verification does not match";
  }

  private static String blankOr(String value) {
    return value == null || value.isBlank() ? "none" : value.trim();
  }

  private static String loadTemplate() {
    try (var in = ContextAgentPromptBuilder.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
      if (in == null) {
        return defaultTemplate();
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load prompt template " + TEMPLATE_RESOURCE, e);
    }
  }

  private static String defaultTemplate() {
    return """
        You are a conversational context agent.

        Your job is to help the user explore, structure, and maintain subject contexts with high autonomy and clear provenance.

        Operating model:
        - general mode: no active subject yet
        - subject mode: one active subject, with draft and confirmed memory

        You should:
        - classify incoming information aggressively and accurately
        - maintain summaries, narratives, decisions, questions, and reminders
        - treat durable reminders and tasks as first-class records, not as chat history
        - curate memory proactively
        - create draft subject structures when a new topic emerges
        - inspect git history when branch lineage matters
        - use exploratory branches to test alternate subject framings
        - rely on the background layer for deeper memory scans instead of re-reading the full history on every response
        - keep a small internal map of the active subject plus nearby candidate subjects, so you can continue the same conversation without reintroducing yourself or asking how to help
        - switch subjects transparently in your reasoning; only ask for confirmation when the current subject is genuinely ambiguous or a switch would change the active focus
        - if the user answers a short confirmation like "yes", resolve it against the immediately previous unresolved question or suggestion
        - answer only what the user asked, unless you have concise and clearly relevant information that should be surfaced proactively
        - never repeat the same clarification once the user has already answered it
        - be mostly concise and direct; do not repeatedly ask "how can I help" or narrate everything you know about the subject
        - avoid ending every turn with a generic offer to help unless the user is actually at a decision point
        - keep subject descriptions short and useful, so the user can recognize where to switch next
        - ask for confirmation only when changing the active subject, promoting uncertain inferences, or overwriting confirmed state
        - promote user requests for reminders, tasks, decisions, and follow-ups into durable memory when safe, and keep the original provenance visible
        - if a useful reminder or task is already known, surface it proactively instead of waiting for the user to ask twice
        - use the durable list and status-update flow to mark items open, done, canceled, or reopened without depending on chat history alone
        - whenever you claim that you changed something, verify the result first by checking the action response or the refreshed durable/state view, and if the verification fails, say that it failed instead of implying success

        You must:
        - never present an inference as confirmed fact
        - never hide whether something is raw, derived, provisional, or confirmed
        - never invent storage state
        - clearly label advice as advice or opinion
        """;
  }
}
