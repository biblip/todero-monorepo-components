package com.shellaia.agent.spotify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.ai.agent.AgentContext;
import com.social100.todero.common.ai.agent.AgentPrompt;
import com.social100.todero.common.ai.llm.LLMClient;
import com.social100.todero.common.ai.llm.OpenAiLLM;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpRequest;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;
import com.social100.todero.common.aiatpio.AiatpResponse;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.routing.AgentCapabilityManifest;
import com.social100.todero.common.routing.ToolCommandSchema;
import com.social100.todero.common.storage.Storage;
import com.social100.todero.common.lineparser.LineParserUtil;
import com.social100.todero.processor.EventDefinition;
import com.shellaia.tutil.todo.RocksDbTodoStore;
import com.shellaia.tutil.todo.TodoAgentFacade;
import com.shellaia.tutil.todo.TodoGoal;
import com.shellaia.tutil.todo.TodoGoalDraft;
import com.shellaia.tutil.todo.TodoManager;
import com.shellaia.tutil.todo.TodoNotFoundException;
import com.shellaia.tutil.todo.TodoPhase;
import com.shellaia.tutil.todo.TodoPhaseDraft;
import com.shellaia.tutil.todo.TodoPlanDocument;
import com.shellaia.tutil.todo.TodoSnapshot;
import com.shellaia.tutil.todo.TodoStatus;
import com.shellaia.tutil.todo.TodoTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import java.util.regex.Matcher;

@AIAController(name = "com.shellaia.agent.spotify",
    type = ServerType.AI,
    visible = true,
    description = "Capability-driven Spotify todo agent that persists plans and executes against the Spotify manifest",
    events = SpotifyAgent.SimpleEvent.class,
    capabilityProvider = SpotifyAgentCapabilities.class)
public class SpotifyAgent {
  private static final String MAIN_GROUP = "Main";
  private static final String OWNER_ID = "com.shellaia.agent.spotify";
  private static final String SOURCE = "todo-process";
  private static final Duration TASK_LEASE = Duration.ofMinutes(5);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern ORDINAL_TARGET_PATTERN = Pattern.compile(
      "(?i)\\b(?:song|track|item)\\s*(?:number\\s*|#\\s*)?(\\d+)\\b|\\b(\\d+)(?:st|nd|rd|th)?\\s*(?:song|track|item)\\b|\\b(?:song|track|item)\\s*#\\s*(\\d+)\\b"
  );
  private static final Pattern PLAYLIST_ADD_PATTERN = Pattern.compile(
      "(?i)^\\s*add\\s+(?:a\\s+)?(?:song|track)\\s+[\"“']?(.+?)[\"”']?\\s+to\\s+playlist\\s+[\"“']?(.+?)[\"”']?\\s*[.!?]*\\s*$"
  );
  private static final Pattern PLAYLIST_PLAY_PATTERN = Pattern.compile(
      "(?i)^\\s*(?:start\\s+playing|start\\s+playback\\s+of|play\\s+the\\s+playlist|start\\s+the\\s+playlist|resume\\s+the\\s+playlist)\\s+[\"“']?(.+?)[\"”']?\\s*(?:playlist)?\\s*[.!?]*\\s*$"
  );
  private static final Pattern PLAYLIST_ADD_CURRENT_REFERENCE_PATTERN = Pattern.compile(
      "(?i)^\\s*add\\s+(?:that|this|the\\s+current|current|now\\s+playing|the\\s+song\\s+playing|that\\s+song|this\\s+song|it)\\s+(?:song|track)?\\s+to\\s+playlist\\s+[\"“']?(.+?)[\"”']?\\s*[.!?]*\\s*$"
  );
  private static final Pattern PLAYLIST_ADD_CURRENT_REFERENCE_INCOMPLETE_PATTERN = Pattern.compile(
      "(?i)^\\s*add\\s+(?:that|this|the\\s+current|current|now\\s+playing|the\\s+song\\s+playing|that\\s+song|this\\s+song|it)\\s+(?:song|track)?\\s+to\\s+playlist\\s*[.!?]*\\s*$"
  );
  private static final Pattern SONG_LIST_PATTERN = Pattern.compile("(?i)^\\s*list\\s+\\d+\\s+(songs?|tracks?)\\b.*");
  private static final Pattern SONG_LIST_WITH_DESCRIPTOR_PATTERN = Pattern.compile("(?i)^\\s*list\\s+\\d+\\s+(.+?)\\s+(songs?|tracks?)\\b.*");
  private static final Pattern SPOTIFY_TRACK_URI_PATTERN = Pattern.compile("spotify:track:[A-Za-z0-9]+");
  private static final Set<String> GENERAL_LIST_MODIFIERS = Set.of(
      "top", "best", "popular", "recent", "recently", "recently-played", "my", "liked", "favorite", "favourite", "saved"
  );
  private static final Set<String> RECOVERABLE_STOP_REASONS = Set.of(
      "tool_execution_failed",
      "invalid_arguments",
      "max_steps_reached",
      "planner_loop_detected",
      "no_forward_progress",
      "tool_succeeded_but_goal_unresolved"
  );

  private final Storage storage;
  private final Path todoDir;
  private final Function<CommandContext, JsonNode> spotifyCapabilitiesProvider;
  private final SpotifyToolExecutor spotifyToolExecutor;
  private final SpotifyGoalRunner spotifyGoalRunner;
  private final AgentMemoryBuffer memoryBuffer = new AgentMemoryBuffer(64);
  private final String openApiKey;
  private final ObjectMapper mapper = new ObjectMapper();
  private final Object spotifyCapabilityLock = new Object();
  private volatile JsonNode spotifyCapabilityManifest;
  private volatile Set<String> spotifyCapabilityCommands;
  private volatile Map<String, ToolCommandSchema> spotifyCapabilitySchemas;
  private volatile CapabilityBootstrapState spotifyCapabilityBootstrapState = CapabilityBootstrapState.UNINITIALIZED;
  private volatile String spotifyCapabilityBootstrapFailureReason = "";

  public SpotifyAgent(Storage storage) {
    this(storage, defaultTodoPath(), null, null, null);
  }

  SpotifyAgent(Storage storage, Path todoDir, Function<CommandContext, JsonNode> spotifyCapabilitiesProvider) {
    this(storage, todoDir, spotifyCapabilitiesProvider, null, null);
  }

  SpotifyAgent(Storage storage,
               Path todoDir,
               Function<CommandContext, JsonNode> spotifyCapabilitiesProvider,
               SpotifyToolExecutor spotifyToolExecutor,
               SpotifyGoalRunner spotifyGoalRunner) {
    this.storage = storage;
    this.todoDir = todoDir;
    this.spotifyCapabilitiesProvider = spotifyCapabilitiesProvider;
    this.spotifyToolExecutor = spotifyToolExecutor == null ? new DefaultSpotifyToolExecutor() : spotifyToolExecutor;
    this.spotifyGoalRunner = spotifyGoalRunner == null ? new CapabilityDrivenSpotifyGoalRunner() : spotifyGoalRunner;
    this.openApiKey = loadOpenApiKey(storage);
  }

