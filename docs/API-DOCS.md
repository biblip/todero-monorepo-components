# Todero Component API Reference

This document describes the current public API surface used by components in `todero-monorepo-components`.

Note: the protocol/runtime types come from `com.social100.todero:aiatp-io`. The main message model is:
- runtime types: `AiatpRequest`, `AiatpResponse`, `AiatpEvent`
- wire types: `AiatpWire.Request`, `AiatpWire.Response`, `AiatpWire.Event`
- wrapper/container: `AiatpIORequestWrapper`

## Annotations

### `@AIAController`
Declares the component and its public namespace.
- `String name()`
- `ServerType type()`
- `boolean visible()`
- `String description()`
- `Class<? extends EventDefinition> events() default NoEvents.class`

### `@Action`
Exposes a method as a command.
- `String group()`
- `String command()`
- `String description()`

Rules:
- action methods must return `Boolean`
- action methods must accept only `CommandContext`
- additional parameters are not supported by the processor

### `EventDefinition`
Event declaration contract for `@AIAController(events = ...)`.
- use an enum so the processor can call `values()`

## Build Requirements

### Maven dependencies
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
```

### Maven compiler plugin
```xml
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

### Required constructor
```java
public ComponentName(Storage storage) { }
```

### Command arguments
Parse arguments from the AIATP request body:
```java
String body = AiatpIO.bodyToString(context.getAiatpRequest().getBody(), AiatpIO.UTF_8).trim();
```

## Response Contract

Every actionable response must include top-level `channels` metadata. `channels.html` is the canonical UI-content key.

```json
{
  "ok": true,
  "message": "...",
  "channels": {
    "chat": { "message": "..." },
    "status": { "message": "..." },
    "html": { "html": null, "mode": "none", "replace": false }
  }
}
```

## `CommandContext`

`CommandContext` is the immutable request context passed to component actions.

Key fields:
- `componentManager`
- `agents`
- `tools`
- `storage`
- `aiatpRequest`
- `instance`
- `responseConsumer`
- `eventConsumer`

### Completion helpers
- `void complete(AiatpResponse result)`
- `void completeText(int status, String text)`
- `void completeJson(int status, String json)`
- `void completeBytes(int status, byte[] bytes, String contentType)`

### Event helpers
- `void emitChat(String message, String phase)`
- `void emitStatus(String message, String phase)`
- `void emitThought(String message, String phase)`
- `void emitHtml(String html, String phase, String mode, boolean replace)`
- `void emitAuthJson(String json, String phase)`
- `void emitError(String message)`
- `void emitControlJson(String json, String phase, String semanticType)`
- `void emitCustom(String eventName, String channelName, String contentType, byte[] bytes, String phase)`
- `AiatpEvent emitEvent(AiatpEvent event)`

### Component invocation
- `void execute(String componentName, String command, CommandContext context)`
- `String getHelp(String componentName, String commandName, OutputType outputType)`

### Immutability helpers
- `CommandContextBuilder toBuilder()`
- `CommandContext withEventConsumer(Consumer<AiatpIORequestWrapper> newEventConsumer)`
- `CommandContext withStorage(Storage newStorage)`
- `CommandContext withLlmRegistry(LLMRegistry registry)`

## Channels / Events

### `EventChannel`
- `void registerEvent(String eventName, String description)`
- `boolean isEventRegistered(String eventName)`
- `void subscribeToEvent(String eventName, EventListener listener)`
- `void triggerEvent(String eventName, AiatpIORequestWrapper wrapper)`
- `Map<String, String> getAvailableEvents()`

Nested types:
- `EventChannel.ReservedEvent`: `START`, `STOP`, `RESTART`, `RESPONSE`
- `EventChannel.EventListener#onEvent(String eventName, AiatpIORequestWrapper wrapper)`

## Component Manager Interface
- `List<String> generateAutocompleteStrings()`
- `String getHelp(String componentName, String commandName, OutputType outputType)`
- `void execute(String componentName, String command, CommandContext context, boolean useComponentsAll)`

## Storage
- `void writeFile(String relativePath, byte[] bytes)`
- `byte[] readFile(String relativePath)`
- `void deleteFile(String relativePath)`
- `List<String> listFiles(String relativeDir)`
- `void putSecret(String key, String value)`
- `String getSecret(String key)`
- `void deleteSecret(String key)`

## Pre/Post Processors

### `PreprocessorInterface`
- `PreprocessorMeta meta()`
- `PreprocessResult before(CommandContext context, AiatpRequest request, PreprocessTarget target)`

### `PreprocessResult`
- `static PreprocessResult continueWith(AiatpRequest request, PreprocessTarget target)`
- `static PreprocessResult continueWithTarget(PreprocessTarget target)`
- `static PreprocessResult continueWithRequest(AiatpRequest request)`
- `static PreprocessResult block(AiatpResponse response)`
- `static PreprocessResult error(String message)`

### `PostprocessorInterface`
- `PostprocessorMeta meta()`
- `PostprocessResult after(CommandContext context, AiatpRequest request, AiatpResponse response, PostprocessTarget target, PostprocessInfo info)`

### `PostprocessResult`
- `static PostprocessResult continueWith(AiatpResponse response)`
- `static PostprocessResult passthrough()`
- `static PostprocessResult error(String message)`
