package com.social100.todero.component.aiaadmin;

import com.social100.processor.AIAController;
import com.social100.processor.Action;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.base.ComponentManagerInterface;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.config.ServerType;
import com.social100.todero.common.runtime.admin.AdminRuntimeAction;
import com.social100.todero.common.runtime.admin.AdminRuntimeRequest;
import com.social100.todero.common.runtime.admin.LifecycleRuntimeAction;
import com.social100.todero.common.runtime.admin.LifecycleRuntimeRequest;
import com.social100.todero.common.runtime.admin.PrivilegedOperationResult;
import com.social100.todero.common.storage.Storage;
import com.social100.todero.util.ArgumentParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@AIAController(name = "com.shellaia.verbatim.component.aia.admin",
    type = ServerType.AIA,
    visible = true,
    description = "AIA tool component for internal set lifecycle operations and server reload")
public class AiaAdminComponent {
  private static final String MAIN_GROUP = "Main";

  public AiaAdminComponent(Storage storage) {
  }

  @Action(group = MAIN_GROUP,
      command = "update",
      description = "Update the current set through internal runtime lifecycle service. Usage: update")
  public Boolean update(CommandContext context) {
    ParsedArgs args = parseBody(context);
    if (!validateAllowedFlags(context, args, usageUpdate(), List.of())) return false;
    if (!args.positional.isEmpty()) return invalidArgs(context, usageUpdate(), args.positional);

    LifecycleRuntimeRequest request = new LifecycleRuntimeRequest(
        LifecycleRuntimeAction.UPDATE_CURRENT_SET,
        null,
        List.of(),
        Map.of()
    );

    PrivilegedOperationResult result = executeLifecycle(context, "update", request);
    if (result == null || !result.success()) {
      return false;
    }
    return reloadInternal(context, new ParsedArgs(List.of(), Map.of(), List.of()));
  }

  @Action(group = MAIN_GROUP,
      command = "install",
      description = "Install coordinates into the current runtime set through internal runtime lifecycle service. Usage: install --coord <g:a:v> [--coord ...]")
  public Boolean install(CommandContext context) {
    ParsedArgs args = parseBody(context);
    if (!validateAllowedFlags(context, args, usageInstall(), List.of("coord"))) return false;
    if (args.tokens.isEmpty()) {
      context.response(usageInstall());
      return false;
    }
    if (!args.positional.isEmpty()) return invalidArgs(context, usageInstall(), args.positional);

    List<String> coords = args.multi("coord");
    if (coords.isEmpty()) {
      context.response("At least one --coord is required\\n" + usageInstall());
      return false;
    }

    LifecycleRuntimeRequest request = new LifecycleRuntimeRequest(
        LifecycleRuntimeAction.INSTALL,
        null,
        coords,
        Map.of()
    );

    PrivilegedOperationResult result = executeLifecycle(context, "install", request);
    if (result == null || !result.success()) {
      return false;
    }
    return reloadInternal(context, new ParsedArgs(List.of(), Map.of(), List.of()));
  }

  @Action(group = MAIN_GROUP,
      command = "uninstall",
      description = "Uninstall coordinates from the current runtime set through internal runtime lifecycle service. Usage: uninstall --coord <g:a:v> [--coord ...]")
  public Boolean uninstall(CommandContext context) {
    ParsedArgs args = parseBody(context);
    if (!validateAllowedFlags(context, args, usageUninstall(), List.of("coord"))) return false;
    if (args.tokens.isEmpty()) {
      context.response(usageUninstall());
      return false;
    }
    if (!args.positional.isEmpty()) return invalidArgs(context, usageUninstall(), args.positional);

    List<String> coords = args.multi("coord");
    if (coords.isEmpty()) {
      context.response("At least one --coord is required\\n" + usageUninstall());
      return false;
    }

    LifecycleRuntimeRequest request = new LifecycleRuntimeRequest(
        LifecycleRuntimeAction.UNINSTALL,
        null,
        coords,
        Map.of()
    );

    PrivilegedOperationResult result = executeLifecycle(context, "uninstall", request);
    if (result == null || !result.success()) {
      return false;
    }
    return reloadInternal(context, new ParsedArgs(List.of(), Map.of(), List.of()));
  }

  @Action(group = MAIN_GROUP,
      command = "versions",
      description = "Show versions for the current runtime set through internal runtime lifecycle service. Usage: versions [--limit <N>]")
  public Boolean versions(CommandContext context) {
    ParsedArgs args = parseBody(context);
    if (!validateAllowedFlags(context, args, usageVersions(), List.of("limit"))) return false;
    if (!args.positional.isEmpty()) return invalidArgs(context, usageVersions(), args.positional);

    Map<String, String> options = new LinkedHashMap<>();
    putIfNotBlank(options, "limit", args.single("limit"));
    LifecycleRuntimeRequest request = new LifecycleRuntimeRequest(
        LifecycleRuntimeAction.VERSIONS,
        null,
        List.of(),
        options
    );

    PrivilegedOperationResult result = executeLifecycle(context, "versions", request);
    return result != null && result.success();
  }

