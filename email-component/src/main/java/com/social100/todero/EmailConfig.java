package com.social100.todero;

import java.util.Properties;

public class EmailConfig {
  public static Properties getProperties(String protocol, String host, int port, boolean useSSL) {
    Properties properties = new Properties();
    properties.put("mail.transport.protocol", "smtp"); // Added for sending emails
    properties.put("mail.store.protocol", protocol);
    properties.put("mail." + protocol + ".host", host);
    properties.put("mail." + protocol + ".port", port);
    properties.put("mail." + protocol + ".ssl.enable", String.valueOf(useSSL));
    properties.put("mail.smtp.auth", "true"); // Added for SMTP authentication
    properties.put("mail.smtp.starttls.enable", "true"); // Enable STARTTLS
    return properties;
  }
}
