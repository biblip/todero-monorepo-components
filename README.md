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

## Publish all modules to Nexus

Publish all components, agents, preprocessors, and postprocessors to the Nexus server at `https://nexus.shellaia.com`:

```sh
cd todero-monorepo-components
./publish-nexus.sh
```

Behavior:
- derives the release version from the root `pom.xml`
- if the current version is `x.y.z-SNAPSHOT`, it publishes `x.y.z`
- if the current version is `x.y.z`, it publishes `x.y.z`
- sets the reactor to that release version and runs `mvn deploy`
- Maven routes releases to `maven-releases` using root `distributionManagement`
- leaves the Maven project on the next patch `-SNAPSHOT`
- current Nexus server is releases-only; snapshot deployment is not part of the operational flow for this server

Credential sources:
- `CI_NEXUS_USER` and `CI_NEXUS_PASSWORD`, or
- the credentials configured for the Nexus `publish` user

Optional env file:
- `.env.example`
- copy to `.env`, edit values, and source it before running the publish script

Example:
```sh
cp .env.example .env
set -a
source .env
set +a
./publish-nexus.sh
```

Useful options:

```sh
./publish-nexus.sh --with-tests
./publish-nexus.sh --version 0.1.7
./publish-nexus.sh --dry-run
```

## Publish releases with `mvn deploy`

The root POM declares `distributionManagement` for the release repository:
- releases: `nexus-releases`

Set Maven credentials in `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>nexus-releases</id>
      <username>${env.CI_NEXUS_USER}</username>
      <password>${env.CI_NEXUS_PASSWORD}</password>
    </server>
  </servers>
</settings>
```

Publish the current release version from the monorepo root:

```sh
CI_NEXUS_USER=user-to-deploy-maven-releases \
CI_NEXUS_PASSWORD='your-password' \
mvn -f todero-monorepo-components/pom.xml \
  -Dnexus.baseUrl=https://nexus.shellaia.com \
  clean deploy
```

Release versions deploy to `maven-releases`.
Snapshot publishing is disabled for the current Nexus server.

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
java -jar todero-runner.jar   --workspace-dir <path>   --server-type AI   --component com.shellaia.agent.spotify   --command process   --body "help"   --no-preprocessors   --no-postprocessors
```

Behavior notes:
- the runner loads jars from workspace `components/`, `preprocessors/`, and `postprocessors/`
- components can call other components internally via `context.execute(...)`
- preprocessors and postprocessors run only when present and enabled
- responses and events are printed to stdout

## IntelliJ remote debug

Example breakpoint target:
- `components/todero-spotify-component/src/main/java/com/shellaia/component/spotify/SpotifyComponent.java`
