package com.shellaia.agent.context.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shellaia.agent.context.model.BackgroundMemoryCandidate;
import com.shellaia.agent.context.model.BackgroundScanResult;
import com.shellaia.agent.context.model.ConversationDurableRecord;
import com.shellaia.agent.context.model.ConversationDurableStatus;
import com.shellaia.agent.context.model.ConversationTurnRecord;
import com.shellaia.agent.context.model.ConversationDurableKind;
import com.shellaia.agent.context.model.BranchManifest;
import com.shellaia.agent.context.model.SubjectBranchOverview;
import com.shellaia.agent.context.model.SubjectIdentity;
import com.shellaia.agent.context.model.SubjectLedger;
import com.shellaia.agent.context.model.SubjectLedgerEntry;
import com.shellaia.agent.context.model.SubjectMemoryKind;
import com.shellaia.agent.context.model.SubjectMemoryLayer;
import com.shellaia.agent.context.model.SubjectMemoryRecord;
import com.shellaia.agent.context.model.SubjectMemoryView;
import com.shellaia.agent.context.model.SubjectWorkspaceSnapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ContextAgentWorkspace {
  private static final Logger LOG = LoggerFactory.getLogger(ContextAgentWorkspace.class);
  private static final TypeReference<List<SubjectMemoryRecord>> MEMORY_RECORD_LIST = new TypeReference<>() { };
  private static final String SUBJECT_DIR = "subjects";
  private static final String LEDGER_DIR = "ledger";
  private static final String BRANCHES_DIR = "branches";
  private static final String IDENTITY_FILE = "subject.json";
  private static final String RAW_FILE = "raw.json";
  private static final String DERIVED_FILE = "derived.json";
  private static final String REMEMBERED_FILE = "remembered.json";
  private static final String LEDGER_FILE = "subject-ledger.json";
  private static final String MANIFEST_FILE = "manifest.json";
  private static final String CONVERSATIONS_DIR = "conversations";
  private static final String THREADS_DIR = "threads";
  private static final String HISTORY_FILE = "history.json";
  private static final String DURABLES_FILE = "durables.json";
  private static final TypeReference<List<ConversationTurnRecord>> CONVERSATION_RECORD_LIST = new TypeReference<>() { };
  private static final TypeReference<List<ConversationDurableRecord>> CONVERSATION_DURABLE_RECORD_LIST = new TypeReference<>() { };

  private final Path root;
  private final ObjectMapper mapper;

  public ContextAgentWorkspace(Path root) {
    this(root, ContextAgentObjectMappers.create());
  }

  ContextAgentWorkspace(Path root, ObjectMapper mapper) {
    this.root = Objects.requireNonNull(root, "root");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  public Path root() {
    return root;
  }

  public SubjectIdentity createSubject(String subjectId, String displayName, String activeBranchId, Instant now) {
    SubjectIdentity identity = SubjectIdentity.create(subjectId, displayName, activeBranchId, now);
    writeSubjectIdentity(identity);
    ensureSubjectDirectories(subjectId);
    return identity;
  }

  public void writeSubjectIdentity(SubjectIdentity identity) {
    writeJson(subjectIdentityFile(identity.subjectId()), identity);
  }

  public SubjectIdentity readSubjectIdentity(String subjectId) {
    return readJson(subjectIdentityFile(subjectId), SubjectIdentity.class).orElse(null);
  }

  public void appendRawRecord(String subjectId, SubjectMemoryRecord record) {
    requireLayer(record, SubjectMemoryLayer.RAW, "raw");
    appendRecord(rawFile(subjectId), record);
  }

  public void appendDerivedRecord(String subjectId, SubjectMemoryRecord record) {
    requireLayer(record, SubjectMemoryLayer.DERIVED, "derived");
    appendRecord(derivedFile(subjectId), record);
  }

  public SubjectMemoryRecord remember(String subjectId, SubjectMemoryRecord source, String rememberedText, Instant promotedAt) {
    if (!Objects.equals(subjectId, source.subjectId())) {
      throw new IllegalArgumentException("source subject does not match target subject");
    }
    SubjectMemoryRecord remembered = source.asRemembered(nextRecordId(), rememberedText, promotedAt);
    appendRecord(rememberedFile(subjectId), remembered);
    return remembered;
  }

  public List<SubjectMemoryRecord> readRawRecords(String subjectId) {
    return readRecords(rawFile(subjectId));
  }

  public List<SubjectMemoryRecord> readDerivedRecords(String subjectId) {
    return readRecords(derivedFile(subjectId));
  }

  public List<SubjectMemoryRecord> readRememberedRecords(String subjectId) {
    return readRecords(rememberedFile(subjectId));
  }

  public SubjectMemoryView readMemoryView(String subjectId, int maxRaw, int maxDerived, int maxRemembered) {
    return new SubjectMemoryView(
        tail(readRawRecords(subjectId), maxRaw),
        tail(readDerivedRecords(subjectId), maxDerived),
        tail(readRememberedRecords(subjectId), maxRemembered)
    );
  }

  public SubjectBranchOverview readBranchOverview(String branchId) {
    return readBranchManifest(branchId).map(SubjectBranchOverview::from).orElse(null);
  }

  public SubjectWorkspaceSnapshot readSnapshot(String subjectId, int maxRaw, int maxDerived, int maxRemembered, int gitHintsLimit) {
    SubjectIdentity identity = readSubjectIdentity(subjectId);
    if (identity == null) {
      return null;
    }
    SubjectLedger ledger = readLedger();
    SubjectLedgerEntry ledgerEntry = ledger.subjects().get(subjectId);
    SubjectBranchOverview branchOverview = null;
    String activeBranchId = identity.activeBranchId();
    if (activeBranchId != null && !activeBranchId.isBlank()) {
      branchOverview = readBranchOverview(activeBranchId);
    } else {
      String canonicalBranchId = ledger.canonicalMap().get(subjectId);
      if (canonicalBranchId != null && !canonicalBranchId.isBlank()) {
        branchOverview = readBranchOverview(canonicalBranchId);
      }
    }
    SubjectMemoryView memoryView = readMemoryView(subjectId, maxRaw, maxDerived, maxRemembered);
    BackgroundScanResult backgroundScanResult = scanBackground(subjectId, maxRaw, maxDerived, maxRemembered, gitHintsLimit);
    return new SubjectWorkspaceSnapshot(identity, ledgerEntry, branchOverview, memoryView, backgroundScanResult);
  }

  public BackgroundScanResult scanBackground(String subjectId, int maxRaw, int maxDerived, int maxRemembered, int gitHintsLimit) {
    SubjectMemoryView memoryView = readMemoryView(subjectId, maxRaw, maxDerived, maxRemembered);
    List<BackgroundMemoryCandidate> candidates = new ArrayList<>();
    addCandidates(candidates, "raw", memoryView.rawRecords());
    addCandidates(candidates, "derived", memoryView.derivedRecords());
    addCandidates(candidates, "remembered", memoryView.rememberedRecords());
    List<String> gitHints = scanGitHints(subjectId, gitHintsLimit);
    return new BackgroundScanResult(subjectId, candidates, gitHints);
  }

  public BranchManifest writeBranchManifest(BranchManifest manifest) {
    writeJson(branchManifestFile(manifest.branchId()), manifest);
    return manifest;
  }

  public Optional<BranchManifest> readBranchManifest(String branchId) {
    return readJson(branchManifestFile(branchId), BranchManifest.class);
  }

  public SubjectLedger readLedger() {
    return readJson(ledgerFile(), SubjectLedger.class).orElse(SubjectLedger.empty(Instant.now()));
  }

  public SubjectLedger writeLedger(SubjectLedger ledger) {
    writeJson(ledgerFile(), ledger);
    return ledger;
  }

  public SubjectLedger registerSubject(SubjectIdentity identity, String branchGoal, String branchStatus, boolean canonical) {
    SubjectLedger ledger = readLedger();
    SubjectLedger updated = ledger.withSubject(SubjectLedgerEntry.from(identity, branchGoal, branchStatus, canonical), Instant.now());
    writeLedger(updated);
    return updated;
  }

  public SubjectLedger registerBranch(BranchManifest manifest) {
    SubjectLedger ledger = readLedger();
    SubjectLedger updated = ledger.withBranch(manifest, Instant.now());
    writeLedger(updated);
    return updated;
  }

  public SubjectLedger mapCanonicalBranch(String subjectId, String branchId) {
    SubjectLedger ledger = readLedger();
    SubjectLedger updated = ledger.withCanonicalMapping(subjectId, branchId, Instant.now());
    writeLedger(updated);
    return updated;
  }

  public SubjectLedger archiveBranch(String branchId) {
    SubjectLedger ledger = readLedger();
    SubjectLedger updated = ledger.archiveBranch(branchId, Instant.now());
    writeLedger(updated);
    return updated;
  }

  public Path subjectDir(String subjectId) {
    return root.resolve(SUBJECT_DIR).resolve(safeId(subjectId));
  }

  public Path branchDir(String branchId) {
    return root.resolve(BRANCHES_DIR).resolve(safeId(branchId));
  }

  public Path ledgerDir() {
    return root.resolve(LEDGER_DIR);
  }

  public Path conversationDir() {
    return root.resolve(CONVERSATIONS_DIR);
  }

  public Path conversationThreadDir(String threadId) {
    return conversationDir().resolve(THREADS_DIR).resolve(safeId(threadId));
  }

  public Path conversationHistoryFile(String threadId) {
    return conversationThreadDir(threadId).resolve(HISTORY_FILE);
  }

  public Path conversationDurablesFile(String threadId) {
    return conversationThreadDir(threadId).resolve(DURABLES_FILE);
  }

  public Path subjectIdentityFile(String subjectId) {
    return subjectDir(subjectId).resolve(IDENTITY_FILE);
  }

  public Path rawFile(String subjectId) {
    return subjectDir(subjectId).resolve(RAW_FILE);
  }

  public Path derivedFile(String subjectId) {
    return subjectDir(subjectId).resolve(DERIVED_FILE);
  }

  public Path rememberedFile(String subjectId) {
    return subjectDir(subjectId).resolve(REMEMBERED_FILE);
  }

  public Path branchManifestFile(String branchId) {
    return branchDir(branchId).resolve(MANIFEST_FILE);
  }

  public Path ledgerFile() {
    return ledgerDir().resolve(LEDGER_FILE);
  }

  public void ensureSubjectDirectories(String subjectId) {
    mkdirs(subjectDir(subjectId));
    mkdirs(subjectDir(subjectId).resolve("raw"));
    mkdirs(subjectDir(subjectId).resolve("derived"));
    mkdirs(subjectDir(subjectId).resolve("confirmed"));
    mkdirs(subjectDir(subjectId).resolve("interactions"));
    mkdirs(subjectDir(subjectId).resolve("skills"));
    mkdirs(subjectDir(subjectId).resolve("drafts"));
    mkdirs(subjectDir(subjectId).resolve("history"));
    mkdirs(ledgerDir());
    mkdirs(root.resolve(BRANCHES_DIR));
    mkdirs(root.resolve(SUBJECT_DIR));
    mkdirs(conversationDir());
    mkdirs(conversationDir().resolve(THREADS_DIR));
  }

  public void ensureConversationDirectories(String threadId) {
    mkdirs(conversationDir());
    mkdirs(conversationDir().resolve(THREADS_DIR));
    mkdirs(conversationThreadDir(threadId));
  }

  public List<ConversationTurnRecord> readConversationHistory(String threadId, int limit) {
    List<ConversationTurnRecord> records = readConversationRecords(conversationHistoryFile(threadId));
    return tail(records, limit);
  }

  public void appendConversationTurn(String threadId, ConversationTurnRecord record) {
    if (record == null) {
      return;
    }
    if (!safeId(threadId).equals(safeId(record.threadId()))) {
      throw new IllegalArgumentException("thread id does not match record thread");
    }
    ensureConversationDirectories(threadId);
    Path file = conversationHistoryFile(threadId);
    try {
      List<ConversationTurnRecord> entries = new ArrayList<>(readConversationRecords(file));
      entries.add(record);
      writeJson(file, entries);
    } catch (RuntimeException e) {
      throw e;
    }
  }

  public List<ConversationDurableRecord> readConversationDurables(String threadId, int limit) {
    List<ConversationDurableRecord> records = readConversationDurableRecords(conversationDurablesFile(threadId));
    return tail(records, limit);
  }

  public void appendConversationDurable(String threadId, ConversationDurableRecord record) {
    if (record == null) {
      return;
    }
    if (!safeId(threadId).equals(safeId(record.threadId()))) {
      throw new IllegalArgumentException("thread id does not match record thread");
    }
    ensureConversationDirectories(threadId);
    Path file = conversationDurablesFile(threadId);
    try {
      List<ConversationDurableRecord> entries = new ArrayList<>(readConversationDurableRecords(file));
      entries.add(record);
      writeJson(file, entries);
    } catch (RuntimeException e) {
      throw e;
    }
  }

  public ConversationDurableRecord updateConversationDurableStatus(String threadId, String recordId, ConversationDurableStatus status, Instant capturedAt) {
    if (status == null) {
      throw new IllegalArgumentException("status is required");
    }
    Path file = conversationDurablesFile(threadId);
    List<ConversationDurableRecord> entries = new ArrayList<>(readConversationDurableRecords(file));
    LOG.info("[ContextAgent][Durable] workspace update start threadId={} recordId={} status={} file={} currentCount={}",
        threadId, recordId, status, file, entries.size());
    if (entries.isEmpty()) {
      LOG.warn("[ContextAgent][Durable] workspace update aborted threadId={} recordId={} reason=empty_thread_file file={}", threadId, recordId, file);
      return null;
    }
    String targetRecordId = safeId(recordId);
    for (int i = 0; i < entries.size(); i++) {
      ConversationDurableRecord record = entries.get(i);
      if (safeId(record.recordId()).equals(targetRecordId)) {
        ConversationDurableRecord updated = record.withStatus(status, capturedAt == null ? Instant.now() : capturedAt);
        entries.set(i, updated);
        writeJson(file, entries);
        LOG.info("[ContextAgent][Durable] workspace update success threadId={} recordId={} previousStatus={} nextStatus={} file={} newCount={}",
            threadId, record.recordId(), record.status(), status, file, entries.size());
        return updated;
      }
    }
    LOG.warn("[ContextAgent][Durable] workspace update not_found threadId={} recordId={} status={} file={} currentCount={}",
        threadId, recordId, status, file, entries.size());
    return null;
  }

  public void ensureBranchDirectories(String branchId) {
    mkdirs(branchDir(branchId));
  }

  private void appendRecord(Path file, SubjectMemoryRecord record) {
    try {
      List<SubjectMemoryRecord> entries = readRecords(file);
      entries = new ArrayList<>(entries);
      entries.add(record);
      writeJson(file, entries);
    } catch (RuntimeException e) {
      throw e;
    }
  }

  private List<ConversationTurnRecord> readConversationRecords(Path file) {
    if (file == null || !Files.exists(file)) {
      return List.of();
    }
    try {
      byte[] bytes = Files.readAllBytes(file);
      if (bytes.length == 0) {
        return List.of();
      }
      List<ConversationTurnRecord> records = mapper.readValue(bytes, CONVERSATION_RECORD_LIST);
      return records == null ? List.of() : records;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read conversation history from " + file, e);
    }
  }

  private List<ConversationDurableRecord> readConversationDurableRecords(Path file) {
    if (file == null || !Files.exists(file)) {
      return List.of();
    }
    try {
      byte[] bytes = Files.readAllBytes(file);
      if (bytes.length == 0) {
        return List.of();
      }
      List<ConversationDurableRecord> records = mapper.readValue(bytes, CONVERSATION_DURABLE_RECORD_LIST);
      return records == null ? List.of() : records;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read durable conversation records from " + file, e);
    }
  }

  private void addCandidates(List<BackgroundMemoryCandidate> candidates, String source, List<SubjectMemoryRecord> records) {
    for (SubjectMemoryRecord record : records) {
      candidates.add(BackgroundMemoryCandidate.from(source, record));
    }
  }

  private List<String> scanGitHints(String subjectId, int gitHintsLimit) {
    int limit = Math.max(0, gitHintsLimit);
    if (limit == 0) {
      return List.of();
    }
    Path subjectPath = subjectDir(subjectId);
    if (!Files.exists(subjectPath)) {
      return List.of();
    }
    Path gitRoot = findGitRoot(root);
    if (gitRoot == null) {
      return List.of();
    }
    String relativePath = gitRoot.relativize(subjectPath).toString();
    if (relativePath.isBlank()) {
      return List.of();
    }
    ProcessBuilder builder = new ProcessBuilder("git", "-C", gitRoot.toString(), "log", "--oneline", "-n", String.valueOf(limit), "--", relativePath);
    builder.redirectErrorStream(true);
    try {
      Process process = builder.start();
      byte[] bytes = process.getInputStream().readAllBytes();
      int exit = process.waitFor();
      if (exit != 0 || bytes.length == 0) {
        return List.of();
      }
      String output = new String(bytes, StandardCharsets.UTF_8).trim();
      if (output.isBlank()) {
        return List.of();
      }
      return Stream.of(output.split("\\R")).filter(line -> !line.isBlank()).toList();
    } catch (IOException e) {
      return List.of();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return List.of();
    }
  }

  private Path findGitRoot(Path start) {
    Path current = start;
    while (current != null) {
      if (Files.exists(current.resolve(".git"))) {
        return current;
      }
      current = current.getParent();
    }
    return null;
  }

  private static <T> List<T> tail(List<T> values, int maxCount) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    int limit = Math.max(0, maxCount);
    if (limit == 0) {
      return List.of();
    }
    int from = Math.max(0, values.size() - limit);
    return List.copyOf(values.subList(from, values.size()));
  }

  private List<SubjectMemoryRecord> readRecords(Path file) {
    if (!Files.exists(file)) {
      return new ArrayList<>();
    }
    try {
      byte[] bytes = Files.readAllBytes(file);
      if (bytes.length == 0) {
        return new ArrayList<>();
      }
      return mapper.readValue(bytes, MEMORY_RECORD_LIST);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read records from " + file, e);
    }
  }

  private <T> Optional<T> readJson(Path file, Class<T> type) {
    if (!Files.exists(file)) {
      return Optional.empty();
    }
    try {
      byte[] bytes = Files.readAllBytes(file);
      if (bytes.length == 0) {
        return Optional.empty();
      }
      return Optional.of(mapper.readValue(bytes, type));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read JSON from " + file, e);
    }
  }

  private void writeJson(Path file, Object value) {
    try {
      mkdirs(file.getParent());
      Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write JSON to " + file, e);
    }
  }

  private void mkdirs(Path path) {
    try {
      if (path != null) {
        Files.createDirectories(path);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create directories for " + path, e);
    }
  }

  private static String safeId(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("id is required");
    }
    return trimmed.replaceAll("[^a-zA-Z0-9._-]", "_");
  }

  private static void requireLayer(SubjectMemoryRecord record, SubjectMemoryLayer layer, String label) {
    if (record == null) {
      throw new IllegalArgumentException(label + " record is required");
    }
    if (record.layer() != layer) {
      throw new IllegalArgumentException("record must be in " + label + " layer");
    }
  }

  private static String nextRecordId() {
    return java.util.UUID.randomUUID().toString();
  }
}
