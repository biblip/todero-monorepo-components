package com.social100.todero.handler;

import jakarta.mail.*;

import java.util.Properties;

public class ImapProtocolHandler implements MailProtocolHandler {
  private final Properties properties;
  private final String username;
  private final String password;
  private Store store;

  public ImapProtocolHandler(Properties properties, String username, String password) {
    this.properties = properties;
    this.username = username;
    this.password = password;
  }

  @Override
  public void connect() throws MessagingException {
    Session session = Session.getInstance(properties);
    store = session.getStore("imap");
    store.connect(username, password);
  }

  @Override
  public Folder getFolder(String folderName) throws MessagingException {
    return store.getFolder(folderName);
  }

  @Override
  public Message[] fetchMessages(Folder folder) throws MessagingException {
    return folder.getMessages();
  }

  @Override
  public void disconnect() throws MessagingException {
    if (store != null) store.close();
  }
}
