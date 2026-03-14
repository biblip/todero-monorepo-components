package com.example.todero.agent.dj;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

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
  void recommendRejectsOverLimit() throws Exception {
    ValidationResult result = validate("recommend", "Celine Dion 100");
    assertEquals("invalid-arguments", result.errorCode);
  }

  @Test
  void suggestAcceptsThemeWithValidLimit() throws Exception {
    ValidationResult valid = validate("suggest", "happy lively party songs 10");
    assertNull(valid.errorCode);
    assertEquals("happy lively party songs 10", valid.args);

    ValidationResult invalid = validate("suggest", "happy lively party songs 20");
    assertEquals("invalid-arguments", invalid.errorCode);
  }

  @Test
  void detectsPlaylistAddIntentFromPrompt() throws Exception {
    Method method = AgentDJComponent.class.getDeclaredMethod("detectPlaylistAddIntent", String.class);
    method.setAccessible(true);
    Object intent = method.invoke(null, "I want to add this song, the one that is playing right now, to my playlist called My Place.");
    assertTrue(intent != null);

    Method playlistName = intent.getClass().getDeclaredMethod("playlistName");
    playlistName.setAccessible(true);
    assertEquals("My Place", playlistName.invoke(intent));
  }

  @Test
  void findsPlaylistIdByNameFromPlaylistsOutput() throws Exception {
    Object step = newToolStep(
        2,
        "playlists 50 0",
        "playlists",
        "50 0",
        """
        Playlists (limit=50, offset=0):
         1) Chill [id=aaa111, uri=spotify:playlist:aaa111, owner=me, public=true, tracks=5]
         2) My Place [id=pl123, uri=spotify:playlist:pl123, owner=me, public=true, tracks=20]
        """,
        0L,
        0L,
        0L);

    Method findPlaylist = AgentDJComponent.class.getDeclaredMethod("findPlaylistIdByName", List.class, String.class);
    findPlaylist.setAccessible(true);
    @SuppressWarnings("unchecked")
    Optional<String> id = (Optional<String>) findPlaylist.invoke(null, List.of(step), "My Place");
    assertTrue(id.isPresent());
    assertEquals("pl123", id.get());
  }

  @Test
  void findsCurrentTrackUriFromStatusOrPlayOutput() throws Exception {
    Object statusStep = newToolStep(
        1,
        "status all",
        "status",
        "all",
        "Track: Corazon de Acero — Yiyo Sarante\nURI: spotify:track:21nc3O8OncUv1jjSrC1ML2",
        0L,
        0L,
        0L);
    Object playlistsStep = newToolStep(
        2,
        "playlists 50 0",
        "playlists",
        "50 0",
        "Playlists (limit=50, offset=0):",
        0L,
        0L,
        0L);

    Method findTrack = AgentDJComponent.class.getDeclaredMethod("findCurrentTrackUri", List.class);
    findTrack.setAccessible(true);
    @SuppressWarnings("unchecked")
    Optional<String> uri = (Optional<String>) findTrack.invoke(null, List.of(statusStep, playlistsStep));
    assertTrue(uri.isPresent());
    assertEquals("spotify:track:21nc3O8OncUv1jjSrC1ML2", uri.get());
  }

  @Test
  void returnsEmptyWhenNoPlaylistIdMatch() throws Exception {
    Object step = newToolStep(
        2,
        "playlists 50 0",
        "playlists",
        "50 0",
        "Playlists (limit=50, offset=0):\n 1) Salsa [id=salsa1, uri=spotify:playlist:salsa1, owner=me, public=true, tracks=9]",
        0L,
        0L,
        0L);

    Method findPlaylist = AgentDJComponent.class.getDeclaredMethod("findPlaylistIdByName", List.class, String.class);
    findPlaylist.setAccessible(true);
    @SuppressWarnings("unchecked")
    Optional<String> id = (Optional<String>) findPlaylist.invoke(null, List.of(step), "My Place");
    assertFalse(id.isPresent());
  }

  @Test
  void detectsCurrentPlaylistSongTitleFromPrompt() throws Exception {
    Method method = AgentDJComponent.class.getDeclaredMethod("detectCurrentPlaylistSongTitle", String.class);
    method.setAccessible(true);
    String quoted = (String) method.invoke(null, "Add \"Caribbean Blue\" to playlist");
    assertEquals("Caribbean Blue", quoted);

    String fallback = (String) method.invoke(null, "Add Aventurera to my playlist");
    assertEquals("Aventurera", fallback);
  }

  @Test
  void marksTerminalInteractiveCommandsAsCompletionCandidates() throws Exception {
    Method method = AgentDJComponent.class.getDeclaredMethod(
        "shouldCompleteAfterSuccessfulTool",
        String.class,
        com.social100.todero.common.ai.action.CommandAgentResponse.class
    );
    method.setAccessible(true);

    com.social100.todero.common.ai.action.CommandAgentResponse playResponse =
        new com.social100.todero.common.ai.action.CommandAgentResponse(
            "play enya",
            "play ${Enya Caribbean Blue}",
            "Playing now.",
            ""
        );
    boolean playCompletes = (boolean) method.invoke(null, "play", playResponse);
    assertTrue(playCompletes);

    com.social100.todero.common.ai.action.CommandAgentResponse suggestResponse =
        new com.social100.todero.common.ai.action.CommandAgentResponse(
            "party songs",
            "suggest party songs",
            "Here are some songs.",
            ""
        );
    boolean suggestCompletes = (boolean) method.invoke(null, "suggest", suggestResponse);
    assertFalse(suggestCompletes);
  }

  private static Constructor<?> findToolStepConstructor() throws Exception {
    Class<?> toolStepClass = Class.forName("com.example.todero.agent.dj.AgentDJComponent$ToolStep");
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

  private record ValidationResult(String command, String args, String errorCode, String error) {
  }
}