  @Action(group = MAIN_GROUP,
      command = "checkout",
      description = "Checkout a version in the current runtime set through internal runtime lifecycle service. Usage: checkout [<ver> | --latest | --previous] [--restore-state] [--force]")
  public Boolean checkout(CommandContext context) {
    ParsedArgs args = parseBody(context);
    if (!validateAllowedFlags(context, args, usageCheckout(), List.of("latest", "previous", "restore-state", "force"))) return false;
    if (args.tokens.isEmpty()) {
      context.response(usageCheckout());
      return false;
    }

    int selectors = 0;
    String positionalVersion = args.positionalVersion();
    if (!isBlank(positionalVersion)) selectors++;
    if (args.bool("latest", false)) selectors++;
    if (args.bool("previous", false)) selectors++;
    if (selectors > 1) {
      context.response("Only one selector is allowed: <ver>, --latest, or --previous\\n" + usageCheckout());
      return false;
    }

    Map<String, String> options = new LinkedHashMap<>();
    putIfNotBlank(options, "version", positionalVersion);
    if (args.bool("latest", false)) {
      options.put("latest", "true");
    }
    if (args.bool("previous", false)) {
      options.put("previous", "true");
    }
    if (args.bool("restore-state", false)) {
      options.put("restore-state", "true");
    }
    if (args.bool("force", false)) {
      options.put("force", "true");
    }

    LifecycleRuntimeRequest request = new LifecycleRuntimeRequest(
        LifecycleRuntimeAction.CHECKOUT,
        null,
        List.of(),
        options
    );

    PrivilegedOperationResult result = executeLifecycle(context, "checkout", request);
    if (result == null || !result.success()) {
      return false;
    }
    return reloadInternal(context, new ParsedArgs(List.of(), Map.of(), List.of()));
  }

  @Action(group = MAIN_GROUP,
      command = "prune",
      description = "Prune old versions in the current runtime set through internal runtime lifecycle service. Usage: prune [--days <N>] [--dry-run]")
  public Boolean prune(CommandContext context) {
    ParsedArgs args = parseBody(context);
    if (!validateAllowedFlags(context, args, usagePrune(), List.of("days", "dry-run"))) return false;
    if (!args.positional.isEmpty()) return invalidArgs(context, usagePrune(), args.positional);

    Map<String, String> options = new LinkedHashMap<>();
    putIfNotBlank(options, "days", args.single("days"));
    if (args.bool("dry-run", false)) {
      options.put("dry-run", "true");
    }

    LifecycleRuntimeRequest request = new LifecycleRuntimeRequest(
        LifecycleRuntimeAction.PRUNE,
        null,
        List.of(),
        options
    );

    PrivilegedOperationResult result = executeLifecycle(context, "prune", request);
    if (result == null || !result.success()) {
      return false;
    }
    return reloadInternal(context, new ParsedArgs(List.of(), Map.of(), List.of()));
  }

  @Action(group = MAIN_GROUP,
      command = "reload",
      description = "Reload the running Todero server through internal admin control service. Usage: reload [--grace-seconds <N>]")
  public Boolean reload(CommandContext context) {
    ParsedArgs args = parseBody(context);
    if (!validateAllowedFlags(context, args, "Usage: reload [--grace-seconds <N>]", List.of("grace-seconds"))) return false;
    if (!args.positional.isEmpty()) return invalidArgs(context, "Usage: reload [--grace-seconds <N>]", args.positional);
    return reloadInternal(context, args);
  }

  private Boolean reloadInternal(CommandContext context, ParsedArgs args) {
    Map<String, String> options = new LinkedHashMap<>();
    putIfNotBlank(options, "grace-seconds", args.single("grace-seconds"));
    AdminRuntimeRequest request = new AdminRuntimeRequest(AdminRuntimeAction.RELOAD_RUNTIME, options);
    PrivilegedOperationResult result = executeAdmin(context, "reload", request);
    return result != null && result.success();
  }

  private PrivilegedOperationResult executeLifecycle(CommandContext context,
                                                     String action,
                                                     LifecycleRuntimeRequest request) {
    ComponentManagerInterface manager = context.getComponentManager();
    if (manager == null) {
      PrivilegedOperationResult fail = PrivilegedOperationResult.fail(
          "missing_component_manager",
          "Component manager is not available in command context.",
          ""
      );
      context.response(renderResult(action, fail));
      return fail;
    }
    PrivilegedOperationResult result = manager.lifecycleRuntimeService().execute(request, null);
    context.response(renderResult(action, result));
    return result;
  }

  private PrivilegedOperationResult executeAdmin(CommandContext context,
                                                 String action,
                                                 AdminRuntimeRequest request) {
    ComponentManagerInterface manager = context.getComponentManager();
    if (manager == null) {
      PrivilegedOperationResult fail = PrivilegedOperationResult.fail(
          "missing_component_manager",
          "Component manager is not available in command context.",
          ""
      );
      context.response(renderResult(action, fail));
      return fail;
    }
    PrivilegedOperationResult result = manager.adminControlRuntimeService().execute(request, null);
    context.response(renderResult(action, result));
    return result;
  }

