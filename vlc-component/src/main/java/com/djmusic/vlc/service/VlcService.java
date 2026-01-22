package com.djmusic.vlc.service;

import com.djmusic.vlc.base.ChannelManager;
import com.djmusic.vlc.media.MediaIndexer;
import com.djmusic.vlc.util.PlaylistFormatter;
import com.social100.todero.processor.EventDefinition;
import com.social100.todero.util.PlaceholderUtils;
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.media.Meta;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.State;

import java.io.File;
import java.util.List;

public class VlcService {
  public final static String MAIN_GROUP = "Main";
  public final static String PLAYLIST_GROUP = "Playlist";
  private static final String[] mediaOptions = {
      ":audio-filter=normvol",
      ":norm-buff-size=20",  // Buffer size for normalization
      ":norm-max-level=1.0"  // Maximum level for normalized audio
  };
  private static final String[] mediaOptions2 = {
      ":audio-filter=compressor",
      ":compressor-rms-peak=0",
      ":compressor-attack=10",
      ":compressor-release=100",
      ":compressor-threshold=-20",
      ":compressor-ratio=4",
      ":compressor-knee=0.5",
      ":compressor-makeup-gain=5"
  };
  private final MediaIndexer mediaIndexer;
  private final ChannelManager channelManager;

  public VlcService(String vlcMediaDirectory) {
    if (RuntimeUtil.isMac()) {
      System.setProperty("jna.library.path", "/Applications/VLC.app/Contents/MacOS/lib/");
      if (loadVLC()) {
        System.out.println("loadVLC success");
      } else {
        System.out.println("loadVLC failure");
      }
    } else if (RuntimeUtil.isWindows()) {
      System.setProperty("jna.library.path", "C:\\Program Files\\VideoLAN\\VLC");
    } else if (RuntimeUtil.isNix()) {
      System.out.println("what?  jna.library.path for linux?");
    }

    channelManager = new ChannelManager();
    channelManager.presetSeamless(10000);
    vlcMediaDirectory = expandHomeDir(vlcMediaDirectory);
    mediaIndexer = new MediaIndexer(vlcMediaDirectory);
  }

  private static String val(String val) {
    return val != null ? val : "<not set>";
  }

  public static String expandHomeDir(String path) {
    if (path == null) return null;
    if (path.startsWith("~" + File.separator) || path.equals("~")) {
      String userHome = System.getProperty("user.home");
      return path.replaceFirst("^~", userHome);
    }
    return path;
  }

  private static boolean loadVLC() {
    return new NativeDiscovery().discover();

//        try {
//            String libvlcCorePath = "/Applications/VLC.app/Contents/MacOS/lib/libvlccore.dylib";
//            String libvlcPath = "/Applications/VLC.app/Contents/MacOS/lib/libvlc.dylib";
//
//            // Print the paths to confirm
//            System.out.println("Checking for libvlc at: " + libvlcPath);
//            System.out.println("Checking for libvlccore at: " + libvlcCorePath);
//
//            // Check if the files exist
//            if (!new java.io.File(libvlcCorePath).exists()) {
//                System.out.println("libvlccore.dylib not found!");
//                return false;
//            }
//            if (!new java.io.File(libvlcPath).exists()) {
//                System.out.println("libvlc.dylib not found!");
//                return false;
//            }
//
//            System.load(libvlcCorePath);
//            System.load(libvlcPath);
//            System.out.println("VLC libraries loaded successfully.");
//            return true;
//        } catch (UnsatisfiedLinkError e) {
//            System.out.println("Failed to load VLC libraries: " + e.getMessage());
//            return false;
//        }
  }

  public String moveCommand(String moveTo) {
    MediaPlayer mediaPlayer = channelManager.getCurrentChannel();
    try {
      long moveToTime = parseTime(moveTo);  // Parse the time string
      mediaPlayer.controls().setTime(moveToTime);
      return "Playback moved to " + moveTo + ".";
    } catch (IllegalArgumentException e) {
      return e.getMessage();
    } catch (Exception e) {
      return "Failed to move playback due to an unexpected error.";
    }
  }

  public String muteCommand() {
    MediaPlayer mediaPlayer = channelManager.getCurrentChannel();

    // Ensure media is present and valid
    if (!mediaPlayer.media().isValid()) {
      return "No valid media loaded. Mute operation is not available.";
    }

    // Check the current mute state to predict the toggle outcome
    boolean wasMute = mediaPlayer.audio().isMute();

    // Toggle the mute state
    mediaPlayer.audio().mute();

    // Feedback based on the expected outcome, not the immediate check
    return wasMute ? "Playback has been unmuted." : "Playback has been muted.";
  }

