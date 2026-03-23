package com.shellaia.component.simple;


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

@AIAController(name = "com.shellaia.simple",
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

    final String commandArgs = requestBody(context);
    context.emitCustom(SimpleEvent.SIMPLE_EVENT.name(), SimpleEvent.SIMPLE_EVENT.name(), "text/plain; charset=utf-8", "No va a salir".getBytes(StandardCharsets.UTF_8), "progress");
    respondText(context, 200, "Ping Ok" + (!commandArgs.isEmpty() ? " : " + commandArgs : ""));
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "hello",
      description = "Does a friendly hello")
  public Boolean instanceMethod(CommandContext context) {
    final String commandArgs = requestBody(context);
    Map<String, Object> mm = Map.of(
        "message", "Hello from instanceMethod",
        "args", commandArgs,
        "metadata", Map.of("key1", "value1", "key2", "value2")
    );
    context.emitCustom(SimpleEvent.OTHER_EVENT.name(), SimpleEvent.OTHER_EVENT.name(), "text/plain; charset=utf-8", "Aja, aqui va!".getBytes(StandardCharsets.UTF_8), "progress");
    respondText(context, 200, mm.toString());
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

    final String commandArgs = requestBody(context);
    Map<String, Object> mm = Map.of(
        "message", "Hello from instanceMethod",
        "args", commandArgs,
        "metadata", Map.of("key1", "value1", "key2", "value2")
    );
    context.emitCustom(SimpleEvent.OTHER_EVENT.name(), SimpleEvent.OTHER_EVENT.name(), "text/plain; charset=utf-8", "Aja, aqui va!".getBytes(StandardCharsets.UTF_8), "progress");
    respondText(context, 200, mm.toString());
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "events",
      description = "Start / Stop Sending events. Usage: events ON|OFF")
  public Boolean eventsCommand(CommandContext context) {
    final String commandArgs = requestBody(context);
    if (commandArgs == null || commandArgs.isEmpty()) {
      respondText(context, 200, context.getInstance().getAvailableEvents().toString());
    } else {
      boolean eventsOn = "on".equalsIgnoreCase(commandArgs);
      if (eventsOn) {
        this.globalContext = context;
        if (backgroundTaskRunner == null) {
          backgroundTaskRunner = new BackgroundTaskRunner(Duration.ofSeconds(10), Duration.ofSeconds(5), true);
          backgroundTaskRunner.start(() -> {
            context.emitCustom(SimpleEvent.SIMPLE_EVENT.name(), SimpleEvent.SIMPLE_EVENT.name(), "text/plain; charset=utf-8", "yeyeyey".getBytes(StandardCharsets.UTF_8), "progress");
          });
          respondText(context, 200, "events are now ON");
        } else {
          respondText(context, 200, "events are already ON");
        }
      } else {
        if (backgroundTaskRunner != null) {
          backgroundTaskRunner.stop();
          backgroundTaskRunner = null;
          respondText(context, 200, "events are now OFF");
          this.globalContext = null;
        } else {
          respondText(context, 200, "events are already OFF");
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


  private static String requestBody(CommandContext context) {
    if (context == null || context.getAiatpRequest() == null || context.getAiatpRequest().getBody() == null) {
      return "";
    }
    String body = AiatpIO.bodyToString(context.getAiatpRequest().getBody(), StandardCharsets.UTF_8);
    return body == null ? "" : body;
  }

  private static void respondText(CommandContext context, int status, String message) {
    String safeMessage = message == null ? "" : message;
    context.completeJson(status, "{"
        + "\"ok\":" + (status < 400) + ","
        + "\"message\":" + quoteJson(safeMessage) + ","
        + "\"channels\":{"
        + "\"chat\":{\"message\":" + quoteJson(safeMessage) + "},"
        + "\"status\":{\"message\":" + quoteJson(safeMessage) + "},"
        + "\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}"
        + "}"
        + "}");
  }

  private static String quoteJson(String value) {
    if (value == null) {
      return "null";
    }
    StringBuilder out = new StringBuilder(value.length() + 2);
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
    return out.toString();
  }
}
