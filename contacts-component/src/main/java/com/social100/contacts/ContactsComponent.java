package com.social100.contacts;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.storage.Storage;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@AIAController(name = "com.shellaia.verbatim.component.contacts",
    type = ServerType.AIA,
    visible = true,
    description = "Contact directory for email workflows")
public class ContactsComponent {
  private static final String MAIN_GROUP = "Contacts";
  private static final String STORE_FILE = "contacts.json";
  private static final Gson GSON = new Gson();
  private static final Type LIST_TYPE = new TypeToken<List<Contact>>() {
  }.getType();

  private final Storage storage;

  public ContactsComponent(Storage storage) {
    this.storage = storage;
  }

  @Action(group = MAIN_GROUP,
      command = "add",
      description = "Add or update a contact. Usage: add name=<name> email=<email> groups=team,ops")
  public Boolean add(CommandContext context) {
    String body = bodyString(context);
    Map<String, String> args = parseKeyValue(body);
    String email = args.get("email");
    if (email == null || email.isBlank()) {
      context.completeJson(200, render(false, "missing_email",
          "Email is required to add a contact.",
          "Missing required parameter: email",
          null, false, null));
      return true;
    }
    String name = args.getOrDefault("name", "").trim();
    List<String> groups = splitCsv(args.get("groups"));

    List<Contact> contacts = loadContacts();
    Optional<Contact> existing = contacts.stream()
        .filter(c -> c.email.equalsIgnoreCase(email))
        .findFirst();

    Contact out;
    String status;
    if (existing.isPresent()) {
      Contact c = existing.get();
      if (!name.isBlank()) c.name = name;
      if (!groups.isEmpty()) c.groups = groups;
      out = c;
      status = "Updated contact: " + c.email;
    } else {
      Contact c = new Contact();
      c.name = name.isBlank() ? email : name;
      c.email = email;
      c.groups = groups;
      contacts.add(c);
      out = c;
      status = "Added contact: " + c.email;
    }

    saveContacts(contacts);
    context.completeJson(200, render(true, null,
        status,
        status,
        null, false,
        Map.of("contact", out)));
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "list",
      description = "List all contacts")
  public Boolean list(CommandContext context) {
    List<Contact> contacts = loadContacts();
    String html = renderContactsHtml("All Contacts", contacts);
    String status = contacts.size() + " contacts";
    context.completeJson(200, render(true, null,
        "Contacts directory loaded.",
        status,
        html, true,
        Map.of("total", contacts.size(), "contacts", contacts)));
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "find",
      description = "Find by name or email. Usage: find query=<text>")
  public Boolean find(CommandContext context) {
    String body = bodyString(context);
    Map<String, String> args = parseKeyValue(body);
    String query = args.get("query");
    if (query == null || query.isBlank()) {
      context.completeJson(200, render(false, "missing_query",
          "Query is required to find contacts.",
          "Missing required parameter: query",
          null, false, null));
      return true;
    }
    String needle = query.toLowerCase(Locale.ROOT);
    List<Contact> matches = loadContacts().stream()
        .filter(c -> c.name.toLowerCase(Locale.ROOT).contains(needle)
            || c.email.toLowerCase(Locale.ROOT).contains(needle))
        .collect(Collectors.toList());
    String html = renderContactsHtml("Matches for \"" + query + "\"", matches);
    String status = matches.size() + " matches";
    context.completeJson(200, render(true, null,
        matches.isEmpty() ? "No matching contacts found." : "Found matching contacts.",
        status,
        html, true,
        Map.of("query", query, "total", matches.size(), "contacts", matches)));
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "group",
      description = "List contacts in a group. Usage: group name=<group>")
  public Boolean group(CommandContext context) {
    String body = bodyString(context);
    Map<String, String> args = parseKeyValue(body);
    String group = args.get("name");
    if (group == null || group.isBlank()) {
      context.completeJson(200, render(false, "missing_group_name",
          "Group name is required.",
          "Missing required parameter: name",
          null, false, null));
      return true;
    }
    String needle = group.toLowerCase(Locale.ROOT);
    List<Contact> matches = loadContacts().stream()
        .filter(c -> c.groups.stream().anyMatch(g -> g.equalsIgnoreCase(needle)))
        .collect(Collectors.toList());
    String html = renderContactsHtml("Group: " + group, matches);
    String status = matches.size() + " contacts in group";
    context.completeJson(200, render(true, null,
        matches.isEmpty() ? "No contacts in that group." : "Group contacts loaded.",
        status,
        html, true,
        Map.of("group", group, "total", matches.size(), "contacts", matches)));
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "remove",
      description = "Remove a contact by email. Usage: remove email=<email>")
  public Boolean remove(CommandContext context) {
    String body = bodyString(context);
    Map<String, String> args = parseKeyValue(body);
    String email = args.get("email");
    if (email == null || email.isBlank()) {
      context.completeJson(200, render(false, "missing_email",
          "Email is required to remove a contact.",
          "Missing required parameter: email",
          null, false, null));
      return true;
    }
    List<Contact> contacts = loadContacts();
    int before = contacts.size();
    contacts.removeIf(c -> c.email.equalsIgnoreCase(email));
    if (contacts.size() == before) {
      context.completeJson(200, render(false, "contact_not_found",
          "Contact not found: " + email,
          "Contact not found",
          null, false, Map.of("email", email)));
      return true;
    }
    saveContacts(contacts);
    context.completeJson(200, render(true, null,
        "Removed contact: " + email,
        "Contact removed",
        null, false, Map.of("email", email)));
    return true;
  }

