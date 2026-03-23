package com.social100.todero;

import com.social100.todero.handler.MailProtocolHandler;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

public class EmailService {
  private final MailProtocolHandler protocolHandler;
  private final Properties smtpProperties;
  private final String smtpUsername;
  private final String smtpPassword;
  private final boolean useOAuth2;
  private final String provider;

  public EmailService(MailProtocolHandler protocolHandler, Properties smtpProperties, String smtpUsername, String smtpPassword, boolean useOAuth2, String provider) {
    this.protocolHandler = protocolHandler;
    this.smtpProperties = smtpProperties;
    this.smtpUsername = smtpUsername;
    this.smtpPassword = smtpPassword;
    this.useOAuth2 = useOAuth2;
    this.provider = provider;
  }

  public void fetchEmails(String folderName) {
    try {
      protocolHandler.connect();
      Folder folder = protocolHandler.getFolder(folderName);
      folder.open(Folder.READ_ONLY);

      Message[] messages = protocolHandler.fetchMessages(folder);

      for (Message message : messages) {
        System.out.println("Subject: " + message.getSubject());
        System.out.println("From: " + message.getFrom()[0]);
        System.out.println("Content: " + getMessageContent(message));

        // Process attachments
        if (message.isMimeType("multipart/*")) {
          MimeMultipart multipart = (MimeMultipart) message.getContent();
          for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart bodyPart = multipart.getBodyPart(i);
            if (Part.ATTACHMENT.equalsIgnoreCase(bodyPart.getDisposition())) {
              System.out.println("Attachment: " + bodyPart.getFileName());
              InputStream is = bodyPart.getInputStream();
              Files.copy(is, Paths.get("/path/to/save/" + bodyPart.getFileName()));
            }
          }
        }
      }

      folder.close(false);
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      try {
        protocolHandler.disconnect();
      } catch (MessagingException e) {
        e.printStackTrace();
      }
    }
  }

  public void sendEmail(String to, String subject, String body) {
    try {
      Session session;
      if (useOAuth2) {
        session = Session.getInstance(smtpProperties);
        smtpProperties.put("mail.smtp.auth.mechanisms", "XOAUTH2");

        // Get the OAuth2 token
        String oauthToken = OAuth2TokenProvider.getToken(provider);

        session.setDebug(true); // Enable debug for testing

        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(smtpUsername));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        message.setSubject(subject);

        // Create the message body
        MimeBodyPart bodyPart = new MimeBodyPart();
        bodyPart.setText(body);

        // Combine parts into a multipart message
        Multipart multipart = new MimeMultipart();
        multipart.addBodyPart(bodyPart);

        message.setContent(multipart);

        // Send email using OAuth2
        Transport transport = session.getTransport("smtp");
        transport.connect(smtpProperties.getProperty("mail.smtp.host"), smtpUsername, oauthToken);
        transport.sendMessage(message, message.getAllRecipients());
        transport.close();

        System.out.println("Email sent successfully to: " + to);
      } else {
        session = Session.getInstance(smtpProperties, new Authenticator() {
          @Override
          protected PasswordAuthentication getPasswordAuthentication() {
            return new PasswordAuthentication(smtpUsername, smtpPassword);
          }
        });

        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(smtpUsername));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        message.setSubject(subject);

        // Create the message body
        MimeBodyPart bodyPart = new MimeBodyPart();
        bodyPart.setText(body);

        // Combine parts into a multipart message
        Multipart multipart = new MimeMultipart();
        multipart.addBodyPart(bodyPart);

        message.setContent(multipart);

        Transport.send(message);

        System.out.println("Email sent successfully to: " + to);
      }
    } catch (MessagingException e) {
      e.printStackTrace();
    }
  }

  private String getMessageContent(Message message) throws Exception {
    if (message.isMimeType("text/plain")) {
      return message.getContent().toString();
    } else if (message.isMimeType("multipart/*")) {
      MimeMultipart mimeMultipart = (MimeMultipart) message.getContent();
      return getTextFromMimeMultipart(mimeMultipart);
    }
    return "";
  }

  private String getTextFromMimeMultipart(MimeMultipart mimeMultipart) throws Exception {
    StringBuilder result = new StringBuilder();
    int count = mimeMultipart.getCount();
    for (int i = 0; i < count; i++) {
      BodyPart bodyPart = mimeMultipart.getBodyPart(i);
      if (bodyPart.isMimeType("text/plain")) {
        result.append(bodyPart.getContent());
      }
    }
    return result.toString();
  }
}