  @Action(group = MAIN_GROUP,
      command = "process",
      description = "Create a todo plan, execute the Spotify request, and persist progress/outcome")
  public Boolean process(CommandContext context) {
    String prompt = requestBody(context);
    if (prompt.isBlank()) {
      complete(context, 500, "invalid_request", "Prompt is required. Usage: process <goal>", "text/plain; charset=utf-8");
      return true;
    }

    try (RocksDbTodoStore store = new RocksDbTodoStore(todoDir)) {
      TodoManager manager = new TodoManager(store);
      TodoAgentFacade facade = new TodoAgentFacade(manager);
      JsonNode spotifyCapabilities = resolveSpotifyCapabilities(context);
      if (spotifyCapabilities == null) {
        complete(context, 503, "tool_capabilities_unavailable", "Spotify capabilities could not be loaded.", "text/plain; charset=utf-8");
        return true;
      }
      IntentProfile intentProfile = analyzeIntent(prompt);
      if (isIncompleteCurrentSongPlaylistAddRequest(prompt)) {
        complete(context, 200, "clarification_needed",
            "Which playlist should I add the current song to?",
            "text/plain; charset=utf-8");
        return true;
      }
      if (!intentProfile.supported()) {
        complete(context, 200, "unsupported_request", intentProfile.unsupportedMessage(), "text/plain; charset=utf-8");
        return true;
      }
      PlaylistAddRequest playlistAddRequest = intentProfile.playlistAddRequest();
      if (playlistAddRequest != null && playlistAddRequest.requiresCurrentPlaybackContext()) {
        ToolExecutionResult playbackStatus = executeSpotifyAction(context, "status all");
        if (!playbackStatus.executed() || !safeTrim(playbackStatus.output()).toLowerCase(Locale.ROOT).contains("playing: true")) {
          complete(context, 200, "clarification_needed",
              "I need the song title or an active playback context before I can add it to a playlist.",
              "text/plain; charset=utf-8");
          return true;
        }
        String currentTrackUri = extractSpotifyTrackUri(playbackStatus.output());
        if (currentTrackUri.isBlank()) {
          complete(context, 200, "clarification_needed",
              "I can see playback is active, but I cannot identify the current track.",
              "text/plain; charset=utf-8");
          return true;
        }
        String playlistId = resolvePlaylistIdByName(context, playlistAddRequest.playlistName());
        if (playlistId.isBlank()) {
          complete(context, 200, "playlist_not_found",
              "I could not find the playlist \"" + playlistAddRequest.playlistName() + "\".",
              "text/plain; charset=utf-8");
          return true;
        }
        ToolExecutionResult addCurrent = executeSpotifyAction(context, "playlist-add " + playlistId + " " + currentTrackUri);
        if (!addCurrent.executed()) {
          complete(context, 200, firstNonBlank(addCurrent.errorCode(), "playlist_add_failed"),
              firstNonBlank(addCurrent.output(), "Failed to add the current track to the playlist."),
              "text/plain; charset=utf-8");
          return true;
        }
        complete(context, 200, "ok", safeTrim(addCurrent.output()), "text/plain; charset=utf-8");
        return true;
      }
      PlaylistPlayRequest playlistPlayRequest = analyzePlaylistPlayRequest(prompt);
      if (playlistPlayRequest != null) {
        String playlistId = resolvePlaylistIdByName(context, playlistPlayRequest.playlistName());
        if (!playlistId.isBlank()) {
          ToolExecutionResult playPlaylist = executeSpotifyAction(context, "playlist-play " + playlistId);
          if (!playPlaylist.executed()) {
            complete(context, 200, firstNonBlank(playPlaylist.errorCode(), "playlist_play_failed"),
                firstNonBlank(playPlaylist.output(), "Failed to start the requested playlist."),
                "text/plain; charset=utf-8");
            return true;
          }
          complete(context, 200, "ok", safeTrim(playPlaylist.output()), "text/plain; charset=utf-8");
          return true;
        }
      }
      TodoGoal goal = facade.plan(buildPlanDraft(prompt, spotifyCapabilities));
      context.emitChat("Todo plan created: " + goal.id(), "progress");
      String finalOutput = runTodoExecutionLoop(context, manager, facade, goal.id(), prompt);
      String body = safeTrim(finalOutput);
      if (body.isEmpty()) {
        TodoSnapshot snapshot = facade.snapshot(goal.id());
        body = JSON.writeValueAsString(Map.of(
            "goalId", goal.id(),
            "status", snapshot.status().name(),
            "message", "Todo plan completed."
        ));
      }
      complete(context, 200, "ok", body, "text/plain; charset=utf-8");
      return true;
    } catch (Exception e) {
      complete(context, 500, "todo_agent_failed",
          safeTrim(e.getMessage()).isEmpty() ? "Todo execution failed." : safeTrim(e.getMessage()),
          "text/plain; charset=utf-8");
      return true;
    }
  }

  @Action(group = MAIN_GROUP,
      command = "plan-status",
      description = "Return the persisted todo snapshot for a goal id")
  public Boolean planStatus(CommandContext context) {
    String goalId = requestBody(context);
    if (goalId.isBlank()) {
      complete(context, 500, "invalid_request", "Goal id is required. Usage: plan-status <goalId>", "text/plain; charset=utf-8");
      return true;
    }
    try (RocksDbTodoStore store = new RocksDbTodoStore(todoDir)) {
      TodoAgentFacade facade = new TodoAgentFacade(new TodoManager(store));
      TodoSnapshot snapshot = facade.snapshot(goalId);
      complete(context, 200, "plan_status", JSON.writeValueAsString(Map.of("snapshot", snapshot)), "application/json; charset=utf-8");
    } catch (TodoNotFoundException e) {
      complete(context, 500, "not_found", "Goal not found: " + goalId, "text/plain; charset=utf-8");
    } catch (Exception e) {
      complete(context, 500, "plan_status_failed",
          safeTrim(e.getMessage()).isEmpty() ? "Failed to load plan status." : safeTrim(e.getMessage()),
          "text/plain; charset=utf-8");
    }
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "health",
      description = "Check whether Spotify capabilities have been loaded and cached")
  public Boolean health(CommandContext context) {
    boolean ready = ensureSpotifyCapabilityBootstrap(context);
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("ready", ready);
    payload.put("capabilitiesLoaded", spotifyCapabilityBootstrapState == CapabilityBootstrapState.READY);
    payload.put("failureReason", safeTrim(spotifyCapabilityBootstrapFailureReason));
    if (ready && spotifyCapabilityManifest != null) {
      payload.put("manifest", spotifyCapabilityManifest);
    }
    complete(context,
        ready ? 200 : 503,
        ready ? "health" : "not_ready",
        safeJson(payload),
        "application/json; charset=utf-8");
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "react",
      description = "Record a forwarded Spotify runtime event")
  public Boolean react(CommandContext context) {
    String payload = requestBody(context);
    if (!payload.isBlank()) {
      memoryBuffer.add("event", "spotify-component", payload);
    }
    String body;
    try {
      body = JSON.writeValueAsString(Map.of("received", true));
    } catch (Exception e) {
      body = "{\"received\":true}";
    }
    complete(context, 200, "react", body, "application/json; charset=utf-8");
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "plan-export",
      description = "Export the persisted todo plan as JSON interchange document")
  public Boolean planExport(CommandContext context) {
    String goalId = requestBody(context);
    if (goalId.isBlank()) {
      complete(context, 500, "invalid_request", "Goal id is required. Usage: plan-export <goalId>", "text/plain; charset=utf-8");
      return true;
    }
    try (RocksDbTodoStore store = new RocksDbTodoStore(todoDir)) {
      TodoAgentFacade facade = new TodoAgentFacade(new TodoManager(store));
      String json = facade.exportGoalPlanJson(goalId, OWNER_ID);
      complete(context, 200, "plan_export", json, "application/json; charset=utf-8");
    } catch (TodoNotFoundException e) {
      complete(context, 500, "not_found", "Goal not found: " + goalId, "text/plain; charset=utf-8");
    } catch (Exception e) {
      complete(context, 500, "plan_export_failed",
          safeTrim(e.getMessage()).isEmpty() ? "Failed to export plan." : safeTrim(e.getMessage()),
          "text/plain; charset=utf-8");
    }
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "capabilities",
      description = "Return runtime capability manifest for discovery")
  public Boolean capabilities(CommandContext context) {
    try {
      AgentCapabilityManifest manifest = new SpotifyAgentCapabilities().manifest();
      complete(context, 200, "capabilities", JSON.writeValueAsString(Map.of("manifest", manifest)), "application/json; charset=utf-8");
    } catch (Exception e) {
      complete(context, 500, "capability_manifest_generate_failed", "Todo DJ capability manifest could not be generated.", "text/plain; charset=utf-8");
    }
    return true;
  }

  private JsonNode resolveSpotifyCapabilities(CommandContext context) {
    return ensureSpotifyCapabilityBootstrap(context) ? spotifyCapabilityManifest : null;
  }

  private boolean ensureSpotifyCapabilityBootstrap(CommandContext parentContext) {
    if (spotifyCapabilityBootstrapState == CapabilityBootstrapState.READY) {
      return true;
    }
    if (spotifyCapabilityBootstrapState == CapabilityBootstrapState.FAILED) {
      return false;
    }
    synchronized (spotifyCapabilityLock) {
      if (spotifyCapabilityBootstrapState == CapabilityBootstrapState.READY) {
        return true;
      }
      if (spotifyCapabilityBootstrapState == CapabilityBootstrapState.FAILED) {
        return false;
      }
      JsonNode manifest = loadSpotifyCapabilities(parentContext);
      if (manifest == null || manifest.isMissingNode() || manifest.isNull()) {
        spotifyCapabilityBootstrapFailureReason = "Spotify capabilities could not be loaded from the Spotify component.";
        spotifyCapabilityBootstrapState = CapabilityBootstrapState.FAILED;
        return false;
      }
      spotifyCapabilityManifest = manifest;
      spotifyCapabilityCommands = extractCapabilityCommands(manifest);
      spotifyCapabilitySchemas = extractCapabilitySchemas(manifest);
      if (spotifyCapabilityCommands == null || spotifyCapabilityCommands.isEmpty()) {
        spotifyCapabilityBootstrapFailureReason = "Spotify capabilities manifest did not expose any commands.";
        spotifyCapabilityBootstrapState = CapabilityBootstrapState.FAILED;
        return false;
      }
      if (spotifyCapabilitySchemas == null || spotifyCapabilitySchemas.isEmpty()) {
        spotifyCapabilityBootstrapFailureReason = "Spotify capabilities manifest did not expose any command schemas.";
        spotifyCapabilityBootstrapState = CapabilityBootstrapState.FAILED;
        return false;
      }
      spotifyCapabilityBootstrapFailureReason = "";
      spotifyCapabilityBootstrapState = CapabilityBootstrapState.READY;
      return true;
    }
  }

