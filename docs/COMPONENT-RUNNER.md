# Todero Component Runner Guide

This document explains how to use the component runner to execute a component locally without starting the full server.

## What the Runner Does

The runner loads component JARs from a workspace directory, constructs a `CommandContext`, executes a component command, and prints responses/events for local debugging.

## Requirements
- Java 17
- a built component JAR
- the runner JAR built from Todero
- a workspace directory with a `components/` subdirectory

## Run a Command

```bash
java -jar todero-component-runner.jar \
  --workspace-dir /path/to/workspace \
  --component com.shellaia.component.template \
  --command ping \
  --body "hello"
```

## What You Will See
- `context.complete(...)` output is printed as the request response
- `context.emit...(...)` output is printed as events

## Common Issues
- component not found: check the JAR location and `@AIAController` name
- no response: verify the action calls one of the `complete*` helpers
- no events: verify the action calls one of the `emit*` helpers
- stale output contract: ensure the response body includes top-level `channels`
