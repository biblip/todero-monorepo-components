package com.shellaia.component.simple;


import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.ai.timer.BackgroundTaskRunner;
import com.social100.todero.common.clock.AlarmInfo;
import com.social100.todero.common.clock.AlarmScheduleRequest;
import com.social100.todero.common.clock.ClockConfig;
import com.social100.todero.common.clock.WakeupHandler;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.storage.Storage;
import com.social100.todero.processor.EventDefinition;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@AIAController(name = "com.shellaia.simple",
    type = ServerType.AIA,
    visible = true,
    description = "Simple Component",
    events = SimpleComponent.SimpleEvent.class)
public class SimpleComponent implements WakeupHandler {
  final static String MAIN_GROUP = "Main";
  private CommandContext globalContext = null;
  BackgroundTaskRunner backgroundTaskRunner = null;
  private final AtomicLong liveWakeupCount = new AtomicLong(0L);
  private volatile Instant lastLiveWakeupAt;
  private volatile String lastAlarmMessage = "";
  private volatile Instant lastAlarmAt;

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

  @Action(group = MAIN_GROUP,
      command = "clock_status",
      description = "Shows current clock config, alarms, and last wakeup/alarm observations")
  public Boolean clockStatus(CommandContext context) {
    ClockConfig config = context.clock().getConfig();
    List<AlarmInfo> alarms = context.clock().listAlarms();
    String message = "{"
        + "\"config\":{"
        + "\"enabled\":" + config.enabled() + ","
        + "\"liveEnabled\":" + config.liveEnabled() + ","
        + "\"liveIntervalMs\":" + config.liveIntervalMs()
        + "},"
        + "\"liveWakeupCount\":" + liveWakeupCount.get() + ","
        + "\"lastLiveWakeupAt\":" + quoteJson(lastLiveWakeupAt == null ? "" : lastLiveWakeupAt.toString()) + ","
        + "\"lastAlarmAt\":" + quoteJson(lastAlarmAt == null ? "" : lastAlarmAt.toString()) + ","
        + "\"lastAlarmMessage\":" + quoteJson(lastAlarmMessage) + ","
        + "\"alarms\":" + alarmsToJson(alarms)
        + "}";
    respondText(context, 200, message);
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "wakeup_on",
      description = "Enables live wakeups. Usage body: optional intervalMs, default 60000")
  public Boolean wakeupOn(CommandContext context) {
    long intervalMs = parseLongOrDefault(requestBody(context), 60000L);
    context.clock().setConfig(new ClockConfig(true, true, Math.max(1L, intervalMs)));
    respondText(context, 200, "wakeup enabled intervalMs=" + Math.max(1L, intervalMs));
    System.out.println("wakeup_on, setting things up");
    context.emitChat("simple live wakeup starting", "progress");
    globalContext = context;
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "wakeup_off",
      description = "Disables live wakeups but leaves alarms available")
  public Boolean wakeupOff(CommandContext context) {
    ClockConfig current = context.clock().getConfig();
    long intervalMs = current == null ? 60000L : Math.max(1L, current.liveIntervalMs());
    context.clock().setConfig(new ClockConfig(true, false, intervalMs));
    respondText(context, 200, "wakeup disabled for com.shellaia.simple");
    globalContext = null;
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "alarm_after",
      description = "Schedules one alarm. Query params: delayMs, action(optional). Body becomes alarm message")
  public Boolean alarmAfter(CommandContext context) {
    context.clock().setConfig(ensureClockEnabled(context.clock().getConfig()));
    long delayMs = queryLong(context, "delayMs", 5000L);
    String action = queryString(context, "action", "clock_alarm_target");
    String body = requestBody(context);
    String alarmId = context.clock().scheduleAlarm(new AlarmScheduleRequest(
        action,
        body == null || body.isBlank() ? "simple one-shot alarm" : body,
        null,
        delayMs,
        null,
        Map.of(),
        true
    ));
    respondText(context, 200, "scheduled one-shot alarmId=" + alarmId + " delayMs=" + delayMs + " action=" + action);
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "alarm_every",
      description = "Schedules a recurring alarm. Query params: everyMs(required), delayMs(optional), action(optional)")
  public Boolean alarmEvery(CommandContext context) {
    context.clock().setConfig(ensureClockEnabled(context.clock().getConfig()));
    long everyMs = queryLong(context, "everyMs", 10000L);
    long delayMs = queryLong(context, "delayMs", everyMs);
    String action = queryString(context, "action", "clock_alarm_target");
    String body = requestBody(context);
    String alarmId = context.clock().scheduleAlarm(new AlarmScheduleRequest(
        action,
        body == null || body.isBlank() ? "simple recurring alarm" : body,
        null,
        delayMs,
        Math.max(1L, everyMs),
        Map.of(),
        true
    ));
    respondText(context, 200, "scheduled recurring alarmId=" + alarmId
        + " delayMs=" + delayMs + " everyMs=" + Math.max(1L, everyMs) + " action=" + action);
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "alarm_list",
      description = "Lists alarms owned by this component")
  public Boolean alarmList(CommandContext context) {
    respondText(context, 200, alarmsToJson(context.clock().listAlarms()));
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "alarm_cancel",
      description = "Cancels one alarm. Usage body: alarmId")
  public Boolean alarmCancel(CommandContext context) {
    String alarmId = requestBody(context).trim();
    boolean cancelled = !alarmId.isEmpty() && context.clock().cancelAlarm(alarmId);
    respondText(context, cancelled ? 200 : 404,
        cancelled ? "cancelled alarmId=" + alarmId : "alarm not found: " + alarmId);
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "clock_alarm_target",
      description = "Target action used by the simple clock examples")
  public Boolean clockAlarmTarget(CommandContext context) {
    String body = requestBody(context);
    lastAlarmMessage = body == null ? "" : body;
    lastAlarmAt = Instant.now();
    System.out.println("simple alarm fired: " + lastAlarmMessage + "   progress");
    context.emitChat("simple alarm fired: " + lastAlarmMessage, "progress");
    respondText(context, 200, "alarm target executed message=" + lastAlarmMessage);
    return true;
  }

