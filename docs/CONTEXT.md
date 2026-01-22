Todero Mock Component Context
=============================

Goal
----
Create a Maven Java project for a mock/demo Todero component that can be loaded
by the local component runner and debugged in IntelliJ.

Key Concepts
------------
- Components expose commands via annotations.
- The component API is provided by the dependency:
  com.social100.todero:todero-component-api:<version>
- Annotation processing is provided by:
  com.social100.todero:processor:<version>
- Component JARs are loaded from a workspace directory, typically:
  <workspace>/components

Local Runner
------------
Main class: com.social100.todero.component.runner.ComponentRunnerMain
Arguments:
  --workspace-dir <path>
  --component <component-name>
  --command <command>
  --body <text>

Example:
  java -jar todero-component-runner.jar \
    --workspace-dir /path/to/workspace \
    --component com.example.todero.component.template \
    --command ping \
    --body "hello"

Component Naming
----------------
Use a stable component name, typically the @AIAController name.

Notes
-----
- Target Java 17 to match the platform.
- Use standard Maven packaging (jar).
- Include sources for debugging.