  private ParsedArgs parseBody(CommandContext context) {
    AiatpIO.HttpRequest httpRequest = context.getHttpRequest();
    String body = AiatpIO.bodyToString(httpRequest.body(), StandardCharsets.UTF_8);
    ArgumentParser parser = new ArgumentParser();
    List<String> tokens = parser.tokenizeCommandLine(body == null ? "" : body);
    return ParsedArgs.fromTokens(tokens);
  }

  private String renderResult(String action, PrivilegedOperationResult result) {
    String code = (result == null || isBlank(result.code())) ? "unknown" : result.code();
    String message = result == null ? "" : result.message();
    String details = result == null ? "" : result.details();
    boolean ok = result != null && result.success();

    StringBuilder sb = new StringBuilder();
    sb.append(action)
        .append(" -> ")
        .append(ok ? "OK" : "ERROR")
        .append(" (code=")
        .append(code)
        .append(")");

    if (!isBlank(message)) {
      sb.append('\n').append("message: ").append(message.trim());
    }
    if (!isBlank(details)) {
      sb.append('\n').append("details:\n").append(details.trim());
    }
    return sb.toString();
  }


  private void putIfNotBlank(Map<String, String> out, String key, String value) {
    if (!isBlank(value)) {
      out.put(key, value.trim());
    }
  }

  private String usageUpdate() {
    return "Usage: update";
  }

  private String usageInstall() {
    return "Usage: install --coord <g:a:v> [--coord ...]";
  }

  private String usageUninstall() {
    return "Usage: uninstall --coord <g:a:v> [--coord ...]";
  }

  private String usageVersions() {
    return "Usage: versions [--limit <N>]";
  }

  private String usageCheckout() {
    return "Usage: checkout [<ver> | --latest | --previous] [--restore-state] [--force]";
  }

  private String usagePrune() {
    return "Usage: prune [--days <N>] [--dry-run]";
  }

  private boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private boolean validateAllowedFlags(CommandContext context, ParsedArgs args, String usage, List<String> allowed) {
    Set<String> allowedLower = new HashSet<>();
    for (String flag : allowed) {
      if (flag != null) {
        allowedLower.add(flag.toLowerCase(Locale.ROOT));
      }
    }
    List<String> unknown = new ArrayList<>();
    for (String key : args.values.keySet()) {
      if (!allowedLower.contains(key.toLowerCase(Locale.ROOT))) {
        unknown.add("--" + key);
      }
    }
    if (!unknown.isEmpty()) {
      context.response("Unrecognized flag(s): " + String.join(", ", unknown) + "\\n" + usage);
      return false;
    }
    return true;
  }

  private boolean invalidArgs(CommandContext context, String usage, List<String> args) {
    context.response("Unrecognized argument(s): " + String.join(" ", args) + "\\n" + usage);
    return false;
  }

  private static final class ParsedArgs {
    private final List<String> tokens;
    private final Map<String, List<String>> values;
    private final List<String> positional;

    private ParsedArgs(List<String> tokens, Map<String, List<String>> values, List<String> positional) {
      this.tokens = tokens;
      this.values = values;
      this.positional = positional;
    }

    static ParsedArgs fromTokens(List<String> tokens) {
      Map<String, List<String>> out = new LinkedHashMap<>();
      List<String> positional = new ArrayList<>();
      int i = 0;
      while (i < tokens.size()) {
        String token = tokens.get(i);
        if (!token.startsWith("--")) {
          positional.add(token);
          i++;
          continue;
        }

        String key = token.substring(2).toLowerCase(Locale.ROOT);
        String value = "true";

        if (key.contains("=")) {
          int idx = key.indexOf('=');
          value = key.substring(idx + 1);
          key = key.substring(0, idx);
        } else if (i + 1 < tokens.size() && !tokens.get(i + 1).startsWith("--")) {
          value = tokens.get(i + 1);
          i++;
        }

        out.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        i++;
      }
      return new ParsedArgs(tokens, out, positional);
    }

    String single(String key) {
      List<String> list = values.get(key.toLowerCase(Locale.ROOT));
      if (list == null || list.isEmpty()) {
        return null;
      }
      return list.get(list.size() - 1);
    }

    List<String> multi(String key) {
      List<String> list = values.get(key.toLowerCase(Locale.ROOT));
      if (list == null) {
        return List.of();
      }
      return List.copyOf(list);
    }

    boolean hasFlag(String key) {
      return values.containsKey(key.toLowerCase(Locale.ROOT));
    }

    boolean bool(String key, boolean defaultValue) {
      String v = single(key);
      if (v == null) {
        return defaultValue;
      }
      return "1".equals(v) || "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v);
    }

    String positionalVersion() {
      if (positional == null || positional.isEmpty()) {
        return null;
      }
      return positional.get(0);
    }
  }
}