  public String pauseCommand() {
    MediaPlayer mediaPlayer = channelManager.getCurrentChannel();
    State state = mediaPlayer.status().state();

    // If the player is currently playing, it will be paused
    if (state == State.PLAYING) {
      mediaPlayer.controls().pause();
      return "Playback paused.";
    }
    // If the player is already paused, it might be intended to resume playback
    else if (state == State.PAUSED) {
      mediaPlayer.controls().play();
      return "Playback resumed.";
    } else {
      return "Playback is not active. Current state: " + state;
    }
  }

  public String playCommand(String mediaPathToPlay) {
    MediaPlayer mediaPlayer = channelManager.getCurrentChannel();
    String currentMediaPath = mediaPlayer.media().info() != null ? mediaPlayer.media().info().mrl() : null;
    if (!mediaPathToPlay.isEmpty()) {
      mediaPathToPlay = processSearchMedia(mediaPathToPlay);
      if (!mediaPathToPlay.equals(currentMediaPath)) {
        Integer index = verifyAndAddToPlaylist(mediaPathToPlay);
        mediaPathToPlay = channelManager.playAtIndex(index);
        return "Playing new media: \"" + mediaPathToPlay + "\"";
      } else {
        mediaPlayer.controls().play();
        return "Resuming current media.";
      }
    } else if (mediaPlayer.media().isValid()) {
      mediaPlayer.controls().play();
      return "Resuming playback of current media.";
    } else {
      return "No media file specified and no current media to play.";
    }
  }

  private String processSearchMedia(String mediaPathToPlay) {
    String search = PlaceholderUtils.extractPlaceholder(mediaPathToPlay);
    System.out.println(mediaPathToPlay);
    System.out.println(search);

    if (search != null && !search.isEmpty()) {
      List<String> found = mediaIndexer.search(search);
      System.out.println("----------------------");
      for (String item : found) {
        System.out.println(item);
      }
      System.out.println("----------------------");

      String mediaFile = !found.isEmpty() ? found.get(0) : "";
      String quotedMedia = !mediaFile.isEmpty() ? mediaFile : "";
      System.out.println("Selected: " + quotedMedia);

      if (!mediaFile.isEmpty()) {
        mediaPathToPlay = PlaceholderUtils.replacePlaceholder(mediaPathToPlay, quotedMedia);
      }
    }
    return mediaPathToPlay;
  }

  public String skipCommand(long skipTime) { // Positive for forward, negative for backward
    MediaPlayer mediaPlayer = channelManager.getCurrentChannel();
    long mediaLength = mediaPlayer.status().length();

    try {
      long currentTime = mediaPlayer.status().time();
      long newTime = currentTime + skipTime * 1000;

      // Ensure new time is within media bounds
      newTime = Math.max(newTime, 0);  // Prevent going before the start
      newTime = Math.min(newTime, mediaLength);  // Prevent going beyond the end

      mediaPlayer.controls().setTime(newTime);
      return String.format("Skipped to %d seconds (%s).", newTime / 1000, formatTime(newTime));
    } catch (NumberFormatException e) {
      return "Error: Invalid skip time format. Please specify the number of seconds as a numeric value.";
    }
  }

  public String statusCommand(Boolean all) {
    StringBuilder statusBuilder = new StringBuilder();
    MediaPlayer mediaPlayer = channelManager.getCurrentChannel();

    if (mediaPlayer.media().isValid()) {
      if (all) {
        // Handle "status all" command
        for (Meta meta : Meta.values()) {
          String value = mediaPlayer.media().meta().get(meta);
          if (value != null && !value.isEmpty()) {
            statusBuilder.append(meta.name()).append(": ").append(value).append("\n");
          }
        }
      } else {
        // Handle regular "status" command
        String title = mediaPlayer.media().meta().get(Meta.TITLE);
        String mediaName = mediaPlayer.media().info().mrl(); // Gets the MRL
        statusBuilder.append("Media Name: ").append(title != null && !title.isEmpty() ? title : mediaName).append("\n");

        long durationMs = mediaPlayer.status().length();
        String duration = formatTime(durationMs);
        statusBuilder.append("Duration: ").append(duration).append("\n");
      }

      // Additional media status information
      String mediaPath = mediaPlayer.media().info().mrl();
      String mediaState = mediaPlayer.status().state().toString();
      String currentTime = formatTime(mediaPlayer.status().time());
      int volume = mediaPlayer.audio().volume();
      boolean isMute = mediaPlayer.audio().isMute();

      statusBuilder.append("Media Path: ").append(mediaPath).append("\n")
          .append("Media State: ").append(mediaState).append("\n")
          .append("Current Time: ").append(currentTime).append("\n")
          .append("Volume: ").append(volume).append("\n")
          .append("Mute: ").append(isMute).append("\n");
    } else {
      statusBuilder.append("No valid media loaded.");
    }

    return statusBuilder.toString();
  }

