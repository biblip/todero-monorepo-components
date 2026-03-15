package com.example.todero.component.template;

import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpRequest;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.storage.Storage;
import java.util.LinkedHashMap;
import java.util.Map;

@AIAController(
    name = "com.example.todero.component.template",
    type = ServerType.AIA,
    visible = true,
    description = "Template component for Todero",
    events = TemplateEvents.class)
public class TemplateComponent {

  @SuppressWarnings("unused")
  private final Storage storage;

  public TemplateComponent(Storage storage) {
    this.storage = storage;
  }

  @Action(group = "template", command = "ping", description = "Respond with pong.")
  public Boolean ping(CommandContext context) {
    context.completeText(200, "pong");
    return Boolean.TRUE;
  }

  @Action(group = "template", command = "echo", description = "Echo the request body.")
  public Boolean echo(CommandContext context) {
    AiatpRequest request = context.getAiatpRequest();
    String body = request == null ? "" : bodyToString(request);
    context.completeText(200, body);
    return Boolean.TRUE;
  }

  @Action(group = "template", command = "headers", description = "Return request headers.")
  public Boolean headers(CommandContext context) {
    AiatpRequest request = context.getAiatpRequest();
    Map<String, String> headers = request == null ? Map.of() : headersToMap(request);
    context.completeText(200, toJsonLike(headers));
    return Boolean.TRUE;
  }

  @Action(group = "template", command = "event", description = "Emit an event and respond.")
  public Boolean event(CommandContext context) {
    context.emitCustom(TemplateEvents.TEMPLATE_EVENT.name(), TemplateEvents.TEMPLATE_EVENT.name(),
        "text/plain; charset=utf-8", "event fired".getBytes(AiatpIO.UTF_8), "final");
    context.completeText(200, "event emitted");
    return Boolean.TRUE;
  }

  private String bodyToString(AiatpRequest request) {
    if (request.getBody() == null) {
      return "";
    }
    return AiatpIO.bodyToString(request.getBody(), AiatpIO.UTF_8);
  }

  private Map<String, String> headersToMap(AiatpRequest request) {
    if (request.getHeaders() == null) {
      return Map.of();
    }
    Map<String, String> headers = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : request.getHeaders()) {
      headers.put(entry.getKey(), entry.getValue());
    }
    return headers;
  }

  private String toJsonLike(Map<String, String> headers) {
    StringBuilder builder = new StringBuilder();
    builder.append("{");
    boolean first = true;
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      if (!first) {
        builder.append(", ");
      }
      first = false;
      builder.append("\"")
          .append(escape(entry.getKey()))
          .append("\": \"")
          .append(escape(entry.getValue()))
          .append("\"");
    }
    builder.append("}");
    return builder.toString();
  }

  private String escape(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
