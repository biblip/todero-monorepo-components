Todero Component API Overview
=============================

Annotations
-----------
- @AIAController(name = "<component-name>", events = <EventDefinition enum>)
  Declares the component and its public namespace.

- @Action(name = "<command>")
  Exposes a method as a command.

Command Context
---------------
Command methods receive a CommandContext, which provides:
- request data (path/body/headers)
- response and event emission
- access to Storage (if configured)

Example Skeleton
----------------
@AIAController(name = "com.example.todero.component.template")
public class TemplateComponent {

  @Action(name = "ping")
  public Boolean ping(CommandContext context) {
    context.response("pong");
    return Boolean.TRUE;
  }
}

Packaging
---------
- Build a JAR with Maven.
- The JAR must include your component classes and annotation-generated wrapper.
- Place the JAR under <workspace>/components.

Notes
-----
- Action methods must return Boolean (not void).
- Components are constructed with (EventChannel.EventListener, Storage).
- Events must be declared in the @AIAController events enum before emitting.

Testing
-------
Optionally depend on:
- com.social100.todero:todero-component-testkit
for in-memory testing utilities.
