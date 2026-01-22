# Task Manager Agent

## Purpose
`task-manager-agent` is an internal AI agent that orchestrates `com.shellaia.verbatim.component.task.manager`.
It is intentionally hidden from public help surfaces (`visible=false`) and is designed for router/internal delegation.

## Build and Test
- Build and test:
  - `mvn -pl task-manager-agent -am clean install`
- Run only this module tests:
  - `mvn -pl task-manager-agent test`

## Deploy (AIA Admin Component Flow)
Use the admin component through console and keep stdin open briefly so long-running responses are emitted:

```bash
{ printf '%s\n' \
  "com.shellaia.verbatim.component.aia.admin install --set service-a --coord com.example.todero:task-manager-agent:0.1.0-SNAPSHOT --reload true"; \
  sleep 12; } \
| java -jar /Users/arturoportilla/IdeaProjects/todero-ecosystem/todero/playground/console.jar --host=aia://brumor.pbxkey.com --sni=brumor.pbxkey.com
```

## Runtime Validation
- Natural-language create via router:
  - `com.shellaia.verbatim.agent.router process create a task for agent.ops to review summary`
- List tasks:
  - `com.shellaia.verbatim.component.task.manager list --assigned agent.ops --limit 10 --format json`
- Lifecycle transition:
  - `claim` -> `start` -> `complete` on a test task id.
- Synthetic react ingestion (through agent process):
  - `com.shellaia.verbatim.agent.router process react {"event_id":"evt-1","seq":1,"event_type":"TASK_DUE","task_id":"tm-1"}`
  - Then:
  - `com.shellaia.verbatim.agent.router process debug memory`

## Logging and Noise Suppression
- Default behavior suppresses large successful loop traces in memory.
- Full trace capture can be enabled when debugging:
  - JVM property: `-Dtodero.taskmanager.agent.verbose-trace=true`
  - or env var: `TODERO_TASKMANAGER_AGENT_VERBOSE_TRACE=true`

## Protocol Contract
All responses include:
- `request`, `action`, `user`, `html`
- `channels` (`chat`, `status`, `webview`)
- `meta` (status, stop reason, correlation id, timestamp)

This is required for router compatibility.

## Exposure Policy
- Current policy: keep internal (`visible=false`) until explicit sign-off.
- If exposure is requested later, update `@AIAController(visible = true)` deliberately and validate router/public behavior.