  private JsonNode loadSpotifyCapabilities(CommandContext context) {
    if (spotifyCapabilitiesProvider != null) {
      return spotifyCapabilitiesProvider.apply(context);
    }
    ToolExecutionResult execution = spotifyToolExecutor.loadCapabilities(context);
    if (execution == null || !execution.executed()) {
      return null;
    }
    try {
      JsonNode payload = JSON.readTree(safeTrim(execution.output()));
      JsonNode manifest = payload.path("manifest");
      return manifest == null || manifest.isMissingNode() || manifest.isNull() ? null : manifest;
    } catch (Exception e) {
      spotifyCapabilityBootstrapFailureReason = "Failed to parse Spotify capabilities manifest: " + safeTrim(e.getMessage());
      return null;
    }
  }

  TodoGoalDraft buildPlanDraft(String prompt) {
    return buildPlanDraft(prompt, null);
  }

  TodoGoalDraft buildPlanDraft(String prompt, JsonNode spotifyCapabilities) {
    String normalizedPrompt = safeTrim(prompt);
    String title = normalizedPrompt.length() > 80 ? normalizedPrompt.substring(0, 80) + "..." : normalizedPrompt;
    IntentProfile intentProfile = analyzeIntent(normalizedPrompt);
    String spotifyCapabilitiesJson = spotifyCapabilities == null ? "" : spotifyCapabilities.toString();
    List<String> spotifyCapabilityCommands = extractSpotifyCapabilityCommands(spotifyCapabilities);
    List<TodoPhaseDraft> phases = new ArrayList<>();
    List<com.shellaia.tutil.todo.TodoTaskDraft> planTasks = new ArrayList<>();
    planTasks.add(new com.shellaia.tutil.todo.TodoTaskDraft(
        "Capture request", normalizedPrompt, null, 100, List.of(), Map.of("taskRole", "capture")));
    if (intentProfile.requiresPlaybackSnapshot()) {
      planTasks.add(new com.shellaia.tutil.todo.TodoTaskDraft(
          "Snapshot playback context",
          "Gather current playback context before execution.",
          null,
          95,
          List.of(),
          Map.of("taskRole", "snapshot_playback", "reason", intentProfile.reason())
      ));
    }
    if (intentProfile.requiresPlaylistScan()) {
      planTasks.add(new com.shellaia.tutil.todo.TodoTaskDraft(
          "Inspect active playlist",
          "Search the active playlist before external playback resolution.",
          null,
          92,
          List.of(),
          Map.of("taskRole", "scan_playlist", "reason", intentProfile.reason())
      ));
    }
    if (intentProfile.requiresTargetResolution()) {
      planTasks.add(new com.shellaia.tutil.todo.TodoTaskDraft(
          "Resolve target request",
          "Resolve the requested track or ordinal target before execution.",
          null,
          90,
          List.of(),
          Map.of("taskRole", "resolve_target", "reason", intentProfile.reason())
      ));
    }
    planTasks.add(new com.shellaia.tutil.todo.TodoTaskDraft(
        "Prepare execution strategy",
        "Delegate to the Spotify execution agent.",
        null,
        85,
        List.of(),
        Map.of("taskRole", "strategy", "reason", intentProfile.reason())
    ));
    phases.add(new TodoPhaseDraft("Plan", "Capture and prepare the request.", planTasks, Map.of(
        "phaseRole", "plan",
        "intentType", intentProfile.intentType()
    )));

    if (!intentProfile.discoveryTasks().isEmpty()) {
      phases.add(new TodoPhaseDraft("Discover", "Collect context required for safe execution.", intentProfile.discoveryTasks(), Map.of(
          "phaseRole", "discover",
          "intentType", intentProfile.intentType()
      )));
    }

    List<com.shellaia.tutil.todo.TodoTaskDraft> executeTasks = new ArrayList<>();
    executeTasks.add(new com.shellaia.tutil.todo.TodoTaskDraft(
        "Fulfill Spotify request",
        normalizedPrompt,
        null,
        80,
        List.of(),
        Map.of(
            "taskRole", "execute",
            "executionPrompt", normalizedPrompt,
            "intentType", intentProfile.intentType(),
            "targetTrackTitle", intentProfile.playlistAddRequest() == null ? "" : intentProfile.playlistAddRequest().trackTitle(),
            "targetPlaylistName", intentProfile.playlistAddRequest() == null ? "" : intentProfile.playlistAddRequest().playlistName()
        )
    ));
    phases.add(new TodoPhaseDraft("Execute", "Run the Spotify workflow.", executeTasks, Map.of(
        "phaseRole", "execute",
        "intentType", intentProfile.intentType()
    )));

    phases.add(new TodoPhaseDraft("Verify", "Persist execution outcome.", List.of(
        new com.shellaia.tutil.todo.TodoTaskDraft(
            "Record final outcome",
            "Store final execution result.",
            null,
            70,
            List.of(),
            Map.of("taskRole", "verify", "intentType", intentProfile.intentType())
        )
    ), Map.of("phaseRole", "verify", "intentType", intentProfile.intentType())));

    return new TodoGoalDraft(
        OWNER_ID,
        "Fulfill Spotify request: " + title,
        normalizedPrompt,
        phases,
        Map.of(
            "request", normalizedPrompt,
            "createdAt", Instant.now().toString(),
            "intentType", intentProfile.intentType(),
            "intentReason", intentProfile.reason(),
            "targetTrackTitle", intentProfile.playlistAddRequest() == null ? "" : intentProfile.playlistAddRequest().trackTitle(),
            "targetPlaylistName", intentProfile.playlistAddRequest() == null ? "" : intentProfile.playlistAddRequest().playlistName(),
            "spotifyToolCapabilities", spotifyCapabilitiesJson,
            "spotifyToolCommands", String.join(",", spotifyCapabilityCommands)
        )
    );
  }

  private List<String> extractSpotifyCapabilityCommands(JsonNode spotifyCapabilities) {
    if (spotifyCapabilities == null || spotifyCapabilities.isNull() || spotifyCapabilities.isMissingNode()) {
      return List.of();
    }
    JsonNode commandsNode = spotifyCapabilities.path("commands");
    if (!commandsNode.isArray()) {
      return List.of();
    }
    List<String> commands = new ArrayList<>();
    for (JsonNode commandNode : commandsNode) {
      String name = safeTrim(commandNode.path("name").asText(""));
      if (!name.isEmpty()) {
        commands.add(name);
      }
    }
    return List.copyOf(commands);
  }

  private Set<String> extractCapabilityCommands(JsonNode manifest) {
    List<String> commands = extractSpotifyCapabilityCommands(manifest);
    if (commands.isEmpty()) {
      return Set.of();
    }
    return Set.copyOf(commands);
  }

  private Map<String, ToolCommandSchema> extractCapabilitySchemas(JsonNode manifest) {
    if (manifest == null || manifest.isMissingNode() || manifest.isNull()) {
      return Map.of();
    }
    Map<String, ToolCommandSchema> schemas = new LinkedHashMap<>();
    JsonNode commandsNode = manifest.path("commands");
    if (commandsNode.isArray()) {
      for (JsonNode commandNode : commandsNode) {
        String name = safeTrim(commandNode.path("name").asText(""));
        if (name.isEmpty()) {
          continue;
        }
        schemas.put(name.toLowerCase(Locale.ROOT), ToolCommandSchema.builder()
            .name(name)
            .description(safeTrim(commandNode.path("description").asText("")))
            .requiredArgs(readStringList(commandNode.path("requiredArgs")))
            .optionalArgs(readStringList(commandNode.path("optionalArgs")))
            .examples(readStringList(commandNode.path("examples")))
            .build());
      }
    }
    return schemas.isEmpty() ? Map.of() : Map.copyOf(schemas);
  }

  private List<String> readStringList(JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    for (JsonNode item : node) {
      String value = safeTrim(item.asText(""));
      if (!value.isEmpty()) {
        values.add(value);
      }
    }
    return List.copyOf(values);
  }

