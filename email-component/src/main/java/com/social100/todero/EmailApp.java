package com.social100.todero;

import com.social100.todero.handler.MailProtocolHandler;
import com.social100.todero.handler.MailProtocolHandlerFactory;

import java.util.Properties;

public class EmailApp {
  public static void main(String[] args) {
    String protocol = "imap"; // Or "pop3"
    String host = protocol.equals("imap") ? "imap.gmail.com" : "pop.gmail.com";
    int port = protocol.equals("imap") ? 993 : 995;
    boolean useSSL = true;
    String username = "arturoportilla@gmail.com";
    String password = "your-app-password";
    boolean useOAuth2 = true; // Change to true for OAuth2
    String provider = "gmail"; // Options: gmail, outlook, yahoo

    try {
      // Fetch properties based on the protocol
      Properties properties = EmailConfig.getProperties(protocol, host, port, useSSL);

      // Create appropriate protocol handler
      MailProtocolHandler protocolHandler = MailProtocolHandlerFactory.create(protocol, properties, username, password);

      // Configure SMTP properties for sending emails
      Properties smtpProperties = EmailConfig.getProperties("smtp", "smtp.gmail.com", 587, true);

      // Initialize EmailService
      EmailService emailService = new EmailService(protocolHandler, smtpProperties, username, password, useOAuth2, provider);

      // Fetch emails from the inbox
      emailService.fetchEmails("INBOX");

      // Send a test email
      emailService.sendEmail("recipient@example.com", "Test Subject", "This is a test email.");

    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
