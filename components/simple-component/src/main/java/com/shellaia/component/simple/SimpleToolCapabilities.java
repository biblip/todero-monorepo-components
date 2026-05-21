package com.shellaia.component.simple;

import com.social100.todero.common.routing.ToolCapabilityManifest;
import com.social100.todero.common.routing.ToolCapabilityProvider;
import com.social100.todero.common.routing.ToolCommandSchema;

import java.util.List;

public final class SimpleToolCapabilities implements ToolCapabilityProvider {
  @Override
  public ToolCapabilityManifest manifest() {
    return ToolCapabilityManifest.builder()
        .contractVersion(1)
        .componentName("com.shellaia.simple")
        .toolSummary("Simple demo component for ping, events, alarms, and clock state.")
        .commands(List.of(
            cmd("ping",
                "Returns a simple ping response.",
                List.of(),
                List.of(),
                List.of("ping")),
            cmd("hello",
                "Returns a sample structured response.",
                List.of(),
                List.of(),
                List.of("hello")),
            cmd("read_write",
                "Demonstrates storage access and emits a demo event.",
                List.of(),
                List.of(),
                List.of("read_write")),
            cmd("events",
                "Start or stop the demo event stream.",
                List.of("ON|OFF"),
                List.of(),
                List.of("events ON", "events OFF")),
            cmd("clock_status",
                "Show current clock config, alarms, and wakeup state.",
                List.of(),
                List.of(),
                List.of("clock_status")),
            cmd("wakeup_on",
                "Enable live wakeups for this component.",
                List.of(),
                List.of("intervalMs"),
                List.of("wakeup_on", "wakeup_on 30000")),
            cmd("wakeup_off",
                "Disable live wakeups for this component.",
                List.of(),
                List.of(),
                List.of("wakeup_off")),
            cmd("alarm_after",
                "Schedule a one-shot alarm.",
                List.of("delayMs"),
                List.of("action", "message"),
                List.of("alarm_after 5000", "alarm_after 5000 clock_alarm_target demo alarm")),
            cmd("alarm_every",
                "Schedule a recurring alarm.",
                List.of("everyMs"),
                List.of("delayMs", "action", "message"),
                List.of("alarm_every 10000", "alarm_every 10000 5000 clock_alarm_target recurring alarm")),
            cmd("alarm_list",
                "List alarms owned by this component.",
                List.of(),
                List.of(),
                List.of("alarm_list")),
            cmd("alarm_cancel",
                "Cancel an alarm by id.",
                List.of("alarmId"),
                List.of(),
                List.of("alarm_cancel alarm_123")),
            cmd("clock_alarm_target",
                "Target action used by the clock examples.",
                List.of(),
                List.of(),
                List.of("clock_alarm_target"))
        ))
        .build();
  }

  private static ToolCommandSchema cmd(String name,
                                       String description,
                                       List<String> requiredArgs,
                                       List<String> optionalArgs,
                                       List<String> examples) {
    return ToolCommandSchema.builder()
        .name(name)
        .description(description)
        .requiredArgs(requiredArgs == null ? List.of() : requiredArgs)
        .optionalArgs(optionalArgs == null ? List.of() : optionalArgs)
        .examples(examples == null ? List.of() : examples)
        .build();
  }
}
