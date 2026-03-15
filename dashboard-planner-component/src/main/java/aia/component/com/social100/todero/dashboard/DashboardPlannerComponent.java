package aia.component.com.social100.todero.dashboard;

import aia.component.com.social100.todero.util.ResourceReader;
import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.ai.action.CommandAgentResponse;
import com.social100.todero.common.ai.agent.Agent;
import com.social100.todero.common.ai.agent.AgentContext;
import com.social100.todero.common.ai.agent.AgentDefinition;
import com.social100.todero.common.ai.agent.AgentPrompt;
import com.social100.todero.common.ai.llm.LLMClient;
import com.social100.todero.common.ai.llm.OpenAiLLM;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.storage.Storage;
import com.social100.todero.console.base.OutputType;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.resource.ClassPathResourceManager;
import io.undertow.server.handlers.resource.ResourceHandler;
import io.undertow.util.Headers;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.core.*;
import io.undertow.websockets.spi.WebSocketHttpExchange;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@AIAController(name = "com.shellaia.verbatim.dashboard.planner",
    type = ServerType.AIA,
    visible = true,
    description = "Dashboard Planner")
public class DashboardPlannerComponent {
  final static String MAIN_GROUP = "Main";
  private static final ConcurrentMap<String, WebSocketChannel> BY_ID = new ConcurrentHashMap<>();
  final AgentDefinition agentDefinition;
  final Undertow server;
  LLMClient llm;
  Agent planner;
  private CommandContext globalContext = null;

  public DashboardPlannerComponent(Storage storage) {
    agentDefinition = AgentDefinition.builder()
        .name("DJ Agent")
        .role("Assistant")
        .description("handle a music playback system")
        .model("gpt-4.1-nano")
        //.systemPrompt(AgentDefinition.loadSystemPromptFromResource("prompts/tool-list-generator.txt"))
        .build();

    agentDefinition.setMetadata("region", "US");

    int port = 8080;

    // Static files from src/main/resources/web (index.html, app.js, etc.)
    ResourceHandler staticHandler = Handlers.resource(
            new ClassPathResourceManager(DashboardPlannerComponent.class.getClassLoader(), "web"))
        .setDirectoryListingEnabled(false)
        .addWelcomeFiles("index.html");

    // WebSocket endpoint /ws: echo + simple broadcast
    WebSocketConnectionCallback wsCallback = new WebSocketConnectionCallback() {
      @Override
      public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {
        // onConnect:
        String id = java.util.UUID.randomUUID().toString();
        channel.setAttribute("id", id); // or use a wrapper map
        BY_ID.put(id, channel);
        channel.addCloseTask((ch) -> BY_ID.remove(id));

        System.out.println("WS open: " + channel.getPeerAddress());
        // Optional: small delay BEFORE sending welcome avoids early-close edge cases
        channel.getIoThread().execute(() ->
            WebSockets.sendText(new ResourceReader().loadResourceAsString("/data.json"), channel, null));

        channel.getReceiveSetter().set(new AbstractReceiveListener() {
          @Override
          protected void onFullTextMessage(WebSocketChannel ch, BufferedTextMessage message) {
            String text = message.getData();
            System.out.println("WS msg: " + text);
            // Echo back to sender
            WebSockets.sendText("Echo: " + text, ch, null);
            // Broadcast to all open peers
            for (WebSocketChannel peer : ch.getPeerConnections()) {
              if (peer.isOpen() && peer != ch) {
                WebSockets.sendText("Echo: " + text, peer, null);
              }
            }
          }

          @Override
          protected void onCloseMessage(CloseMessage cm, WebSocketChannel ch) {
            System.out.println("WS close: code=" + cm.getCode() + " reason=" + cm.getReason());
          }
        });
        channel.resumeReceives();
      }
    };

    PathHandler routes = Handlers.path()
        .addPrefixPath("/", exchange -> {
          exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-store");
          staticHandler.handleRequest(exchange);
        })
        .addPrefixPath("/ws", Handlers.websocket(wsCallback));

    server = Undertow.builder()
        .addHttpListener(port, "0.0.0.0")
        .setHandler(routes)
        .build();

  }

  @Action(group = MAIN_GROUP,
      command = "start",
      description = "Send data to show in the dashboard")
  public Boolean start(CommandContext context) {
    server.start();
    System.out.println("HTTP+WS on http://127.0.0.1:8080 (WS at /ws)");
    return true;
  }

  @Action(group = MAIN_GROUP,
      command = "send",
      description = "Send data to show in the dashboard")
  public Boolean send(CommandContext context) {
    System.out.println("send");
    final String commandArgs = context.getAiatpRequest() == null || context.getAiatpRequest().getBody() == null
        ? ""
        : AiatpIO.bodyToString(context.getAiatpRequest().getBody(), StandardCharsets.UTF_8);
    if (commandArgs != null && !commandArgs.isEmpty()) {
      String json = commandArgs;
      System.out.println("send : " + json);
      // somewhere else:
      BY_ID.keySet().stream().forEach(id -> {
        WebSocketChannel target = BY_ID.get(id);
        if (target != null && target.isOpen()) {
          System.out.println("Actually sended");
          WebSockets.sendText(json, target, null);
        }
      });
    }


    return true;
  }


  @Action(group = MAIN_GROUP,
      command = "process",
      description = "Send data to show in the dashboard")
  public Boolean process(CommandContext context) {
    if (llm == null) {
      llm = new OpenAiLLM(System.getenv("OPENAI_API_KEY"), agentDefinition.getModel());
      planner = new Agent(agentDefinition);
    }
    String prompt = context.getHelp("", "", OutputType.JSON);

    //  ******************************************************************
    //  ******************************************************************
    //  ******************************************************************

    AgentContext agentContext = new AgentContext();
    //context.set("name", "Arturo");
    //context.set("goal", "greet the user and confirm last command");
    //context.set("lastCommand", "restart nginx");

    // Agent 1: Planner (e.g. decomposes task)


    AgentPrompt agentPrompt = new AgentPrompt(prompt);

    try {
      CommandAgentResponse ss = (CommandAgentResponse) planner.process(llm, agentPrompt, agentContext);
      String action = ss.getAction();
      if (action != null) {
        String line = action.strip();
        List<String> arguments = null; //parseArguments(line);
        String command = arguments.isEmpty() ? null : arguments.remove(0);
        String[] commandArgs = arguments.toArray(new String[0]);

        CommandContext internalContext = CommandContext.builder()
            .terminalConsumer(context::complete)
            .build();

        //context.execute("com.shellaia.verbatim.component.vlc", command, internalContext);
        context.execute("com.shellaia.verbatim.agent.dashboard", command, internalContext);
      };

    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    return true;
  }
}
