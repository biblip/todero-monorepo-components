package com.shellaia.aia;

import com.social100.todero.common.routing.ToolCapabilityManifest;
import com.social100.todero.common.routing.ToolCommandSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AiaProtocolToolCapabilitiesTest {
  @Test
  void manifestDescribesOutboundAiaCommands() {
    ToolCapabilityManifest manifest = new AiaProtocolToolCapabilities().manifest();

    assertEquals(1, manifest.getContractVersion());
    assertEquals("com.shellaia.aia", manifest.getComponentName());
    assertEquals("Manage named outbound AIA protocol connections, session headers, and remote command execution.", manifest.getToolSummary());

    Map<String, ToolCommandSchema> commands = manifest.getCommands().stream()
        .collect(Collectors.toMap(ToolCommandSchema::getName, command -> command));

    assertCommand(commands.get("list"), "list", List.of(), List.of(), List.of("list"));
    assertCommand(commands.get("register"), "register",
        List.of("--host <url>", "--name <name>"),
        List.of("--sni <server-name>", "--trust-anchors <path>", "--pinned-spki <hash>", "--client-pkcs12 <path>", "--client-key-alias <alias>", "--client-password <password>"),
        List.of("register --host aia://remote.example.com --name remote", "register --host aia://remote.example.com --name remote --sni remote.example.com", "register --host aia://remote.example.com --name remote --trust-anchors /path/to/ca.pem"));
    assertCommand(commands.get("unregister"), "unregister", List.of("--name <name>"), List.of(), List.of("unregister --name remote"));
    assertCommand(commands.get("exec"), "exec", List.of("--name <name>", "<command>"), List.of("<command-arguments>"), List.of("exec --name remote com.shellaia.simple ping", "exec --name remote com.shellaia.spotify status"));
    assertCommand(commands.get("set-header"), "set-header", List.of("--name <name>", "--header <header>", "--value <value>"), List.of(), List.of("set-header --name remote --header Authorization --value Bearer-token"));
    assertCommand(commands.get("unset-header"), "unset-header", List.of("--name <name>", "--header <header>"), List.of(), List.of("unset-header --name remote --header Authorization"));
  }

  private static void assertCommand(ToolCommandSchema command,
                                    String name,
                                    List<String> requiredArgs,
                                    List<String> optionalArgs,
                                    List<String> examples) {
    assertNotNull(command, "Missing command: " + name);
    assertEquals(name, command.getName());
    assertEquals(requiredArgs, command.getRequiredArgs());
    assertEquals(optionalArgs, command.getOptionalArgs());
    assertIterableEquals(examples, command.getExamples());
  }
}
