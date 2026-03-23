package com.shellaia.component.ssh;

import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.storage.Storage;
import com.shellaia.component.ssh.controller.SshController;
import com.shellaia.component.ssh.service.SshService;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

@AIAController(name = "com.shellaia.ssh",
    type = ServerType.AIA,
    visible = true,
    description = "SSH Component",
    events = SshService.SshEvents.class)
public class SshComponent {
  private final SshController controller;

  public SshComponent(Storage storage) {
    this.controller = new SshController();
  }

  @Action(group = SshService.MAIN_GROUP,
      command = "open",
      description = "Opens a ssh connection required user and a pem file     Usage: open <id> <host> <user> <pemPath>")
  public Boolean open(CommandContext context) {
    final String commandArgs = requestBody(context);
    if (commandArgs == null || commandArgs.isEmpty()) {
      respondText(context, 200, "Usage: open <id> <host> <user> <pemPath>");
      return false;
    }
    String[] args = commandArgs.split(" ", 3);
    controller.openConnectionWithKey(args[0], args[1], args[2], args[3]);
    respondText(context, 200, "Connection opened.");
    return true;
  }

  @Action(group = SshService.MAIN_GROUP,
      command = "open-pass",
      description = "Opens a ssh connection, requires user and password     Usage: open-pass <id> <host> <user> <passwordFilePath>")
  public Boolean openWithPassword(CommandContext context) {
    final String commandArgs = requestBody(context);
    if (commandArgs == null || commandArgs.isEmpty()) {
      respondText(context, 200, "Usage: open-pass <id> <host> <user> <passwordFilePath>");
      return false;
    }
    try {
      String[] args = commandArgs.split(" ", 4);
      String passwordFile = args[3];

      // Expand ~ to the user's home directory
      if (passwordFile.startsWith("~" + File.separator) || passwordFile.equals("~")) {
        passwordFile = passwordFile.replaceFirst("^~", System.getProperty("user.home"));
      }

      String password = Files.readString(Path.of(passwordFile)).strip();
      controller.openConnectionWithPassword(args[0], args[1], args[2], password);
      respondText(context, 200, "Connection opened with password. id:" + args[0]);
      return true;
    } catch (IOException e) {
      respondText(context, 200, "Failed to read password file: " + e.getMessage());
      return false;
    }
  }

  @Action(group = SshService.MAIN_GROUP,
      command = "run",
      description = "Execute commands on a ssh connection     Usage: run <id> <command>")
  public Boolean run(CommandContext context) {
    final String commandArgs = requestBody(context);
    if (commandArgs == null || commandArgs.isEmpty()) {
      respondText(context, 200, "Usage: run <id> <command>");
      return false;
    }
    String[] args = commandArgs.split(" ", 2);
    controller.executeCommand(args[0],
        args[1],
        Duration.ofSeconds(60),
        output -> respondText(context, 200, output),
        output -> respondText(context, 200, output));
    respondText(context, 200, "Command executed.");
    return true;
  }

  @Action(group = SshService.MAIN_GROUP,
      command = "upload",
      description = "Upload a file or script to the ssh server     Usage: upload <id> <localPath> <remotePath>")
  public Boolean upload(CommandContext context) {
    final String commandArgs = requestBody(context);
    if (commandArgs == null || commandArgs.isEmpty()) {
      respondText(context, 200, "Usage: upload <id> <localPath> <remotePath>");
      return false;
    }
    String[] args = commandArgs.split(" ", 3);
    controller.uploadScript(args[0], args[1], args[2]);
    respondText(context, 200, "Upload complete.");
    return true;
  }

  @Action(group = SshService.MAIN_GROUP,
      command = "delete",
      description = "Delete a remote file     Usage: delete <id> <remotePath>")
  public Boolean delete(CommandContext context) {
    final String commandArgs = requestBody(context);
    if (commandArgs == null || commandArgs.isEmpty()) {
      respondText(context, 200, "Usage: delete <id> <remotePath>");
      return false;
    }
    String[] args = commandArgs.split(" ", 2);
    controller.deleteRemoteFile(args[0], args[1]);
    respondText(context, 200, "Remote file deleted.");
    return true;
  }

  @Action(group = SshService.MAIN_GROUP,
      command = "close",
      description = "Close a ssh connection     Usage: close <id>")
  public Boolean close(CommandContext context) {
    final String commandArgs = requestBody(context);
    if (commandArgs == null || commandArgs.isEmpty()) {
      respondText(context, 200, "Usage: close <id>");
      return false;
    }
    controller.closeConnection(commandArgs);
    respondText(context, 200, "Connection closed.");
    return true;
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

  private static String requestBody(CommandContext context) {
    if (context == null || context.getAiatpRequest() == null || context.getAiatpRequest().getBody() == null) {
      return "";
    }
    String body = AiatpIO.bodyToString(context.getAiatpRequest().getBody(), StandardCharsets.UTF_8);
    return body == null ? "" : body;
  }
}
