package com.shellaia.agent.dj;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Map;

import com.social100.todero.common.storage.Storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDJActionValidationTest {

  @Test
  void queueRejectsArguments() throws Exception {
    ValidationResult result = validate("queue", "unexpected");
    assertEquals("invalid-arguments", result.errorCode);
  }

  @Test
  void recentlyPlayedValidatesLimitRange() throws Exception {
    ValidationResult invalid = validate("recently-played", "0");
    assertEquals("invalid-arguments", invalid.errorCode);

    ValidationResult valid = validate("recently-played", "10");
    assertNull(valid.errorCode);
    assertEquals("10", valid.args);
  }

  @Test
  void topTracksValidatesRangeToken() throws Exception {
    ValidationResult invalid = validate("top-tracks", "5 invalid");
    assertEquals("invalid-arguments", invalid.errorCode);

    ValidationResult valid = validate("top-tracks", "5 short_term");
    assertNull(valid.errorCode);
    assertEquals("5 short_term", valid.args);
  }

  @Test
  void playlistReorderValidatesNumericPositions() throws Exception {
    ValidationResult invalid = validate("playlist-reorder", "plid x 1");
    assertEquals("invalid-arguments", invalid.errorCode);

    ValidationResult valid = validate("playlist-reorder", "plid 0 1 2");
    assertNull(valid.errorCode);
    assertEquals("plid 0 1 2", valid.args);
  }

  @Test
  void playlistListValidatesLimit() throws Exception {
    ValidationResult invalid = validate("playlist-list", "plid many");
    assertEquals("invalid-arguments", invalid.errorCode);

    ValidationResult valid = validate("playlist-list", "plid 25");
    assertNull(valid.errorCode);
    assertEquals("plid 25", valid.args);
  }

  @Test
  void playlistAddRequiresPlaylistAndUris() throws Exception {
    ValidationResult invalid = validate("playlist-add", "plid");
    assertEquals("invalid-arguments", invalid.errorCode);

    ValidationResult valid = validate("playlist-add", "plid spotify:track:abc spotify:track:def");
    assertNull(valid.errorCode);
    assertEquals("plid spotify:track:abc spotify:track:def", valid.args);
  }

  @Test
  void playlistAddCurrentRequiresSongTitle() throws Exception {
    ValidationResult invalid = validate("playlist-add-current", "");
    assertEquals("invalid-arguments", invalid.errorCode);

    ValidationResult valid = validate("playlist-add-current", "Aventurera");
    assertNull(valid.errorCode);
    assertEquals("Aventurera", valid.args);
  }

  @Test
  void playlistPlayRequiresCanonicalPlaylistIdentifier() throws Exception {
    ValidationResult invalid = validate("playlist-play", "My Playlist #1");
    assertEquals("invalid-arguments", invalid.errorCode);

    ValidationResult valid = validate("playlist-play", "spotify:playlist:7cpHMBDK9bGgj2XlogYX9F 0");
    assertNull(valid.errorCode);
    assertEquals("spotify:playlist:7cpHMBDK9bGgj2XlogYX9F 0", valid.args);
  }

  @Test
  void resolveTrackRequiresQuery() throws Exception {
    ValidationResult result = validate("resolve-track", "");
    assertEquals("invalid-arguments", result.errorCode);
  }

  @Test
  void resolveTrackAcceptsQuery() throws Exception {
    ValidationResult valid = validate("resolve-track", "Caribbean Blue Enya");
    assertNull(valid.errorCode);
    assertEquals("Caribbean Blue Enya", valid.args);
  }

  @Test
  void currentPlaybackGoalsRequireContextSnapshotBeforePlanning() throws Exception {
    Object component = new AgentDJComponent(new InMemoryStorage());
    Object goalIntent = newGoalIntent("general_spotify_control", "current_playback", "current-playback", true, true, true, 1, 0.95d, "test");
    Method method = AgentDJComponent.class.getDeclaredMethod(
        "inferPlanState",
        Class.forName("com.shellaia.agent.dj.AgentDJComponent$GoalIntent"),
        List.class,
        List.class);
    method.setAccessible(true);

    Object result = method.invoke(component, goalIntent, List.of(), List.of());
    assertEquals("need_context_snapshot", result);
  }

  @Test
  void ordinalTargetsRequireContextSnapshotBeforePlanning() throws Exception {
    Object component = new AgentDJComponent(new InMemoryStorage());
    Object goalIntent = newGoalIntent("general_spotify_control", "playback", "song 4", true, false, true, 1, 0.95d, "test");
    Method method = AgentDJComponent.class.getDeclaredMethod(
        "inferPlanState",
        Class.forName("com.shellaia.agent.dj.AgentDJComponent$GoalIntent"),
        List.class,
        List.class,
        String.class);
    method.setAccessible(true);

    Object result = method.invoke(component, goalIntent, List.of(), List.of(), "go to song 4");
    assertEquals("need_context_snapshot", result);
  }

  @Test
  void directTrackPlaybackRequestsRequireContextSnapshotBeforePlanning() throws Exception {
    Object component = new AgentDJComponent(new InMemoryStorage());
    Object goalIntent = newGoalIntent("general_spotify_control", "explicit_request", "Rivers of Babylon", true, false, true, 1, 0.95d, "test");
    Method method = AgentDJComponent.class.getDeclaredMethod(
        "inferPlanState",
        Class.forName("com.shellaia.agent.dj.AgentDJComponent$GoalIntent"),
        List.class,
        List.class,
        String.class);
    method.setAccessible(true);

    Object result = method.invoke(component, goalIntent, List.of(), List.of(), "play Rivers of Babylon");
    assertEquals("need_context_snapshot", result);
  }

  @Test
  void playlistScopedTrackRequestsPreferPlaylistResolutionOverGenericSearchPlayback() throws Exception {
    Object component = new AgentDJComponent(new InMemoryStorage());
    Object goalIntent = newGoalIntent("general_spotify_control", "playlist", "Rivers of Babylon", true, false, true, 1, 0.95d, "test");
    Object statusStep = newToolStep(
        1,
        "status all",
        "status",
        "all",
        "Device: Arturo’s Mac mini\nPlaying: true\nContext: playlist (spotify:playlist:7cpHMBDK9bGgj2XlogYX9F)\nContextId: 7cpHMBDK9bGgj2XlogYX9F\nTrack: Desesperada — Marta Sánchez\nURI: spotify:track:5XRV6ZW1D8SpdXMXmuuhQi\nPosition: 00:21 / 03:47\nPlaylistPosition: 4/99",
        0L,
        0L,
        0L);
    Object observation = invokeObservation(component, "play Rivers of Babylon in the playlist", goalIntent, 1,
        newToolExecution(true, "status", "all", """
            Device: Arturo’s Mac mini
            Playing: true
            Context: playlist (spotify:playlist:7cpHMBDK9bGgj2XlogYX9F)
            ContextId: 7cpHMBDK9bGgj2XlogYX9F
            Track: Desesperada — Marta Sánchez
            URI: spotify:track:5XRV6ZW1D8SpdXMXmuuhQi
            Position: 00:21 / 03:47
            PlaylistPosition: 4/99
            """, "", "", "INTERMEDIATE_RESULT"),
        List.of(statusStep));
    Method method = AgentDJComponent.class.getDeclaredMethod(
        "inferPlanState",
        Class.forName("com.shellaia.agent.dj.AgentDJComponent$GoalIntent"),
        List.class,
        List.class,
        String.class);
    method.setAccessible(true);

    Object result = method.invoke(component, goalIntent, List.of(), List.of(observation), "play Rivers of Babylon in the playlist");
    assertEquals("need_playlist_resolution", result);
  }

  @Test
  void directTrackPlaybackRequestsInPlaylistContextPreferPlaylistResolution() throws Exception {
    Object component = new AgentDJComponent(new InMemoryStorage());
    Object goalIntent = newGoalIntent("general_spotify_control", "explicit_request", "Rivers of Babylon", true, false, true, 1, 0.95d, "test");
    Object statusStep = newToolStep(
        1,
        "status all",
        "status",
        "all",
        "Device: Arturo’s Mac mini\nPlaying: true\nContext: playlist (spotify:playlist:7cpHMBDK9bGgj2XlogYX9F)\nContextId: 7cpHMBDK9bGgj2XlogYX9F\nTrack: Desesperada — Marta Sánchez\nURI: spotify:track:5XRV6ZW1D8SpdXMXmuuhQi\nPosition: 00:21 / 03:47\nPlaylistPosition: 4/99",
        0L,
        0L,
        0L);
    Object tool = newToolExecution(true, "status", "all", """
        Device: Arturo’s Mac mini
        Playing: true
        Context: playlist (spotify:playlist:7cpHMBDK9bGgj2XlogYX9F)
        ContextId: 7cpHMBDK9bGgj2XlogYX9F
        Track: Desesperada — Marta Sánchez
        URI: spotify:track:5XRV6ZW1D8SpdXMXmuuhQi
        Position: 00:21 / 03:47
        PlaylistPosition: 4/99
        """, "", "", "INTERMEDIATE_RESULT");
    Object observation = invokeObservation(component, "play Rivers of Babylon", goalIntent, 1, tool, List.of(statusStep));
    Method method = AgentDJComponent.class.getDeclaredMethod(
        "inferPlanState",
        Class.forName("com.shellaia.agent.dj.AgentDJComponent$GoalIntent"),
        List.class,
        List.class,
        String.class);
    method.setAccessible(true);

    Object result = method.invoke(component, goalIntent, List.of(statusStep), List.of(observation), "play Rivers of Babylon");
    assertEquals("need_playlist_scan", result);
  }

  @Test
  void currentPlaybackGoalsCompleteAfterStatusConfirmsTransition() throws Exception {
    Object component = new AgentDJComponent(new InMemoryStorage());
    Object goalIntent = newGoalIntent("general_spotify_control", "current_playback", "current-playback", true, true, true, 1, 0.95d, "test");
    Object tool1 = newToolExecution(true, "status", "all", """
        Device: Arturo’s Mac mini
        Playing: true
        Context: playlist (spotify:playlist:7cpHMBDK9bGgj2XlogYX9F)
        Track: Desesperada — Marta Sánchez
        URI: spotify:track:5XRV6ZW1D8SpdXMXmuuhQi
        Position: 00:21 / 03:47
        """, "", "", "INTERMEDIATE_RESULT");
    Object tool2 = newToolExecution(true, "status", "all", """
        Device: Arturo’s Mac mini
        Playing: true
        Context: playlist (spotify:playlist:7cpHMBDK9bGgj2XlogYX9F)
        Track: Every Breath You Take — The Police
        URI: spotify:track:1JSTJqkT5qHq8MDJnJbRE1
        Position: 00:01 / 04:13
        """, "", "", "INTERMEDIATE_RESULT");
    Object observation1 = invokeObservation(component, "go to the next song", goalIntent, 1, tool1, List.of());
    Object observation2 = invokeObservation(component, "go to the next song", goalIntent, 2, tool2, List.of(newToolStep(
        1,
        "status all",
        "status",
        "all",
        "Device: Arturo’s Mac mini\nPlaying: true\nContext: playlist (spotify:playlist:7cpHMBDK9bGgj2XlogYX9F)\nTrack: Desesperada — Marta Sánchez\nURI: spotify:track:5XRV6ZW1D8SpdXMXmuuhQi\nPosition: 00:21 / 03:47",
        0L,
        0L,
        0L)));

    Method method = AgentDJComponent.class.getDeclaredMethod(
        "inferPlanState",
        Class.forName("com.shellaia.agent.dj.AgentDJComponent$GoalIntent"),
        List.class,
        List.class);
    method.setAccessible(true);

    assertEquals("goal_completed", method.invoke(component, goalIntent, List.of(), List.of(observation1, observation2)));
  }

  @Test
  void observesPlaylistCandidateFromPlaylistsOutput() throws Exception {
    Object component = new AgentDJComponent(new InMemoryStorage());
    Object goalIntent = newGoalIntent("general_spotify_control", "playlist", "My Place", true, false, true, 1, 0.95d, "test");
    Object tool = newToolExecution(true, "playlists", "50 0", """
        Playlists (limit=50, offset=0):
         1) Chill [id=aaa111, uri=spotify:playlist:aaa111, owner=me, public=true, tracks=5]
         2) My Place [id=pl1234567890ABCDEfghij, uri=spotify:playlist:pl1234567890ABCDEfghij, owner=me, public=true, tracks=20]
        """, "", "", "INTERMEDIATE_RESULT");
    Object observation = invokeObservation(component, "play my playlist called \"My Place\"", goalIntent, 2, tool, List.of());

    assertEquals("playlists", accessor(observation, "toolCommand"));
    assertEquals("intermediate", accessor(observation, "terminality"));
    assertEquals("My Place", accessor(observation, "canonicalName"));
    assertEquals("pl1234567890ABCDEfghij", accessor(observation, "canonicalId"));
    assertEquals("spotify:playlist:pl1234567890ABCDEfghij", accessor(observation, "canonicalUri"));
    assertTrue(((String) accessor(observation, "usefulFacts")).contains("matched_playlist_id=pl1234567890ABCDEfghij"));
  }

  @Test
  void observesPlaylistFailureWithoutInventingIdentifier() throws Exception {
    Object component = new AgentDJComponent(new InMemoryStorage());
    Object goalIntent = newGoalIntent("general_spotify_control", "playlist", "My Place", true, false, true, 1, 0.95d, "test");
    Object tool = newToolExecution(false, "playlist-play", "My Place", "playlist-play usage: <playlistId|uri> [offset].", "invalid-arguments", "", "FAILURE");
    Object observation = invokeObservation(component, "play my playlist called \"My Place\"", goalIntent, 1, tool, List.of());

    assertEquals("invalid_arguments", accessor(observation, "terminality"));
    assertEquals("", accessor(observation, "canonicalId"));
    assertEquals("", accessor(observation, "canonicalUri"));
    assertEquals("invalid-arguments", accessor(observation, "blockers"));
    assertTrue(((String) accessor(observation, "usefulFacts")).contains("usage"));
  }

  @Test
  void observesCurrentTrackFactsFromStatusOutput() throws Exception {
    Object component = new AgentDJComponent(new InMemoryStorage());
    Object goalIntent = newGoalIntent("recommendation_playback", "current_playback", "current-playback", true, true, true, 1, 0.95d, "test");
    Object tool = newToolExecution(true, "status", "all", "Device: Arturo’s Mac mini\nPlaying: false\nContext: playlist (spotify:playlist:7cpHMBDK9bGgj2XlogYX9F)\nTrack: Corazon de Acero — Yiyo Sarante\nURI: spotify:track:21nc3O8OncUv1jjSrC1ML2\nPosition: 00:00 / 03:12", "", "", "INTERMEDIATE_RESULT");
    Object observation = invokeObservation(component, "play something similar", goalIntent, 1, tool, List.of(newToolStep(
        1,
        "status all",
        "status",
        "all",
        "Device: Arturo’s Mac mini\nPlaying: false\nContext: playlist (spotify:playlist:7cpHMBDK9bGgj2XlogYX9F)\nTrack: Corazon de Acero — Yiyo Sarante\nURI: spotify:track:21nc3O8OncUv1jjSrC1ML2\nPosition: 00:00 / 03:12",
        0L,
        0L,
        0L)));

    assertEquals("spotify:track:21nc3O8OncUv1jjSrC1ML2", accessor(observation, "canonicalUri"));
    assertEquals("", accessor(observation, "playlistPosition"));
    assertEquals("playlist", accessor(observation, "contextType"));
    assertEquals("spotify:playlist:7cpHMBDK9bGgj2XlogYX9F", accessor(observation, "contextUri"));
    assertEquals("7cpHMBDK9bGgj2XlogYX9F", accessor(observation, "contextId"));
    assertEquals(Boolean.FALSE, accessor(observation, "playbackActive"));
    assertTrue(((String) accessor(observation, "usefulFacts")).contains("playback_active=false"));
  }

  @Test
  void observesPlaylistPositionFromStatusOutput() throws Exception {
    Object component = new AgentDJComponent(new InMemoryStorage());
    Object goalIntent = newGoalIntent("general_spotify_control", "current_playback", "current-playback", true, true, true, 1, 0.95d, "test");
    Object tool = newToolExecution(true, "status", "all", """
        Device: Arturo’s Mac mini
        Playing: true
        Context: playlist (spotify:playlist:7cpHMBDK9bGgj2XlogYX9F)
        Track: Desesperada — Marta Sánchez
        URI: spotify:track:5XRV6ZW1D8SpdXMXmuuhQi
        Position: 00:21 / 03:47
        PlaylistPosition: 4/99
        """, "", "", "INTERMEDIATE_RESULT");
    Object observation = invokeObservation(component, "go to song 4", goalIntent, 1, tool, List.of(newToolStep(
        1,
        "status all",
        "status",
        "all",
        "Device: Arturo’s Mac mini\nPlaying: true\nContext: playlist (spotify:playlist:7cpHMBDK9bGgj2XlogYX9F)\nContextId: 7cpHMBDK9bGgj2XlogYX9F\nTrack: Desesperada — Marta Sánchez\nURI: spotify:track:5XRV6ZW1D8SpdXMXmuuhQi\nPosition: 00:21 / 03:47\nPlaylistPosition: 4/99",
        0L,
        0L,
        0L)));

    assertEquals("4/99", accessor(observation, "playlistPosition"));
    assertTrue(((String) accessor(observation, "usefulFacts")).contains("playlist_position=4/99"));
    assertEquals("7cpHMBDK9bGgj2XlogYX9F", accessor(observation, "contextId"));
  }

  @Test
  void playlistScanExactTrackCandidatePromotesCandidateState() throws Exception {
    Object component = new AgentDJComponent(new InMemoryStorage());
    Object goalIntent = newGoalIntent("general_spotify_control", "playlist", "Rivers of Babylon", true, false, true, 1, 0.95d, "test");
    Object statusStep = newToolStep(
        1,
        "status all",
        "status",
        "all",
        "Device: Arturo’s Mac mini\nPlaying: true\nContext: playlist (spotify:playlist:7cpHMBDK9bGgj2XlogYX9F)\nContextId: 7cpHMBDK9bGgj2XlogYX9F\nTrack: Oxygène, Pt. 2 — Jean-Michel Jarre\nURI: spotify:track:7vLKG4ww0P8seUUsbgpcz3\nPosition: 00:21 / 07:46\nPlaylistPosition: 3/99",
        0L,
        0L,
        0L);
    Object playlistStep = newToolStep(
        2,
        "playlist-list 7cpHMBDK9bGgj2XlogYX9F 100",
        "playlist-list",
        "7cpHMBDK9bGgj2XlogYX9F 100",
        "Playlist: 7cpHMBDK9bGgj2XlogYX9F\n 5) Rivers of Babylon — Boney M. [uri=spotify:track:78His8pbKjbDQF7aX5asgv]",
        0L,
        0L,
        0L);
    Object statusObservation = invokeObservation(component, "play Rivers of Babylon in the current playlist", goalIntent, 1,
        newToolExecution(true, "status", "all", """
            Device: Arturo’s Mac mini
            Playing: true
            Context: playlist (spotify:playlist:7cpHMBDK9bGgj2XlogYX9F)
            ContextId: 7cpHMBDK9bGgj2XlogYX9F
            Track: Oxygène, Pt. 2 — Jean-Michel Jarre
            URI: spotify:track:7vLKG4ww0P8seUUsbgpcz3
            Position: 00:21 / 07:46
            PlaylistPosition: 3/99
            """, "", "", "INTERMEDIATE_RESULT"),
        List.of(statusStep));
    Object observation = invokeObservation(component, "play Rivers of Babylon in the current playlist", goalIntent, 2,
        newToolExecution(true, "playlist-list", "7cpHMBDK9bGgj2XlogYX9F 100", """
            Playlist: 7cpHMBDK9bGgj2XlogYX9F
             5) Rivers of Babylon — Boney M. [uri=spotify:track:78His8pbKjbDQF7aX5asgv]
            """, "", "", "INTERMEDIATE_RESULT"),
        List.of(statusStep, playlistStep));

    assertEquals("Rivers of Babylon — Boney M.", accessor(observation, "canonicalName"));
    assertEquals("spotify:playlist:7cpHMBDK9bGgj2XlogYX9F", accessor(observation, "canonicalUri"));
    assertEquals("7cpHMBDK9bGgj2XlogYX9F", accessor(observation, "canonicalId"));
    assertEquals("5", accessor(observation, "playlistTrackPosition"));
    assertEquals("snapshot active playback so the next step can search the current playlist with exact context", accessor(statusObservation, "observationGoal"));

    Method method = AgentDJComponent.class.getDeclaredMethod(
        "inferPlanState",
        Class.forName("com.shellaia.agent.dj.AgentDJComponent$GoalIntent"),
        List.class,
        List.class,
        String.class);
    method.setAccessible(true);

    Object result = method.invoke(component, goalIntent, List.of(statusStep, playlistStep), List.of(statusObservation, observation), "play Rivers of Babylon in the current playlist");
    assertEquals("have_playlist_track_candidate", result);

    Object playStep = newToolStep(
        3,
        "playlist-play 7cpHMBDK9bGgj2XlogYX9F 4",
        "playlist-play",
        "7cpHMBDK9bGgj2XlogYX9F 4",
        "Playing context spotify:playlist:7cpHMBDK9bGgj2XlogYX9F at index 4.",
        0L,
        0L,
        0L);
    Object playObservation = invokeObservation(component, "play Rivers of Babylon in the current playlist", goalIntent, 3,
        newToolExecution(true, "playlist-play", "7cpHMBDK9bGgj2XlogYX9F 4", "Playing context spotify:playlist:7cpHMBDK9bGgj2XlogYX9F at index 4.", "", "", "INTERMEDIATE_RESULT"),
        List.of(statusStep, playlistStep, playStep));

    assertEquals("5", accessor(playObservation, "playlistTrackPosition"));
    assertEquals("goal_completed", method.invoke(component, goalIntent, List.of(statusStep, playlistStep, playStep), List.of(statusObservation, observation, playObservation), "play Rivers of Babylon in the current playlist"));
  }

  @Test
  void playlistScanMissAllowsIntentDrivenFallbackState() throws Exception {
    Object component = new AgentDJComponent(new InMemoryStorage());
    Object goalIntent = newGoalIntent("general_spotify_control", "playlist", "Ghost Track", true, false, true, 1, 0.95d, "test");
    Object statusStep = newToolStep(
        1,
        "status all",
        "status",
        "all",
        "Device: Arturo’s Mac mini\nPlaying: true\nContext: playlist (spotify:playlist:7cpHMBDK9bGgj2XlogYX9F)\nContextId: 7cpHMBDK9bGgj2XlogYX9F\nTrack: Oxygène, Pt. 2 — Jean-Michel Jarre\nURI: spotify:track:7vLKG4ww0P8seUUsbgpcz3\nPosition: 00:21 / 07:46\nPlaylistPosition: 3/99",
        0L,
        0L,
        0L);
    Object playlistStep = newToolStep(
        2,
        "playlist-list 7cpHMBDK9bGgj2XlogYX9F 100",
        "playlist-list",
        "7cpHMBDK9bGgj2XlogYX9F 100",
        "Playlist: 7cpHMBDK9bGgj2XlogYX9F\n 5) Rivers of Babylon — Boney M. [uri=spotify:track:78His8pbKjbDQF7aX5asgv]",
        0L,
        0L,
        0L);
    Object statusObservation = invokeObservation(component, "play Ghost Track in the current playlist", goalIntent, 1,
        newToolExecution(true, "status", "all", """
            Device: Arturo’s Mac mini
            Playing: true
            Context: playlist (spotify:playlist:7cpHMBDK9bGgj2XlogYX9F)
            ContextId: 7cpHMBDK9bGgj2XlogYX9F
            Track: Oxygène, Pt. 2 — Jean-Michel Jarre
            URI: spotify:track:7vLKG4ww0P8seUUsbgpcz3
            Position: 00:21 / 07:46
            PlaylistPosition: 3/99
            """, "", "", "INTERMEDIATE_RESULT"),
        List.of(statusStep));
    Object observation = invokeObservation(component, "play Ghost Track in the current playlist", goalIntent, 2,
        newToolExecution(true, "playlist-list", "7cpHMBDK9bGgj2XlogYX9F 100", """
            Playlist: 7cpHMBDK9bGgj2XlogYX9F
             5) Rivers of Babylon — Boney M. [uri=spotify:track:78His8pbKjbDQF7aX5asgv]
            """, "", "", "INTERMEDIATE_RESULT"),
        List.of(statusStep, playlistStep));

    Method method = AgentDJComponent.class.getDeclaredMethod(
        "inferPlanState",
        Class.forName("com.shellaia.agent.dj.AgentDJComponent$GoalIntent"),
        List.class,
        List.class,
        String.class);
    method.setAccessible(true);

    assertEquals("playlist_scan_miss", method.invoke(component, goalIntent, List.of(statusStep, playlistStep), List.of(statusObservation, observation), "play Ghost Track in the current playlist"));
  }

  @Test
  void classifiesToolResponseDispositionFromOutcome() throws Exception {
    Method method = AgentDJComponent.class.getDeclaredMethod(
        "responseDispositionForSuccessfulTool",
        Class.forName("com.shellaia.agent.dj.AgentDJComponent$ToolExecution")
    );
    method.setAccessible(true);

    Object playTool = newToolExecution(true, "play", "spotify:track:1", "Playing now.", "", "",
        "GOAL_COMPLETED");
    Object resolveTrackTool = newToolExecution(true, "resolve-track", "Caribbean Blue Enya", "Resolved a candidate.", "", "",
        "INTERMEDIATE_RESULT");
    Object authBeginTool = newToolExecution(true, "auth-begin", "", "Open the Spotify link.", "", "",
        "AWAIT_EXTERNAL_COMPLETION");

    assertEquals("GOAL_COMPLETED", String.valueOf(method.invoke(null, playTool)));
    assertEquals("CONTINUE", String.valueOf(method.invoke(null, resolveTrackTool)));
    assertEquals("AWAIT_EXTERNAL_COMPLETION", String.valueOf(method.invoke(null, authBeginTool)));
  }

  private static Constructor<?> findToolStepConstructor() throws Exception {
    Class<?> toolStepClass = Class.forName("com.shellaia.agent.dj.AgentDJComponent$ToolStep");
    Constructor<?> ctor = toolStepClass.getDeclaredConstructor(
        int.class, String.class, String.class, String.class, String.class, long.class, long.class, long.class);
    ctor.setAccessible(true);
    return ctor;
  }

  private static Object newToolStep(int step,
                                       String agentAction,
                                       String toolCommand,
                                       String toolArgs,
                                       String toolOutput,
                                       long planningDurationMs,
                                       long toolDurationMs,
                                       long stepDurationMs) throws Exception {
    Constructor<?> ctor = findToolStepConstructor();
    return ctor.newInstance(step, agentAction, toolCommand, toolArgs, toolOutput, planningDurationMs, toolDurationMs, stepDurationMs);
  }

  private static ValidationResult validate(String command, String args) throws Exception {
    Method method = AgentDJComponent.class.getDeclaredMethod("validateAndNormalizeAction", String.class, String.class);
    method.setAccessible(true);
    Object validated = method.invoke(null, command, args);
    return new ValidationResult(
        invokeStringAccessor(validated, "command"),
        invokeStringAccessor(validated, "args"),
        invokeStringAccessor(validated, "errorCode"),
        invokeStringAccessor(validated, "error"));
  }

  private static String invokeStringAccessor(Object target, String methodName) throws Exception {
    Method method = target.getClass().getDeclaredMethod(methodName);
    method.setAccessible(true);
    return (String) method.invoke(target);
  }

  private static Object newToolExecution(boolean executed,
                                         String command,
                                         String args,
                                         String output,
                                         String errorCode,
                                         String rawOutput,
                                         String responseOutcomeName) throws Exception {
    Class<?> outcomeClass = Class.forName("com.shellaia.agent.dj.AgentDJComponent$ToolResponseOutcome");
    Object outcome = Enum.valueOf((Class<Enum>) outcomeClass.asSubclass(Enum.class), responseOutcomeName);
    Class<?> toolClass = Class.forName("com.shellaia.agent.dj.AgentDJComponent$ToolExecution");
    Constructor<?> ctor = toolClass.getDeclaredConstructor(
        boolean.class, String.class, String.class, String.class, String.class, String.class, outcomeClass);
    ctor.setAccessible(true);
    return ctor.newInstance(executed, command, args, output, errorCode, rawOutput, outcome);
  }

  private static Object newGoalIntent(String intent,
                                      String targetScope,
                                      String seedHint,
                                      boolean wantsPlayback,
                                      boolean referencesCurrentPlayback,
                                      boolean needsDiscovery,
                                      int requestedCount,
                                      double confidence,
                                      String reason) throws Exception {
    Class<?> clazz = Class.forName("com.shellaia.agent.dj.AgentDJComponent$GoalIntent");
    Constructor<?> ctor = clazz.getDeclaredConstructor(String.class, String.class, String.class, boolean.class, boolean.class,
        boolean.class, int.class, boolean.class, String.class, double.class, String.class);
    ctor.setAccessible(true);
    return ctor.newInstance(intent, targetScope, seedHint, wantsPlayback, referencesCurrentPlayback, needsDiscovery,
        requestedCount, true, "", confidence, reason);
  }

  private static Object invokeObservation(Object component,
                                          String initialPrompt,
                                          Object goalIntent,
                                          int step,
                                          Object toolExecution,
                                          List<Object> toolSteps) throws Exception {
    Method method = AgentDJComponent.class.getDeclaredMethod(
        "observeToolResult",
        String.class,
        Class.forName("com.shellaia.agent.dj.AgentDJComponent$GoalIntent"),
        int.class,
        Class.forName("com.shellaia.agent.dj.AgentDJComponent$ToolExecution"),
        List.class);
    method.setAccessible(true);
    @SuppressWarnings("unchecked")
    Object result = method.invoke(component, initialPrompt, goalIntent, step, toolExecution, toolSteps);
    return result;
  }

  private static Object accessor(Object target, String name) throws Exception {
    try {
      Method method = target.getClass().getDeclaredMethod(name);
      method.setAccessible(true);
      return method.invoke(target);
    } catch (NoSuchMethodException ignored) {
      String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
      Method method = target.getClass().getMethod(getter);
      method.setAccessible(true);
      return method.invoke(target);
    }
  }

  private static final class InMemoryStorage implements Storage {
    private final Map<String, byte[]> files = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> secrets = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void writeFile(String relativePath, byte[] bytes) {
      files.put(relativePath, bytes == null ? new byte[0] : bytes);
    }

    @Override
    public byte[] readFile(String relativePath) {
      if (".env".equals(relativePath)) {
        return new byte[0];
      }
      byte[] bytes = files.get(relativePath);
      if (bytes == null) {
        throw new RuntimeException("file not found: " + relativePath);
      }
      return bytes;
    }

    @Override
    public void deleteFile(String relativePath) {
      files.remove(relativePath);
    }

    @Override
    public List<String> listFiles(String relativeDir) {
      return List.copyOf(files.keySet());
    }

    @Override
    public void putSecret(String key, String value) {
      secrets.put(key, value);
    }

    @Override
    public String getSecret(String key) {
      return secrets.get(key);
    }

    @Override
    public void deleteSecret(String key) {
      secrets.remove(key);
    }
  }

  private record ValidationResult(String command, String args, String errorCode, String error) {
  }
}