  private String runTodoExecutionLoop(CommandContext context,
                                      TodoManager manager,
                                      TodoAgentFacade facade,
                                      String goalId,
                                      String prompt) {
    System.out.println("[SPOTIFY-AGENT][TODO] loop-start goalId=" + goalId + " owner=" + OWNER_ID);
    String finalOutput = "";
    while (true) {
      TodoSnapshot snapshot = facade.snapshot(goalId);
      System.out.println("[SPOTIFY-AGENT][TODO] snapshot goalId=" + goalId
          + " status=" + snapshot.status()
          + " version=" + snapshot.version()
          + " progress=" + safeTrim(String.valueOf(snapshot.progress())));
      if (snapshot.status() == TodoStatus.COMPLETED
          || snapshot.status() == TodoStatus.FAILED
          || snapshot.status() == TodoStatus.CANCELED
          || snapshot.status() == TodoStatus.BLOCKED) {
        System.out.println("[SPOTIFY-AGENT][TODO] loop-stop goalId=" + goalId + " reason=terminal_status status=" + snapshot.status());
        return finalOutput;
      }

      var claimed = manager.claimNextReadyTask(goalId, OWNER_ID, TASK_LEASE);
      if (claimed.isEmpty()) {
        System.out.println("[SPOTIFY-AGENT][TODO] loop-stop goalId=" + goalId + " reason=no_ready_task");
        return finalOutput;
      }

      var ref = claimed.orElseThrow();
      TodoTask task = manager.findTask(goalId, ref.phaseId(), ref.taskId())
          .orElseThrow(() -> new TodoNotFoundException("Task not found: " + ref.taskId()));
      String taskRole = safeTrim(task.metadata().get("taskRole"));
      System.out.println("[SPOTIFY-AGENT][TODO] claimed goalId=" + goalId
          + " phaseId=" + ref.phaseId()
          + " taskId=" + ref.taskId()
          + " role=" + taskRole
          + " status=" + task.status()
          + " title=" + task.title());
      manager.updateTaskStatus(goalId, ref.phaseId(), ref.taskId(), TodoStatus.IN_PROGRESS,
          "Working on todo role=" + taskRole + ".");

      String stepOutput = "";
      switch (taskRole) {
        case "capture" -> {
          System.out.println("[SPOTIFY-AGENT][TODO] complete role=capture goalId=" + goalId);
          facade.completeTask(goalId, ref.phaseId(), ref.taskId(), "Captured user request: " + prompt);
        }
        case "snapshot_playback" -> {
          System.out.println("[SPOTIFY-AGENT][TODO] complete role=snapshot_playback goalId=" + goalId);
          facade.completeTask(goalId, ref.phaseId(), ref.taskId(),
              "Playback snapshot planned before execution.");
        }
        case "scan_playlist" -> {
          System.out.println("[SPOTIFY-AGENT][TODO] complete role=scan_playlist goalId=" + goalId);
          facade.completeTask(goalId, ref.phaseId(), ref.taskId(),
              "Playlist scan planned before execution.");
        }
        case "resolve_target" -> {
          System.out.println("[SPOTIFY-AGENT][TODO] complete role=resolve_target goalId=" + goalId);
          facade.completeTask(goalId, ref.phaseId(), ref.taskId(),
              "Target resolution planned before execution.");
        }
        case "strategy" -> {
          System.out.println("[SPOTIFY-AGENT][TODO] complete role=strategy goalId=" + goalId);
          facade.completeTask(goalId, ref.phaseId(), ref.taskId(),
              "Prepared manifest-driven Spotify execution using com.shellaia.spotify.");
        }
        case "execute" -> stepOutput = executeGoalTask(context, manager, facade, goalId, ref.phaseId(), ref.taskId(), prompt, task, false);
        case "repair" -> stepOutput = executeGoalTask(context, manager, facade, goalId, ref.phaseId(), ref.taskId(), prompt, task, true);
        case "verify" -> {
          System.out.println("[SPOTIFY-AGENT][TODO] complete role=verify goalId=" + goalId);
          facade.completeTask(goalId, ref.phaseId(), ref.taskId(), buildVerificationOutcome(manager, goalId));
        }
        default -> {
          System.out.println("[SPOTIFY-AGENT][TODO] fail role=" + taskRole + " goalId=" + goalId + " reason=unknown_task_role");
          facade.failTask(goalId, ref.phaseId(), ref.taskId(), "Unknown task role: " + taskRole);
        }
      }
      if (!safeTrim(stepOutput).isEmpty()) {
        finalOutput = stepOutput;
      }
    }
  }

  private String executeGoalTask(CommandContext context,
                                 TodoManager manager,
                                 TodoAgentFacade facade,
                                 String goalId,
                                 String phaseId,
                                 String taskId,
                                 String prompt,
                                 TodoTask task,
                                 boolean repair) {
    String executionPrompt = repair ? firstNonBlank(task.metadata().get("executionPrompt"), prompt) : prompt;
    System.out.println("[SPOTIFY-AGENT][TODO] execute-start goalId=" + goalId
        + " taskId=" + taskId
        + " repair=" + repair
        + " prompt=" + previewForLog(executionPrompt));
    GoalExecutionResult execution = spotifyGoalRunner.run(context, executionPrompt, true, repair ? SOURCE + "-repair" : SOURCE, true);
    System.out.println("[SPOTIFY-AGENT][TODO] execute-result goalId=" + goalId
        + " taskId=" + taskId
        + " repair=" + repair
        + " success=" + execution.success()
        + " stopReason=" + execution.stopReasonCode()
        + " steps=" + execution.stepCount()
        + " correlationId=" + execution.correlationId()
        + " message=" + previewForLog(execution.message()));
    if (execution.success()) {
      facade.completeTask(goalId, phaseId, taskId,
          (repair ? "Repair completed request." : "Goal execution completed.")
              + " stopReason=" + execution.stopReasonCode()
              + " steps=" + execution.stepCount()
              + " correlationId=" + execution.correlationId());
      return execution.message();
    }
    boolean repairPlanned = maybeInsertRepairTask(manager, goalId, phaseId, taskId, executionPrompt, execution);
    if (repairPlanned) {
      facade.completeTask(goalId, phaseId, taskId,
          (repair ? "Repair attempt ended and another repair was planned." : "Initial goal execution ended and repair was planned.")
              + " stopReason=" + execution.stopReasonCode()
              + " message=" + execution.message()
              + " correlationId=" + execution.correlationId());
      return execution.message();
    }
    facade.failTask(goalId, phaseId, taskId,
        (repair ? "Repair failed." : "Goal execution failed.")
            + " stopReason=" + execution.stopReasonCode()
            + " message=" + execution.message()
            + " correlationId=" + execution.correlationId());
    if (repair) {
      blockVerificationPhase(manager, facade, goalId, "Verification skipped because repair failed.");
    }
    return execution.message();
  }

  private String buildVerificationOutcome(TodoManager manager, String goalId) {
    TodoGoal goal = manager.findGoal(goalId).orElseThrow(() -> new TodoNotFoundException("Goal not found: " + goalId));
    for (TodoPhase phase : goal.phases()) {
      if (!"execute".equalsIgnoreCase(safeTrim(phase.metadata().get("phaseRole")))) {
        continue;
      }
      for (TodoTask task : phase.tasks()) {
        String role = safeTrim(task.metadata().get("taskRole"));
        if ("execute".equalsIgnoreCase(role) || "repair".equalsIgnoreCase(role)) {
          return "Verified execution outcome from task '" + task.title() + "': " + safeTrim(task.outcome());
        }
      }
    }
    return "Verified plan state.";
  }

