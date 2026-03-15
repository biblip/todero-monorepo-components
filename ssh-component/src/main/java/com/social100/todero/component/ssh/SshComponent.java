package com.social100.todero.component.ssh;

import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.storage.Storage;
import com.social100.todero.component.ssh.controller.SshController;
import com.social100.todero.component.ssh.service.SshService;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

@AIAController(name = "com.shellaia.verbatim.component.ssh",
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
      context.completeText(200, "Usage: open <id> <host> <user> <pemPath>");
      return false;
    }
    String[] args = commandArgs.split(" ", 3);
    controller.openConnectionWithKey(args[0], args[1], args[2], args[3]);
    context.completeText(200, "Connection opened.");
    return true;
  }

  @Action(group = SshService.MAIN_GROUP,
      command = "open-pass",
      description = "Opens a ssh connection, requires user and password     Usage: open-pass <id> <host> <user> <passwordFilePath>")
  public Boolean openWithPassword(CommandContext context) {
    final String commandArgs = requestBody(context);
    if (commandArgs == null || commandArgs.isEmpty()) {
      context.completeText(200, "Usage: open-pass <id> <host> <user> <passwordFilePath>");
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
      context.completeText(200, "Connection opened with password. id:" + args[0]);
      return true;
    } catch (IOException e) {
      context.completeText(200, "Failed to read password file: " + e.getMessage());
      return false;
    }
  }

  @Action(group = SshService.MAIN_GROUP,
      command = "run",
      description = "Execute commands on a ssh connection     Usage: run <id> <command>")
  public Boolean run(CommandContext context) {
    final String commandArgs = requestBody(context);
    if (commandArgs == null || commandArgs.isEmpty()) {
      context.completeText(200, "Usage: run <id> <command>");
      return false;
    }
    String[] args = commandArgs.split(" ", 2);
    controller.executeCommand(args[0],
        args[1],
        Duration.ofSeconds(60),
        output -> context.completeText(200, output),
        output -> context.completeText(200, output));
    context.completeText(200, "Command executed.");
    return true;
  }

  @Action(group = SshService.MAIN_GROUP,
      command = "upload",
      description = "Upload a file or script to the ssh server     Usage: upload <id> <localPath> <remotePath>")
  public Boolean upload(CommandContext context) {
    final String commandArgs = requestBody(context);
    if (commandArgs == null || commandArgs.isEmpty()) {
      context.completeText(200, "Usage: upload <id> <localPath> <remotePath>");
      return false;
    }
    String[] args = commandArgs.split(" ", 3);
    controller.uploadScript(args[0], args[1], args[2]);
    context.completeText(200, "Upload complete.");
    return true;
  }

  @Action(group = SshService.MAIN_GROUP,
      command = "delete",
      description = "Delete a remote file     Usage: delete <id> <remotePath>")
  public Boolean delete(CommandContext context) {
    final String commandArgs = requestBody(context);
    if (commandArgs == null || commandArgs.isEmpty()) {
      context.completeText(200, "Usage: delete <id> <remotePath>");
      return false;
    }
    String[] args = commandArgs.split(" ", 2);
    controller.deleteRemoteFile(args[0], args[1]);
    context.completeText(200, "Remote file deleted.");
    return true;
  }

  @Action(group = SshService.MAIN_GROUP,
      command = "close",
      description = "Close a ssh connection     Usage: close <id>")
  public Boolean close(CommandContext context) {
    final String commandArgs = requestBody(context);
    if (commandArgs == null || commandArgs.isEmpty()) {
      context.completeText(200, "Usage: close <id>");
      return false;
    }
    controller.closeConnection(commandArgs);
    context.completeText(200, "Connection closed.");
    return true;
  }


  private static String requestBody(CommandContext context) {
    if (context == null || context.getAiatpRequest() == null || context.getAiatpRequest().getBody() == null) {
      return "";
    }
    String body = AiatpIO.bodyToString(context.getAiatpRequest().getBody(), StandardCharsets.UTF_8);
    return body == null ? "" : body;
  }
}
