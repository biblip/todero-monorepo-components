package com.shellaia.component.renderer;

import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpRequest;
import com.social100.todero.common.aiatpio.AiatpResponse;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.storage.Storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@AIAController(
    name = "com.shellaia.renderer",
    type = ServerType.AIA,
    visible = false,
    description = "Fallback HTML renderer for components without native html actions"
)
public class RendererComponent {
  static final String MAIN_GROUP = "Main";
  private static final String TEMPLATE_PATH = "com/shellaia/component/renderer/component.html";
  private static final String TEMPLATE = loadResourceText(TEMPLATE_PATH);

  public RendererComponent(Storage storage) {
  }

  @Action(
      group = MAIN_GROUP,
      command = "html",
      description = "Render fallback HTML for components without native html actions. Usage: html"
  )
  public Boolean htmlCommand(CommandContext context) {
    AiatpRequest request = context == null ? null : context.getAiatpRequest();
    String targetComponent = trimToDefault(request == null ? null : request.getQueryParam("target-component"), "unknown");
    String targetPath = trimToDefault(request == null ? null : request.getQueryParam("target-path"), "/unknown/html");
    String host = trimToDefault(resolveHost(request), "unknown");
    String generatedAt = Instant.now().toString();
    String html = TEMPLATE
        .replace("${TITLE}", escapeHtml("ShellAIA Renderer"))
        .replace("${HEADING}", escapeHtml("ShellAIA Default HTML Renderer"))
        .replace("${SUMMARY}", escapeHtml("Fallback HTML generated for a component that does not implement its own html action."))
        .replace("${TARGET_COMPONENT}", escapeHtml(targetComponent))
        .replace("${TARGET_PATH}", escapeHtml(targetPath))
        .replace("${HOST}", escapeHtml(host))
        .replace("${GENERATED_AT}", escapeHtml(generatedAt));
    context.complete(buildTextResponse(200, html, "text/html; charset=utf-8"));
    return true;
  }

  private static AiatpResponse buildTextResponse(int statusCode, String body, String contentType) {
    AiatpIO.Headers headers = new AiatpIO.Headers();
    headers.set("Content-Type", contentType);
    return AiatpResponse.builder()
        .statusCode(statusCode)
        .reasonPhrase(statusCode >= 400 ? "error" : "completed")
        .headers(headers)
        .body(AiatpIO.Body.ofString(body == null ? "" : body, StandardCharsets.UTF_8))
        .build();
  }

  private static String resolveHost(AiatpRequest request) {
    if (request == null || request.getHeaders() == null) {
      return null;
    }
    return request.getHeaders().getFirst("Host");
  }

  private static String trimToDefault(String value, String fallback) {
    if (value == null) {
      return fallback;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? fallback : trimmed;
  }

  private static String escapeHtml(String value) {
    String input = value == null ? "" : value;
    return input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private static String loadResourceText(String path) {
    try (InputStream in = RendererComponent.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("Resource not found: " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load resource: " + path, e);
    }
  }
}
