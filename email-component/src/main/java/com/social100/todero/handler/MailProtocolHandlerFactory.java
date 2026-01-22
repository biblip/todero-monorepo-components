package com.social100.todero.handler;

import java.util.Properties;

public class MailProtocolHandlerFactory {
  public static MailProtocolHandler create(String protocol, Properties properties, String username, String password) {
    return switch (protocol.toLowerCase()) {
      case "imap" -> new ImapProtocolHandler(properties, username, password);
      case "pop3" -> new Pop3ProtocolHandler(properties, username, password);
      default -> throw new IllegalArgumentException("Unsupported protocol: " + protocol);
    };
  }
}
