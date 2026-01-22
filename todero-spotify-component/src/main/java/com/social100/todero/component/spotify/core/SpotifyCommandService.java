package com.social100.todero.component.spotify.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.exceptions.detailed.ForbiddenException;
import se.michaelthelin.spotify.exceptions.detailed.TooManyRequestsException;
import se.michaelthelin.spotify.exceptions.detailed.UnauthorizedException;
import se.michaelthelin.spotify.model_objects.IPlaylistItem;
import se.michaelthelin.spotify.model_objects.miscellaneous.CurrentlyPlayingContext;
import se.michaelthelin.spotify.model_objects.miscellaneous.Device;
import se.michaelthelin.spotify.model_objects.special.PlaybackQueue;
import se.michaelthelin.spotify.model_objects.specification.Artist;
import se.michaelthelin.spotify.model_objects.specification.Episode;
import se.michaelthelin.spotify.model_objects.specification.Paging;
import se.michaelthelin.spotify.model_objects.specification.PagingCursorbased;
import se.michaelthelin.spotify.model_objects.specification.PlayHistory;
import se.michaelthelin.spotify.model_objects.specification.Playlist;
import se.michaelthelin.spotify.model_objects.specification.PlaylistSimplified;
import se.michaelthelin.spotify.model_objects.specification.PlaylistTrack;
import se.michaelthelin.spotify.model_objects.specification.Recommendations;
import se.michaelthelin.spotify.model_objects.specification.Track;
import se.michaelthelin.spotify.model_objects.specification.User;
import se.michaelthelin.spotify.requests.data.library.SaveTracksForUserRequest;
import se.michaelthelin.spotify.requests.data.player.*;
import se.michaelthelin.spotify.requests.data.playlists.AddItemsToPlaylistRequest;
import se.michaelthelin.spotify.requests.data.playlists.CreatePlaylistRequest;
import se.michaelthelin.spotify.requests.data.playlists.GetPlaylistsItemsRequest;
import se.michaelthelin.spotify.requests.data.playlists.RemoveItemsFromPlaylistRequest;
import se.michaelthelin.spotify.requests.data.playlists.ReorderPlaylistsItemsRequest;

