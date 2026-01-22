package com.djmusic.vlc.base;

import org.w3c.dom.Document;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.base.SynchronisedOneShotMediaPlayerEventListener;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChannelManager {

  /* ────────────────────── CORE OBJECTS ──────────────────────────── */
  private final MediaPlayerFactory factory;
  private final MediaPlayer mainChannel;           // immutable instances
  private final Map<MediaPlayer, Thread> activeFades = new HashMap<>();
  /* ────────────────────── PLAYLIST & STATE ──────────────────────── */
  private final List<String> playlist = new ArrayList<>();
  /* ──────────────────── CONFIGURABLE TIMINGS ────────────────────── */
  private volatile int overlapDurationMs = 5_000;
  private volatile int fadeOutDurationMs = 2_000;
  private volatile int fadeOutOffsetMs = 0;
  private volatile int fadeInDurationMs = 2_000;
  private volatile int fadeInOffsetMs = 0;
  private int playlistIndex = 0;
  private volatile boolean autoPlayEnabled = true;

  private MediaPlayerEventAdapter mediaPlayerEventAdapter = new SynchronisedOneShotMediaPlayerEventListener(new CountDownLatch(3)) {
    ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void error(MediaPlayer mediaPlayer) {
      super.error(mediaPlayer);
    }

    @Override
    public void playing(MediaPlayer mp) {
      log(mp.userData().toString(), "playing");
    }

    @Override
    public void paused(MediaPlayer mp) {
      log(mp.userData().toString(), "paused");
    }

    @Override
    public void stopped(MediaPlayer mp) {
      log(mp.userData().toString(), "stopped");
    }

    @Override
    public void finished(MediaPlayer mp) {
      log(mp.userData().toString(), "finished");
      if (autoPlayEnabled) {
        executor.execute(() -> {
          playNextWithTransition();
        });
      }


    }

    @Override
    public void timeChanged(MediaPlayer mp, long t) {
      //maybeAutoStartNext(t, mp);
      //System.out.printf("[Chan %s] time changed: %d%n", mp.userData().toString(), t);
    }

    @Override
    public void volumeChanged(MediaPlayer mp, float newVolume) {
      //System.out.printf("[Chan %s] volume changed: %.2f%n", mp.userData().toString(), newVolume);
    }
  };

  /* ──────────────────────── CONSTRUCTOR ─────────────────────────── */
  public ChannelManager() {
    factory = new MediaPlayerFactory();
    mainChannel = createPlayer("A");
  }

  private static void log(String tag, String msg) {
    System.out.printf("[Chan %s] %s%n", tag, msg);
  }

  private static String escapeXml(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;");
  }

  /* ─────────────────────── PUBLIC SETTERS ───────────────────────── */
  public void setOverlapDuration(int ms) {
    overlapDurationMs = ms;
  }

  public void setFadeDurations(int outMs, int inMs) {
    fadeOutDurationMs = outMs;
    fadeInDurationMs = inMs;
  }

  /* ─────────────────── PRESET CONFIGURATIONS ────────────────────── */

  public void setFadeOffsets(int outOffset, int inOffset) {
    fadeOutOffsetMs = outOffset;
    fadeInOffsetMs = inOffset;
  }

  public void setAutoPlayEnabled(boolean enabled) {
    autoPlayEnabled = enabled;
  }

  /**
   * Classic cross-fade: fades fully overlap, no silence gaps.
   */
  public void presetSeamless(int overlapMs) {
    overlapDurationMs = overlapMs;
    fadeOutDurationMs = fadeInDurationMs = overlapMs;
    fadeOutOffsetMs = fadeInOffsetMs = 0;
  }

  /**
   * Fade-out ends, a gap follows, then fade-in begins.
   */
  public void presetGapAfterMain(int overlapMs, int fadeOutMs,
                                 int gapMs, int fadeInMs) {
    overlapDurationMs = overlapMs;
    fadeOutDurationMs = fadeOutMs;
    fadeInDurationMs = fadeInMs;
    fadeOutOffsetMs = 0;
    fadeInOffsetMs = fadeOutMs + gapMs;        // starts after gap
  }

  /**
   * Fade-in starts immediately, fade-out starts late (over-lapping tails).
   */
  public void presetStaggered(int overlapMs,
                              int fadeOutMs, int fadeInMs,
                              int fadeOutStartsBeforeEndMs) {
    overlapDurationMs = overlapMs;
    fadeOutDurationMs = fadeOutMs;
    fadeInDurationMs = fadeInMs;
    fadeInOffsetMs = 0;
    fadeOutOffsetMs = Math.max(0, overlapMs - fadeOutStartsBeforeEndMs - fadeOutMs);
  }

  /* ────────────────────── PLAYLIST METHODS ──────────────────────── */
  public Integer addToPlaylist(String uri) {
    playlist.add(uri);
    return playlist.size() - 1;
  }

  public List<String> getPlaylist() {
    return new ArrayList<>(playlist);
  }

  public int getPlaylistIndex() {
    return playlistIndex;
  }

  /* ──────────────────── MANUAL PLAY/ADVANCE ─────────────────────── */
  public String playFirst() {
    playlistIndex = 0;
    return playNext();                 // kicks things off
  }

  public String playNext() {
    return playNextWithTransition();
  }

  /* ───────────────────── CURRENT CHANNEL MANAGEMENT   ───────────── */

  public String remove() {
    String mediaToRemove = playlist.get(playlistIndex);
    int itemToRemove = playlistIndex;
    playlist.remove(itemToRemove);
    if (!playlist.isEmpty()) {
      if (playlistIndex >= playlist.size()) {
        playlistIndex = 0;
      }
      playAtIndex(playlistIndex);
    } else {
      mainChannel.controls().stop();
    }
    return mediaToRemove;
  }

  /**
   * Play the specified playlist entry (with or without transition).
   */
  public String playAtIndex(int index) {
    if (index < 0 || index >= playlist.size()) return "";
    playlistIndex = index;
    return startTrackAtIndex(index);
  }

  public MediaPlayer getCurrentChannel() {
    return mainChannel;
  }

  /* ───────────────────── IMPORT / EXPORT (M3U / XSPF) ───────────── */
  public String importPlaylist(File file) {
    try {
      String n = file.getName().toLowerCase();
      if (n.endsWith(".m3u") || n.endsWith(".m3u8")) return parseM3U(file);
      if (n.endsWith(".xspf")) return parseXSPF(file);
      return "Unsupported playlist format.";
    } catch (Exception e) {
      return "Import failed: " + e.getMessage();
    }
  }

  public String exportToM3U(File file) {
    return writeM3U(file);
  }

  public String exportToXSPF(File file) {
    return writeXSPF(file);
  }

  public String saveState(File f) {
    try (ObjectOutputStream o = new ObjectOutputStream(new FileOutputStream(f))) {
      o.writeObject(new PlayerState("A",
          playlist, playlistIndex,
          mainChannel.status().time()));
      return "State saved.";
    } catch (IOException e) {
      return "Save failed: " + e.getMessage();
    }
  }

  @SuppressWarnings("unchecked")
  public String loadState(File f) {
    try (ObjectInputStream i = new ObjectInputStream(new FileInputStream(f))) {
      PlayerState s = (PlayerState) i.readObject();
      //mediaChannels.getMain()      = s.chan.equals("A") ? channelA : channelB;
      //mediaChannels.getSecondary() = mediaChannels.getMain() == channelA ? channelB : channelA;
      playlist.clear();
      playlist.addAll(s.pl());
      playlistIndex = s.idx();
      playFirst();
      mainChannel.controls().setTime(s.pos());
      return "State restored.";
    } catch (Exception e) {
      return "Load failed: " + e.getMessage();
    }
  }

  /* ───────────────────────── AUTO-PLAY LOGIC ─────────────────────── */
  private void maybeAutoStartNext(long elapsedMs, MediaPlayer mp) {
    if (!autoPlayEnabled) return;

    long duration = mp.media().info().duration();
    if (duration <= 0) return;

    long remaining = duration - elapsedMs;
    long leadTime = Math.max(overlapDurationMs,
        Math.max(fadeOutOffsetMs + fadeOutDurationMs,
            fadeInOffsetMs + fadeInDurationMs));

    if (remaining > 0 && remaining <= leadTime) {
      playNextWithTransition();  // safe due to internal guard
    }
  }

  /* ────────────────── CROSSFADE / TRANSITION ENGINE ─────────────── */
  private String playNextWithTransition() {
    // Hard coded permanent loop through the list
    if ((playlistIndex + 1) >= playlist.size()) {
      playlistIndex = 0;
    } else {
      playlistIndex++;
    }
    return startTrackAtIndex(playlistIndex);
  }

  private String startTrackAtIndex(int index) {
    if (index >= playlist.size()) return "";

    String nextUri = playlist.get(index);
    System.out.printf("[Transition] Playing index %d → %s%n", index, nextUri);

    // Direct replace
    mainChannel.media().play(nextUri);

    return nextUri;
  }

  /* ──────────────────── PLAYER CREATION & EVENTS ─────────────────── */
  private MediaPlayer createPlayer(String tag) {
    MediaPlayer p = factory.mediaPlayers().newMediaPlayer();
    p.events().addMediaPlayerEventListener(mediaPlayerEventAdapter);
    p.userData(tag);

    return p;
  }

  /* ───────────────────────── UNLOAD / CLEANUP ─────────────────────── */
  public void unload() {
    mainChannel.controls().stop();
    mainChannel.release();
    factory.release();
  }

  /* ──────────────────────── PLAYLIST I/O UTILS ───────────────────── */
  private String parseM3U(File f) throws IOException {
    int c = 0;
    try (BufferedReader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
      for (String l; (l = r.readLine()) != null; )
        if (!l.trim().isEmpty() && !l.trim().startsWith("#")) {
          playlist.add(l.trim());
          c++;
        }
    }
    return c + " item(s) imported.";
  }

  private String parseXSPF(File f) throws Exception {
    int c = 0;
    Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f);
    var tracks = doc.getElementsByTagName("location");
    for (int i = 0; i < tracks.getLength(); i++) {
      String uri = tracks.item(i).getTextContent().trim();
      if (!uri.isEmpty()) {
        playlist.add(uri);
        c++;
      }
    }
    return c + " item(s) imported.";
  }

  private String writeM3U(File f) {
    try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
      w.write("#EXTM3U\n");
      for (String p : playlist) {
        w.write(p);
        w.write("\n");
      }
      return "Exported " + playlist.size() + " item(s).";
    } catch (IOException e) {
      return "Export failed: " + e.getMessage();
    }
  }

  private String writeXSPF(File f) {
    try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
      w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<playlist version=\"1\" xmlns=\"http://xspf.org/ns/0/\">\n  <trackList>\n");
      for (String p : playlist) {
        w.write("    <track>\n      <location>" + escapeXml(p) + "</location>\n    </track>\n");
      }
      w.write("  </trackList>\n</playlist>\n");
      return "Exported " + playlist.size() + " item(s).";
    } catch (IOException e) {
      return "Export failed: " + e.getMessage();
    }
  }

  /* ───────────────────── STATE SAVE / RESTORE ───────────────────── */
  private record PlayerState(String chan, List<String> pl, int idx, long pos) implements Serializable {
  }
}
