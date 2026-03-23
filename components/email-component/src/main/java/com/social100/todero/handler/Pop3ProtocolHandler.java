package com.social100.todero.handler;

import jakarta.mail.*;

import java.util.Properties;

public class Pop3ProtocolHandler implements MailProtocolHandler {
  private final Properties properties;
  private final String username;
  private final String password;
  private Store store;

  public Pop3ProtocolHandler(Properties properties, String username, String password) {
    this.properties = properties;
    this.username = username;
    this.password = password;
  }

  @Override
  public void connect() throws MessagingException {
    Session session = Session.getInstance(properties);
    store = session.getStore("pop3");
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
