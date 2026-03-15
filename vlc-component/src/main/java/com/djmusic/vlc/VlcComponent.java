package com.djmusic.vlc;

import com.djmusic.vlc.service.VlcService;
import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.storage.Storage;
import com.social100.todero.scheduler.TaskScheduler;
import io.github.cdimascio.dotenv.Dotenv;

import java.nio.charset.StandardCharsets;

@AIAController(name = "com.shellaia.verbatim.component.vlc",
    type = ServerType.AIA,
    visible = true,
    description = "description",
    events = VlcService.VlcComponentEvents.class)
//@AIADependencies(components = {DjyComponent.class, SimpleComponent.class})
public class VlcComponent {
  private final TaskScheduler scheduler = new TaskScheduler();
  CommandContext globalContext = null;
  private String vlcMediaDirectory;
  private VlcService vlcService;

  public VlcComponent(Storage storage) {
  }

  public void init() {
    Dotenv dotenv = Dotenv.configure().filename(".env-vlc").load();
    vlcMediaDirectory = dotenv.get("MEDIA");
    this.vlcService = new VlcService(vlcMediaDirectory);
    // Create an instance of the TaskScheduler
    scheduler.scheduleTask(() -> {
      if (globalContext != null) {
        globalContext.emitCustom(VlcService.VlcComponentEvents.VOLUME_CHANGE.name(), VlcService.VlcComponentEvents.VOLUME_CHANGE.name(), "text/plain; charset=utf-8", "The Volume has changed".getBytes(StandardCharsets.UTF_8), "progress");
      }
    }, 10000);
    // Add a shutdown hook to gracefully stop the scheduler
    Runtime.getRuntime().addShutdownHook(new Thread(scheduler::stop));
  }

  @Action(group = VlcService.MAIN_GROUP,
      command = "events",
      description = "Start / Stop Sending events. Usage: events ON|OFF")
  public Boolean eventsCommand(CommandContext context) {
    final String commandArgs = requestBody(context);
    if (commandArgs == null || commandArgs.isEmpty()) {
      context.completeText(200, context.getInstance().getAvailableEvents().toString());
    } else {
      boolean eventsOn = "on".equalsIgnoreCase(commandArgs);
      if (eventsOn) {
        this.globalContext = context;
        context.completeText(200, "events are now ON");
      } else {
        context.completeText(200, "events are now OFF");
        this.globalContext = null;
      }
    }
    return true;
  }

  @Action(group = VlcService.MAIN_GROUP,
      command = "move",
      description = "Moves the playback to the specified time. Usage: move <HH:MM:SS|MM:SS|SS>")
  public Boolean moveCommand(CommandContext context) {
    final String commandArgs = requestBody(context);
    if (commandArgs == null || commandArgs.isEmpty()) {
      context.completeText(200, "Error: Please specify the time to move to. Usage: move <time>");
      return true;
    }
    context.completeText(200, this.vlcService.moveCommand(commandArgs));
    return true;
  }

  @Action(group = VlcService.MAIN_GROUP,
      command = "mute",
      description = "Toggles the mute state of the playback if valid media is loaded.")
  public Boolean muteCommand(CommandContext context) {
    context.completeText(200, this.vlcService.muteCommand());
    return true;
  }

  @Action(group = VlcService.MAIN_GROUP,
      command = "pause",
      description = "Pauses the playback if it is currently playing.")
  public Boolean pauseCommand(CommandContext context) {
    context.completeText(200, this.vlcService.pauseCommand());
    return true;
  }

  @Action(group = VlcService.MAIN_GROUP,
      command = "play",
      description = "Plays the specified media file. If no file is specified, resumes the current one. Usage: play [media]")
  public Boolean playCommand(CommandContext context) {
    final String commandArgs = requestBody(context);
    if (commandArgs == null || commandArgs.isEmpty()) {
      context.completeText(200, "Error: Please specify the song to play");
      return true;
    }
    context.completeText(200, this.vlcService.playCommand(commandArgs));
    return true;
  }

