# Todero Monorepo Components

This repository contains all runtime extension modules for Todero, grouped by role so their responsibility is obvious at a glance.

## Repository layout

```text
todero-monorepo-components/
  components/
    <component-module>/
  agents/
    <agent-module>/
  processors/
    preprocessors/
      <preprocessor-module>/
    postprocessors/
      <postprocessor-module>/
  docs/
  agents-playground/
```

Role meaning:
- `components/`: user-facing runtime tools and integrations
- `agents/`: orchestration and reasoning layers that call tools/components
- `processors/preprocessors/`: request interceptors
- `processors/postprocessors/`: response interceptors

Runtime workspace layout does not change:

```text
<workspace>/
  components/
    <module-dir>/
      <module-jar>
  preprocessors/
    <module-dir>/
      <module-jar>
  postprocessors/
    <module-dir>/
      <module-jar>
```

## Build

Build the whole monorepo:

```sh
mvn -f todero-monorepo-components/pom.xml clean package
```

## Publish all modules to local Nexus

The repo root contains `version.txt`, which stores the next release version to publish.

Publish all components, agents, preprocessors, and postprocessors to the local Nexus instance on `http://localhost:8081`:

```sh
cd todero-monorepo-components
./publish-all-to-nexus.sh
```

Behavior:
- reads the release version from `version.txt`
- deploys the full monorepo to `maven-releases`
- increments the patch in `version.txt`
- leaves the Maven project on the next patch `-SNAPSHOT`

Credential sources:
- `CI_NEXUS_USER` and `CI_NEXUS_TOKEN`, or
- `../nexus/provisioning/output/ci-publisher.token`

Useful options:

```sh
./publish-all-to-nexus.sh --with-tests
./publish-all-to-nexus.sh --version 0.1.7
./publish-all-to-nexus.sh --dry-run
```

Build selected modules by role path:

```sh
mvn -f todero-monorepo-components/pom.xml   -pl components/todero-spotify-component,agents/todero-spotify-agent -am clean package
```

Build processors:

```sh
mvn -f todero-monorepo-components/pom.xml   -pl processors/preprocessors/auth-header-preprocessor,processors/postprocessors/response-header-postprocessor -am clean package
```

## Install artifacts into a workspace

Copy built jars into the runtime workspace by runtime role, not source role:

```sh
mkdir -p <workspace>/components/todero-spotify-component
cp components/todero-spotify-component/target/spotify-component-0.1.0-SNAPSHOT.jar   <workspace>/components/todero-spotify-component/

mkdir -p <workspace>/components/todero-spotify-agent
cp agents/todero-spotify-agent/target/spotify-agent-0.1.0-SNAPSHOT.jar   <workspace>/components/todero-spotify-agent/

mkdir -p <workspace>/preprocessors/auth-header-preprocessor
cp processors/preprocessors/auth-header-preprocessor/target/auth-header-preprocessor-0.1.0-SNAPSHOT.jar   <workspace>/preprocessors/auth-header-preprocessor/

mkdir -p <workspace>/postprocessors/response-header-postprocessor
cp processors/postprocessors/response-header-postprocessor/target/response-header-postprocessor-0.1.0-SNAPSHOT.jar   <workspace>/postprocessors/response-header-postprocessor/
```

If you run the Spotify pair directly, copy environment templates from:
- `agents/todero-spotify-agent/env-spotify-agent`
- `components/todero-spotify-component/env-spotify-component`

## Local runner

Assumes `todero-runner.jar` is available at the project root.

```sh
java -jar todero-runner.jar   --workspace-dir <path>   --server-type AI   --component com.shellaia.agent.dj   --command process   --body "help"   --no-preprocessors   --no-postprocessors
```

Behavior notes:
- the runner loads jars from workspace `components/`, `preprocessors/`, and `postprocessors/`
- components can call other components internally via `context.execute(...)`
- preprocessors and postprocessors run only when present and enabled
- responses and events are printed to stdout

## IntelliJ remote debug

Example breakpoint target:
- `components/todero-spotify-component/src/main/java/com/shellaia/component/spotify/SpotifyComponent.java`
