package com.social100.todero.handler;

import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;

public interface MailProtocolHandler {
  void connect() throws MessagingException;

  Folder getFolder(String folderName) throws MessagingException;

  Message[] fetchMessages(Folder folder) throws MessagingException;

  void disconnect() throws MessagingException;
}