  @Action(group = VlcService.MAIN_GROUP,
      command = "skip",
      description = "Skips the playback forward or backward by the specified number of seconds. Usage: skip <+/-seconds>")
  public Boolean skipCommand(CommandContext context) {
    final String commandArgs = requestBody(context);
    if (commandArgs == null || commandArgs.isEmpty()) {
      context.completeText(200, "Error: Please specify the number of seconds to skip. Usage: skip <+/-seconds>");
      return true;
    }
    long skipTime = Long.parseLong(commandArgs);
    context.completeText(200, this.vlcService.skipCommand(skipTime));
    return true;
  }

  @Action(group = VlcService.MAIN_GROUP,
      command = "status",
      description = "Displays the current status of VLC. Use 'status all' for all media info available.")
  public Boolean statusCommand(CommandContext context) {
    final String commandArgs = requestBody(context);
    boolean all = (commandArgs != null && !commandArgs.isEmpty() && "all".equalsIgnoreCase(commandArgs));
    context.completeText(200, this.vlcService.statusCommand(all));
    return true;
  }

  @Action(group = VlcService.MAIN_GROUP,
      command = "stop",
      description = "Stops the playback if it is currently active.")
  public Boolean stopCommand(CommandContext context) {
    context.completeText(200, this.vlcService.stopCommand());
    return true;
  }

  @Action(group = VlcService.MAIN_GROUP,
      command = "volume",
      description = "Sets the volume to a specified level between 0 and 150. Usage: volume <level>")
  public Boolean volumeCommand(CommandContext context) {
    final String commandArgs = requestBody(context);
    if (commandArgs == null || commandArgs.isEmpty()) {
      context.completeText(200, "No volume level provided. Please specify a volume level between 0 and 150.");
      return true;
    }
    int volume = Integer.parseInt(commandArgs);
    context.completeText(200, this.vlcService.volumeCommand(volume));
    return true;
  }

  @Action(group = VlcService.MAIN_GROUP,
      command = "volume-down",
      description = "Decreases the volume by 5 units.")
  public Boolean volumeDownCommand(CommandContext context) {
    context.completeText(200, this.vlcService.volumeDownCommand());
    return true;
  }

  @Action(group = VlcService.MAIN_GROUP,
      command = "volume-up",
      description = "Increases the volume by 5 units.")
  public Boolean volumeUpCommand(CommandContext context) {
    context.completeText(200, this.vlcService.volumeUpCommand());
    return true;
  }

  @Action(group = VlcService.PLAYLIST_GROUP,
      command = "playlist-add",
      description = "Adds the specified media file to the playlist. Usage: add-playlist [media]")
  public Boolean playlistAdd(CommandContext context) {
    final String commandArgs = requestBody(context);
    if (commandArgs == null || commandArgs.isEmpty()) {
      context.completeText(200, "No song to add.");
      return true;
    }
    context.completeText(200, this.vlcService.playlistAdd(commandArgs));
    return true;
  }

  @Action(group = VlcService.PLAYLIST_GROUP,
      command = "playlist-remove",
      description = "Remove current paying media from the playlist Usage: playlist-remove, if there is no current media playing then does nothing")
  public Boolean playlistRemove(CommandContext context) {
    context.completeText(200, this.vlcService.playlistRemove());
    return true;
  }

  @Action(group = VlcService.PLAYLIST_GROUP,
      command = "playlist-next",
      description = "Play the next media in the playlist. Usage: playlist-next")
  public Boolean playlistNext(CommandContext context) {
    context.completeText(200, this.vlcService.playlistNext());
    return true;
  }

  @Action(group = VlcService.PLAYLIST_GROUP,
      command = "playlist-list",
      description = "Inform the user of the playlist items and which is the current item. Usage: playlist-list")
  public Boolean playlistList(CommandContext context) {
    context.completeText(200, this.vlcService.playlistList());
    return true;
  }


  private static String requestBody(CommandContext context) {
    if (context == null || context.getAiatpRequest() == null || context.getAiatpRequest().getBody() == null) {
      return "";
    }
    String body = AiatpIO.bodyToString(context.getAiatpRequest().getBody(), StandardCharsets.UTF_8);
    return body == null ? "" : body;
  }
}
