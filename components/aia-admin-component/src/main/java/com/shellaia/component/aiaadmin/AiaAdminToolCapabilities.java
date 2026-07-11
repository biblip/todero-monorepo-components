package com.shellaia.component.aiaadmin;

import com.social100.todero.common.routing.ToolCapabilityManifest;
import com.social100.todero.common.routing.ToolCapabilityProvider;
import com.social100.todero.common.routing.ToolCommandSchema;

import java.util.List;

/**
 * Capability surface for operator UX (console help). The wire/protocol does not depend on this.
 */
public final class AiaAdminToolCapabilities implements ToolCapabilityProvider {
  @Override
  public ToolCapabilityManifest manifest() {
    return ToolCapabilityManifest.builder()
        .contractVersion(1)
        .componentName("com.shellaia.aia.admin")
        .toolSummary("Internal AIA lifecycle tool: manage the current runtime set and reload the server.")
        .commands(List.of(
            cmd("update",
                "Start an asynchronous update of floating coordinates in the current runtime set.",
                List.of(),
                List.of(),
                List.of("update")),
            cmd("job-status",
                "Get the status and final result of the latest or a specific asynchronous lifecycle job.",
                List.of(),
                List.of("<jobId>"),
                List.of("job-status", "job-status lifecycle-123")),
            cmd("install",
                "Start an asynchronous install of one or more coordinates into the current runtime set.",
                List.of("<coord>"),
                List.of(),
                List.of("install com.shellaia:renderer-component", "install com.shellaia:renderer-component:0.1.15")),
            cmd("uninstall",
                "Uninstall one or more coordinates from the current runtime set.",
                List.of("<coord>"),
                List.of(),
                List.of("uninstall com.shellaia:renderer-component", "uninstall com.shellaia:renderer-component:0.1.15")),
            cmd("versions",
                "List versions for the current runtime set.",
                List.of(),
                List.of("--limit"),
                List.of("versions", "versions --limit 5")),
            cmd("components",
                "List installed components for the current runtime set.",
                List.of(),
                List.of(),
                List.of("components")),
            cmd("checkout",
                "Checkout a version of the current runtime set.",
                List.of(),
                List.of("<ver>", "--latest", "--previous", "--restore-state", "--force"),
                List.of("checkout --latest", "checkout 20260427T185414 --restore-state")),
            cmd("prune",
                "Prune old set versions.",
                List.of(),
                List.of("--days", "--dry-run"),
                List.of("prune --days 14", "prune --days 14 --dry-run")),
            cmd("reload",
                "Reload the running Todero server.",
                List.of(),
                List.of("--grace-seconds"),
                List.of("reload", "reload --grace-seconds 5"))
        ))
        .build();
  }

  private static ToolCommandSchema cmd(String name,
                                       String description,
                                       List<String> requiredArgs,
                                       List<String> optionalArgs,
                                       List<String> examples) {
    return ToolCommandSchema.builder()
        .name(name)
        .description(description)
        .requiredArgs(requiredArgs == null ? List.of() : requiredArgs)
        .optionalArgs(optionalArgs == null ? List.of() : optionalArgs)
        .examples(examples == null ? List.of() : examples)
        .build();
  }
}