  private String bodyString(CommandContext context) {
    return context.getAiatpRequest() == null || context.getAiatpRequest().getBody() == null ? "" : AiatpIO.bodyToString(context.getAiatpRequest().getBody(), StandardCharsets.UTF_8).trim();
  }

  private List<Contact> loadContacts() {
    try {
      byte[] raw = storage.readFile(STORE_FILE);
      if (raw == null || raw.length == 0) return new ArrayList<>();
      List<Contact> parsed = GSON.fromJson(new String(raw, StandardCharsets.UTF_8), LIST_TYPE);
      return parsed == null ? new ArrayList<>() : parsed;
    } catch (IOException e) {
      return new ArrayList<>();
    }
  }

  private void saveContacts(List<Contact> contacts) {
    try {
      storage.writeFile(STORE_FILE, GSON.toJson(contacts).getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new RuntimeException("Failed to save contacts", e);
    }
  }

  private Map<String, String> parseKeyValue(String input) {
    if (input == null || input.isBlank()) return Collections.emptyMap();
    Map<String, String> out = new LinkedHashMap<>();
    String normalized = input.replace(";", " ").trim();
    for (String token : normalized.split("\\s+")) {
      if (token.isBlank()) continue;
      int eq = token.indexOf('=');
      if (eq <= 0 || eq == token.length() - 1) continue;
      String key = token.substring(0, eq).trim().toLowerCase(Locale.ROOT);
      String value = token.substring(eq + 1).trim();
      out.put(key, value);
    }
    return out;
  }

  private List<String> splitCsv(String raw) {
    if (raw == null || raw.isBlank()) return new ArrayList<>();
    return List.of(raw.split(",")).stream()
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toList());
  }

  private String render(boolean ok,
                        String errorCode,
                        String chatMessage,
                        String statusMessage,
                        String webviewHtml,
                        boolean replace,
                        Map<String, Object> data) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("ok", ok);
    payload.put("errorCode", errorCode);
    payload.put("message", statusMessage);

    Map<String, Object> channels = new LinkedHashMap<>();
    channels.put("chat", Map.of("message", chatMessage == null ? "" : chatMessage));
    channels.put("status", Map.of("message", statusMessage == null ? "" : statusMessage));
    Map<String, Object> webview = new LinkedHashMap<>();
    webview.put("html", webviewHtml);
    webview.put("mode", webviewHtml == null || webviewHtml.isBlank() ? "none" : "html");
    webview.put("replace", replace);
    channels.put("webview", webview);
    payload.put("channels", channels);

    payload.put("auth", null);
    payload.put("data", data == null ? Map.of() : data);
    payload.put("meta", Map.of(
        "component", "contacts",
        "timestamp", Instant.now().toString()
    ));
    return GSON.toJson(payload);
  }

  private String renderContactsHtml(String title, List<Contact> contacts) {
    StringBuilder html = new StringBuilder(1024);
    html.append("<html><body style=\"font-family:sans-serif;background:#0d1117;color:#e6edf3;padding:12px;margin:0;\">");
    html.append("<div style=\"border-radius:12px;background:#161b22;padding:12px;border:1px solid #30363d;\">");
    html.append("<div style=\"font-size:16px;font-weight:700;margin-bottom:8px;\">").append(escapeHtml(title)).append("</div>");
    html.append("<div style=\"font-size:12px;color:#8b949e;margin-bottom:10px;\">").append(contacts.size()).append(" contacts</div>");
    if (contacts.isEmpty()) {
      html.append("<div style=\"font-size:13px;color:#c9d1d9;\">No contacts found.</div>");
    } else {
      for (Contact c : contacts) {
        html.append("<div style=\"margin-bottom:10px;border-bottom:1px solid #21262d;padding-bottom:8px;\">");
        html.append("<div style=\"font-size:14px;font-weight:600;\">").append(escapeHtml(c.name)).append("</div>");
        html.append("<div style=\"font-size:12px;color:#58a6ff;\">").append(escapeHtml(c.email)).append("</div>");
        if (c.groups != null && !c.groups.isEmpty()) {
          html.append("<div style=\"font-size:12px;color:#8b949e;margin-top:4px;\">groups: ")
              .append(escapeHtml(String.join(", ", c.groups))).append("</div>");
        }
        html.append("</div>");
      }
    }
    html.append("</div></body></html>");
    return html.toString();
  }

  private static String escapeHtml(String value) {
    if (value == null) return "";
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private static final class Contact {
    String name;
    String email;
    List<String> groups = new ArrayList<>();
  }
}