  private GoalExecutionResult runCapabilityDrivenGoal(CommandContext context,
                                                      String prompt,
                                                      boolean interactiveRequest,
                                                      String source,
                                                      boolean emitFinal) {
    String correlationId = newCorrelationId();
    System.out.println("[SPOTIFY-AGENT][BUILD] correlationId=" + correlationId + " source=" + safeTrim(source));
    IntentProfile intentProfile = analyzeIntent(prompt);
    if (!ensureSpotifyCapabilityBootstrap(context)) {
      String reason = firstNonBlank(spotifyCapabilityBootstrapFailureReason, "Spotify capabilities could not be loaded.");
      return new GoalExecutionResult(false, "capabilities_unavailable", 0, correlationId, reason);
    }
    LLMClient llm = resolveSystemLlm(context);
    if (llm == null) {
      return new GoalExecutionResult(false, "no_llm", 0, correlationId, "No system LLM available.");
    }
    String latestObservation = "";
    String finalUserMessage = "";
    String lastAction = "";
    ToolExecutionResult lastTool = null;
    int executedSteps = 0;
    List<Map<String, Object>> recentSteps = new ArrayList<>();
    String systemPrompt = loadSystemPrompt("prompts/default-system-prompt.md");

    for (int plannerTurn = 0; plannerTurn < 4; plannerTurn++) {
      Map<String, Object> plannerContext = new LinkedHashMap<>();
      plannerContext.put("source", safeTrim(source));
      plannerContext.put("correlationId", safeTrim(correlationId));
      plannerContext.put("interactive", interactiveRequest);
      plannerContext.put("goalEvaluation", Map.of(
          "status", "pending",
          "latestObservation", latestObservation,
          "lastAction", lastAction,
          "remainingGap", describeRemainingGap(intentProfile, lastAction, latestObservation),
          "successCriteria", describeSuccessCriteria(intentProfile)
      ));
      plannerContext.put("goal", Map.of(
          "request", safeTrim(prompt),
          "status", "pending",
          "intentType", intentProfile.intentType(),
          "intentReason", intentProfile.reason(),
          "targetTrackTitle", intentProfile.playlistAddRequest() == null ? "" : intentProfile.playlistAddRequest().trackTitle(),
          "targetPlaylistName", intentProfile.playlistAddRequest() == null ? "" : intentProfile.playlistAddRequest().playlistName(),
          "successCriteria", describeSuccessCriteria(intentProfile)
      ));
      plannerContext.put("capabilities", spotifyCapabilityManifest);
      plannerContext.put("availableCommands", spotifyCapabilityCommands == null ? List.of() : List.copyOf(spotifyCapabilityCommands));
      plannerContext.put("recentSteps", List.copyOf(recentSteps));
      plannerContext.put("latestObservation", latestObservation);

      String contextJson;
      try {
        contextJson = mapper.writeValueAsString(plannerContext);
      } catch (Exception e) {
        return new GoalExecutionResult(false, "planner_context_failed", executedSteps, correlationId, safeTrim(e.getMessage()));
      }

      String rawPlanner;
      try {
        rawPlanner = llm.chat(systemPrompt, prompt, contextJson);
      } catch (Exception e) {
        return new GoalExecutionResult(false, "planner_exception", executedSteps, correlationId, safeTrim(e.getMessage()));
      }

      JsonNode root = extractFirstJsonBlockLocal(rawPlanner);
      List<String> plannedActions = parsePlannerActions(root);
      String user = firstNonBlank(readPath(root, "user"), readPath(root, "plan.user"));
      if (plannedActions.isEmpty()) {
        return new GoalExecutionResult(true, "action_none", executedSteps, correlationId,
            firstNonBlank(user, firstNonBlank(finalUserMessage, "Goal already satisfied.")));
      }

      if (plannedActions.size() == 1) {
        System.out.println("[SPOTIFY-AGENT][PLAN] mode=single action=" + plannedActions.get(0));
      } else {
        System.out.println("[SPOTIFY-AGENT][PLAN] mode=multi steps=" + plannedActions.size() + " actions=" + String.join(" | ", plannedActions));
      }
      System.out.println("[SPOTIFY-AGENT][PLAN] steps=" + plannedActions.size() + " actions=" + String.join(" | ", plannedActions));
      if (plannedActions.size() == 1 && "none".equalsIgnoreCase(plannedActions.get(0))) {
        return new GoalExecutionResult(true, "action_none", executedSteps, correlationId,
            firstNonBlank(user, firstNonBlank(finalUserMessage, "Goal already satisfied.")));
      }

      boolean plannerRequestsMore = false;
      for (int i = 0; i < plannedActions.size(); i++) {
        String action = safeTrim(plannedActions.get(i));
        if (action.isEmpty() || "none".equalsIgnoreCase(action)) {
          continue;
        }
        System.out.println("[SPOTIFY-AGENT][PLAN] action=" + action);
        ToolExecutionResult tool = executeSpotifyAction(context, action);
        executedSteps++;
        System.out.println("[SPOTIFY-AGENT][PLAN] tool-result action=" + action
            + " executed=" + tool.executed()
            + " command=" + safeTrim(tool.command())
            + " status=" + tool.statusCode()
            + " errorCode=" + safeTrim(tool.errorCode())
            + " output=" + previewForLog(tool.output()));
        if (!tool.executed()) {
          return new GoalExecutionResult(false,
              firstNonBlank(tool.errorCode(), "tool_execution_failed"),
              executedSteps,
              correlationId,
              firstNonBlank(tool.output(), firstNonBlank(tool.errorCode(), "Tool execution failed.")));
        }
        lastTool = tool;
        lastAction = action;
        latestObservation = safeTrim(tool.output());
        finalUserMessage = latestObservation;
        recentSteps.add(buildStepSummary(action, tool));
        plannerRequestsMore = shouldContinuePlanning(intentProfile, lastAction);
      }

      if (plannedActions.size() == 1 && requiresPlaybackFollowThrough(intentProfile, lastAction)) {
        String trackUri = extractSpotifyTrackUri(lastTool == null ? "" : lastTool.output());
        if (trackUri.isBlank()) {
          return new GoalExecutionResult(false, "tool_succeeded_but_goal_unresolved", executedSteps, correlationId,
              "Resolved track output did not include a Spotify track URI.");
        }
        System.out.println("[SPOTIFY-AGENT][PLAN] follow-up action=play " + trackUri);
        ToolExecutionResult playTool = executeSpotifyAction(context, "play " + trackUri);
        executedSteps++;
        System.out.println("[SPOTIFY-AGENT][PLAN] tool-result action=play " + trackUri
            + " executed=" + playTool.executed()
            + " command=" + safeTrim(playTool.command())
            + " status=" + playTool.statusCode()
            + " errorCode=" + safeTrim(playTool.errorCode())
            + " output=" + previewForLog(playTool.output()));
        if (!playTool.executed()) {
          return new GoalExecutionResult(false,
              firstNonBlank(playTool.errorCode(), "tool_execution_failed"),
              executedSteps,
              correlationId,
              firstNonBlank(playTool.output(), "Failed to start playback after resolving the track."));
        }
        latestObservation = safeTrim(playTool.output());
        finalUserMessage = latestObservation;
        lastTool = playTool;
        lastAction = "play " + trackUri;
        recentSteps.add(buildStepSummary("play " + trackUri, playTool));
        if (hasCapabilityCommand("status")) {
          ToolExecutionResult statusTool = executeSpotifyAction(context, "status all");
          executedSteps++;
          System.out.println("[SPOTIFY-AGENT][PLAN] tool-result action=status all"
              + " executed=" + statusTool.executed()
              + " command=" + safeTrim(statusTool.command())
              + " status=" + statusTool.statusCode()
              + " errorCode=" + safeTrim(statusTool.errorCode())
              + " output=" + previewForLog(statusTool.output()));
          if (!statusTool.executed()) {
            return new GoalExecutionResult(false,
                firstNonBlank(statusTool.errorCode(), "tool_execution_failed"),
                executedSteps,
                correlationId,
                firstNonBlank(statusTool.output(), "Failed to verify playback after starting the track."));
          }
          if (!isPlaybackVerified(statusTool.output(), trackUri)) {
            return new GoalExecutionResult(false,
                "goal_unverified",
                executedSteps,
                correlationId,
                "Playback verification did not confirm the resolved track is active.");
          }
          return new GoalExecutionResult(true, "tool_executed", executedSteps, correlationId, safeTrim(statusTool.output()));
        }
        return new GoalExecutionResult(true, "tool_executed", executedSteps, correlationId, safeTrim(playTool.output()));
      }

      if (!plannerRequestsMore) {
        if (lastTool == null) {
          return new GoalExecutionResult(true, "action_none", executedSteps, correlationId,
              firstNonBlank(user, firstNonBlank(finalUserMessage, "Goal already satisfied.")));
        }
        return new GoalExecutionResult(true, "tool_executed", executedSteps, correlationId, safeTrim(lastTool.output()));
      }
    }

    return new GoalExecutionResult(false,
        "planner_max_turns",
        executedSteps,
        correlationId,
        firstNonBlank(finalUserMessage, "Planner did not converge within the allowed number of turns."));
  }

  private LLMClient resolveSystemLlm(CommandContext context) {
    AgentContext agentContext = new AgentContext();
    if (context != null) {
      context.bindAgentLlmRegistry(agentContext);
    }
    return agentContext.systemLlm()
        .map(instance -> instance.client())
        .orElseGet(() -> safeTrim(openApiKey).isBlank() ? null : new OpenAiLLM(openApiKey, "gpt-4.1-mini"));
  }

  private String summarizeToolOutput(String output) {
    String text = safeTrim(output);
    if (text.isEmpty()) {
      return "";
    }
    String[] lines = text.split("\\R");
    if (lines.length == 0) {
      return text;
    }
    String first = safeTrim(lines[0]);
    if (first.length() > 240) {
      return first.substring(0, 240) + "...";
    }
    return first;
  }

  private ToolExecutionResult executeSpotifyAction(CommandContext parentContext, String action) {
    LineParserUtil.ParsedLine parsed = LineParserUtil.parse(action);
    if (parsed == null || safeTrim(parsed.first).isEmpty()) {
      return ToolExecutionResult.error("invalid-action", "", "", "Unable to parse action: " + action, 400);
    }
    String command = safeTrim(parsed.first).toLowerCase(Locale.ROOT);
    String args = joinArgs(parsed.second, parsed.remaining);
    Map<String, ToolCommandSchema> schemas = spotifyCapabilitySchemas == null ? Map.of() : spotifyCapabilitySchemas;
    ValidatedAction validated = validateAndNormalizeActionWithCapabilityManifest(command, args, schemas);
    if (!safeTrim(validated.error()).isEmpty()) {
      System.out.println("[SPOTIFY-AGENT][TOOL] validation-failed command=" + safeTrim(command)
          + " args=" + previewForLog(args)
          + " errorCode=" + safeTrim(validated.errorCode())
          + " error=" + previewForLog(validated.error()));
      return ToolExecutionResult.error(validated.errorCode(), validated.command(), validated.args(), validated.error(), 400);
    }
    System.out.println("[SPOTIFY-AGENT][TOOL] call command=" + validated.command() + " args=" + previewForLog(validated.args()));
    return spotifyToolExecutor.execute(parentContext, validated.command(), validated.args());
  }

  private boolean requiresPlaybackFollowThrough(IntentProfile intentProfile, String action) {
    if (intentProfile == null || action == null) {
      return false;
    }
    String normalizedAction = safeTrim(action).toLowerCase(Locale.ROOT);
    return normalizedAction.startsWith("resolve-track") && isPlaybackIntent(intentProfile.intentType());
  }

  private boolean isPlaybackIntent(String intentType) {
    String normalized = safeTrim(intentType).toLowerCase(Locale.ROOT);
    return normalized.contains("playback")
        || normalized.contains("track")
        || normalized.contains("recommendation")
        || normalized.contains("playlist");
  }

  private boolean hasCapabilityCommand(String command) {
    String normalized = safeTrim(command).toLowerCase(Locale.ROOT);
    return spotifyCapabilitySchemas != null && spotifyCapabilitySchemas.containsKey(normalized);
  }

  private static String extractSpotifyTrackUri(String output) {
    String text = safeTrim(output);
    if (text.isEmpty()) {
      return "";
    }
    java.util.regex.Matcher matcher = SPOTIFY_TRACK_URI_PATTERN.matcher(text);
    return matcher.find() ? matcher.group() : "";
  }

