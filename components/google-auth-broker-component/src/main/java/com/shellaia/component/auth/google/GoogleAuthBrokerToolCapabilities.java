package com.shellaia.component.auth.google;

import com.social100.todero.common.routing.ToolCapabilityManifest;
import com.social100.todero.common.routing.ToolCapabilityProvider;
import com.social100.todero.common.routing.ToolCommandSchema;

import java.util.List;

public final class GoogleAuthBrokerToolCapabilities implements ToolCapabilityProvider {
  @Override
  public ToolCapabilityManifest manifest() {
    return ToolCapabilityManifest.builder()
        .contractVersion(1)
        .componentName("com.shellaia.auth.google")
        .toolSummary("Google Account device-flow auth broker for Todero HTML sign-in, session tracking, and principal storage.")
        .commands(List.of(
            cmd("html",
                "Render the Google auth broker surface.",
                List.of(),
                List.of(),
                List.of("html")),
            cmd("settings_get",
                "Get the Google auth broker settings.",
                List.of(),
                List.of(),
                List.of("settings_get")),
            cmd("settings_put",
                "Update the Google auth broker settings. Body must be JSON.",
                List.of(),
                List.of("clientId=<oauth-client-id>", "clientSecret=<oauth-client-secret>", "projectId=<google-project-id>", "authProvider=<google>", "deviceAuthorizationUri=<device-auth-uri>", "tokenUri=<token-uri>", "userInfoUri=<userinfo-uri>", "scope=<openid email profile>", "projectName=<display-name>"),
                List.of("settings_put clientId=client-123 projectId=proj-123 deviceAuthorizationUri=http://127.0.0.1:9999/device/code tokenUri=http://127.0.0.1:9999/token userInfoUri=http://127.0.0.1:9999/userinfo scope=openid email profile")),
            cmd("auth_begin",
                "Start a Google device-code auth session. Body may override scope and endpoint URIs.",
                List.of(),
                List.of("scope=<openid email profile>", "projectId=<google-project-id>", "authProvider=<google>", "deviceAuthorizationUri=<device-auth-uri>", "tokenUri=<token-uri>", "userInfoUri=<userinfo-uri>", "clientId=<oauth-client-id>", "clientSecret=<oauth-client-secret>"),
                List.of("auth_begin scope=openid email profile deviceAuthorizationUri=http://127.0.0.1:9999/device/code tokenUri=http://127.0.0.1:9999/token userInfoUri=http://127.0.0.1:9999/userinfo")),
            cmd("auth_status",
                "Get the Google auth broker status.",
                List.of(),
                List.of(),
                List.of("auth_status")),
            cmd("auth_poll",
                "Poll the Google token endpoint for the current device-code session.",
                List.of(),
                List.of(),
                List.of("auth_poll")),
            cmd("capabilities",
                "Return the Google auth broker capability manifest.",
                List.of(),
                List.of(),
                List.of("capabilities")),
            cmd("auth_complete",
                "Alias for auth_poll in device-code mode.",
                List.of(),
                List.of(),
                List.of("auth_complete")),
            cmd("auth_cancel",
                "Cancel the current Google auth session.",
                List.of(),
                List.of(),
                List.of("auth_cancel"))
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
