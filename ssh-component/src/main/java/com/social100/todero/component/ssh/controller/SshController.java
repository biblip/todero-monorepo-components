package com.social100.todero.component.ssh.controller;

import com.social100.todero.component.ssh.service.SshService;

import java.time.Duration;
import java.util.function.Consumer;

public class SshController {

  private final SshService sshService = new SshService();

  public void openConnectionWithKey(String id, String host, String user, String pemPath) {
    try {
      sshService.connectWithKey(id, host, user, pemPath);
      System.out.println("Connection opened (key): " + id);
    } catch (Exception e) {
      System.err.println("Failed to open key-based connection: " + e.getMessage());
    }
  }

  public void openConnectionWithPassword(String id, String host, String user, String password) {
    try {
      sshService.connectWithPassword(id, host, user, password);
      System.out.println("Connection opened (password): " + id);
    } catch (Exception e) {
      System.err.println("Failed to open password-based connection: " + e.getMessage());
    }
  }

  public void uploadScript(String id, String localPath, String remotePath) {
    try {
      sshService.uploadFile(id, localPath, remotePath);
      System.out.println("Uploaded " + localPath + " to " + remotePath);
    } catch (Exception e) {
      System.err.println("Upload failed: " + e.getMessage());
    }
  }

  public void deleteRemoteFile(String id, String remotePath) {
    try {
      sshService.deleteRemoteFile(id, remotePath);
      System.out.println("Deleted remote file: " + remotePath);
    } catch (Exception e) {
      System.err.println("Deletion failed: " + e.getMessage());
    }
  }

  public void closeConnection(String id) {
    try {
      sshService.disconnect(id);
      System.out.println("Connection closed: " + id);
    } catch (Exception e) {
      System.err.println("Failed to close connection: " + e.getMessage());
    }
  }

  public boolean checkConnection(String id) {
    return sshService.isConnected(id);
  }

  public void executeCommand(String id,
                             String command,
                             Duration timeout,
                             Consumer<String> stdoutConsumer,
                             Consumer<String> stderrConsumer) {
    sshService.runCommand(id, command, stdoutConsumer, stderrConsumer, timeout)
        .thenAccept(ignore -> {
        }).exceptionally(e -> {
          stderrConsumer.accept(e.getMessage());
          return null;
        });
  }
}