  private static boolean isPlaybackVerified(String output, String trackUri) {
    String text = safeTrim(output).toLowerCase(Locale.ROOT);
    if (text.isEmpty()) {
      return false;
    }
    if (!text.contains("playing: true")) {
      return false;
    }
    String normalizedTrackUri = safeTrim(trackUri).toLowerCase(Locale.ROOT);
    return normalizedTrackUri.isEmpty() || text.contains(normalizedTrackUri);
  }

  private static ValidatedAction validateAndNormalizeActionWithCapabilityManifest(String command,
                                                                                 String rawArgs,
                                                                                 Map<String, ToolCommandSchema> capabilitySchemas) {
    Map<String, ToolCommandSchema> schemas = capabilitySchemas == null ? Map.of() : capabilitySchemas;
    if (schemas.isEmpty()) {
      return ValidatedAction.error(command, rawArgs, "capabilities_unavailable",
          "Spotify capabilities manifest is unavailable; cannot validate command: " + command);
    }
    String normalizedCommand = safeTrim(command).toLowerCase(Locale.ROOT);
    ToolCommandSchema schema = schemas.get(normalizedCommand);
    if (schema == null) {
      return ValidatedAction.error(command, rawArgs, "unsupported-command",
          "Planned command is not allowed: " + command + ". Allowed: " + String.join(", ", schemas.keySet()));
    }
    String args = safeTrim(rawArgs);
    int requiredCount = schema.getRequiredArgs() == null ? 0 : schema.getRequiredArgs().size();
    String[] tokens = args.isEmpty() ? new String[0] : args.split("\\s+");
    if (tokens.length < requiredCount) {
      return ValidatedAction.error(command, args, "invalid-arguments",
          "Command '" + schema.getName() + "' requires at least " + requiredCount + " argument(s).");
    }
    return ValidatedAction.ok(schema.getName(), args);
  }

  private JsonNode extractFirstJsonBlockLocal(String raw) {
    String s = safeTrim(raw);
    if (s.isEmpty()) {
      return mapper.createObjectNode();
    }
    try {
      return mapper.readTree(s);
    } catch (Exception ignored) {
    }
    int first = s.indexOf('{');
    int last = s.lastIndexOf('}');
    if (first >= 0 && last > first) {
      try {
        return mapper.readTree(s.substring(first, last + 1));
      } catch (Exception ignored) {
      }
    }
    return mapper.createObjectNode();
  }

  private static String readPath(JsonNode root, String path) {
    if (root == null || path == null || path.isBlank()) {
      return "";
    }
    JsonNode current = root;
    for (String part : path.split("\\.")) {
      if (current == null || current.isMissingNode() || current.isNull()) {
        return "";
      }
      current = current.path(part);
    }
    if (current == null || current.isMissingNode() || current.isNull()) {
      return "";
    }
    return current.isTextual() ? current.asText() : current.toString();
  }

  private List<String> parsePlannerActions(JsonNode root) {
    if (root == null || root.isMissingNode() || root.isNull()) {
      return List.of();
    }
    List<String> steps = readPlannerStepList(root.path("steps"));
    if (steps.isEmpty()) {
      steps = readPlannerStepList(root.path("plan").path("steps"));
    }
    if (!steps.isEmpty()) {
      return steps;
    }
    String singleAction = firstNonBlank(readPath(root, "action"), readPath(root, "plan.action"));
    if (singleAction.isBlank()) {
      return List.of();
    }
    return List.of(singleAction);
  }

  private List<String> readPlannerStepList(JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<String> actions = new ArrayList<>();
    for (JsonNode step : node) {
      if (step == null || step.isNull() || step.isMissingNode()) {
        continue;
      }
      String action = step.isTextual()
          ? safeTrim(step.asText(""))
          : firstNonBlank(readPath(step, "action"), readPath(step, "plan.action"));
      if (!action.isBlank()) {
        actions.add(action);
      }
    }
    return actions;
  }

