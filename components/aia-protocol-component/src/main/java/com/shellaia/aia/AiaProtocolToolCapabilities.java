package com.shellaia.aia;

import com.social100.todero.common.routing.ToolCapabilityManifest;
import com.social100.todero.common.routing.ToolCapabilityProvider;
import com.social100.todero.common.routing.ToolCommandSchema;

import java.util.List;

/** Capability surface for managing outbound AIA protocol registrations. */
public final class AiaProtocolToolCapabilities implements ToolCapabilityProvider {
  @Override
  public ToolCapabilityManifest manifest() {
    return ToolCapabilityManifest.builder()
        .contractVersion(1)
        .componentName("com.shellaia.aia")
        .toolSummary("Manage named outbound AIA protocol connections, session headers, and remote command execution.")
        .commands(List.of(
            cmd("list",
                "List the active named AIA server registrations for the current session.",
                List.of(),
                List.of(),
                List.of("list")),
            cmd("register",
                "Register a named remote AIA server connection for the current session.",
                List.of("--host <url>", "--name <name>"),
                List.of("--sni <server-name>", "--trust-anchors <path>", "--pinned-spki <hash>", "--client-pkcs12 <path>", "--client-key-alias <alias>", "--client-password <password>"),
                List.of("register --host aia://remote.example.com --name remote", "register --host aia://remote.example.com --name remote --sni remote.example.com", "register --host aia://remote.example.com --name remote --trust-anchors /path/to/ca.pem")),
            cmd("unregister",
                "Close and remove a named remote AIA server connection.",
                List.of("--name <name>"),
                List.of(),
                List.of("unregister --name remote")),
            cmd("exec",
                "Execute a command on a registered remote AIA server.",
                List.of("--name <name>", "<command>"),
                List.of("<command-arguments>"),
                List.of("exec --name remote com.shellaia.simple ping", "exec --name remote com.shellaia.spotify status")),
            cmd("set-header",
                "Set or replace a session header for a named remote connection.",
                List.of("--name <name>", "--header <header>", "--value <value>"),
                List.of(),
                List.of("set-header --name remote --header Authorization --value Bearer-token")),
            cmd("unset-header",
                "Remove a session header from a named remote connection.",
                List.of("--name <name>", "--header <header>"),
                List.of(),
                List.of("unset-header --name remote --header Authorization"))
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
        .requiredArgs(requiredArgs)
        .optionalArgs(optionalArgs)
        .examples(examples)
        .build();
  }
}
