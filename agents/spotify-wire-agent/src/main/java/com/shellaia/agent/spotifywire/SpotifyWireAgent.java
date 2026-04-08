package com.shellaia.agent.spotifywire;

import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpRequest;
import com.social100.todero.common.base.ComponentManagerInterface;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.storage.Storage;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * POC "tool agent" facade for {@code com.shellaia.spotify.wire}.
 *
 * This agent uses the AIATP/1.0 wire primitives only (plain text),
 * and forwards successful requests directly to the tool component.
 */
@AIAController(
    name = "com.shellaia.agent.spotify.wire",
    type = ServerType.AI,
    visible = true,
    description = "Spotify POC agent (wire-only responses)",
    capabilityProvider = SpotifyWireAgentCapabilities.class
)
public class SpotifyWireAgent {
  private static final String TOOL_COMPONENT = "com.shellaia.spotify.wire";

  @SuppressWarnings("unused")
  private final Storage storage;

  public SpotifyWireAgent(Storage storage) {
    this.storage = storage;
  }

  @Action(group = "Main", command = "process",
      description = "Process a user prompt and delegate to Spotify tool. Usage: process <text>")
  public Boolean process(CommandContext context) {
    String prompt = readBody(context);
    if (prompt.isBlank()) {
      context.completeText(500, "Prompt is required. Usage: process <text>");
      return Boolean.TRUE;
    }

    String p = prompt.trim();
    String lower = p.toLowerCase(Locale.ROOT);

    // Minimal deterministic routing (no LLM).
    if (lower.startsWith("play ")) {
      return forward(context, "play");
    }
    if (lower.equals("play")) {
      return forward(context, "play");
    }
    if (lower.startsWith("volume ")) {
      return forward(context, "volume");
    }
    if (lower.equals("status") || lower.equals("status all") || lower.startsWith("status ")) {
      return forward(context, "status");
    }
    if (lower.startsWith("auth-begin")) {
      return forward(context, "auth-begin");
    }
    if (lower.startsWith("auth-complete")) {
      return forward(context, "auth-complete");
    }

    context.completeText(500, "Unsupported request for this POC agent. Try: play <song>, status, volume <n>, auth-begin.");
    return Boolean.TRUE;
  }

  @Action(group = "Main", command = "auth-begin", description = "Forward to tool auth-begin.")
  public Boolean authBegin(CommandContext context) {
    return forward(context, "auth-begin");
  }

  @Action(group = "Main", command = "auth-complete", description = "Forward to tool auth-complete.")
  public Boolean authComplete(CommandContext context) {
    return forward(context, "auth-complete");
  }

  @Action(group = "Main", command = "play", description = "Forward to tool play.")
  public Boolean play(CommandContext context) {
    return forward(context, "play");
  }

  @Action(group = "Main", command = "volume", description = "Forward to tool volume.")
  public Boolean volume(CommandContext context) {
    return forward(context, "volume");
  }

  @Action(group = "Main", command = "status", description = "Forward to tool status.")
  public Boolean status(CommandContext context) {
    return forward(context, "status");
  }

  private static Boolean forward(CommandContext context, String toolCommand) {
    ComponentManagerInterface cm = context == null ? null : context.getComponentManager();
    if (cm == null) {
      context.completeText(500, "Cannot delegate: missing component manager.");
      return Boolean.TRUE;
    }
    cm.execute(TOOL_COMPONENT, toolCommand, context, true);
    return Boolean.TRUE;
  }

  private static String readBody(CommandContext context) {
    AiatpRequest req = context == null ? null : context.getAiatpRequest();
    String body = req == null || req.getBody() == null
        ? ""
        : AiatpIO.bodyToString(req.getBody(), StandardCharsets.UTF_8);
    return body == null ? "" : body.trim();
  }
}
