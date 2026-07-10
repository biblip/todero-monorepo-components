package com.shellaia.component.auth.google;

import com.social100.todero.common.routing.ToolCapabilityManifest;
import com.social100.todero.common.routing.ToolCommandSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GoogleAuthBrokerToolCapabilitiesTest {
  @Test
  void manifestIncludesBrokerCommands() {
    ToolCapabilityManifest manifest = new GoogleAuthBrokerToolCapabilities().manifest();

    assertEquals(1, manifest.getContractVersion());
    assertEquals("com.shellaia.auth.google", manifest.getComponentName());
    assertEquals("Google Account device-flow auth broker for Todero HTML sign-in, session tracking, and principal storage.", manifest.getToolSummary());

    Map<String, ToolCommandSchema> commands = manifest.getCommands().stream()
        .collect(Collectors.toMap(ToolCommandSchema::getName, c -> c));

    assertCommand(commands.get("html"), "html", List.of(), List.of(), List.of("html"));
    assertCommand(commands.get("settings_get"), "settings_get", List.of(), List.of(), List.of("settings_get"));
    assertCommand(commands.get("settings_put"), "settings_put", List.of(), List.of(
        "clientId=<oauth-client-id>",
        "clientSecret=<oauth-client-secret>",
        "projectId=<google-project-id>",
        "authProvider=<google>",
        "deviceAuthorizationUri=<device-auth-uri>",
        "tokenUri=<token-uri>",
        "userInfoUri=<userinfo-uri>",
        "scope=<openid email profile>",
        "projectName=<display-name>"
    ), List.of("settings_put clientId=client-123 projectId=proj-123 deviceAuthorizationUri=http://127.0.0.1:9999/device/code tokenUri=http://127.0.0.1:9999/token userInfoUri=http://127.0.0.1:9999/userinfo scope=openid email profile"));
    assertCommand(commands.get("auth_begin"), "auth_begin", List.of(), List.of(
        "scope=<openid email profile>",
        "projectId=<google-project-id>",
        "authProvider=<google>",
        "deviceAuthorizationUri=<device-auth-uri>",
        "tokenUri=<token-uri>",
        "userInfoUri=<userinfo-uri>",
        "clientId=<oauth-client-id>",
        "clientSecret=<oauth-client-secret>"
    ), List.of("auth_begin scope=openid email profile deviceAuthorizationUri=http://127.0.0.1:9999/device/code tokenUri=http://127.0.0.1:9999/token userInfoUri=http://127.0.0.1:9999/userinfo"));
    assertCommand(commands.get("auth_poll"), "auth_poll", List.of(), List.of(), List.of("auth_poll"));
    assertCommand(commands.get("auth_status"), "auth_status", List.of(), List.of(), List.of("auth_status"));
    assertCommand(commands.get("capabilities"), "capabilities", List.of(), List.of(), List.of("capabilities"));
    assertCommand(commands.get("auth_complete"), "auth_complete", List.of(), List.of(), List.of("auth_complete"));
    assertCommand(commands.get("auth_cancel"), "auth_cancel", List.of(), List.of(), List.of("auth_cancel"));
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
