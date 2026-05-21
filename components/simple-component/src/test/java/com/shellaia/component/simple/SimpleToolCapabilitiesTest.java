package com.shellaia.component.simple;

import com.social100.todero.common.routing.ToolCapabilityManifest;
import com.social100.todero.common.routing.ToolCommandSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SimpleToolCapabilitiesTest {
  @Test
  void manifestIncludesSimpleDemoCommands() {
    ToolCapabilityManifest manifest = new SimpleToolCapabilities().manifest();

    assertEquals(1, manifest.getContractVersion());
    assertEquals("com.shellaia.simple", manifest.getComponentName());
    assertEquals("Simple demo component for ping, events, alarms, and clock state.", manifest.getToolSummary());

    Map<String, ToolCommandSchema> commands = manifest.getCommands().stream()
        .collect(java.util.stream.Collectors.toMap(c -> c.getName(), c -> c));

    assertCommand(commands.get("ping"), "ping", List.of(), List.of(), List.of("ping"));
    assertCommand(commands.get("hello"), "hello", List.of(), List.of(), List.of("hello"));
    assertCommand(commands.get("read_write"), "read_write", List.of(), List.of(), List.of("read_write"));
    assertCommand(commands.get("events"), "events", List.of("ON|OFF"), List.of(), List.of("events ON", "events OFF"));
    assertCommand(commands.get("clock_status"), "clock_status", List.of(), List.of(), List.of("clock_status"));
    assertCommand(commands.get("wakeup_on"), "wakeup_on", List.of(), List.of("intervalMs"), List.of("wakeup_on", "wakeup_on 30000"));
    assertCommand(commands.get("wakeup_off"), "wakeup_off", List.of(), List.of(), List.of("wakeup_off"));
    assertCommand(commands.get("alarm_after"), "alarm_after", List.of("delayMs"), List.of("action", "message"), List.of("alarm_after 5000", "alarm_after 5000 clock_alarm_target demo alarm"));
    assertCommand(commands.get("alarm_every"), "alarm_every", List.of("everyMs"), List.of("delayMs", "action", "message"), List.of("alarm_every 10000", "alarm_every 10000 5000 clock_alarm_target recurring alarm"));
    assertCommand(commands.get("alarm_list"), "alarm_list", List.of(), List.of(), List.of("alarm_list"));
    assertCommand(commands.get("alarm_cancel"), "alarm_cancel", List.of("alarmId"), List.of(), List.of("alarm_cancel alarm_123"));
    assertCommand(commands.get("clock_alarm_target"), "clock_alarm_target", List.of(), List.of(), List.of("clock_alarm_target"));
  }

  private static void assertCommand(ToolCommandSchema command,
                                    String name,
                                    List<String> requiredArgs,
                                    List<String> optionalArgs,
                                    List<String> examples) {
    assertNotNull(command, "Missing command: " + name);
    assertEquals(name, command.getName(), "name mismatch for " + name);
    assertEquals(requiredArgs, command.getRequiredArgs(), "requiredArgs mismatch for " + name);
    assertEquals(optionalArgs, command.getOptionalArgs(), "optionalArgs mismatch for " + name);
    assertIterableEquals(examples, command.getExamples(), "examples mismatch for " + name);
  }
}
