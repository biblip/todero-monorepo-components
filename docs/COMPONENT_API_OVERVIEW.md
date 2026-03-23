Todero Component API Overview
=============================

Annotations
-----------
- `@AIAController(name = "<component-name>", events = <EventDefinition enum>)`
  Declares the component and its public namespace.
- `@Action(group = "<group>", command = "<command>", description = "<desc>")`
  Exposes a method as a command.

Command Context
---------------
Command methods receive a `CommandContext`, which provides:
- request data through `getAiatpRequest()`
- deterministic completion through `complete*` helpers
- optional event emission through `emit*` helpers
- access to `Storage` and the component manager

Action Signature
----------------
- action methods must return `Boolean`
- action methods must accept only `CommandContext`

Example Skeleton
----------------
```java
@AIAController(name = "com.shellaia.component.template")
public class TemplateComponent {

  public TemplateComponent(Storage storage) {}

  @Action(group = "Main", command = "ping", description = "Ping")
  public Boolean ping(CommandContext context) {
    context.completeJson(200, "{\"ok\":true,\"message\":\"pong\",\"channels\":{\"chat\":{\"message\":\"pong\"},\"status\":{\"message\":\"pong\"},\"html\":{\"html\":null,\"mode\":\"none\",\"replace\":false}}}");
    return Boolean.TRUE;
  }
}
```

Notes
-----
- components must expose a `public ComponentName(Storage storage)` constructor
- parse command arguments from `AiatpIO.bodyToString(context.getAiatpRequest().getBody(), AiatpIO.UTF_8)`
- responses are request-completion; events are optional progress/follow-on signals
- UI payload metadata lives under `channels.chat`, `channels.status`, and `channels.html`