  public String stopCommand() {
    MediaPlayer mediaPlayer = channelManager.getCurrentChannel();
    State currentState = mediaPlayer.status().state();

    if (currentState != State.STOPPED) {
      mediaPlayer.controls().stop();
      return "Playback stopped.";
    } else {
      return "Playback is already stopped.";
    }
  }

  public String volumeCommand(int volume) {
    MediaPlayer mediaPlayer = channelManager.getCurrentChannel();
    try {
      if (volume >= 0 && volume <= 150) {
        mediaPlayer.audio().setVolume(volume);
        return "Volume set to " + volume + ".";
      } else {
        return "Invalid volume level. Volume must be between 0 and 150.";
      }
    } catch (NumberFormatException e) {
      return "Invalid volume level. Please provide a number between 0 and 150.";
    }
  }

  public String volumeDownCommand() {
    MediaPlayer mediaPlayer = channelManager.getCurrentChannel();
    int volume = mediaPlayer.audio().volume();
    int newVolume = Math.max(0, volume - 5);  // Ensure volume does not go below 0
    mediaPlayer.audio().setVolume(newVolume);

    if (newVolume == volume) {
      return "Volume is already at the minimum level.";
    } else {
      return "Volume decreased to " + newVolume + ".";
    }
  }

  public String volumeUpCommand() {
    MediaPlayer mediaPlayer = channelManager.getCurrentChannel();
    int volume = mediaPlayer.audio().volume();
    int newVolume = Math.min(150, volume + 5);  // Ensure volume does not exceed the max limit of 150

    mediaPlayer.audio().setVolume(newVolume);

    if (newVolume == volume) {
      return "Volume is already at the maximum level.";
    } else {
      return "Volume increased to " + newVolume + ".";
    }
  }

  public String playlistAdd(String mediaPathToAdd) {
    System.out.println("mediaPathToAdd");
    System.out.println(mediaPathToAdd);
    mediaPathToAdd = processSearchMedia(mediaPathToAdd);
    if (verifyAndAddToPlaylist(mediaPathToAdd) >= 0) {
      return "Adding new media: \"" + mediaPathToAdd + "\"";
    }
    return "Error Adding new media: \"" + mediaPathToAdd + "\"";
  }

  public String playlistRemove() {
    return "Removed From Playlist : " + channelManager.remove();
  }

  public String playlistNext() {
    return "Playing " + channelManager.playNext();
  }

  public String playlistList() {
    return PlaylistFormatter.formatPlaylist(channelManager.getPlaylist(), channelManager.getPlaylistIndex());
  }

  private Integer verifyAndAddToPlaylist(String mediaPathToAdd) {
    MediaPlayer mediaPlayer = channelManager.getCurrentChannel();

    String currentMediaPath = mediaPlayer.media().info() != null ? mediaPlayer.media().info().mrl() : null;

    if (!mediaPathToAdd.isEmpty()) {
      if (!mediaPathToAdd.equals(currentMediaPath)) {
        return channelManager.addToPlaylist(mediaPathToAdd);
      }
    }
    return -1;
  }

  private long parseTime(String timeStr) {
    String[] parts = timeStr.split(":");
    long hours = 0;
    long minutes = 0;
    long seconds = 0;

    try {
      if (parts.length == 3) {
        // Format is HH:MM:SS
        hours = Long.parseLong(parts[0]);
        minutes = Long.parseLong(parts[1]);
        seconds = Long.parseLong(parts[2]);
      } else if (parts.length == 2) {
        // Format is MM:SS
        minutes = Long.parseLong(parts[0]);
        seconds = Long.parseLong(parts[1]);
      } else if (parts.length == 1) {
        // Format is SS
        seconds = Long.parseLong(parts[0]);
      } else {
        throw new IllegalArgumentException("Invalid time format. Use HH:MM:SS, MM:SS, or SS.");
      }
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid time format. Use HH:MM:SS, MM:SS, or SS.");
    }

    return ((hours * 60 + minutes) * 60 + seconds) * 1000;  // Convert to milliseconds
  }

  private String formatTime(long timeInMillis) {
    long totalSeconds = timeInMillis / 1000;
    long hours = totalSeconds / 3600;
    long minutes = (totalSeconds % 3600) / 60;
    long seconds = totalSeconds % 60;

    if (hours > 0) {
      return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    } else {
      return String.format("%02d:%02d", minutes, seconds);
    }
  }

  public enum VlcComponentEvents implements EventDefinition {
    VOLUME_CHANGE("A change in the volume"),
    CHANNEL_END("a channel stop playing"),
    CHANNEL_START("a channel start playing");

    private final String description;

    VlcComponentEvents(String description) {
      this.description = description;
    }

    @Override
    public String getDescription() {
      return description;
    }
  }
}
