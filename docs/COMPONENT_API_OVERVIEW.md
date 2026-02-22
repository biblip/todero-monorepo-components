Todero Component API Overview
=============================

Annotations
-----------
- @AIAController(name = "<component-name>", events = <EventDefinition enum>)
  Declares the component and its public namespace.

- @Action(group = "<group>", command = "<command>", description = "<desc>")
  Exposes a method as a command.

Command Context
---------------
Command methods receive a CommandContext, which provides:
- request data (path/body/headers)
- response and event emission
- access to Storage (if configured)

Action Signature
----------------
- Action methods must return Boolean (not void).
- Action methods must accept **only** `CommandContext` as a parameter.
- Additional parameters are not supported by the processor.

Example Skeleton
----------------
@AIAController(name = "com.example.todero.component.template")
public class TemplateComponent {

  public TemplateComponent(Storage storage) {}

  @Action(group = "Main", command = "ping", description = "Ping")
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

Required Maven Processor Snippet
--------------------------------
```xml
<dependency>
  <groupId>com.social100.todero</groupId>
  <artifactId>todero-component-api</artifactId>
  <version>${todero.version}</version>
  <scope>provided</scope>
</dependency>
<dependency>
  <groupId>com.social100.todero</groupId>
  <artifactId>processor</artifactId>
  <version>${todero.version}</version>
  <scope>provided</scope>
</dependency>

<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <version>3.11.0</version>
  <configuration>
    <annotationProcessorPaths>
      <path>
        <groupId>com.social100.todero</groupId>
        <artifactId>processor</artifactId>
        <version>${todero.version}</version>
      </path>
    </annotationProcessorPaths>
  </configuration>
</plugin>
```

Notes
-----
- Action methods must return Boolean (not void).
- Components must expose a `public ComponentName(Storage storage)` constructor.
- Events must be declared in the @AIAController events enum before emitting.
- Parse command arguments from request body (e.g., JSON) using `AiatpIO.bodyToString`.

Testing
-------
Optionally depend on:
- com.social100.todero:todero-component-testkit
for in-memory testing utilities.
