package com.social100.todero.component;


import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.ai.timer.BackgroundTaskRunner;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.storage.Storage;
import com.social100.todero.processor.EventDefinition;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@AIAController(name = "com.shellaia.verbatim.component.simple",
    type = ServerType.AIA,
    visible = true,
    description = "Simple Component",
    events = SimpleComponent.SimpleEvent.class)
public class SimpleComponent {
  final static String MAIN_GROUP = "Main";
  private CommandContext globalContext = null;
  BackgroundTaskRunner backgroundTaskRunner = null;

  public SimpleComponent(Storage storage) {
  }

  @Action(group = MAIN_GROUP,
      command = "ping",
      description = "Does the ping")
  public Boolean pingCommand(CommandContext context) {

    AiatpIO.HttpRequest httpRequest = context.getHttpRequest();
    final String commandArgs = AiatpIO.bodyToString(httpRequest.body(), StandardCharsets.UTF_8);
    context.event(SimpleEvent.SIMPLE_EVENT.name(), "No va a salir");
    context.response("Ping Ok" + (!commandArgs.isEmpty() ? " : " + commandArgs : ""));
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "hello",
      description = "Does a friendly hello")
  public Boolean instanceMethod(CommandContext context) {
    AiatpIO.HttpRequest httpRequest = context.getHttpRequest();
    final String commandArgs = AiatpIO.bodyToString(httpRequest.body(), StandardCharsets.UTF_8);
    Map<String, Object> mm = Map.of(
        "message", "Hello from instanceMethod",
        "args", commandArgs,
        "metadata", Map.of("key1", "value1", "key2", "value2")
    );
    context.event(SimpleEvent.OTHER_EVENT.name(), "Aja, aqui va!");
    context.response(mm.toString());
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "read_write",
      description = "Reads and write for this component")
  public Boolean readAndWrite(CommandContext context) {
    Storage storage = context.getStorage();
    //storage.writeFile("readme.txt", "Hello, local!".getBytes(StandardCharsets.UTF_8));
    //System.out.println(new String(storage.readFile("readme.txt"), StandardCharsets.UTF_8));
    //storage.putSecret("apiKey", "12345");
    //System.out.println("secret apiKey=" + storage.getSecret("apiKey"));

    AiatpIO.HttpRequest httpRequest = context.getHttpRequest();
    final String commandArgs = AiatpIO.bodyToString(httpRequest.body(), StandardCharsets.UTF_8);
    Map<String, Object> mm = Map.of(
        "message", "Hello from instanceMethod",
        "args", commandArgs,
        "metadata", Map.of("key1", "value1", "key2", "value2")
    );
    context.event(SimpleEvent.OTHER_EVENT.name(), "Aja, aqui va!");
    context.response(mm.toString());
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "events",
      description = "Start / Stop Sending events. Usage: events ON|OFF")
  public Boolean eventsCommand(CommandContext context) {
    AiatpIO.HttpRequest httpRequest = context.getHttpRequest();
    final String commandArgs = AiatpIO.bodyToString(httpRequest.body(), StandardCharsets.UTF_8);
    if (commandArgs == null || commandArgs.isEmpty()) {
      context.response(context.getInstance().getAvailableEvents().toString());
    } else {
      boolean eventsOn = "on".equalsIgnoreCase(commandArgs);
      if (eventsOn) {
        this.globalContext = context;
        if (backgroundTaskRunner == null) {
          backgroundTaskRunner = new BackgroundTaskRunner(Duration.ofSeconds(10), Duration.ofSeconds(5), true);
          backgroundTaskRunner.start(() -> {
            context.event(SimpleEvent.SIMPLE_EVENT.name(), "yeyeyey");
          });
          context.response("events are now ON");
        } else {
          context.response("events are already ON");
        }
      } else {
        if (backgroundTaskRunner != null) {
          backgroundTaskRunner.stop();
          backgroundTaskRunner = null;
          context.response("events are now OFF");
          this.globalContext = null;
        } else {
          context.response("events are already OFF");
        }
      }
    }
    return true;
  }

  public enum SimpleEvent implements EventDefinition {
    SIMPLE_EVENT("A event to demo"),
    OTHER_EVENT("Other event to demo");

    private final String description;

    SimpleEvent(String description) {
      this.description = description;
    }

    @Override
    public String getDescription() {
      return description;
    }
  }
}