import java.io.IOException;
import java.time.Duration;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpotifyCommandService {
  public final static String MAIN_GROUP = "Main";
  public final static String PLAYLIST_GROUP = "Playlist";

  private final SpotifyPkceService spotifyPkceService;
  private final String preferredDeviceId;        // may be null
  private Integer cachedVolumeBeforeMute;        // for mute toggle
  private ScheduledExecutorService eventsExec;
  private ScheduledFuture<?> eventsTask;
  private volatile PlaybackSnapshot lastPlaybackSnapshot;
  private static final int MAX_API_RETRIES = 3;
  private static final long BASE_RETRY_MS = 250L;
  private final Map<String, CommandStats> commandStats = new ConcurrentHashMap<>();

  @FunctionalInterface
  private interface SpotifySupplier<T> {
    T get() throws Exception;
  }

  @FunctionalInterface
  private interface CommandSupplier {
    String run() throws Exception;
  }

  public SpotifyCommandService(SpotifyPkceService spotifyPkceService, String preferredDeviceId) {
    this.spotifyPkceService = spotifyPkceService;
    this.preferredDeviceId = preferredDeviceId;
  }

  /**
   * If input is "${...}", return inside; else return raw trimmed string.
   */
  private static String extractPlaceholderOrRaw(String input) {
    String s = input.trim();
    if (s.startsWith("${") && s.endsWith("}") && s.length() >= 3) {
      return s.substring(2, s.length() - 1).trim();
    }
    return s;
  }

  /* ======================== Playlist ======================== */

  /**
   * Lightweight scoring: token overlap + contains + popularity tie-breaker.
   */
  private static double scoreTrackAgainstQuery(Track t, String query) {
    String q = query.toLowerCase();
    String name = t.getName() == null ? "" : t.getName().toLowerCase();
    String artist = (t.getArtists() != null && t.getArtists().length > 0 && t.getArtists()[0].getName() != null)
        ? t.getArtists()[0].getName().toLowerCase() : "";
    String combined = (name + " " + artist).trim();

    // Basic features
    boolean exactName = name.equals(q);
    boolean contains = combined.contains(q);
    int overlap = tokenOverlap(combined, q); // crude token overlap count
    int popularity = t.getPopularity() == null ? 0 : t.getPopularity();

    double score = 0.0;
    if (exactName) score += 3.0;
    if (contains) score += 1.5;
    score += Math.min(overlap, 5) * 0.4;            // up to +2.0
    score += (popularity / 100.0) * 0.5;            // up to +0.5
    return score;
  }

  private static int tokenOverlap(String hay, String needle) {
    String[] a = hay.split("\\s+");
    String[] b = needle.split("\\s+");
    int count = 0;
    for (String bb : b) {
      for (String aa : a) {
        if (!bb.isBlank() && aa.equals(bb)) {
          count++;
          break;
        }
      }
    }
    return count;
  }

  private static long parseTimestampToMillis(String s) {
    // Accept HH:MM:SS | MM:SS | SS
    Pattern p = Pattern.compile("^(?:(\\d+):)?(\\d{1,2}):(\\d{2})$|^(\\d+)$");
    Matcher m = p.matcher(s.trim());
    if (!m.matches()) throw new IllegalArgumentException("Invalid time. Use HH:MM:SS, MM:SS or SS.");
    if (m.group(4) != null) { // SS
      long sec = Long.parseLong(m.group(4));
      return Duration.ofSeconds(sec).toMillis();
    }
    long h = (m.group(1) == null) ? 0 : Long.parseLong(m.group(1));
    long min = Long.parseLong(m.group(2));
    long sec = Long.parseLong(m.group(3));
    return Duration.ofHours(h).plusMinutes(min).plusSeconds(sec).toMillis();
  }

  private static String formatTime(long ms) {
    long totalSeconds = ms / 1000;
    long h = totalSeconds / 3600;
    long m = (totalSeconds % 3600) / 60;
    long s = totalSeconds % 60;
    return (h > 0)
        ? String.format("%02d:%02d:%02d", h, m, s)
        : String.format("%02d:%02d", m, s);
  }

  private String executeCommand(String command, CommandSupplier supplier) {
    long startedAtNs = System.nanoTime();
    CommandStats stats = commandStats.computeIfAbsent(command, k -> new CommandStats());
    stats.total.incrementAndGet();
    try {
      String response = supplier.run();
      if (isFailureMessage(response)) {
        stats.failures.incrementAndGet();
      } else {
        stats.success.incrementAndGet();
      }
      return response;
    } catch (Exception e) {
      ServiceError err = mapException(command, e);
      if ("rate_limited".equals(err.errorCode)) {
        stats.rateLimited.incrementAndGet();
      }
      stats.failures.incrementAndGet();
      return formatFailure(command, err.errorCode, err.message);
    } finally {
      stats.totalLatencyNanos.addAndGet(System.nanoTime() - startedAtNs);
    }
  }

  private static boolean isFailureMessage(String message) {
    if (message == null) {
      return false;
    }
    return message.toLowerCase(Locale.ROOT).matches("^[a-z0-9-]+ failed.*");
  }

  private static String formatFailure(String command, String errorCode, String message) {
    return command + " failed [error_code=" + errorCode + "]: " + message;
  }

  private <T> T callSpotifyApi(String command, SpotifySupplier<T> supplier) throws Exception {
    int attempt = 0;
    while (true) {
      try {
        return supplier.get();
      } catch (TooManyRequestsException e) {
        if (attempt >= MAX_API_RETRIES) {
          throw e;
        }
        int retryAfterSeconds = Math.max(1, e.getRetryAfter());
        sleepQuietly(retryAfterSeconds * 1000L + jitterMs(120));
        attempt++;
      } catch (SpotifyWebApiException e) {
        if (attempt >= MAX_API_RETRIES || !isTransientSpotify(e)) {
          throw e;
        }
        sleepQuietly(BASE_RETRY_MS * (attempt + 1) + jitterMs(150));
        attempt++;
      } catch (IOException e) {
        if (attempt >= MAX_API_RETRIES) {
          throw e;
        }
        sleepQuietly(BASE_RETRY_MS * (attempt + 1) + jitterMs(150));
        attempt++;
      }
    }
  }

  private static boolean isTransientSpotify(SpotifyWebApiException e) {
    String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
    return msg.contains("timed out") || msg.contains("timeout") || msg.contains("temporar") || msg.contains("service unavailable");
  }

  private static long jitterMs(int boundExclusive) {
    return ThreadLocalRandom.current().nextInt(Math.max(1, boundExclusive));
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(Math.max(1L, millis));
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }

  private ServiceError mapException(String command, Exception e) {
    if (e instanceof SpotifyAuthorizationRequiredException authRequired) {
      return new ServiceError(authRequired.errorCode(), authRequired.getMessage());
    }
    if (e instanceof TooManyRequestsException tmr) {
      return new ServiceError("rate_limited", "Retry after " + tmr.getRetryAfter() + "s.");
    }
    if (e instanceof UnauthorizedException) {
      return new ServiceError("unauthorized", "Spotify authorization is invalid or expired. Run auth flow again.");
    }
    if (e instanceof ForbiddenException) {
      return new ServiceError("forbidden", "Spotify rejected operation. Ensure Premium account and required scopes are granted.");
    }
    if (e instanceof IllegalArgumentException) {
      return new ServiceError("invalid_arguments", e.getMessage() == null ? "Invalid arguments." : e.getMessage());
    }
    String msg = e.getMessage() == null ? "Unknown failure." : e.getMessage();
    return new ServiceError("execution_failed", msg);
  }

  /* ======================== Main ======================== */

  // playlist-list
  public String playlists(int limit, int offset) {
    return executeCommand("playlists", () -> {
      int cappedLimit = Math.max(1, Math.min(limit, 50));
      int safeOffset = Math.max(0, offset);
      Paging<PlaylistSimplified> page = callSpotifyApi("playlists", () -> spotifyPkceService.getSpotifyApi()
          .getListOfCurrentUsersPlaylists()
          .limit(cappedLimit)
          .offset(safeOffset)
          .build()
          .execute());
      PlaylistSimplified[] items = page == null ? null : page.getItems();
      if (items == null || items.length == 0) {
        return "No playlists found.";
      }
      StringJoiner sj = new StringJoiner("\n");
      sj.add("Playlists (limit=" + cappedLimit + ", offset=" + safeOffset + "):");
      for (int i = 0; i < items.length; i++) {
        PlaylistSimplified p = items[i];
        String owner = (p.getOwner() != null && p.getOwner().getDisplayName() != null)
            ? p.getOwner().getDisplayName()
            : (p.getOwner() != null ? p.getOwner().getId() : "unknown");
        int trackCount = p.getTracks() == null ? -1 : p.getTracks().getTotal();
        sj.add(String.format("%2d) %s [id=%s, uri=%s, owner=%s, public=%s, tracks=%s]",
            i + 1,
            p.getName(),
            p.getId(),
            p.getUri(),
            owner,
            p.getIsPublicAccess(),
            trackCount < 0 ? "?" : String.valueOf(trackCount)));
      }
      return sj.toString();
    });
  }

  public String playlistList(String playlistId, int limit) {
    return executeCommand("playlist-list", () -> {
      GetPlaylistsItemsRequest req = spotifyPkceService.getSpotifyApi().getPlaylistsItems(playlistId)
          .limit(Math.max(1, Math.min(limit, 100)))
          .build();
      Paging<PlaylistTrack> page = callSpotifyApi("playlist-list", req::execute);

      // figure out current context / track
      CurrentlyPlayingContext ctx = safeGetPlayback();
      String currentCtxUri = (ctx != null && ctx.getContext() != null) ? ctx.getContext().getUri() : null;
      String currentTrackUri = (ctx != null && ctx.getItem() instanceof Track t) ? t.getUri() : null;

      StringJoiner sj = new StringJoiner("\n");
      sj.add("Playlist: " + playlistId);
      PlaylistTrack[] items = page.getItems();
      for (int i = 0; i < items.length; i++) {
        IPlaylistItem item = items[i].getTrack();
        if (item instanceof Track t) {
          String artist = (t.getArtists() != null && t.getArtists().length > 0)
              ? t.getArtists()[0].getName() : "Unknown";
          boolean isCurrent = ("spotify:playlist:" + playlistId).equals(currentCtxUri)
              && t.getUri().equals(currentTrackUri);
          sj.add(String.format("%2d) %s — %s [uri=%s]%s",
              i + 1, t.getName(), artist, t.getUri(), isCurrent ? "  <-- CURRENT" : ""));
        }
      }
      return sj.toString();
    });
  }

  // playlist-add
  public String playlistAdd(String playlistId, List<String> trackUris) {
    return executeCommand("playlist-add", () -> {
      JsonArray uris = new JsonArray();
      for (String u : trackUris) uris.add(new JsonPrimitive(u));
      AddItemsToPlaylistRequest req = spotifyPkceService.getSpotifyApi().addItemsToPlaylist(playlistId, uris).build();
      callSpotifyApi("playlist-add", req::execute);
      return "Added " + trackUris.size() + " item(s) to playlist " + playlistId + ".";
    });
  }

  public String playlistAddCurrentBySongTitle(String rawSongTitle) {
    return executeCommand("playlist-add-current", () -> {
      String songTitle = extractPlaceholderOrRaw(rawSongTitle == null ? "" : rawSongTitle);
      if (songTitle.isBlank()) {
        throw new IllegalArgumentException("playlist-add-current requires a song title.");
      }

      CurrentlyPlayingContext ctx = safeGetPlayback();
      if (ctx == null || ctx.getContext() == null || ctx.getContext().getUri() == null) {
        return "playlist-add-current failed [error_code=invalid_context]: no active playback context.";
      }
      String contextUri = ctx.getContext().getUri();
      if (!contextUri.startsWith("spotify:playlist:")) {
        return "playlist-add-current failed [error_code=invalid_context]: current context is not a playlist.";
      }
      String playlistId = contextUri.substring("spotify:playlist:".length());

      Paging<Track> page = callSpotifyApi("playlist-add-current", () -> spotifyPkceService.getSpotifyApi()
          .searchTracks(songTitle)
          .limit(10)
          .build()
          .execute());
      Track[] items = page == null ? null : page.getItems();
      if (items == null || items.length == 0) {
        return "playlist-add-current failed [error_code=no_results]: no Spotify search results for \"" + songTitle + "\".";
      }

      Track exact = null;
      String normalizedQuery = normalizeTrackTitle(songTitle);
      for (Track candidate : items) {
        String candidateName = candidate == null || candidate.getName() == null ? "" : candidate.getName();
        if (normalizeTrackTitle(candidateName).equals(normalizedQuery)) {
          exact = candidate;
          break;
        }
      }
      if (exact == null) {
        StringJoiner sj = new StringJoiner("\n");
        sj.add("playlist-add-current failed [error_code=no_exact_match]: no exact title match for \"" + songTitle + "\".");
        sj.add("Top candidates:");
        for (int i = 0; i < Math.min(items.length, 3); i++) {
          Track t = items[i];
          if (t == null) continue;
          String artist = (t.getArtists() != null && t.getArtists().length > 0 && t.getArtists()[0] != null)
              ? t.getArtists()[0].getName() : "Unknown";
          sj.add(String.format(" - %s — %s [%s]", t.getName(), artist, t.getUri()));
        }
        return sj.toString();
      }

      String uri = exact.getUri();
      JsonArray uris = new JsonArray();
      uris.add(new JsonPrimitive(uri));
      AddItemsToPlaylistRequest req = spotifyPkceService.getSpotifyApi().addItemsToPlaylist(playlistId, uris).build();
      callSpotifyApi("playlist-add-current", req::execute);
      String artist = (exact.getArtists() != null && exact.getArtists().length > 0 && exact.getArtists()[0] != null)
          ? exact.getArtists()[0].getName() : "Unknown";
      return "Added exact match \"" + exact.getName() + "\" — " + artist + " (" + uri + ") to current playlist " + playlistId + ".";
    });
  }

  // playlist-next
  public String playlistNext() {
    return executeCommand("playlist-next", () -> {
      String ensure = ensureActiveDeviceString();
      if (ensure != null) return ensure;
      callSpotifyApi("playlist-next", () -> spotifyPkceService.getSpotifyApi().skipUsersPlaybackToNextTrack().build().execute());
      return "Skipped to next.";
    });
  }

  // playlist-remove (remove current playing track if playing from a playlist)
  public String playlistRemoveCurrentIfFromPlaylist() {
    return executeCommand("playlist-remove", () -> {
      CurrentlyPlayingContext ctx = safeGetPlayback();
      if (ctx == null || ctx.getContext() == null || ctx.getContext().getUri() == null) {
        return "Nothing playing from a playlist.";
      }
      if (!(ctx.getItem() instanceof Track t)) {
        return "Current item is not a track.";
      }
      String contextUri = ctx.getContext().getUri(); // spotify:playlist:ID
      if (!contextUri.startsWith("spotify:playlist:")) {
        return "Current context is not a playlist.";
      }
      String playlistId = contextUri.substring("spotify:playlist:".length());

      JsonArray items = new JsonArray();
      JsonObject obj = new JsonObject();
      obj.addProperty("uri", t.getUri());
      items.add(obj);

      RemoveItemsFromPlaylistRequest req = spotifyPkceService.getSpotifyApi().removeItemsFromPlaylist(playlistId, items).build();
      callSpotifyApi("playlist-remove", req::execute);
      return "Removed current track from playlist " + playlistId + ".";
    });
  }

  // play [mediaOrSearch]
  // Accepts:
  //   - null/blank  -> resume current
  //   - spotify:track/album/artist/playlist URIs
  //   - "${search term}"  -> search and play best match
  //   - "search term" (no braces) -> also search and play best match
  public String play(String mediaOrSearch) {
    return executeCommand("play", () -> {
      String ensure = ensureActiveDeviceString();
      if (ensure != null) return ensure;

      StartResumeUsersPlaybackRequest.Builder b = spotifyPkceService.getSpotifyApi().startResumeUsersPlayback();

      // 1) Resume if empty
      if (mediaOrSearch == null || mediaOrSearch.isBlank()) {
        // resume current context
      }
      // 2) If it's a direct Spotify URI, play it as-is
      else if (mediaOrSearch.startsWith("spotify:track:")) {
        JsonArray uris = new JsonArray();
        uris.add(new JsonPrimitive(mediaOrSearch));
        b.uris(uris);
      } else if (mediaOrSearch.startsWith("spotify:album:")
          || mediaOrSearch.startsWith("spotify:artist:")
          || mediaOrSearch.startsWith("spotify:playlist:")) {
        b.context_uri(mediaOrSearch);
      }
      // 3) Otherwise, treat as a search (supports "${...}" or plain text)
      else {
        String q = extractPlaceholderOrRaw(mediaOrSearch);
        // Search a *short list* of candidates
        Paging<Track> page = callSpotifyApi("play", () -> spotifyPkceService.getSpotifyApi().searchTracks(q).limit(5).build().execute());
        Track[] items = page.getItems();
        if (items == null || items.length == 0) {
          return "No results found for \"" + q + "\".";
        }
        // Pick the closest match
        int bestIdx = 0;
        double bestScore = -1.0;
        for (int i = 0; i < items.length; i++) {
          double s = scoreTrackAgainstQuery(items[i], q);
          if (s > bestScore) {
            bestScore = s;
            bestIdx = i;
          }
        }
        Track best = items[bestIdx];

        // Play the best match
        JsonArray uris = new JsonArray();
        uris.add(new JsonPrimitive(best.getUri()));
        b.uris(uris);

        // Optional: include a tiny list of top candidates in the feedback
        StringBuilder picked = new StringBuilder();
        picked.append("Best match: ")
            .append(best.getName())
            .append(" — ")
            .append(best.getArtists() != null && best.getArtists().length > 0 ? best.getArtists()[0].getName() : "Unknown")
            .append(" (").append(best.getUri()).append(")\n");

        picked.append("Other candidates:\n");
        for (int i = 0, shown = 0; i < items.length && shown < 2; i++) { // show up to 2 others
          if (i == bestIdx) continue;
          Track t = items[i];
          picked.append(" - ")
              .append(t.getName()).append(" — ")
              .append(t.getArtists() != null && t.getArtists().length > 0 ? t.getArtists()[0].getName() : "Unknown")
              .append(" (").append(t.getUri()).append(")\n");
          shown++;
        }

        if (preferredDeviceId != null && !preferredDeviceId.isBlank()) b = b.device_id(preferredDeviceId);
        StartResumeUsersPlaybackRequest request = b.build();
        callSpotifyApi("play", request::execute);
        return "Playing search for \"" + q + "\".\n" + picked.toString().trim();
      }

      if (preferredDeviceId != null && !preferredDeviceId.isBlank()) b = b.device_id(preferredDeviceId);
      StartResumeUsersPlaybackRequest request = b.build();
      callSpotifyApi("play", request::execute);

      return (mediaOrSearch == null || mediaOrSearch.isBlank())
          ? "Resumed playback."
          : "Playing: " + mediaOrSearch;
    });
  }

  public String pause() {
    return executeCommand("pause", () -> {
      String ensure = ensureActiveDeviceString();
      if (ensure != null) return ensure;
      callSpotifyApi("pause", () -> spotifyPkceService.getSpotifyApi().pauseUsersPlayback().build().execute());
      return "Playback paused.";
    });
  }

  public String stop() {
    // Spotify has no hard stop; pause is closest.
    return pause();
  }

  // volume <0..150> (mapped to 0..100)
  public String volume(int level0to150) {
    return executeCommand("volume", () -> {
      String ensure = ensureActiveDeviceString();
      if (ensure != null) return ensure;

      int pct = Math.max(0, Math.min(100, (int) Math.round(level0to150 * (100.0 / 150.0))));
      SetVolumeForUsersPlaybackRequest.Builder b = spotifyPkceService.getSpotifyApi().setVolumeForUsersPlayback(pct);
      if (preferredDeviceId != null && !preferredDeviceId.isBlank()) b = b.device_id(preferredDeviceId);
      SetVolumeForUsersPlaybackRequest request = b.build();
      callSpotifyApi("volume", request::execute);

      cachedVolumeBeforeMute = (pct == 0)
          ? (cachedVolumeBeforeMute == null ? 50 : cachedVolumeBeforeMute)
          : pct;

      return "Volume set to " + pct + "%.";
    });
  }

  public String volumeUp() {
    return stepVolume(+5);
  }

  public String volumeDown() {
    return stepVolume(-5);
  }

  private String stepVolume(int delta) {
    return executeCommand(delta > 0 ? "volume-up" : "volume-down", () -> {
      String ensure = ensureActiveDeviceString();
      if (ensure != null) return ensure;

      CurrentlyPlayingContext ctx = safeGetPlayback();
      int current = (ctx != null && ctx.getDevice() != null && ctx.getDevice().getVolume_percent() != null)
          ? ctx.getDevice().getVolume_percent() : 50;
      int next = Math.max(0, Math.min(100, current + delta));
      callSpotifyApi(delta > 0 ? "volume-up" : "volume-down",
          () -> spotifyPkceService.getSpotifyApi().setVolumeForUsersPlayback(next).build().execute());
      return "Volume " + (delta > 0 ? "increased" : "decreased") + " to " + next + "%.";
    });
  }

  public String muteToggle() {
    return executeCommand("mute", () -> {
      String ensure = ensureActiveDeviceString();
      if (ensure != null) return ensure;

      CurrentlyPlayingContext ctx = safeGetPlayback();
      int current = (ctx != null && ctx.getDevice() != null && ctx.getDevice().getVolume_percent() != null)
          ? ctx.getDevice().getVolume_percent() : 50;
      if (current == 0) {
        int restore = (cachedVolumeBeforeMute == null) ? 50 : Math.max(1, Math.min(100, cachedVolumeBeforeMute));
        callSpotifyApi("mute", () -> spotifyPkceService.getSpotifyApi().setVolumeForUsersPlayback(restore).build().execute());
        return "Playback unmuted. Volume " + restore + "%.";
      } else {
        cachedVolumeBeforeMute = current;
        callSpotifyApi("mute", () -> spotifyPkceService.getSpotifyApi().setVolumeForUsersPlayback(0).build().execute());
        return "Playback muted.";
      }
    });
  }

  public String move(String timestamp) {
    return executeCommand("move", () -> {
      String ensure = ensureActiveDeviceString();
      if (ensure != null) return ensure;

      int posMs = (int) parseTimestampToMillis(timestamp);
      callSpotifyApi("move", () -> spotifyPkceService.getSpotifyApi().seekToPositionInCurrentlyPlayingTrack(posMs).build().execute());
      return "Moved to " + timestamp + ".";
    });
  }

  public String skipSeconds(int seconds) {
    return executeCommand("skip", () -> {
      String ensure = ensureActiveDeviceString();
      if (ensure != null) return ensure;

      CurrentlyPlayingContext ctx = safeGetPlayback();
      if (ctx == null || !(ctx.getItem() instanceof Track t)) return "Nothing is playing.";
      int cur = (ctx.getProgress_ms() != null) ? ctx.getProgress_ms() : 0;
      int dur = (t.getDurationMs() != null) ? t.getDurationMs() : 0;
      int next = Math.max(0, Math.min(dur == 0 ? Integer.MAX_VALUE : dur - 1, cur + seconds * 1000));
      callSpotifyApi("skip", () -> spotifyPkceService.getSpotifyApi().seekToPositionInCurrentlyPlayingTrack(next).build().execute());
      return "Skipped to " + (next / 1000) + "s.";
    });
  }

  // status / status all
  public String status(boolean all) {
    return executeCommand("status", () -> {
      CurrentlyPlayingContext c = safeGetPlayback();
      if (c == null) return "No active playback.";

      StringBuilder sb = new StringBuilder();
      String deviceName = c.getDevice() != null ? c.getDevice().getName() : "<unknown device>";
      sb.append("Device: ").append(deviceName).append("\n");
      sb.append("Playing: ").append(Boolean.TRUE.equals(c.getIs_playing())).append("\n");

      if (c.getContext() != null) {
        String ctxUri = c.getContext().getUri();
        String ctxType = (c.getContext().getType() != null) ? c.getContext().getType().getType() : null;
        sb.append("Context: ").append(ctxType).append(" (").append(ctxUri).append(")\n");
      }

      if (c.getItem() instanceof Track t) {
        String artist = (t.getArtists() != null && t.getArtists().length > 0) ? t.getArtists()[0].getName() : "Unknown";
        sb.append("Track: ").append(t.getName()).append(" — ").append(artist).append("\n");
        sb.append("URI: ").append(t.getUri()).append("\n");
        int dur = (t.getDurationMs() != null) ? t.getDurationMs() : 0;
        int pos = (c.getProgress_ms() != null) ? c.getProgress_ms() : 0;
        sb.append("Position: ").append(formatTime(pos)).append(" / ").append(formatTime(dur)).append("\n");
      }

      if (all) {
        sb.append("Shuffle: ").append(Boolean.TRUE.equals(c.getShuffle_state())).append("\n");
        sb.append("Repeat: ").append(c.getRepeat_state()).append("\n");
        if (c.getDevice() != null && c.getDevice().getVolume_percent() != null) {
          sb.append("Volume: ").append(c.getDevice().getVolume_percent()).append("%\n");
        }
      }
      return sb.toString().trim();
    });
  }

  /* ================== Spotify-specific extras ================== */

  public String events(String onOrOff, long intervalMs, EventsConfig config, PlaybackListener listener) {
    return executeCommand("events", () -> {
      if ("OFF".equalsIgnoreCase(onOrOff)) {
        if (eventsTask != null) eventsTask.cancel(true);
        if (eventsExec != null) eventsExec.shutdownNow();
        eventsTask = null;
        eventsExec = null;
        lastPlaybackSnapshot = null;
        return "Events stopped.";
      }
      if (!"ON".equalsIgnoreCase(onOrOff)) return "Use events ON|OFF.";
      if (listener == null) return "Listener required.";
      EventsConfig effectiveConfig = config == null ? EventsConfig.defaults() : config;

      if (eventsExec != null) {
        events("OFF", 0, null, null);
      }
      lastPlaybackSnapshot = null;
      eventsExec = Executors.newSingleThreadScheduledExecutor();
      eventsTask = eventsExec.scheduleAtFixedRate(() -> {
        try {
          emitPlaybackEvent(effectiveConfig, listener);
        } catch (Exception ignored) {
        }
      }, 0, Math.max(250, intervalMs), TimeUnit.MILLISECONDS);
      return "Events started. mode=" + effectiveConfig.outputMode.name().toLowerCase(Locale.ROOT)
          + " filter=" + effectiveConfig.filter.name().toLowerCase(Locale.ROOT) + ".";
    });
  }

  private void emitPlaybackEvent(EventsConfig config, PlaybackListener listener) {
    if (config.outputMode == EventOutputMode.LEGACY_TEXT) {
      String status = status(true);
      listener.onPlayback(new PlaybackEventData(
          "state_change",
          "playback_status",
          System.currentTimeMillis(),
          null,
          Map.of(),
          status,
          status == null ? "" : status.trim(),
          status
      ));
      return;
    }

    CurrentlyPlayingContext playback = safeGetPlayback();
    PlaybackSnapshot current = PlaybackSnapshot.from(playback);
    PlaybackSnapshot previous = lastPlaybackSnapshot;
    Map<String, String> delta = computeDelta(previous, current);
    if (previous != null && delta.isEmpty()) {
      return;
    }

    String kind = deriveKind(delta.keySet());
    if (!matchesFilter(kind, config.filter)) {
      lastPlaybackSnapshot = current;
      return;
    }

    String summary = buildSummary(current, delta);
    String fingerprint = kind + "|" + current.trackUri() + "|" + current.playing() + "|" + current.contextUri() + "|" + current.deviceId();
    PlaybackEventData event = new PlaybackEventData(
        "state_change",
        kind,
        System.currentTimeMillis(),
        current,
        delta,
        summary,
        fingerprint,
        status(true)
    );
    listener.onPlayback(event);
    lastPlaybackSnapshot = current;
  }

  private static Map<String, String> computeDelta(PlaybackSnapshot previous, PlaybackSnapshot current) {
    Map<String, String> delta = new LinkedHashMap<>();
    if (previous == null) {
      delta.put("initialized", "true");
      if (current.trackUri() != null) delta.put("track_uri", current.trackUri());
      delta.put("is_playing", Boolean.toString(current.playing()));
      if (current.contextUri() != null) delta.put("context_uri", current.contextUri());
      if (current.deviceId() != null) delta.put("device_id", current.deviceId());
      return delta;
    }

    addDelta(delta, "is_playing", previous.playing(), current.playing());
    addDelta(delta, "track_uri", previous.trackUri(), current.trackUri());
    addDelta(delta, "track_name", previous.trackName(), current.trackName());
    addDelta(delta, "artist_name", previous.artistName(), current.artistName());
    addDelta(delta, "context_uri", previous.contextUri(), current.contextUri());
    addDelta(delta, "context_type", previous.contextType(), current.contextType());
    addDelta(delta, "device_id", previous.deviceId(), current.deviceId());
    addDelta(delta, "device_name", previous.deviceName(), current.deviceName());
    addDelta(delta, "volume_percent", previous.volumePercent(), current.volumePercent());
    addDelta(delta, "shuffle_state", previous.shuffleState(), current.shuffleState());
    addDelta(delta, "repeat_state", previous.repeatState(), current.repeatState());
    return delta;
  }

  private static void addDelta(Map<String, String> delta, String key, Object previous, Object current) {
    if (!Objects.equals(previous, current)) {
      delta.put(key, String.valueOf(current));
    }
  }

  private static String deriveKind(Set<String> changedKeys) {
    if (changedKeys.contains("track_uri")) return "track_changed";
    if (changedKeys.contains("is_playing")) return "playback_transition";
    if (changedKeys.contains("device_id")) return "device_changed";
    if (changedKeys.contains("context_uri")) return "context_changed";
    if (changedKeys.contains("volume_percent") || changedKeys.contains("shuffle_state") || changedKeys.contains("repeat_state")) {
      return "playback_settings_changed";
    }
    return "playback_state_delta";
  }

  private static boolean matchesFilter(String kind, EventFilter filter) {
    return switch (filter) {
      case ALL -> true;
      case TRACK -> "track_changed".equals(kind);
      case PLAYBACK -> "playback_transition".equals(kind) || "playback_settings_changed".equals(kind);
      case DEVICE -> "device_changed".equals(kind);
      case CONTEXT -> "context_changed".equals(kind);
    };
  }

  private static String buildSummary(PlaybackSnapshot state, Map<String, String> delta) {
    String track = state.trackName() == null || state.trackName().isBlank() ? "Unknown track" : state.trackName();
    String artist = state.artistName() == null || state.artistName().isBlank() ? "Unknown artist" : state.artistName();
    String status = state.playing() ? "playing" : "paused";
    if (delta.isEmpty()) {
      return "Playback state unchanged.";
    }
    if (delta.containsKey("track_uri") || delta.containsKey("track_name")) {
      return "Track changed to " + track + " by " + artist + ".";
    }
    if (delta.containsKey("is_playing")) {
      return "Playback is now " + status + ".";
    }
    if (delta.containsKey("device_name")) {
      return "Active device changed to " + (state.deviceName() == null ? "unknown" : state.deviceName()) + ".";
    }
    return "Playback state updated (" + String.join(", ", delta.keySet()) + ").";
  }

  public String shuffle(boolean enabled) {
    return executeCommand("shuffle", () -> {
      String ensure = ensureActiveDeviceString();
      if (ensure != null) return ensure;

      ToggleShuffleForUsersPlaybackRequest.Builder b = spotifyPkceService.getSpotifyApi().toggleShuffleForUsersPlayback(enabled);
      if (preferredDeviceId != null && !preferredDeviceId.isBlank()) b = b.device_id(preferredDeviceId);
      ToggleShuffleForUsersPlaybackRequest request = b.build();
      callSpotifyApi("shuffle", request::execute);
      return "Shuffle " + (enabled ? "enabled." : "disabled.");
    });
  }

  public String repeat(String mode /* off|context|track */) {
    return executeCommand("repeat", () -> {
      String ensure = ensureActiveDeviceString();
      if (ensure != null) return ensure;

      SetRepeatModeOnUsersPlaybackRequest.Builder b = spotifyPkceService.getSpotifyApi().setRepeatModeOnUsersPlayback(mode);
      if (preferredDeviceId != null && !preferredDeviceId.isBlank()) b = b.device_id(preferredDeviceId);
      SetRepeatModeOnUsersPlaybackRequest request = b.build();
      callSpotifyApi("repeat", request::execute);
      return "Repeat set to: " + mode + ".";
    });
  }

  public String queueAdd(String trackOrEpisodeUri) {
    return executeCommand("queue-add", () -> {
      String ensure = ensureActiveDeviceString();
      if (ensure != null) return ensure;

      AddItemToUsersPlaybackQueueRequest.Builder b = spotifyPkceService.getSpotifyApi().addItemToUsersPlaybackQueue(trackOrEpisodeUri);
      if (preferredDeviceId != null && !preferredDeviceId.isBlank()) b = b.device_id(preferredDeviceId);
      AddItemToUsersPlaybackQueueRequest request = b.build();
      callSpotifyApi("queue-add", request::execute);
      return "Queued: " + trackOrEpisodeUri + ".";
    });
  }

  public String queueList() {
    return executeCommand("queue", () -> {
      PlaybackQueue q = callSpotifyApi("queue", () -> spotifyPkceService.getSpotifyApi().getTheUsersQueue().build().execute());
      if (q == null) {
        return "Queue unavailable.";
      }
      StringJoiner sj = new StringJoiner("\n");
      sj.add("Queue:");
      if (q.getCurrentlyPlaying() != null) {
        sj.add("Now: " + formatPlaylistItem(q.getCurrentlyPlaying()));
      }
      List<IPlaylistItem> items = q.getQueue();
      if (items == null || items.isEmpty()) {
        sj.add("Next: (empty)");
      } else {
        int max = Math.min(10, items.size());
        for (int i = 0; i < max; i++) {
          sj.add(String.format("%2d) %s", i + 1, formatPlaylistItem(items.get(i))));
        }
      }
      return sj.toString();
    });
  }

  public String previous() {
    return executeCommand("previous", () -> {
      String ensure = ensureActiveDeviceString();
      if (ensure != null) return ensure;
      callSpotifyApi("previous", () -> spotifyPkceService.getSpotifyApi().skipUsersPlaybackToPreviousTrack().build().execute());
      return "Skipped to previous track.";
    });
  }

  public String recentlyPlayed(int limit) {
    return executeCommand("recently-played", () -> {
      int capped = Math.max(1, Math.min(limit, 50));
      PagingCursorbased<PlayHistory> paging = callSpotifyApi("recently-played",
          () -> spotifyPkceService.getSpotifyApi().getCurrentUsersRecentlyPlayedTracks().limit(capped).build().execute());
      PlayHistory[] items = paging == null ? null : paging.getItems();
      if (items == null || items.length == 0) {
        return "No recently played items.";
      }
      StringJoiner sj = new StringJoiner("\n");
      sj.add("Recently played:");
      for (int i = 0; i < items.length; i++) {
        PlayHistory h = items[i];
        Track t = h.getTrack();
        if (t == null) {
          continue;
        }
        String artist = (t.getArtists() != null && t.getArtists().length > 0) ? t.getArtists()[0].getName() : "Unknown";
        String at = h.getPlayedAt() == null ? "unknown-time" : h.getPlayedAt().toInstant().toString();
        sj.add(String.format("%2d) %s — %s [%s]", i + 1, t.getName(), artist, at));
      }
      return sj.toString();
    });
  }

  public String topTracks(int limit, String timeRange) {
    return executeCommand("top-tracks", () -> {
      int capped = Math.max(1, Math.min(limit, 50));
      String tr = normalizeTimeRange(timeRange);
      Paging<Track> page = callSpotifyApi("top-tracks", () -> spotifyPkceService.getSpotifyApi().getUsersTopTracks()
          .time_range(tr)
          .limit(capped)
          .build()
          .execute());
      Track[] items = page == null ? null : page.getItems();
      if (items == null || items.length == 0) {
        return "No top tracks available.";
      }
      StringJoiner sj = new StringJoiner("\n");
      sj.add("Top tracks (" + tr + "):");
      for (int i = 0; i < items.length; i++) {
        Track t = items[i];
        String artist = (t.getArtists() != null && t.getArtists().length > 0) ? t.getArtists()[0].getName() : "Unknown";
        sj.add(String.format("%2d) %s — %s [uri=%s]", i + 1, t.getName(), artist, t.getUri()));
      }
      return sj.toString();
    });
  }

  public String topArtists(int limit, String timeRange) {
    return executeCommand("top-artists", () -> {
      int capped = Math.max(1, Math.min(limit, 50));
      String tr = normalizeTimeRange(timeRange);
      Paging<Artist> page = callSpotifyApi("top-artists", () -> spotifyPkceService.getSpotifyApi().getUsersTopArtists()
          .time_range(tr)
          .limit(capped)
          .build()
          .execute());
      Artist[] items = page == null ? null : page.getItems();
      if (items == null || items.length == 0) {
        return "No top artists available.";
      }
      StringJoiner sj = new StringJoiner("\n");
      sj.add("Top artists (" + tr + "):");
      for (int i = 0; i < items.length; i++) {
        Artist a = items[i];
        sj.add(String.format("%2d) %s [uri=%s]", i + 1, a.getName(), a.getUri()));
      }
      return sj.toString();
    });
  }

  public String playlistCreate(String name, boolean isPublic, String description) {
    return executeCommand("playlist-create", () -> {
      User me = callSpotifyApi("playlist-create", () -> spotifyPkceService.getSpotifyApi().getCurrentUsersProfile().build().execute());
      if (me == null || me.getId() == null || me.getId().isBlank()) {
        return "playlist-create failed [error_code=execution_failed]: could not resolve current user profile.";
      }
      CreatePlaylistRequest req = spotifyPkceService.getSpotifyApi()
          .createPlaylist(me.getId(), name)
          .public_(isPublic)
          .description(description == null ? "" : description)
          .build();
      Playlist playlist = callSpotifyApi("playlist-create", req::execute);
      if (playlist == null) {
        return "Created playlist: " + name + ".";
      }
      return "Created playlist: " + playlist.getName() + " [id=" + playlist.getId() + ", uri=" + playlist.getUri() + "].";
    });
  }

  public String playlistReorder(String playlistId, int rangeStart, int insertBefore, int rangeLength) {
    return executeCommand("playlist-reorder", () -> {
      if (rangeStart < 0 || insertBefore < 0) {
        throw new IllegalArgumentException("rangeStart and insertBefore must be >= 0.");
      }
      if (rangeLength <= 0 || rangeLength > 100) {
        throw new IllegalArgumentException("rangeLength must be between 1 and 100.");
      }
      ReorderPlaylistsItemsRequest req = spotifyPkceService.getSpotifyApi()
          .reorderPlaylistsItems(playlistId, rangeStart, insertBefore)
          .range_length(rangeLength)
          .build();
      callSpotifyApi("playlist-reorder", req::execute);
      return "Reordered playlist " + playlistId + " (rangeStart=" + rangeStart + ", insertBefore=" + insertBefore + ", rangeLength=" + rangeLength + ").";
    });
  }

  public String playlistRemovePosition(String playlistId, int position) {
    return executeCommand("playlist-remove-pos", () -> {
      if (position < 0) {
        throw new IllegalArgumentException("position must be >= 0.");
      }
      GetPlaylistsItemsRequest get = spotifyPkceService.getSpotifyApi()
          .getPlaylistsItems(playlistId)
          .offset(position)
          .limit(1)
          .build();
      Paging<PlaylistTrack> page = callSpotifyApi("playlist-remove-pos", get::execute);
      PlaylistTrack[] items = page == null ? null : page.getItems();
      if (items == null || items.length == 0 || !(items[0].getTrack() instanceof Track t)) {
        return "No playlist item found at position " + position + ".";
      }
      JsonArray payload = new JsonArray();
      JsonObject target = new JsonObject();
      target.addProperty("uri", t.getUri());
      JsonArray positions = new JsonArray();
      positions.add(new JsonPrimitive(position));
      target.add("positions", positions);
      payload.add(target);
      RemoveItemsFromPlaylistRequest remove = spotifyPkceService.getSpotifyApi().removeItemsFromPlaylist(playlistId, payload).build();
      callSpotifyApi("playlist-remove-pos", remove::execute);
      return "Removed track at position " + position + " from playlist " + playlistId + ".";
    });
  }

  public String recommendByTrackSeed(String seed, int limit) {
    return executeCommand("recommend", () -> {
      int capped = Math.max(1, Math.min(limit, 20));
      String seedTrackId = resolveSeedTrackId(seed);
      if (seedTrackId == null || seedTrackId.isBlank()) {
        return "recommend failed [error_code=invalid_arguments]: could not resolve a seed track from input.";
      }
      Recommendations rec = callSpotifyApi("recommend", () -> spotifyPkceService.getSpotifyApi().getRecommendations()
          .seed_tracks(seedTrackId)
          .limit(capped)
          .build()
          .execute());
      Track[] tracks = rec == null ? null : rec.getTracks();
      if (tracks == null || tracks.length == 0) {
        return "No recommendations available.";
      }
      StringJoiner sj = new StringJoiner("\n");
      sj.add("Recommendations:");
      for (int i = 0; i < tracks.length; i++) {
        Track t = tracks[i];
        String artist = (t.getArtists() != null && t.getArtists().length > 0) ? t.getArtists()[0].getName() : "Unknown";
        sj.add(String.format("%2d) %s — %s [uri=%s]", i + 1, t.getName(), artist, t.getUri()));
      }
      return sj.toString();
    });
  }

  public String suggestByTheme(String rawTheme, int limit) {
    return executeCommand("suggest", () -> {
      int capped = Math.max(1, Math.min(limit, 12));
      String theme = extractPlaceholderOrRaw(rawTheme == null ? "" : rawTheme).trim();
      if (theme.isBlank()) {
        throw new IllegalArgumentException("suggest requires a non-empty theme/query.");
      }

      // Build a few focused query variants to keep results relevant while resilient.
      List<String> queries = List.of(
          theme,
          theme + " upbeat",
          theme + " party"
      );

      Map<String, Track> bestByUri = new LinkedHashMap<>();
      Map<String, Double> scoreByUri = new LinkedHashMap<>();

      for (String q : queries) {
        Paging<Track> page = callSpotifyApi("suggest", () -> spotifyPkceService.getSpotifyApi()
            .searchTracks(q)
            .limit(Math.max(5, Math.min(capped * 2, 20)))
            .build()
            .execute());
        Track[] items = page == null ? null : page.getItems();
        if (items == null) {
          continue;
        }
        for (Track track : items) {
          if (track == null || track.getUri() == null || track.getUri().isBlank()) {
            continue;
          }
          String uri = track.getUri();
          double score = scoreTrackAgainstQuery(track, theme);
          if (!scoreByUri.containsKey(uri) || score > scoreByUri.get(uri)) {
            scoreByUri.put(uri, score);
            bestByUri.put(uri, track);
          }
        }
      }

      if (bestByUri.isEmpty()) {
        return "No suggestions found for \"" + theme + "\".";
      }

      List<Map.Entry<String, Track>> ranked = bestByUri.entrySet().stream()
          .sorted((a, b) -> Double.compare(
              scoreByUri.getOrDefault(b.getKey(), 0.0),
              scoreByUri.getOrDefault(a.getKey(), 0.0)))
          .limit(capped)
          .toList();

      StringJoiner sj = new StringJoiner("\n");
      sj.add("Suggestions for \"" + theme + "\":");
      for (int i = 0; i < ranked.size(); i++) {
        Track t = ranked.get(i).getValue();
        String artist = (t.getArtists() != null && t.getArtists().length > 0)
            ? t.getArtists()[0].getName() : "Unknown";
        sj.add(String.format("%2d) %s — %s [uri=%s]",
            i + 1, t.getName(), artist, t.getUri()));
      }
      return sj.toString();
    });
  }

  public String devices() {
    return executeCommand("devices", () -> {
      Device[] ds = callSpotifyApi("devices", () -> spotifyPkceService.getSpotifyApi().getUsersAvailableDevices().build().execute());
      if (ds == null || ds.length == 0) return "No devices online.";
      StringJoiner sj = new StringJoiner("\n");
      sj.add("Devices:");
      for (Device d : ds) {
        sj.add(String.format("- %s  id=%s  active=%s  type=%s  volume=%s%%",
            d.getName(), d.getId(), d.getIs_active(), d.getType(),
            d.getVolume_percent() == null ? "?" : d.getVolume_percent().toString()));
      }
      return sj.toString();
    });
  }

  public String selectDevice(String deviceId) {
    return executeCommand("select-device", () -> {
      JsonArray ids = new JsonArray();
      ids.add(new JsonPrimitive(deviceId));
      TransferUsersPlaybackRequest req = spotifyPkceService.getSpotifyApi().transferUsersPlayback(ids).build();
      callSpotifyApi("select-device", req::execute);
      return "Transferred playback to device " + deviceId + ".";
    });
  }

  public String likeCurrentTrack() {
    return executeCommand("like", () -> {
      CurrentlyPlayingContext c = safeGetPlayback();
      if (c == null || !(c.getItem() instanceof Track t)) return "Nothing playing.";
      SaveTracksForUserRequest req = spotifyPkceService.getSpotifyApi().saveTracksForUser(new String[]{t.getId()}).build();
      callSpotifyApi("like", req::execute);
      return "Saved to your library: " + t.getName() + ".";
    });
  }

  /* ======================== Helpers ======================== */

  public String playContextWithOffset(String contextUri, int index) {
    return executeCommand("play-context", () -> {
      String ensure = ensureActiveDeviceString();
      if (ensure != null) return ensure;

      StartResumeUsersPlaybackRequest.Builder b = spotifyPkceService.getSpotifyApi().startResumeUsersPlayback().context_uri(contextUri);
      JsonObject offset = new JsonObject();
      offset.addProperty("position", Math.max(0, index));
      b.offset(offset);
      StartResumeUsersPlaybackRequest request = b.build();
      callSpotifyApi("play-context", request::execute);
      return "Playing context " + contextUri + " at index " + index + ".";
    });
  }

  public String playlistPlay(String playlistIdOrUri, int index) {
    return executeCommand("playlist-play", () -> {
      String raw = extractPlaceholderOrRaw(playlistIdOrUri == null ? "" : playlistIdOrUri);
      if (raw.isBlank()) {
        throw new IllegalArgumentException("playlistId is required.");
      }
      String contextUri;
      if (raw.startsWith("spotify:playlist:")) {
        contextUri = raw;
      } else {
        contextUri = "spotify:playlist:" + raw;
      }
      return playContextWithOffset(contextUri, index)
          .replaceFirst("^play-context", "playlist-play");
    });
  }

  private String ensureActiveDeviceString() {
    try {
      ensureActiveDevice();
      return null;
    } catch (Exception e) {
      return e.getMessage() == null ? "No active device." : e.getMessage();
    }
  }

  private void ensureActiveDevice() throws IOException, SpotifyWebApiException, org.apache.hc.core5.http.ParseException {
    Device[] ds;
    try {
      ds = callSpotifyApi("device-ensure", () -> spotifyPkceService.getSpotifyApi().getUsersAvailableDevices().build().execute());
    } catch (Exception e) {
      if (e instanceof IOException io) {
        throw io;
      }
      if (e instanceof SpotifyWebApiException swe) {
        throw swe;
      }
      throw new RuntimeException(e);
    }
    if (ds == null || ds.length == 0) {
      throw new IllegalStateException("No devices online. Open a Spotify app and try again.");
    }
    // If preferred device is set, ensure it is active
    if (preferredDeviceId != null && !preferredDeviceId.isBlank()) {
      boolean found = false, active = false;
      for (Device d : ds) {
        if (preferredDeviceId.equals(d.getId())) {
          found = true;
          active = Boolean.TRUE.equals(d.getIs_active());
          break;
        }
      }
      if (!found) throw new IllegalStateException("Preferred device not found. Use 'devices' to list.");
      if (!active) {
        JsonArray ids = new JsonArray();
        ids.add(new JsonPrimitive(preferredDeviceId));
        TransferUsersPlaybackRequest req = spotifyPkceService.getSpotifyApi().transferUsersPlayback(ids).build();
        try {
          callSpotifyApi("device-ensure", req::execute);
        } catch (Exception e) {
          if (e instanceof IOException io) {
            throw io;
          }
          if (e instanceof SpotifyWebApiException swe) {
            throw swe;
          }
          throw new RuntimeException(e);
        }
      }
      return;
    }
    // Otherwise, if none active, activate first
    for (Device d : ds) if (Boolean.TRUE.equals(d.getIs_active())) return;
    JsonArray ids = new JsonArray();
    ids.add(new JsonPrimitive(ds[0].getId()));
    TransferUsersPlaybackRequest req = spotifyPkceService.getSpotifyApi().transferUsersPlayback(ids).build();
    try {
      callSpotifyApi("device-ensure", req::execute);
    } catch (Exception e) {
      if (e instanceof IOException io) {
        throw io;
      }
      if (e instanceof SpotifyWebApiException swe) {
        throw swe;
      }
      throw new RuntimeException(e);
    }
  }

  private CurrentlyPlayingContext safeGetPlayback() {
    try {
      return callSpotifyApi("status", () -> spotifyPkceService.getSpotifyApi().getInformationAboutUsersCurrentPlayback().build().execute());
    } catch (Exception e) {
      return null;
    }
  }

  public String metricsReport() {
    StringJoiner sj = new StringJoiner("\n");
    sj.add("Spotify command metrics:");
    if (commandStats.isEmpty()) {
      sj.add("- no metrics yet");
      return sj.toString();
    }
    commandStats.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(e -> {
          CommandStats s = e.getValue();
          long total = s.total.get();
          long success = s.success.get();
          long failures = s.failures.get();
          long rateLimited = s.rateLimited.get();
          long avgMs = total == 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(s.totalLatencyNanos.get() / total);
          sj.add("- " + e.getKey()
              + " total=" + total
              + " success=" + success
              + " failures=" + failures
              + " rateLimited=" + rateLimited
              + " avgMs=" + avgMs);
        });
    return sj.toString();
  }

  public interface PlaybackListener {
    void onPlayback(PlaybackEventData event);
  }

  private static String normalizeTimeRange(String raw) {
    String tr = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    if (tr.isEmpty()) {
      return "medium_term";
    }
    return switch (tr) {
      case "short", "short_term" -> "short_term";
      case "medium", "medium_term" -> "medium_term";
      case "long", "long_term" -> "long_term";
      default -> throw new IllegalArgumentException("timeRange must be short_term, medium_term, or long_term.");
    };
  }

  private static String normalizeTrackTitle(String value) {
    if (value == null) {
      return "";
    }
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9 ]", " ")
        .replaceAll("\\s+", " ")
        .trim();
    return normalized;
  }

  private String resolveSeedTrackId(String seedInput) throws Exception {
    String seed = extractPlaceholderOrRaw(seedInput == null ? "" : seedInput);
    if (seed.startsWith("spotify:track:")) {
      return seed.substring("spotify:track:".length());
    }
    if (seed.matches("^[A-Za-z0-9]{22}$")) {
      return seed;
    }
    Track[] hits = callSpotifyApi("recommend", () -> spotifyPkceService.getSpotifyApi()
        .searchTracks(seed)
        .limit(1)
        .build()
        .execute()
        .getItems());
    if (hits == null || hits.length == 0) {
      return null;
    }
    return hits[0].getId();
  }

  private static String formatPlaylistItem(IPlaylistItem item) {
    if (item instanceof Track t) {
      String artist = (t.getArtists() != null && t.getArtists().length > 0) ? t.getArtists()[0].getName() : "Unknown";
      return t.getName() + " — " + artist + " [uri=" + t.getUri() + "]";
    }
    if (item instanceof Episode e) {
      return e.getName() + " [uri=" + e.getUri() + "]";
    }
    return item == null ? "(null)" : item.toString();
  }

  public enum EventOutputMode {
    DELTA_TYPED,
    LEGACY_TEXT
  }

  public enum EventFilter {
    ALL,
    TRACK,
    PLAYBACK,
    DEVICE,
    CONTEXT
  }

  public record EventsConfig(EventOutputMode outputMode, EventFilter filter) {
    public static EventsConfig defaults() {
      return new EventsConfig(EventOutputMode.DELTA_TYPED, EventFilter.ALL);
    }
  }

  public record PlaybackEventData(String eventType,
                                  String kind,
                                  long atMs,
                                  PlaybackSnapshot state,
                                  Map<String, String> delta,
                                  String summary,
                                  String fingerprint,
                                  String legacyStatus) {
  }

  public record PlaybackSnapshot(boolean playing,
                                 String trackUri,
                                 String trackName,
                                 String artistName,
                                 String contextUri,
                                 String contextType,
                                 String deviceId,
                                 String deviceName,
                                 Integer volumePercent,
                                 Boolean shuffleState,
                                 String repeatState,
                                 Integer progressMs,
                                 Integer durationMs) {
    static PlaybackSnapshot from(CurrentlyPlayingContext c) {
      if (c == null) {
        return new PlaybackSnapshot(false, null, null, null, null, null, null, null, null, null, null, null, null);
      }
      String trackUri = null;
      String trackName = null;
      String artistName = null;
      Integer durationMs = null;
      if (c.getItem() instanceof Track t) {
        trackUri = t.getUri();
        trackName = t.getName();
        artistName = (t.getArtists() != null && t.getArtists().length > 0) ? t.getArtists()[0].getName() : null;
        durationMs = t.getDurationMs();
      }
      String contextUri = c.getContext() != null ? c.getContext().getUri() : null;
      String contextType = (c.getContext() != null && c.getContext().getType() != null) ? c.getContext().getType().getType() : null;
      String deviceId = c.getDevice() != null ? c.getDevice().getId() : null;
      String deviceName = c.getDevice() != null ? c.getDevice().getName() : null;
      Integer volumePercent = c.getDevice() != null ? c.getDevice().getVolume_percent() : null;
      return new PlaybackSnapshot(
          Boolean.TRUE.equals(c.getIs_playing()),
          trackUri,
          trackName,
          artistName,
          contextUri,
          contextType,
          deviceId,
          deviceName,
          volumePercent,
          c.getShuffle_state(),
          c.getRepeat_state(),
          c.getProgress_ms(),
          durationMs
      );
    }
  }

  private static class CommandStats {
    final AtomicLong total = new AtomicLong();
    final AtomicLong success = new AtomicLong();
    final AtomicLong failures = new AtomicLong();
    final AtomicLong rateLimited = new AtomicLong();
    final AtomicLong totalLatencyNanos = new AtomicLong();
  }

  private record ServiceError(String errorCode, String message) {
  }
}
