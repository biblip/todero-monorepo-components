package com.social100.todero.aia;

import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.aia.parser.AIAArgumentParser;
import com.social100.todero.aia.service.ApiAIAProtocolService;
import com.social100.todero.common.aiatpio.AiatpIORequestWrapper;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.storage.Storage;
import com.social100.todero.remote.RemoteCliConfig;
import com.social100.todero.processor.EventDefinition;
import com.social100.todero.util.ArgumentParser;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@AIAController(name = "com.shellaia.verbatim.component.aia",
    type = ServerType.AIA,
    visible = true,
    description = "AIA Protocol Component",
    events = AIARemote.AIAProtocolEvents.class)
public class AIARemote {
  final CommandContext[] context = {null};
  Map<String, Map<String, ApiAIAProtocolService>> sessionUserApiAIAProtocolServiceMap = new ConcurrentHashMap<>();
  //Map<String, ApiAIAProtocolService> stringApiAIAProtocolServiceMap = new ConcurrentHashMap<>();
  public AIARemote(Storage storage) {
  }

  @Action(group = ApiAIAProtocolService.MAIN_GROUP,
      command = "list",
      description = "List all registrations")
  public Boolean listCommand(CommandContext context) {
    Map<String, ApiAIAProtocolService> stringApiAIAProtocolServiceMap = sessionUserApiAIAProtocolServiceMap.get(context.getId());
    if (stringApiAIAProtocolServiceMap == null || stringApiAIAProtocolServiceMap.isEmpty()) {
      return true;
    }
    context.completeText(200, "[" + stringApiAIAProtocolServiceMap.entrySet().stream()
        .map(entry -> entry.getKey() + " : " + entry.getValue().getServer() + " -> " + entry.getValue().getStatus())
        .collect(Collectors.joining("\n")) + "]");
    return true;
  }

  @Action(group = ApiAIAProtocolService.MAIN_GROUP,
      command = "register",
      description = "Register a aia server   register --host <url> --name <name>")
  public Boolean registerCommand(CommandContext context) {
    this.context[0] = context;

    final String commandArgs = requestBody(context);
    if (commandArgs == null || commandArgs.isEmpty()) {
      context.completeText(200, "Register a aia server   register --host <url> --name <name>");
      return false;
    }
    AIAArgumentParser parser = new AIAArgumentParser();
    RemoteCliConfig cli = parser.parseArgs(commandArgs);

    if (cli != null) {
      String name = parser.getArgument("name").toLowerCase(Locale.ROOT);
      Map<String, ApiAIAProtocolService> stringApiAIAProtocolServiceMap = sessionUserApiAIAProtocolServiceMap.computeIfAbsent(context.getId(), k -> new ConcurrentHashMap<>());
      if (stringApiAIAProtocolServiceMap.containsKey(name)) {
        context.completeText(200, "name already used ... '" + cli.getServerRawHost() + "'   name: " + name);
        return false;
      }
      ApiAIAProtocolService service = new ApiAIAProtocolService(cli, (eventName, message) -> {
        AiatpIORequestWrapper wrapper = message;
        if (wrapper.getAiatpEvent() != null) {
          context.emitEvent(wrapper.getAiatpEvent());
        } else if (wrapper.getAiatpTerminalResult() != null) {
          context.complete(wrapper.getAiatpTerminalResult());
        }
      });
      context.completeText(200, "Connecting ... '" + cli.getServerRawHost() + "'   called: " + name);
      stringApiAIAProtocolServiceMap.put(name, service);
      context.completeText(200, "Registration active with '" + cli.getVhostSni() + " : " + cli.getPort() + "'");
    } else {
      context.completeText(200, parser.errorMessage());
    }
    return true;
  }

  @Action(group = ApiAIAProtocolService.MAIN_GROUP,
      command = "unregister",
      description = "De register the aia server   unregister --name <name>")
  public Boolean unregisterCommand(CommandContext context) {
    this.context[0] = context;
    ArgumentParser parser = new ArgumentParser();

    // Define rules with default values
    parser.addRule("name", value -> value != null && !value.trim().isEmpty(), "defaultName");

    //if (parser.parse(context.getArgs())) {
      String name = parser.getArgument("name").toLowerCase(Locale.ROOT);

      context.completeText(200, "Unregister ... '" + name + "'");

      Map<String, ApiAIAProtocolService> stringApiAIAProtocolServiceMap = sessionUserApiAIAProtocolServiceMap.get(context.getId());
      if (stringApiAIAProtocolServiceMap == null || stringApiAIAProtocolServiceMap.isEmpty()) {
        return true;
      }

      ApiAIAProtocolService service = stringApiAIAProtocolServiceMap.remove(name);

      service.unregister();

      context.completeText(200, "server is no longer active name : '" + name + "'");
    //} else {
      context.completeText(200, parser.errorMessage());
    //}
    return true;
  }

