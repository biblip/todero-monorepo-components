package com.shellaia.component.ssh.service;

public record CommandResult(int exitCode, String stdout, String stderr) {
}

