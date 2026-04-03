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

Publish all components, agents, preprocessors, and postprocessors to the local Nexus instance on `http://localhost:8081`:

```sh
cd todero-monorepo-components
./publish-all-to-nexus.sh
```

Behavior:
- derives the release version from the root `pom.xml`
- if the current version is `x.y.z-SNAPSHOT`, it publishes `x.y.z`
- if the current version is `x.y.z`, it publishes `x.y.z`
- sets the reactor to that release version and runs `mvn deploy`
- Maven routes releases to `maven-releases` using root `distributionManagement`
- leaves the Maven project on the next patch `-SNAPSHOT`

Credential sources:
- `CI_NEXUS_USER` and `CI_NEXUS_PASSWORD`, or
- `../nexus/provisioning/output/ci-publisher.credentials`

Useful options:

```sh
./publish-all-to-nexus.sh --with-tests
./publish-all-to-nexus.sh --version 0.1.7
./publish-all-to-nexus.sh --dry-run
```

## Publish snapshots with `mvn deploy`

The root POM now declares `distributionManagement` for both hosted repos:
- releases: `nexus-releases`
- snapshots: `nexus-snapshots`

Set Maven credentials in `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>nexus-releases</id>
      <username>${env.CI_NEXUS_USER}</username>
      <password>${env.CI_NEXUS_PASSWORD}</password>
    </server>
    <server>
      <id>nexus-snapshots</id>
      <username>${env.CI_NEXUS_USER}</username>
      <password>${env.CI_NEXUS_PASSWORD}</password>
    </server>
  </servers>
</settings>
```

Publish the current snapshot version from the monorepo root:

```sh
CI_NEXUS_USER=user-to-deploy-maven-releases \
CI_NEXUS_PASSWORD='your-password' \
mvn -f todero-monorepo-components/pom.xml \
  -Dnexus.baseUrl=http://localhost:8081 \
  clean deploy
```

If the project version ends with `-SNAPSHOT`, Maven deploys to `maven-snapshots`. Release versions deploy to `maven-releases`.

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