  @Override
  public void onLiveWakeup(CommandContext context) {
    long count = liveWakeupCount.incrementAndGet();
    lastLiveWakeupAt = Instant.now();
    System.out.println("simple live wakeup #" + count + "  progress");
    if (globalContext != null) {
      globalContext.emitChat("simple live wakeup #" + count, "progress");
    }
  }

  public enum SimpleEvent implements EventDefinition {
    chat("Build-in chat event"),
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

  private static ClockConfig ensureClockEnabled(ClockConfig current) {
    if (current != null && current.enabled()) {
      return current;
    }
    return new ClockConfig(true, false, 60000L);
  }

  private static String queryString(CommandContext context, String key, String fallback) {
    if (context == null || context.getAiatpRequest() == null || context.getAiatpRequest().getQueryParams() == null) {
      return fallback;
    }
    String value = context.getAiatpRequest().getQueryParams().getFirst(key);
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static long queryLong(CommandContext context, String key, long fallback) {
    return parseLongOrDefault(queryString(context, key, Long.toString(fallback)), fallback);
  }

  private static long parseLongOrDefault(String raw, long fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      return Long.parseLong(raw.trim());
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static String alarmsToJson(List<AlarmInfo> alarms) {
    StringBuilder out = new StringBuilder("[");
    for (int i = 0; i < alarms.size(); i++) {
      AlarmInfo alarm = alarms.get(i);
      if (i > 0) {
        out.append(',');
      }
      out.append('{')
          .append("\"alarmId\":").append(quoteJson(alarm.alarmId())).append(',')
          .append("\"action\":").append(quoteJson(alarm.action())).append(',')
          .append("\"message\":").append(quoteJson(alarm.message())).append(',')
          .append("\"nextFireAt\":").append(quoteJson(alarm.nextFireAt() == null ? "" : alarm.nextFireAt().toString())).append(',')
          .append("\"everyMs\":").append(alarm.everyMs() == null ? "null" : alarm.everyMs()).append(',')
          .append("\"enabled\":").append(alarm.enabled())
          .append('}');
    }
    out.append(']');
    return out.toString();
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
