# Todero Spotify Component + Agent

This project contains the Spotify AIA component and a companion AI agent module
used to call it via `todero-runner.jar`.

## Build

```sh
mvn -pl todero-spotify-component,todero-spotify-agent -am clean package
```

## Convenience scripts

- `./runner.sh` builds (if needed), copies the JARs into the workspace layout,
  and runs `todero-runner.jar` (supports extra runner flags after the body).
- `./debug-runner.sh` builds (if needed), copies the JARs into the workspace layout,
  and starts `todero-runner.jar` with JDWP on port 5005.

## Install the component or agent

Copy the built JAR into a component-specific directory under your Todero
workspace components / agent folder:

```sh
mkdir -p <workspace>/components/todero-spotify-component
cp todero-spotify-component/target/todero-spotify-component-0.1.0-SNAPSHOT.jar \
  <workspace>/components/todero-spotify-component/
mkdir -p <workspace>/components/todero-spotify-agent
cp todero-spotify-agent/target/todero-spotify-agent-0.1.0-SNAPSHOT.jar \
  <workspace>/components/todero-spotify-agent/
```

If you run the agent directly, copy the environment template from
`todero-spotify-agent/env-spotify-agent` into the agent component storage directory as `.env`, and 
`todero-spotify-component/env-spotify-component` into the component storage directory as `.env`.

### Workspace layout

```
<workspace>/
  components/
    <component-dir>/
      <component-jar>
  preprocessors/
    <preprocessor-dir>/
      <preprocessor-jar>
  postprocessors/
    <postprocessor-dir>/
      <postprocessor-jar>
```

### Run

Assumes `todero-runner.jar` is available at the project root.

```sh
java -jar todero-runner.jar \
  --workspace-dir <path> \
  --component com.shellaia.verbatim.agent.dj \
  --command process \
  --body "help" \
  --header "X-Auth: my-token" \
  --server-type AI \
  --expose-all \
  --no-preprocessors \
  --no-preprocessors-fail-open \
  --no-postprocessors \
  --no-postprocessors-fail-open
```

### Arguments

- `--workspace-dir` required, path containing `components/`
- `--component` required, component name (controller name)
- `--command` required, command name
- `--body` optional, request payload
- `--header` optional, repeatable header in `Name: value` format
- `--server-type` optional, `AI` or `AIA` (default: `AI`)
- `--expose-all` optional, enables hybrid mode (AI and AIA together, default: disabled)
- `--no-preprocessors` optional, disables preprocessors (default: enabled)
- `--no-preprocessors-fail-open` optional, disables fail-open (default: enabled)
- `--no-postprocessors` optional, disables postprocessors (default: enabled)
- `--no-postprocessors-fail-open` optional, disables fail-open (default: enabled)

### Behavior notes

- The runner builds an `ACTION /<component>/<command>` request and sends it
  through `CliCommandManager`.
- When `--expose-all` is set, component resolution uses the full component
  registry (AI and AIA together) instead of filtering by the runner `--server-type`.
- `--expose-all` overrides `--server-type`; the runner prints a warning if both are set.
- Components can call other components internally via `context.execute(...)`.
- Preprocessors and postprocessors run when enabled and present under the
  workspace directory.
- Responses and events are printed to stdout.

## Spotify production caveats

- Playback control commands (`play`, `pause`, `skip`, `previous`, `queue-add`, `shuffle`, `repeat`) may require Spotify Premium and an active device.
- Library, playlist, top-items, and recently-played commands depend on granted scopes; re-consent is required after scope expansion.
- Spotify API rate limits can return `429`; component retries are bounded and may still return structured failures for sustained limits.
- Some endpoints can intermittently fail with transport allocation issues; retrying after a short pause usually succeeds.
- Recommendations and queue endpoints can return empty or unavailable data based on account region, market availability, and current playback state.

## IntelliJ Remote Debug (runner JAR)

Use the runner JAR with JDWP and attach IntelliJ.

1) Start the runner in debug mode (from this project root):

```sh
java "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005" \
  -jar ./todero-runner.jar \
  --workspace-dir ./workspace \
  --component com.shellaia.verbatim.agent.dj \
  --command process \
  --body "help"
```

2) In IntelliJ, create a Remote JVM Debug configuration:
   - Run > Edit Configurations > + > Remote JVM Debug
   - Host: `localhost`
   - Port: `5005`

3) Set breakpoints in `todero-spotify-component/src/main/java/com/social100/todero/component/spotify/SpotifyComponent.java`
   and click Debug to attach.
