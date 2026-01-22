package com.example.todero.agent.taskmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.base.ComponentManagerInterface;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.model.component.ComponentDescriptor;
import com.social100.todero.common.storage.Storage;
import com.social100.todero.console.base.OutputType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskManagerAgentComponentTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void validateCommandRejectsUnsupportedCommand() throws Exception {
    TaskManagerAgentComponent component = newComponent();
    Object result = invoke(component, "validateCommand", "unknown", "");
    assertFalse((Boolean) readField(result, "valid"));
    assertEquals("unsupported_action", readField(result, "errorCode"));
  }

  @Test
  void validateCommandRequiresExpectedOptions() throws Exception {
    TaskManagerAgentComponent component = newComponent();
    Object result = invoke(component, "validateCommand", "claim", "--task-id t1 --agent a1");
    assertFalse((Boolean) readField(result, "valid"));
    assertEquals("invalid_arguments", readField(result, "errorCode"));
  }

  @Test
  void validateCommandAcceptsValidArguments() throws Exception {
    TaskManagerAgentComponent component = newComponent();
    Object result = invoke(component, "validateCommand", "complete", "--task-id t1 --agent a1");
    assertTrue((Boolean) readField(result, "valid"));
  }

  @Test
  void validateCommandSupportsAttemptLookup() throws Exception {
    TaskManagerAgentComponent component = newComponent();
    Object result = invoke(component, "validateCommand", "attempt", "--task-id t1 --attempt-number 1");
    assertTrue((Boolean) readField(result, "valid"));
  }

  @Test
  void validateCommandRejectsLegacySnoozeUntilOption() throws Exception {
    TaskManagerAgentComponent component = newComponent();
    Object result = invoke(component, "validateCommand", "snooze", "--task-id t1 --until 2026-01-01T00:00:00Z");
    assertFalse((Boolean) readField(result, "valid"));
    assertEquals("invalid_arguments", readField(result, "errorCode"));
  }

  @Test
  void validateCommandFailDoesNotRequireErrorText() throws Exception {
    TaskManagerAgentComponent component = newComponent();
    Object result = invoke(component, "validateCommand", "fail", "--task-id t1 --agent a1");
    assertTrue((Boolean) readField(result, "valid"));
  }

  @Test
  void validateCommandSupportsSubscribeAndUnsubscribe() throws Exception {
    TaskManagerAgentComponent component = newComponent();
    Object subscribe = invoke(component, "validateCommand", "subscribe", "--agent agent-a");
    Object unsubscribe = invoke(component, "validateCommand", "unsubscribe", "--agent agent-a --all true");
    assertTrue((Boolean) readField(subscribe, "valid"));
    assertTrue((Boolean) readField(unsubscribe, "valid"));
  }

  @Test
  void parseToolEnvelopeParsesSuccessBody() throws Exception {
    TaskManagerAgentComponent component = newComponent();
    String body = "{\"ok\":true,\"message\":\"ok\",\"data\":{\"k\":\"v\"},\"meta\":{\"m\":\"x\"}}";
    Object parsed = invoke(component, "parseToolEnvelope", "health", "--format json", 200, body);
    assertTrue((Boolean) readField(parsed, "ok"));
    assertEquals("ok", readField(parsed, "message"));
  }

  @Test
  void parseToolEnvelopeClassifiesMalformedErrorResponse() throws Exception {
    TaskManagerAgentComponent component = newComponent();
    Object parsed = invoke(component, "parseToolEnvelope", "health", "--format json", 500, "oops");
    assertFalse((Boolean) readField(parsed, "ok"));
    assertEquals("malformed_tool_error_response", readField(parsed, "errorCode"));
  }

  @Test
  void ensureJsonFormatAppendsFlag() throws Exception {
    String appended = (String) invokeStatic(TaskManagerAgentComponent.class, "ensureJsonFormat", "--task-id x");
    assertTrue(appended.contains("--format json"));
  }

  @Test
  void processHappyPathWithMockedToolResponse() throws Exception {
    AtomicReference<AiatpIO.HttpResponse> out = new AtomicReference<>();
    FakeComponentManager manager = new FakeComponentManager(200, "{\"ok\":true,\"message\":\"healthy\"}");
    CommandContext context = newContext("process", "execute health", manager, out);

    TaskManagerAgentComponent component = newComponent();
    assertTrue(component.process(context));

    String responseBody = responseBody(out.get());
    JsonNode root = JSON.readTree(responseBody);
    assertEquals("ok", root.path("meta").path("status").asText());
    assertEquals("ACTION_NONE", root.path("meta").path("stopReason").asText());
    assertTrue(manager.lastArgs.contains("--format json"));
  }

  @Test
  void processToolFailurePathReturnsExecutionFailure() throws Exception {
    AtomicReference<AiatpIO.HttpResponse> out = new AtomicReference<>();
    FakeComponentManager manager = new FakeComponentManager(400,
        "{\"ok\":false,\"errorCode\":\"execution_failed\",\"message\":\"boom\"}");
    CommandContext context = newContext("process", "execute health", manager, out);

    TaskManagerAgentComponent component = newComponent();
    assertTrue(component.process(context));

    String responseBody = responseBody(out.get());
    JsonNode root = JSON.readTree(responseBody);
    assertEquals("error", root.path("meta").path("status").asText());
    assertEquals("TOOL_EXECUTION_FAILED", root.path("meta").path("stopReason").asText());
    assertEquals("execution_failed", root.path("meta").path("errorCode").asText());
  }

  @Test
  void reactPayloadAcceptedPathReturnsAcceptedEnvelope() throws Exception {
    AtomicReference<AiatpIO.HttpResponse> out = new AtomicReference<>();
    CommandContext context = newContext(
        "react",
        "{\"event_id\":\"e1\",\"seq\":1,\"event_type\":\"TASK_DUE\",\"task_id\":\"t1\"}",
        new FakeComponentManager(200, "{\"ok\":true}"),
        out);

    TaskManagerAgentComponent component = newComponent();
    assertTrue(component.react(context));

    String responseBody = responseBody(out.get());
    JsonNode root = JSON.readTree(responseBody);
    assertEquals("accepted", root.path("meta").path("status").asText());
    assertEquals("ACTION_NONE", root.path("meta").path("stopReason").asText());
  }

  @Test
  void reactPayloadMissingRequiredFieldReturnsInvalidArguments() throws Exception {
    AtomicReference<AiatpIO.HttpResponse> out = new AtomicReference<>();
    CommandContext context = newContext(
        "react",
        "{\"event_id\":\"e1\",\"seq\":1,\"event_type\":\"TASK_DUE\"}",
        new FakeComponentManager(200, "{\"ok\":true}"),
        out);

    TaskManagerAgentComponent component = newComponent();
    assertTrue(component.react(context));

    String responseBody = responseBody(out.get());
    JsonNode root = JSON.readTree(responseBody);
    assertEquals("error", root.path("meta").path("status").asText());
    assertEquals("INVALID_ARGUMENTS", root.path("meta").path("stopReason").asText());
    assertEquals("invalid_event_payload", root.path("meta").path("errorCode").asText());
  }

  private static TaskManagerAgentComponent newComponent() {
    return new TaskManagerAgentComponent(new FakeStorage());
  }

  private static CommandContext newContext(String command,
                                           String body,
                                           ComponentManagerInterface manager,
                                           AtomicReference<AiatpIO.HttpResponse> out) {
    return CommandContext.builder()
        .sourceId("test-client")
        .componentManager(manager)
        .consumer(out::set)
        .httpRequest(AiatpIO.HttpRequest.newBuilder("ACTION", "/com.shellaia.verbatim.agent.task.manager/" + command)
            .body(AiatpIO.Body.ofString(body, StandardCharsets.UTF_8))
            .build())
        .build();
  }

  private static String responseBody(AiatpIO.HttpResponse response) {
    assertNotNull(response);
    return AiatpIO.bodyToString(response.body(), StandardCharsets.UTF_8);
  }

  private static Object invoke(Object target, String method, Object... args) throws Exception {
    Method m = findMethod(target.getClass(), method, args);
    m.setAccessible(true);
    return m.invoke(target, args);
  }

  private static Object invokeStatic(Class<?> targetClass, String method, Object... args) throws Exception {
    Method m = findMethod(targetClass, method, args);
    m.setAccessible(true);
    return m.invoke(null, args);
  }

  private static Method findMethod(Class<?> type, String name, Object... args) throws NoSuchMethodException {
    for (Method method : type.getDeclaredMethods()) {
      if (!method.getName().equals(name)) {
        continue;
      }
      Class<?>[] paramTypes = method.getParameterTypes();
      if (paramTypes.length != args.length) {
        continue;
      }
      boolean compatible = true;
      for (int i = 0; i < paramTypes.length; i++) {
        if (args[i] == null) {
          continue;
        }
        Class<?> expected = wrap(paramTypes[i]);
        Class<?> provided = wrap(args[i].getClass());
        if (!expected.isAssignableFrom(provided)) {
          compatible = false;
          break;
        }
      }
      if (compatible) {
        return method;
      }
    }
    throw new NoSuchMethodException(type.getName() + "#" + name);
  }

  private static Object readField(Object target, String fieldName) throws Exception {
    Field f = target.getClass().getDeclaredField(fieldName);
    f.setAccessible(true);
    return f.get(target);
  }

  private static Class<?> wrap(Class<?> type) {
    if (!type.isPrimitive()) {
      return type;
    }
    if (type == boolean.class) {
      return Boolean.class;
    }
    if (type == int.class) {
      return Integer.class;
    }
    if (type == long.class) {
      return Long.class;
    }
    if (type == double.class) {
      return Double.class;
    }
    if (type == float.class) {
      return Float.class;
    }
    if (type == short.class) {
      return Short.class;
    }
    if (type == byte.class) {
      return Byte.class;
    }
    if (type == char.class) {
      return Character.class;
    }
    return type;
  }

  private static final class FakeComponentManager implements ComponentManagerInterface {
    private final int status;
    private final String responseBody;
    private String lastArgs = "";

    private FakeComponentManager(int status, String responseBody) {
      this.status = status;
      this.responseBody = responseBody;
    }

    @Override
    public List<String> generateAutocompleteStrings() {
      return List.of();
    }

    @Override
    public String getHelp(String componentName, String commandName, OutputType outputType) {
      return "";
    }

    @Override
    public List<ComponentDescriptor> getComponents(boolean includeHidden, ServerType typeFilter) {
      return List.of();
    }

    @Override
    public ComponentDescriptor getComponent(String componentName, boolean includeHidden) {
      return null;
    }

    @Override
    public void execute(String componentName, String command, CommandContext context, boolean useComponentsAll) {
      this.lastArgs = AiatpIO.bodyToString(context.getHttpRequest().body(), StandardCharsets.UTF_8);
      context.response(AiatpIO.HttpResponse.newBuilder(status)
          .setHeader("Content-Type", "application/json; charset=utf-8")
          .body(AiatpIO.Body.ofString(responseBody, StandardCharsets.UTF_8))
          .build());
    }
  }

  private static final class FakeStorage implements Storage {
    @Override
    public void writeFile(String relativePath, byte[] bytes) {
    }

    @Override
    public byte[] readFile(String relativePath) throws IOException {
      throw new IOException("not found");
    }

    @Override
    public void deleteFile(String relativePath) {
    }

    @Override
    public List<String> listFiles(String relativeDir) {
      return List.of();
    }

    @Override
    public void putSecret(String key, String value) {
    }

    @Override
    public String getSecret(String key) {
      return null;
    }

    @Override
    public void deleteSecret(String key) {
    }
  }
}
