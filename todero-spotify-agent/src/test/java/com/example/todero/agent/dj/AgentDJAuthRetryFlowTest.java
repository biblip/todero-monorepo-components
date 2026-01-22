package com.example.todero.agent.dj;

import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.base.ComponentManagerInterface;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.model.component.ComponentDescriptor;
import com.social100.todero.common.storage.Storage;
import com.social100.todero.console.base.OutputType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDJAuthRetryFlowTest {

  @Test
  void authCompleteRetriesPendingToolActionSuccessfully() throws Exception {
    Path ledgerDir = Files.createTempDirectory("dj-ledger-test-");
      System.setProperty("todero.agent.dj.ledger.dir", ledgerDir.toString());

    try {
      AgentDJComponent component = new AgentDJComponent(new InMemoryStorage());
      StubManager manager = new StubManager();
      CommandContext parent = CommandContext.builder()
          .componentManager(manager)
          .httpRequest(AiatpIO.HttpRequest.newBuilder("ACTION", "/com.shellaia.verbatim.agent.dj/process")
              .body(AiatpIO.Body.ofString("test", StandardCharsets.UTF_8))
              .build())
          .build();

      String rootWorkId = openRootWork(component);

      Object pending = newPendingAuthRetry("play", "spotify:track:abc", "play song", rootWorkId);
      putPendingRetry(component, "sess-1", pending);

      Object intent = parseIntent("auth-complete session-id=sess-1 state=st1 code=code1");
      Method run = AgentDJComponent.class.getDeclaredMethod(
          "runAuthCompletionFlow",
          CommandContext.class,
          intent.getClass(),
          String.class,
          String.class,
          String.class,
          String.class
      );
      run.setAccessible(true);

      Object loop = run.invoke(component, parent, intent, "auth-complete session-id=sess-1 state=st1 code=code1",
          "process", "corr-1", rootWorkId);

      Method stopReasonAccessor = loop.getClass().getDeclaredMethod("stopReason");
      stopReasonAccessor.setAccessible(true);
      Object stopReason = stopReasonAccessor.invoke(loop);
      assertEquals("ACTION_NONE", stopReason.toString());

      assertTrue(manager.executedCommands.contains("auth-complete"));
      assertTrue(manager.executedCommands.contains("play"));

      Map<?, ?> pendingMap = pendingMap(component);
      assertTrue(!pendingMap.containsKey("sess-1"));
    } finally {
      System.clearProperty("todero.agent.dj.ledger.dir");
    }
  }

  private static String openRootWork(AgentDJComponent component) throws Exception {
    Method open = AgentDJComponent.class.getDeclaredMethod(
        "openRootLedgerWork", String.class, String.class, boolean.class, String.class);
    open.setAccessible(true);
    Object root = open.invoke(component, "process", "play something", true, "corr-1");
    Method workId = root.getClass().getDeclaredMethod("workId");
    workId.setAccessible(true);
    return (String) workId.invoke(root);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> pendingMap(AgentDJComponent component) throws Exception {
    Field field = AgentDJComponent.class.getDeclaredField("pendingAuthRetries");
    field.setAccessible(true);
    return (Map<String, Object>) field.get(component);
  }

  private static void putPendingRetry(AgentDJComponent component, String sessionId, Object pending) throws Exception {
    pendingMap(component).put(sessionId, pending);
  }

  private static Object newPendingAuthRetry(String command, String args, String initialPrompt, String rootWorkId) throws Exception {
    Class<?> clazz = Class.forName("com.example.todero.agent.dj.AgentDJComponent$PendingAuthRetry");
    Constructor<?> ctor = clazz.getDeclaredConstructor(String.class, String.class, String.class, String.class);
    ctor.setAccessible(true);
    return ctor.newInstance(command, args, initialPrompt, rootWorkId);
  }

  private static Object parseIntent(String prompt) throws Exception {
    Method parse = AgentDJComponent.class.getDeclaredMethod("parseAuthCompletionIntent", String.class);
    parse.setAccessible(true);
    return parse.invoke(null, prompt);
  }

  private static final class StubManager implements ComponentManagerInterface {
    private final List<String> executedCommands = new ArrayList<>();

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
      executedCommands.add(command);
      String body;
      if ("auth-complete".equals(command)) {
        body = "{\"ok\":true,\"errorCode\":null,\"message\":\"Authorization completed.\",\"data\":{\"text\":\"Authorization completed.\"}}";
      } else if ("play".equals(command)) {
        body = "{\"ok\":true,\"errorCode\":null,\"message\":\"Playing track.\",\"data\":{\"text\":\"Playing track.\"}}";
      } else {
        body = "{\"ok\":false,\"errorCode\":\"execution_failed\",\"message\":\"Unsupported command in test\"}";
      }
      AiatpIO.HttpResponse response = AiatpIO.HttpResponse.newBuilder(200)
          .setHeader("Content-Type", "application/json; charset=utf-8")
          .body(AiatpIO.Body.ofString(body, StandardCharsets.UTF_8))
          .build();
      context.response(response);
    }
  }

  private static final class InMemoryStorage implements Storage {
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Map<String, String> secrets = new ConcurrentHashMap<>();

    @Override
    public void writeFile(String relativePath, byte[] bytes) {
      files.put(relativePath, bytes == null ? new byte[0] : bytes);
    }

    @Override
    public byte[] readFile(String relativePath) {
      if (".env".equals(relativePath)) {
        return new byte[0];
      }
      byte[] bytes = files.get(relativePath);
      if (bytes == null) {
        throw new RuntimeException("file not found: " + relativePath);
      }
      return bytes;
    }

    @Override
    public void deleteFile(String relativePath) {
      files.remove(relativePath);
    }

    @Override
    public List<String> listFiles(String relativeDir) {
      return Collections.unmodifiableList(new ArrayList<>(files.keySet()));
    }

    @Override
    public void putSecret(String key, String value) {
      secrets.put(key, value);
    }

    @Override
    public String getSecret(String key) {
      return secrets.get(key);
    }

    @Override
    public void deleteSecret(String key) {
      secrets.remove(key);
    }
  }
}