  @Action(group = ApiAIAProtocolService.MAIN_GROUP,
      command = "exec",
      description = "Execute a command into the remote console  exec --name <name> command...")
  public Boolean execCommand(CommandContext context) {
    this.context[0] = context;

    final String commandArgs = requestBody(context);
    if (commandArgs == null || commandArgs.isEmpty()) {
      context.completeText(200, "Register a aia server   register --url <url> --name <name>");
      return false;
    }

    ArgumentParser parser = new ArgumentParser();

    String[] args = parser.tokenizeCommandLine(commandArgs).toArray(new String[0]);

    // Define rules with default values
    parser.addRule("name", value -> value != null && !value.trim().isEmpty(), "defaultName");

    if (args.length < 2) {
      context.completeText(200, "Not enough arguments  exec --name <name> command...");
    }

    String[] subArray = Arrays.copyOfRange(args, 0, 2);
    String[] rest = Arrays.copyOfRange(args, 2, args.length);

    if (parser.parse(subArray)) {
      String name = parser.getArgument("name").toLowerCase(Locale.ROOT);

      Set<String> stopWords = Set.of("exec", "aia", "register");

      // Initialize a variable to store the offended word
      final String[] offendingWord = {null};

      // Convert the array to a Stream, process it, and join the result
      String line = Arrays.stream(rest)
          .takeWhile(arg -> {
            if (stopWords.contains(arg)) {
              offendingWord[0] = arg; // Log the offended word
              return false;         // Stop processing
            }
            return true;
          })
          .collect(Collectors.joining(" "));

      if (offendingWord[0] != null) {
        String warningMessage = "Offending word " + offendingWord[0] + " in '" + String.join(" ", rest) + "'";
        System.out.println(warningMessage);
        context.completeText(200, warningMessage);
        return true;
      }

      Map<String, ApiAIAProtocolService> stringApiAIAProtocolServiceMap = sessionUserApiAIAProtocolServiceMap.get(context.getId());
      if (stringApiAIAProtocolServiceMap == null || stringApiAIAProtocolServiceMap.isEmpty()) {
        return true;
      }

      ApiAIAProtocolService service = stringApiAIAProtocolServiceMap.get(name);

      if (service != null) {
        service.exec(line);
        return true;
      }
      context.completeText(200, "name not found:  name='" + name + "'");
      return false;
    } else {
      context.completeText(200, parser.errorMessage());
    }
    return true;
  }

  @Action(group = ApiAIAProtocolService.MAIN_GROUP,
      command = "set-header",
      description = "Set a session header  set-header --name <name> --header <Header> --value <Value>")
  public Boolean setHeaderCommand(CommandContext context) {
    final String commandArgs = requestBody(context);
    if (commandArgs == null || commandArgs.isEmpty()) {
      context.completeText(200, "Set a session header  set-header --name <name> --header <Header> --value <Value>");
      return false;
    }

    ArgumentParser parser = new ArgumentParser();
    parser.addRule("name", value -> value != null && !value.trim().isEmpty(), null);
    parser.addRule("header", value -> value != null && !value.trim().isEmpty(), null);
    parser.addRule("value", value -> value != null && !value.trim().isEmpty(), null);

    if (!parser.parse(commandArgs)) {
      context.completeText(200, parser.errorMessage());
      return false;
    }

    String name = parser.getArgument("name").toLowerCase(Locale.ROOT);
    String header = parser.getArgument("header");
    String value = parser.getArgument("value");

    Map<String, ApiAIAProtocolService> services = sessionUserApiAIAProtocolServiceMap.get(context.getId());
    if (services == null || services.isEmpty()) {
      context.completeText(200, "No active sessions for this user.");
      return false;
    }
    ApiAIAProtocolService service = services.get(name);
    if (service == null) {
      context.completeText(200, "name not found:  name='" + name + "'");
      return false;
    }
    service.setSessionHeader(header, value);
    context.completeText(200, "Header set for '" + name + "': " + header);
    return true;
  }

  @Action(group = ApiAIAProtocolService.MAIN_GROUP,
      command = "unset-header",
      description = "Remove a session header  unset-header --name <name> --header <Header>")
  public Boolean unsetHeaderCommand(CommandContext context) {
    final String commandArgs = requestBody(context);
    if (commandArgs == null || commandArgs.isEmpty()) {
      context.completeText(200, "Remove a session header  unset-header --name <name> --header <Header>");
      return false;
    }

    ArgumentParser parser = new ArgumentParser();
    parser.addRule("name", value -> value != null && !value.trim().isEmpty(), null);
    parser.addRule("header", value -> value != null && !value.trim().isEmpty(), null);

    if (!parser.parse(commandArgs)) {
      context.completeText(200, parser.errorMessage());
      return false;
    }

    String name = parser.getArgument("name").toLowerCase(Locale.ROOT);
    String header = parser.getArgument("header");

    Map<String, ApiAIAProtocolService> services = sessionUserApiAIAProtocolServiceMap.get(context.getId());
    if (services == null || services.isEmpty()) {
      context.completeText(200, "No active sessions for this user.");
      return false;
    }
    ApiAIAProtocolService service = services.get(name);
    if (service == null) {
      context.completeText(200, "name not found:  name='" + name + "'");
      return false;
    }
    service.removeSessionHeader(header);
    context.completeText(200, "Header removed for '" + name + "': " + header);
    return true;
  }

  @Action(group = ApiAIAProtocolService.RESERVED_GROUP,
      command = "subscribe",
      description = "Subscribe to an event in this component")
  public Boolean subscribeToEvent(CommandContext context) {
    this.context[0] = context;
    context.completeText(200, "Done");
    return true;
  }

  @Action(group = ApiAIAProtocolService.RESERVED_GROUP,
      command = "unsubscribe",
      description = "Unubscribe from an event in this component")
  public Boolean unsubscribeFromEvent(CommandContext context) {
    this.context[0] = context;
    context.completeText(200, "Done");
    return true;
  }

  private static String requestBody(CommandContext context) {
    if (context == null || context.getAiatpRequest() == null || context.getAiatpRequest().getBody() == null) {
      return "";
    }
    String body = AiatpIO.bodyToString(context.getAiatpRequest().getBody(), StandardCharsets.UTF_8);
    return body == null ? "" : body;
  }

  public enum AIAProtocolEvents implements EventDefinition {
    DOOR_OPEN("The door_open description"),
    WINDOW_OPEN("The window_broken description"),
    HIGH_TEMP("The high_temp description");

    private final String description;

    AIAProtocolEvents(String description) {
      this.description = description;
    }

    @Override
    public String getDescription() {
      return description;
    }
  }
}
