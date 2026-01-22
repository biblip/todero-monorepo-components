package com.social100.todero.component.ssh.service;

public record CommandResult(int exitCode, String stdout, String stderr) {
}

