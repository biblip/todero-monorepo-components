package com.shellaia.component.contacts;

import com.social100.todero.common.routing.ToolCapabilityManifest;
import com.social100.todero.common.routing.ToolCapabilityProvider;
import com.social100.todero.common.routing.ToolCommandSchema;

import java.util.List;

public final class ContactsToolCapabilities implements ToolCapabilityProvider {
  @Override
  public ToolCapabilityManifest manifest() {
    return ToolCapabilityManifest.builder()
        .contractVersion(1)
        .componentName("com.shellaia.contacts")
        .toolSummary("Contact directory tool for storing, searching, grouping, and removing contacts.")
        .commands(List.of(
            cmd("add", "Add or update a contact."),
            cmd("list", "List all contacts."),
            cmd("find", "Find contacts by name or email."),
            cmd("group", "List contacts by group."),
            cmd("remove", "Remove a contact.")
        ))
        .build();
  }

  private static ToolCommandSchema cmd(String name, String description) {
    return ToolCommandSchema.builder()
        .name(name)
        .description(description)
        .requiredArgs(List.of())
        .optionalArgs(List.of())
        .examples(List.of())
        .build();
  }
}