  private Map<String, Object> buildStepSummary(String action, ToolExecutionResult tool) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("action", safeTrim(action));
    summary.put("command", safeTrim(tool.command()));
    summary.put("status", tool.statusCode());
    summary.put("executed", tool.executed());
    summary.put("output", summarizeToolOutput(tool.output()));
    return summary;
  }

  private boolean shouldContinuePlanning(IntentProfile intentProfile, String lastAction) {
    if (intentProfile == null) {
      return false;
    }
    String normalizedIntent = safeTrim(intentProfile.intentType()).toLowerCase(Locale.ROOT);
    String normalizedAction = safeTrim(lastAction).toLowerCase(Locale.ROOT);
    if (normalizedIntent.contains("playlist_add")) {
      return !normalizedAction.startsWith("playlist-add");
    }
    return false;
  }

  private String describeRemainingGap(IntentProfile intentProfile, String lastAction, String latestObservation) {
    if (intentProfile == null) {
      return "Choose one safe next capability-driven action or a valid multi-step sequence from the manifest. Do not loop.";
    }
    String normalizedIntent = safeTrim(intentProfile.intentType()).toLowerCase(Locale.ROOT);
    String normalizedAction = safeTrim(lastAction).toLowerCase(Locale.ROOT);
    if (normalizedIntent.contains("playlist_add")) {
      if (normalizedAction.startsWith("playlist-add")) {
        return "The track has been added. Verify the playlist contents if needed, then stop.";
      }
      if (normalizedAction.startsWith("resolve-track")) {
        return "The track is resolved. Resolve the playlist identifier and add the track to that playlist.";
      }
      if (normalizedAction.startsWith("playlists")) {
        return "Playlist candidates were listed. Resolve the target track, then add it to the chosen playlist.";
      }
      return "Resolve the target track and playlist, then add the track to the playlist.";
    }
    if (normalizedIntent.contains("direct_track") || normalizedIntent.contains("playlist") || normalizedIntent.contains("playback")) {
      return "Choose one safe next capability-driven action or a valid multi-step sequence from the manifest. Do not loop.";
    }
    if (!safeTrim(latestObservation).isEmpty()) {
      return "Use the latest observation to decide the next manifest command or return none.";
    }
    return "Choose one safe next capability-driven action or a valid multi-step sequence from the manifest. Do not loop.";
  }

  private String describeSuccessCriteria(IntentProfile intentProfile) {
    if (intentProfile == null) {
      return "Use only the Spotify capability manifest. Any published command may be used when it advances the goal. Return exactly one action, a valid steps array, or none.";
    }
    String normalizedIntent = safeTrim(intentProfile.intentType()).toLowerCase(Locale.ROOT);
    if (normalizedIntent.contains("playlist_add")) {
      return "Use only the Spotify capability manifest. For playlist addition, resolve the target track and playlist, then use playlist-add or playlist-add-current, and verify the playlist if needed.";
    }
    return "Use only the Spotify capability manifest. Any published command may be used when it advances the goal. Return exactly one action, a valid steps array, or none.";
  }

  private static String joinArgs(String second, String remaining) {
    String a = safeTrim(second);
    String b = safeTrim(remaining);
    if (a.isEmpty()) {
      return b;
    }
    if (b.isEmpty()) {
      return a;
    }
    return a + " " + b;
  }

  private static String previewForLog(String value) {
    String text = safeTrim(value).replace('\n', ' ').replace('\r', ' ');
    if (text.isEmpty()) {
      return "";
    }
    int limit = 500;
    if (text.length() <= limit) {
      return text;
    }
    return text.substring(0, limit) + "...";
  }

  private static String newCorrelationId() {
    return UUID.randomUUID().toString();
  }

  private static String loadSystemPrompt(String resourcePath) {
    try (java.io.InputStream in = SpotifyAgent.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new RuntimeException("Missing system prompt resource: " + resourcePath);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read system prompt: " + resourcePath, e);
    }
  }

  public enum SimpleEvent implements EventDefinition {
    status("Built-in status channel event"),
    chat("Built-in chat channel event"),
    html("Built-in html channel event"),
    auth("Built-in auth channel event"),
    error("Built-in error channel event"),
    thought("Reasoning/thought channel"),
    AGENT_DECISION("Agent produced a structured decision event with trace metadata"),
    AGENT_REACTION("Agent reacted to a runtime event");

    private final String description;

    SimpleEvent(String description) {
      this.description = description;
    }

    @Override
    public String getDescription() {
      return description;
    }
  }

  private static String loadOpenApiKey(Storage storage) {
    if (storage == null) {
      return "";
    }
    try {
      byte[] envBytes = storage.readFile(".env");
      if (envBytes == null || envBytes.length == 0) {
        return "";
      }
      return com.social100.todero.common.config.Util.parseDotenv(envBytes).getOrDefault("OPENAI_API_KEY", "");
    } catch (IOException | RuntimeException e) {
      return "";
    }
  }

  interface SpotifyGoalRunner {
    GoalExecutionResult run(CommandContext context, String prompt, boolean interactiveRequest, String source, boolean emitFinal);
  }

  interface SpotifyToolExecutor {
    ToolExecutionResult loadCapabilities(CommandContext parentContext);

    ToolExecutionResult execute(CommandContext parentContext, String command, String args);
  }

  record GoalExecutionResult(boolean success,
                             String stopReasonCode,
                             int stepCount,
                             String correlationId,
                             String message) {
  }

  public record ToolExecutionResult(boolean executed,
                                    String command,
                                    String args,
                                    String output,
                                    String errorCode,
                                    int statusCode) {
    public static ToolExecutionResult success(String command, String args, String output, int statusCode) {
      return new ToolExecutionResult(true, safeTrim(command), safeTrim(args), safeTrim(output), "", statusCode);
    }

    public static ToolExecutionResult error(String errorCode, String command, String args, String output, int statusCode) {
      return new ToolExecutionResult(false, safeTrim(command), safeTrim(args), safeTrim(output), safeTrim(errorCode), statusCode);
    }
  }

  private record ValidatedAction(String command, String args, String errorCode, String error) {
    private static ValidatedAction ok(String command, String args) {
      return new ValidatedAction(safeTrim(command), safeTrim(args), "", "");
    }

    private static ValidatedAction error(String command, String args, String errorCode, String error) {
      return new ValidatedAction(safeTrim(command), safeTrim(args), safeTrim(errorCode), safeTrim(error));
    }
  }

  private enum CapabilityBootstrapState {
    UNINITIALIZED,
    READY,
    FAILED
  }

  private static final class DefaultSpotifyToolExecutor implements SpotifyToolExecutor {
    @Override
    public ToolExecutionResult loadCapabilities(CommandContext parentContext) {
      return execute(parentContext, "capabilities", "");
    }

    @Override
    public ToolExecutionResult execute(CommandContext parentContext, String command, String args) {
      try {
        String spotifyArgs = safeTrim(args);
        System.out.println("[SPOTIFY-AGENT][TOOL] dispatch component=com.shellaia.spotify command="
            + safeTrim(command) + " args=" + previewForLog(spotifyArgs));
        AtomicReference<AiatpResponse> responseRef = new AtomicReference<>();
        AiatpRequest internalRequest = AiatpRuntimeAdapter.request(
            "ACTION",
            "/" + "com.shellaia.spotify" + "/" + safeTrim(command),
            AiatpIO.Body.ofString(spotifyArgs, StandardCharsets.UTF_8)
        );
        CommandContext internalContext = parentContext.toBuilder()
            .aiatpRequest(internalRequest)
            .responseConsumer(responseRef::set)
            .build();
        parentContext.execute("com.shellaia.spotify", safeTrim(command), internalContext);
        AiatpResponse response = responseRef.get();
        if (response == null) {
          System.out.println("[SPOTIFY-AGENT][TOOL] response command=" + safeTrim(command) + " status=500 reason=missing_response body=No response returned from Spotify component.");
          return ToolExecutionResult.error("tool-execution-failed", command, args, "No response returned from Spotify component.", 500);
        }
        int status = response.getStatusCode();
        String body = AiatpIO.bodyToString(response.getBody(), StandardCharsets.UTF_8);
        System.out.println("[SPOTIFY-AGENT][TOOL] response command=" + safeTrim(command)
            + " status=" + status
            + " reason=" + safeTrim(response.getReasonPhrase())
            + " body=" + previewForLog(body));
        return new ToolExecutionResult(status < 400, safeTrim(command), safeTrim(args), safeTrim(body), safeTrim(response.getReasonPhrase()), status);
      } catch (Exception e) {
        System.out.println("[SPOTIFY-AGENT][TOOL] response command=" + safeTrim(command)
            + " status=500 reason=exception body=" + previewForLog("Tool execution failed: " + safeTrim(e.getMessage())));
        return ToolExecutionResult.error("tool-execution-failed", command, args, "Tool execution failed: " + safeTrim(e.getMessage()), 500);
      }
    }
  }

  private final class CapabilityDrivenSpotifyGoalRunner implements SpotifyGoalRunner {
    @Override
    public GoalExecutionResult run(CommandContext context, String prompt, boolean interactiveRequest, String source, boolean emitFinal) {
      return runCapabilityDrivenGoal(context, prompt, interactiveRequest, source, emitFinal);
    }
  }

  private static Path defaultTodoPath() {
    String custom = System.getProperty("todero.agent.spotify.todo.dir");
    if (custom != null && !custom.isBlank()) {
      return Path.of(custom.trim());
    }
    return Path.of(System.getProperty("user.home"), ".todero", "data", "state", "agent-todo", "spotify-agent");
  }

  private static String requestBody(CommandContext context) {
    AiatpRequest request = context == null ? null : context.getAiatpRequest();
    if (request == null || request.getBody() == null) {
      return "";
    }
    String body = AiatpIO.bodyToString(request.getBody(), StandardCharsets.UTF_8);
    return body == null ? "" : body.trim();
  }

  private static void complete(CommandContext context, int statusCode, String reason, String body, String contentType) {
    if (context == null) {
      return;
    }
    AiatpIO.Headers headers = new AiatpIO.Headers();
    headers.set("Content-Type", safeTrim(contentType).isEmpty() ? "text/plain; charset=utf-8" : safeTrim(contentType));
    context.complete(AiatpResponse.builder()
        .statusCode(statusCode)
        .reasonPhrase(safeTrim(reason).isEmpty() ? "completed" : safeTrim(reason))
        .headers(headers)
        .body(AiatpIO.Body.ofString(body == null ? "" : body, StandardCharsets.UTF_8))
        .build());
  }

  private static String safeTrim(String value) {
    return value == null ? "" : value.trim();
  }

  private static String safeJson(Map<String, Object> payload) {
    try {
      return JSON.writeValueAsString(payload);
    } catch (Exception e) {
      return "{\"ready\":false,\"failureReason\":\"Unable to serialize health payload.\"}";
    }
  }

  private static String firstNonBlank(String first, String second) {
    String a = safeTrim(first);
    return a.isEmpty() ? safeTrim(second) : a;
  }

  private boolean maybeInsertRepairTask(TodoManager manager,
                                        String goalId,
                                        String phaseId,
                                        String taskId,
                                        String prompt,
                                        GoalExecutionResult execution) {
    if (!RECOVERABLE_STOP_REASONS.contains(safeTrim(execution.stopReasonCode()).toLowerCase(Locale.ROOT))) {
      blockVerificationPhase(manager, new TodoAgentFacade(manager), goalId,
          "Verification skipped because goal execution failed without a recoverable stop reason.");
      return false;
    }
    TodoPhase phase = manager.findPhase(goalId, phaseId)
        .orElseThrow(() -> new TodoNotFoundException("Phase not found: " + phaseId));
    int anchorIndex = -1;
    for (int i = 0; i < phase.tasks().size(); i++) {
      if (phase.tasks().get(i).id().equals(taskId)) {
        anchorIndex = i;
        break;
      }
    }
    if (anchorIndex < 0) {
      return false;
    }
    boolean repairExists = phase.tasks().stream()
        .anyMatch(task -> "repair".equalsIgnoreCase(safeTrim(task.metadata().get("taskRole"))));
    if (repairExists) {
      blockVerificationPhase(manager, new TodoAgentFacade(manager), goalId,
          "Verification skipped because a repair task already failed.");
      return false;
    }
    String repairPrompt = prompt + "\nRepair hint: previous stop reason=" + execution.stopReasonCode()
        + "; previous message=" + safeTrim(execution.message());
    manager.insertTask(goalId, phaseId, new com.shellaia.tutil.todo.TodoTaskDraft(
        "Repair execution issue",
        "Retry Spotify execution with failure context.",
        null,
        75,
        List.of(),
        Map.of(
            "taskRole", "repair",
            "executionPrompt", repairPrompt,
            "repairForTaskId", taskId,
            "priorStopReason", safeTrim(execution.stopReasonCode())
        )
    ), anchorIndex + 1);
    return true;
  }

  private void blockVerificationPhase(TodoManager manager, TodoAgentFacade facade, String goalId, String outcome) {
    TodoGoal goal = manager.findGoal(goalId).orElseThrow(() -> new TodoNotFoundException("Goal not found: " + goalId));
    for (TodoPhase phase : goal.phases()) {
      if (!"verify".equalsIgnoreCase(safeTrim(phase.metadata().get("phaseRole")))) {
        continue;
      }
      for (TodoTask task : phase.tasks()) {
        if ("verify".equalsIgnoreCase(safeTrim(task.metadata().get("taskRole")))
            && task.status() != TodoStatus.COMPLETED
            && task.status() != TodoStatus.FAILED
            && task.status() != TodoStatus.CANCELED) {
          facade.blockTask(goalId, phase.id(), task.id(), outcome);
          return;
        }
      }
    }
  }

  private static IntentProfile analyzeIntent(String prompt) {
    String normalized = safeTrim(prompt).toLowerCase(Locale.ROOT);
    UnsupportedSongList unsupportedSongList = analyzeUnsupportedSongList(normalized);
    if (unsupportedSongList.unsupported()) {
      return new IntentProfile(
          "unsupported_song_list",
          "genre_specific_song_list_unsupported",
          false,
          false,
          false,
          List.of(),
          false,
          unsupportedSongList.message(),
          null
      );
    }
    PlaylistAddRequest playlistAddRequest = analyzePlaylistAddRequest(safeTrim(prompt));
    boolean genericSongList = SONG_LIST_PATTERN.matcher(normalized).find();
    boolean ordinalTarget = ORDINAL_TARGET_PATTERN.matcher(normalized).find();
    boolean playlistScoped = normalized.contains("playlist")
        || normalized.contains("current playlist")
        || normalized.contains("in the playlist")
        || normalized.contains("from the playlist");
    boolean currentPlayback = normalized.contains("current")
        || normalized.contains("next song")
        || normalized.contains("previous song")
        || normalized.contains("resume")
        || normalized.contains("pause")
        || normalized.contains("skip")
        || normalized.contains("status");
    boolean directTrackRequest = normalized.startsWith("play ")
        && !playlistScoped
        && !normalized.contains("playlist")
        && !normalized.contains("album");
    boolean recommendation = normalized.contains("recommend")
        || normalized.contains("similar")
        || normalized.contains("like this");

    List<com.shellaia.tutil.todo.TodoTaskDraft> discoveryTasks = new ArrayList<>();
    String intentType = "general_playback";
    String reason = "general";
    boolean requiresSnapshot = false;
    boolean requiresPlaylistScan = false;
    boolean requiresResolution = false;
    boolean supported = true;
    String unsupportedMessage = "";

    if (currentPlayback || ordinalTarget) {
      requiresSnapshot = true;
      discoveryTasks.add(new com.shellaia.tutil.todo.TodoTaskDraft(
          "Collect playback context",
          "Confirm current playback, context type, and active position.",
          null,
          88,
          List.of(),
          Map.of("taskRole", "snapshot_playback", "intentType", "stateful_playback")
      ));
      intentType = "stateful_playback";
      reason = ordinalTarget ? "ordinal_target" : "current_playback_reference";
    }
    if (playlistScoped || ordinalTarget) {
      requiresPlaylistScan = true;
      discoveryTasks.add(new com.shellaia.tutil.todo.TodoTaskDraft(
          "Scan playlist context",
          "Inspect playlist contents before selecting or moving playback.",
          null,
          86,
          List.of(),
          Map.of("taskRole", "scan_playlist", "intentType", "playlist_scoped")
      ));
      intentType = "playlist_scoped";
      reason = ordinalTarget ? "ordinal_playlist_target" : "playlist_scoped_request";
    }
    if (directTrackRequest || recommendation || ordinalTarget) {
      requiresResolution = true;
      discoveryTasks.add(new com.shellaia.tutil.todo.TodoTaskDraft(
          "Resolve target entity",
          "Resolve the requested track, seed, or ordinal target before execution.",
          null,
          84,
          List.of(),
          Map.of("taskRole", "resolve_target", "intentType", directTrackRequest ? "direct_track" : intentType)
      ));
      if (directTrackRequest) {
        intentType = "direct_track";
        reason = "direct_track_request";
      } else if (recommendation) {
        intentType = "recommendation";
        reason = "recommendation_request";
      }
    }
    if (genericSongList && !playlistScoped && !currentPlayback && !ordinalTarget) {
      intentType = "song_list";
      reason = "generic_song_list";
    }
    if (playlistAddRequest != null) {
      requiresResolution = true;
      discoveryTasks.add(new com.shellaia.tutil.todo.TodoTaskDraft(
          "Resolve target song",
          "Resolve the requested song title to a concrete Spotify track URI.",
          null,
          91,
          List.of(),
          Map.of("taskRole", "resolve_target", "intentType", "playlist_add", "targetType", "track")
      ));
      discoveryTasks.add(new com.shellaia.tutil.todo.TodoTaskDraft(
          "Resolve target playlist",
          "Resolve the playlist name to a concrete Spotify playlist URI.",
          null,
          89,
          List.of(),
          Map.of("taskRole", "resolve_playlist", "intentType", "playlist_add", "targetType", "playlist")
      ));
      intentType = "playlist_add";
      reason = "playlist_add_request";
    }

    return new IntentProfile(intentType, reason, requiresSnapshot, requiresPlaylistScan, requiresResolution, discoveryTasks, supported, unsupportedMessage, playlistAddRequest);
  }

  private record IntentProfile(String intentType,
                               String reason,
                               boolean requiresPlaybackSnapshot,
                               boolean requiresPlaylistScan,
                               boolean requiresTargetResolution,
                               List<com.shellaia.tutil.todo.TodoTaskDraft> discoveryTasks,
                               boolean supported,
                               String unsupportedMessage,
                               PlaylistAddRequest playlistAddRequest) {
  }

  private record PlaylistAddRequest(String trackTitle, String playlistName, boolean requiresCurrentPlaybackContext) {
  }

  private record PlaylistPlayRequest(String playlistName) {
  }

  private record UnsupportedSongList(boolean unsupported, String message) {
  }

  private static UnsupportedSongList analyzeUnsupportedSongList(String normalizedPrompt) {
    if (normalizedPrompt == null || normalizedPrompt.isBlank()) {
      return new UnsupportedSongList(false, "");
    }
    if (!normalizedPrompt.contains("list") || !(normalizedPrompt.contains("song") || normalizedPrompt.contains("track"))) {
      return new UnsupportedSongList(false, "");
    }
    java.util.regex.Matcher matcher = SONG_LIST_WITH_DESCRIPTOR_PATTERN.matcher(normalizedPrompt);
    if (!matcher.find()) {
      return new UnsupportedSongList(false, "");
    }
    String descriptor = safeTrim(matcher.group(1)).toLowerCase(Locale.ROOT);
    if (descriptor.isEmpty() || GENERAL_LIST_MODIFIERS.contains(descriptor)) {
      return new UnsupportedSongList(false, "");
    }
    return new UnsupportedSongList(true,
        "I can list general Spotify results, but I cannot enumerate genre-specific song lists like '"
            + descriptor
            + "' with the current Spotify capability manifest.");
  }

  private static PlaylistAddRequest analyzePlaylistAddRequest(String normalizedPrompt) {
    if (normalizedPrompt == null || normalizedPrompt.isBlank()) {
      return null;
    }
    String prompt = safeTrim(normalizedPrompt);
    Matcher matcher = PLAYLIST_ADD_PATTERN.matcher(prompt);
    if (matcher.matches()) {
      String trackTitle = safeTrim(matcher.group(1));
      String playlistName = safeTrim(matcher.group(2));
      if (trackTitle.isBlank() || playlistName.isBlank()) {
        return null;
      }
      return new PlaylistAddRequest(trackTitle, playlistName, false);
    }
    matcher = PLAYLIST_ADD_CURRENT_REFERENCE_PATTERN.matcher(prompt);
    if (matcher.matches()) {
      String playlistName = safeTrim(matcher.group(1));
      if (playlistName.isBlank()) {
        return null;
      }
      return new PlaylistAddRequest("", playlistName, true);
    }
    return null;
  }

  private static PlaylistPlayRequest analyzePlaylistPlayRequest(String normalizedPrompt) {
    if (normalizedPrompt == null || normalizedPrompt.isBlank()) {
      return null;
    }
    String prompt = safeTrim(normalizedPrompt);
    if (!prompt.contains("play")) {
      return null;
    }
    if (prompt.contains("song") || prompt.contains("track") || prompt.contains("album") || prompt.contains("artist")) {
      return null;
    }
    Matcher matcher = PLAYLIST_PLAY_PATTERN.matcher(prompt);
    if (!matcher.matches()) {
      return null;
    }
    String playlistName = safeTrim(matcher.group(1));
    if (playlistName.isBlank()) {
      return null;
    }
    return new PlaylistPlayRequest(playlistName);
  }

  private static boolean isIncompleteCurrentSongPlaylistAddRequest(String prompt) {
    if (prompt == null || prompt.isBlank()) {
      return false;
    }
    return PLAYLIST_ADD_CURRENT_REFERENCE_INCOMPLETE_PATTERN.matcher(safeTrim(prompt)).matches();
  }

  private String resolvePlaylistIdByName(CommandContext context, String playlistName) {
    String targetName = safeTrim(playlistName);
    if (targetName.isBlank()) {
      return "";
    }
    ToolExecutionResult playlists = executeSpotifyAction(context, "playlists 50 0");
    if (!playlists.executed()) {
      return "";
    }
    return extractPlaylistIdFromListing(playlists.output(), targetName);
  }

  private static String extractPlaylistIdFromListing(String output, String playlistName) {
    String text = safeTrim(output);
    if (text.isEmpty() || safeTrim(playlistName).isBlank()) {
      return "";
    }
    String normalizedTarget = normalizePlaylistName(playlistName);
    java.util.regex.Pattern linePattern = java.util.regex.Pattern.compile("(?mi)^\\s*\\d+\\)\\s*(.+?)\\s+\\[id=([^,\\]]+)");
    java.util.regex.Matcher matcher = linePattern.matcher(text);
    while (matcher.find()) {
      String name = safeTrim(matcher.group(1));
      String id = safeTrim(matcher.group(2));
      if (!name.isBlank() && normalizePlaylistName(name).equals(normalizedTarget) && !id.isBlank()) {
        return id;
      }
    }
    return "";
  }

  private static String normalizePlaylistName(String value) {
    return safeTrim(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
  }
}
