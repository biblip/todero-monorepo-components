package com.shellaia.agent.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;
import com.social100.todero.common.aiatpio.AiatpResponse;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.storage.Storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotifyAgentTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir
  Path tempDir;

  @Test
  void buildsTodoPlanDraftWithExpectedPhases() {
    SpotifyAgent component = new SpotifyAgent(new EmptyStorage(), tempDir.resolve("todo-db"), ctx -> spotifyCapabilityManifestFixture(), null, null);

    var draft = component.buildPlanDraft("play rivers of babylon");

    assertEquals("com.shellaia.agent.spotify", draft.ownerId());
    assertEquals(4, draft.phases().size());
    assertEquals("Plan", draft.phases().get(0).title());
    assertEquals("Discover", draft.phases().get(1).title());
    assertEquals("Execute", draft.phases().get(2).title());
    assertEquals("Verify", draft.phases().get(3).title());
    assertEquals("Resolve target entity", draft.phases().get(1).tasks().get(0).title());
    assertEquals("Fulfill Spotify request", draft.phases().get(2).tasks().get(0).title());
  }

  @Test
  void capabilitiesReturnsTodoAgentManifest() throws Exception {
    SpotifyAgent component = new SpotifyAgent(new EmptyStorage(), tempDir.resolve("todo-db"), ctx -> spotifyCapabilityManifestFixture(), null, null);
    AtomicReference<AiatpResponse> out = new AtomicReference<>();
    CommandContext context = CommandContext.builder()
        .sourceId("spotify-cap-test")
        .responseConsumer(out::set)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.spotify/capabilities",
            AiatpIO.Body.ofString("", StandardCharsets.UTF_8)))
        .build();

    assertTrue(component.capabilities(context));

    JsonNode root = JSON.readTree(AiatpIO.bodyToString(out.get().getBody(), StandardCharsets.UTF_8));
    assertEquals(200, out.get().getStatusCode());
    assertEquals("com.shellaia.agent.spotify", root.path("manifest").path("agentName").asText());
    assertEquals(5, root.path("manifest").path("commands").size());
  }

  @Test
  void planExportReturnsJsonDocument() throws Exception {
    SpotifyAgent component = new SpotifyAgent(new EmptyStorage(), tempDir.resolve("todo-db"), ctx -> spotifyCapabilityManifestFixture(), null, new CountingGoalRunner());
    component.process(CommandContext.builder()
        .sourceId("spotify-process")
        .responseConsumer(response -> { })
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.spotify/process",
            AiatpIO.Body.ofString("play rivers of babylon", StandardCharsets.UTF_8)))
        .build());

    String goalId;
    try (com.shellaia.tutil.todo.RocksDbTodoStore store = new com.shellaia.tutil.todo.RocksDbTodoStore(tempDir.resolve("todo-db"))) {
      goalId = new com.shellaia.tutil.todo.TodoManager(store).listGoals().get(0).id();
    }

    AtomicReference<AiatpResponse> out = new AtomicReference<>();
    CommandContext exportContext = CommandContext.builder()
        .sourceId("spotify-export")
        .responseConsumer(out::set)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.spotify/plan-export",
            AiatpIO.Body.ofString(goalId, StandardCharsets.UTF_8)))
        .build();

    assertTrue(component.planExport(exportContext));

    JsonNode root = JSON.readTree(AiatpIO.bodyToString(out.get().getBody(), StandardCharsets.UTF_8));
    assertEquals(200, out.get().getStatusCode());
    assertEquals("tutil.todo.plan.v1", root.path("schemaVersion").asText());
    assertEquals(1, root.path("goals").size());
  }

  @Test
  void processUsesTodoFlowToDriveExecutionAndVerification() throws Exception {
    CountingGoalRunner goalRunner = new CountingGoalRunner();
    Path dbDir = tempDir.resolve("todo-db-flow");
    SpotifyAgent component = new SpotifyAgent(new EmptyStorage(), dbDir, ctx -> spotifyCapabilityManifestFixture(), null, goalRunner);

    AtomicReference<AiatpResponse> out = new AtomicReference<>();

    assertTrue(component.process(CommandContext.builder()
        .sourceId("spotify-process-flow")
        .responseConsumer(out::set)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.spotify/process",
            AiatpIO.Body.ofString("play rivers of babylon", StandardCharsets.UTF_8)))
        .build()));

    try (com.shellaia.tutil.todo.RocksDbTodoStore store = new com.shellaia.tutil.todo.RocksDbTodoStore(dbDir)) {
      com.shellaia.tutil.todo.TodoGoal goal = new com.shellaia.tutil.todo.TodoManager(store).listGoals().get(0);
      assertEquals(1, goalRunner.calls.get());
      assertEquals(com.shellaia.tutil.todo.TodoStatus.COMPLETED, goal.status());
      assertEquals(4, goal.phases().size());
      assertEquals(com.shellaia.tutil.todo.TodoStatus.COMPLETED, goal.phases().get(0).status());
      assertEquals(com.shellaia.tutil.todo.TodoStatus.COMPLETED, goal.phases().get(1).status());
      assertEquals(com.shellaia.tutil.todo.TodoStatus.COMPLETED, goal.phases().get(2).status());
      assertEquals(com.shellaia.tutil.todo.TodoStatus.COMPLETED, goal.phases().get(3).status());
      assertTrue(goal.phases().get(1).tasks().stream().anyMatch(task -> "resolve_target".equals(task.metadata().get("taskRole"))));
      assertTrue(goal.phases().get(3).tasks().get(0).outcome().contains("Verified execution outcome"));
      assertTrue(goal.metadata().get("spotifyToolCapabilities").contains("\"componentName\":\"com.shellaia.spotify\""));
      assertTrue(goal.metadata().get("spotifyToolCommands").contains("capabilities"));
    }

    assertEquals(200, out.get().getStatusCode());
    assertEquals("ok", AiatpIO.bodyToString(out.get().getBody(), StandardCharsets.UTF_8));
  }

  @Test
  void unsupportedGenreSongListReturnsExplanationWithoutPlanning() throws Exception {
    CountingGoalRunner goalRunner = new CountingGoalRunner();
    Path dbDir = tempDir.resolve("todo-db-unsupported");
    SpotifyAgent component = new SpotifyAgent(new EmptyStorage(), dbDir, ctx -> spotifyCapabilityManifestFixture(), null, goalRunner);

    AtomicReference<AiatpResponse> out = new AtomicReference<>();
    assertTrue(component.process(CommandContext.builder()
        .sourceId("spotify-unsupported")
        .responseConsumer(out::set)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.spotify/process",
            AiatpIO.Body.ofString("list 10 salsa songs", StandardCharsets.UTF_8)))
        .build()));

    assertEquals(200, out.get().getStatusCode());
    String body = AiatpIO.bodyToString(out.get().getBody(), StandardCharsets.UTF_8);
    assertTrue(body.contains("cannot enumerate genre-specific song lists"));
    assertEquals(0, goalRunner.calls.get());

    try (com.shellaia.tutil.todo.RocksDbTodoStore store = new com.shellaia.tutil.todo.RocksDbTodoStore(dbDir)) {
      assertTrue(new com.shellaia.tutil.todo.TodoManager(store).listGoals().isEmpty());
    }
  }

  @Test
  void playlistAddRequestIsRecognizedWithTargets() throws Exception {
    Object intentProfile = invokePrivateStatic(
        SpotifyAgent.class,
        "analyzeIntent",
        new Class<?>[]{String.class},
        "add a song \"vivir mi vida\" to playlist \"Salsa Hits\""
    );

    assertEquals("playlist_add", invokePrivate(intentProfile, "intentType", new Class<?>[]{}));
    assertEquals("playlist_add_request", invokePrivate(intentProfile, "reason", new Class<?>[]{}));

    Object playlistAddRequest = invokePrivate(intentProfile, "playlistAddRequest", new Class<?>[]{});
    assertEquals("vivir mi vida", invokePrivate(playlistAddRequest, "trackTitle", new Class<?>[]{}));
    assertEquals("Salsa Hits", invokePrivate(playlistAddRequest, "playlistName", new Class<?>[]{}));
    assertEquals(false, invokePrivate(playlistAddRequest, "requiresCurrentPlaybackContext", new Class<?>[]{}));
  }

  @Test
  void playlistAddCurrentReferenceIsRecognized() throws Exception {
    Object intentProfile = invokePrivateStatic(
        SpotifyAgent.class,
        "analyzeIntent",
        new Class<?>[]{String.class},
        "add that song to playlist my place"
    );

    assertEquals("playlist_add", invokePrivate(intentProfile, "intentType", new Class<?>[]{}));
    Object playlistAddRequest = invokePrivate(intentProfile, "playlistAddRequest", new Class<?>[]{});
    assertEquals("", invokePrivate(playlistAddRequest, "trackTitle", new Class<?>[]{}));
    assertEquals("my place", invokePrivate(playlistAddRequest, "playlistName", new Class<?>[]{}));
    assertEquals(true, invokePrivate(playlistAddRequest, "requiresCurrentPlaybackContext", new Class<?>[]{}));
  }

  @Test
  void incompleteCurrentSongPlaylistAddRequestReturnsClarification() throws Exception {
    CountingGoalRunner goalRunner = new CountingGoalRunner();
    Path dbDir = tempDir.resolve("todo-db-current-song-clarification");
    SpotifyAgent component = new SpotifyAgent(new EmptyStorage(), dbDir, ctx -> spotifyCapabilityManifestFixture(), null, goalRunner);

    AtomicReference<AiatpResponse> out = new AtomicReference<>();
    assertTrue(component.process(CommandContext.builder()
        .sourceId("spotify-current-song-clarification")
        .responseConsumer(out::set)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.spotify/process",
            AiatpIO.Body.ofString("add that song to playlist", StandardCharsets.UTF_8)))
        .build()));

    assertEquals(200, out.get().getStatusCode());
    String body = AiatpIO.bodyToString(out.get().getBody(), StandardCharsets.UTF_8);
    assertTrue(body.contains("Which playlist should I add the current song to?"));
    assertEquals(0, goalRunner.calls.get());

    try (com.shellaia.tutil.todo.RocksDbTodoStore store = new com.shellaia.tutil.todo.RocksDbTodoStore(dbDir)) {
      assertTrue(new com.shellaia.tutil.todo.TodoManager(store).listGoals().isEmpty());
    }
  }

  @Test
  void currentSongPlaylistAddUsesCurrentTrackUriAndPlaylistAdd() throws Exception {
    RecordingSpotifyToolExecutor toolExecutor = new RecordingSpotifyToolExecutor();
    Path dbDir = tempDir.resolve("todo-db-current-song-playlist-add");
    SpotifyAgent component = new SpotifyAgent(new EmptyStorage(), dbDir, ctx -> spotifyCapabilityManifestFixture(), toolExecutor, new CountingGoalRunner());

    AtomicReference<AiatpResponse> out = new AtomicReference<>();
    assertTrue(component.process(CommandContext.builder()
        .sourceId("spotify-current-song-playlist-add")
        .responseConsumer(out::set)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.spotify/process",
            AiatpIO.Body.ofString("add that song to playlist \"my place\"", StandardCharsets.UTF_8)))
        .build()));

    assertEquals(200, out.get().getStatusCode());
    String body = AiatpIO.bodyToString(out.get().getBody(), StandardCharsets.UTF_8);
    assertTrue(body.contains("Added 1 item(s) to playlist 2cWl3P4A6zkbQcVQix0kHT."));
    assertEquals(List.of("status:all", "playlists:50 0", "playlist-add:2cWl3P4A6zkbQcVQix0kHT spotify:track:5WyX397vZTvYQwSrylCka0"), toolExecutor.calls);

    try (com.shellaia.tutil.todo.RocksDbTodoStore store = new com.shellaia.tutil.todo.RocksDbTodoStore(dbDir)) {
      assertTrue(new com.shellaia.tutil.todo.TodoManager(store).listGoals().isEmpty());
    }
  }

  @Test
  void playlistPlaybackByNameUsesPlaylistPlay() throws Exception {
    RecordingSpotifyToolExecutor toolExecutor = new RecordingSpotifyToolExecutor();
    Path dbDir = tempDir.resolve("todo-db-playlist-play");
    SpotifyAgent component = new SpotifyAgent(new EmptyStorage(), dbDir, ctx -> spotifyCapabilityManifestFixture(), toolExecutor, new CountingGoalRunner());

    AtomicReference<AiatpResponse> out = new AtomicReference<>();
    assertTrue(component.process(CommandContext.builder()
        .sourceId("spotify-playlist-play")
        .responseConsumer(out::set)
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.spotify/process",
            AiatpIO.Body.ofString("start playing my place", StandardCharsets.UTF_8)))
        .build()));

    assertEquals(200, out.get().getStatusCode());
    String body = AiatpIO.bodyToString(out.get().getBody(), StandardCharsets.UTF_8);
    assertTrue(body.contains("Playing context spotify:playlist:2cWl3P4A6zkbQcVQix0kHT at index 0."));
    assertEquals(List.of("playlists:50 0", "playlist-play:2cWl3P4A6zkbQcVQix0kHT"), toolExecutor.calls);

    try (com.shellaia.tutil.todo.RocksDbTodoStore store = new com.shellaia.tutil.todo.RocksDbTodoStore(dbDir)) {
      assertTrue(new com.shellaia.tutil.todo.TodoManager(store).listGoals().isEmpty());
    }
  }

  @Test
  void buildsDynamicPlanForCurrentPlaylistOrdinalRequest() {
    SpotifyAgent component = new SpotifyAgent(new EmptyStorage(), tempDir.resolve("todo-db-dynamic"), ctx -> spotifyCapabilityManifestFixture(), null, null);

    var draft = component.buildPlanDraft("go to song 4 in the current playlist");

    assertEquals(4, draft.phases().size());
    assertTrue(draft.phases().get(0).tasks().stream().anyMatch(task -> "snapshot_playback".equals(task.metadata().get("taskRole"))));
    assertTrue(draft.phases().get(0).tasks().stream().anyMatch(task -> "scan_playlist".equals(task.metadata().get("taskRole"))));
    assertTrue(draft.phases().get(1).tasks().stream().anyMatch(task -> "resolve_target".equals(task.metadata().get("taskRole"))));
  }

  @Test
  void playbackResolveTrackMustFollowThroughToPlayAndVerify() throws Exception {
    SpotifyAgent component = new SpotifyAgent(new EmptyStorage(), tempDir.resolve("todo-db-followthrough"), ctx -> spotifyCapabilityManifestFixture(), null, null);

    Object intentProfile = invokePrivateStatic(
        SpotifyAgent.class,
        "analyzeIntent",
        new Class<?>[]{String.class},
        "play caribbean blue by enya"
    );

    boolean mustFollowThrough = (boolean) invokePrivate(
        component,
        "requiresPlaybackFollowThrough",
        new Class<?>[]{intentProfile.getClass(), String.class},
        intentProfile,
        "resolve-track enya caribbean blue"
    );
    boolean shouldNotFollowThroughOnPlay = (boolean) invokePrivate(
        component,
        "requiresPlaybackFollowThrough",
        new Class<?>[]{intentProfile.getClass(), String.class},
        intentProfile,
        "play spotify:track:5KZ4DC772dYcRBAizx0yYk"
    );

    String uri = (String) invokePrivateStatic(
        SpotifyAgent.class,
        "extractSpotifyTrackUri",
        new Class<?>[]{String.class},
        "Resolved track: Caribbean Blue - 2009 Remaster — Enya [uri=spotify:track:5KZ4DC772dYcRBAizx0yYk]"
    );

    boolean verified = (boolean) invokePrivateStatic(
        SpotifyAgent.class,
        "isPlaybackVerified",
        new Class<?>[]{String.class, String.class},
        "Device: Arturo’s Mac mini\nPlaying: true\nTrack: Caribbean Blue - 2009 Remaster — Enya\nURI: spotify:track:5KZ4DC772dYcRBAizx0yYk",
        uri
    );

    assertTrue(mustFollowThrough);
    assertTrue(!shouldNotFollowThroughOnPlay);
    assertEquals("spotify:track:5KZ4DC772dYcRBAizx0yYk", uri);
    assertTrue(verified);
  }

  @Test
  void parsesPlannerStepsForMultiStepPlans() throws Exception {
    SpotifyAgent component = new SpotifyAgent(new EmptyStorage(), tempDir.resolve("todo-db-steps"), ctx -> spotifyCapabilityManifestFixture(), null, null);
    JsonNode root = JSON.readTree("""
        {
          "steps": [
            {"action": "devices"},
            {"action": "select-device device-123"},
            {"action": "play-context spotify:playlist:abc123"}
          ]
        }
        """);

    @SuppressWarnings("unchecked")
    List<String> actions = (List<String>) invokePrivate(
        component,
        "parsePlannerActions",
        new Class<?>[]{JsonNode.class},
        root
    );

    assertEquals(List.of(
        "devices",
        "select-device device-123",
        "play-context spotify:playlist:abc123"
    ), actions);
  }

  @Test
  void insertsRepairTaskAfterRecoverableDelegateFailure() throws Exception {
    SequencedGoalRunner goalRunner = new SequencedGoalRunner(
        new SpotifyAgent.GoalExecutionResult(false, "tool_execution_failed", 1, "corr-1", "failed once"),
        new SpotifyAgent.GoalExecutionResult(true, "action_none", 2, "corr-2", "recovered")
    );
    Path dbDir = tempDir.resolve("todo-db-repair");
    SpotifyAgent component = new SpotifyAgent(new EmptyStorage(), dbDir, ctx -> spotifyCapabilityManifestFixture(), null, goalRunner);

    assertTrue(component.process(CommandContext.builder()
        .sourceId("spotify-repair")
        .responseConsumer(response -> { })
        .aiatpRequest(AiatpRuntimeAdapter.request("ACTION", "/com.shellaia.agent.spotify/process",
            AiatpIO.Body.ofString("play rivers of babylon", StandardCharsets.UTF_8)))
        .build()));

    try (com.shellaia.tutil.todo.RocksDbTodoStore store = new com.shellaia.tutil.todo.RocksDbTodoStore(dbDir)) {
      com.shellaia.tutil.todo.TodoGoal goal = new com.shellaia.tutil.todo.TodoManager(store).listGoals().get(0);
      assertEquals(2, goalRunner.calls.get());
      assertEquals(com.shellaia.tutil.todo.TodoStatus.COMPLETED, goal.status());
      assertTrue(goal.phases().get(2).tasks().stream().anyMatch(task -> "repair".equals(task.metadata().get("taskRole"))));
    }
  }

  private static final class CountingGoalRunner implements SpotifyAgent.SpotifyGoalRunner {
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public SpotifyAgent.GoalExecutionResult run(CommandContext context, String prompt, boolean interactiveRequest, String source, boolean emitFinal) {
      calls.incrementAndGet();
      return new SpotifyAgent.GoalExecutionResult(true, "action_none", 3, "counting-correlation", "ok");
    }
  }

  private static final class SequencedGoalRunner implements SpotifyAgent.SpotifyGoalRunner {
    private final AtomicInteger calls = new AtomicInteger();
    private final java.util.List<SpotifyAgent.GoalExecutionResult> results;

    SequencedGoalRunner(SpotifyAgent.GoalExecutionResult... results) {
      this.results = java.util.List.of(results);
    }

    @Override
    public SpotifyAgent.GoalExecutionResult run(CommandContext context, String prompt, boolean interactiveRequest, String source, boolean emitFinal) {
      int index = calls.getAndIncrement();
      if (index >= results.size()) {
        return results.get(results.size() - 1);
      }
      return results.get(index);
    }
  }

  private static final class RecordingSpotifyToolExecutor implements SpotifyAgent.SpotifyToolExecutor {
    private final java.util.List<String> calls = new java.util.ArrayList<>();

    @Override
    public SpotifyAgent.ToolExecutionResult loadCapabilities(CommandContext parentContext) {
      calls.add("capabilities:");
      return SpotifyAgent.ToolExecutionResult.success("capabilities", "", "{\"manifest\":{\"commands\":[{\"name\":\"capabilities\"}]}}", 200);
    }

    @Override
    public SpotifyAgent.ToolExecutionResult execute(CommandContext parentContext, String command, String args) {
      String normalizedArgs = args == null ? "" : args.trim();
      calls.add(command + ":" + normalizedArgs);
      return switch (command) {
        case "status" -> SpotifyAgent.ToolExecutionResult.success("status", normalizedArgs,
            "Device: Arturo’s Mac mini\nPlaying: true\nTrack: Amiga — Maelo Ruiz\nURI: spotify:track:5WyX397vZTvYQwSrylCka0\nTrackUri: spotify:track:5WyX397vZTvYQwSrylCka0\nPosition: 00:42 / 04:15\nProgressMs: 42959\nDurationMs: 255280\nShuffle: false\nRepeat: off\nVolume: 100%", 200);
        case "playlists" -> SpotifyAgent.ToolExecutionResult.success("playlists", normalizedArgs,
            "Playlists (limit=50, offset=0):\n 1) my place [id=2cWl3P4A6zkbQcVQix0kHT, uri=spotify:playlist:2cWl3P4A6zkbQcVQix0kHT, owner=Arturoportilla, public=true, tracks=3]", 200);
        case "playlist-play" -> SpotifyAgent.ToolExecutionResult.success("playlist-play", normalizedArgs,
            "Playing context spotify:playlist:2cWl3P4A6zkbQcVQix0kHT at index 0.", 200);
        case "playlist-add" -> SpotifyAgent.ToolExecutionResult.success("playlist-add", normalizedArgs,
            "Added 1 item(s) to playlist 2cWl3P4A6zkbQcVQix0kHT.", 200);
        default -> SpotifyAgent.ToolExecutionResult.error("unexpected_command", command, normalizedArgs, "Unexpected command: " + command, 500);
      };
    }
  }

  private static JsonNode spotifyCapabilityManifestFixture() {
    try {
      return JSON.readTree("""
          {
            "contractVersion": 1,
            "componentName": "com.shellaia.spotify",
            "commands": [
              {"name":"capabilities"},
              {"name":"status"},
              {"name":"play"},
              {"name":"playlist-play"},
              {"name":"playlist-list"},
              {"name":"playlists"},
              {"name":"playlist-add"},
              {"name":"devices"},
              {"name":"select-device"},
              {"name":"resolve-track"}
            ]
          }
          """);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Object invokePrivate(Object target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
    Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
    method.setAccessible(true);
    return method.invoke(target, args);
  }

  private static Object invokePrivateStatic(Class<?> target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
    Method method = target.getDeclaredMethod(methodName, parameterTypes);
    method.setAccessible(true);
    return method.invoke(null, args);
  }

  private static final class EmptyStorage implements Storage {
    @Override
    public void writeFile(String relativePath, byte[] bytes) {
    }

    @Override
    public byte[] readFile(String relativePath) throws IOException {
      throw new IOException("not found");
    }

    @Override
    public void deleteFile(String relativePath) {
    }

    @Override
    public List<String> listFiles(String relativeDir) {
      return List.of();
    }

    @Override
    public void putSecret(String key, String value) {
    }

    @Override
    public String getSecret(String key) {
      return null;
    }

    @Override
    public void deleteSecret(String key) {
    }
  }
}
