# Task Manager Component

Component id: `com.shellaia.verbatim.component.task.manager`

Purpose:
- Shared task lifecycle manager for agents/components.
- Durable task persistence (SQLite).
- Native attempt history (`task_attempts`) with one-open-attempt invariant per task.
- Scheduler for due/expiry/claim-expiry transitions.
- Outbox-based event dispatch with at-least-once semantics.

## State Machine

States:
- `NEW`
- `READY`
- `CLAIMED`
- `IN_PROGRESS`
- `BLOCKED`
- `COMPLETED` (terminal)
- `FAILED` (terminal)
- `CANCELED` (terminal)
- `EXPIRED` (terminal)
- `SNOOZED`

Key transitions:
- `CREATE` -> `NEW` or `READY` (if immediately due)
- `EVALUATE`: `NEW|SNOOZED -> READY` when due
- `EVALUATE`: non-terminal -> `EXPIRED` when deadline/window expires
- `CLAIM`: `READY -> CLAIMED`
- `RENEW_CLAIM`: `CLAIMED -> CLAIMED`
- `CLAIM_EXPIRED`: `CLAIMED -> READY`
- `START`: `READY|CLAIMED -> IN_PROGRESS`
- `BLOCK`: `IN_PROGRESS -> BLOCKED`
- `RESUME`: `BLOCKED -> IN_PROGRESS`
- `COMPLETE`: `IN_PROGRESS -> COMPLETED`
- `FAIL`: `IN_PROGRESS -> FAILED` or retry path back to `READY`
- `CANCEL`: `NEW|READY|CLAIMED|IN_PROGRESS|BLOCKED|SNOOZED -> CANCELED`
- `SNOOZE`: `NEW|READY -> SNOOZED`

Safety:
- Due events are emitted only when transition result is `READY`.
- Terminal tasks are never source of due-event emissions.

## Actions

Core:
- `health`
- `metrics`
- `create`
- `get`
- `list`
- `attempts`
- `attempt`
- `update`
- `evaluate`
- `claim`
- `renew-claim`
- `start`
- `complete`
- `fail`
- `cancel`
- `snooze`
- `ack-event`

In-process subscriber demo:
- `subscribe`
- `unsubscribe`

All commands support `--format json|text` (default `json`).
Execution lifecycle commands:
- `complete --note <text>` accepted.
- `fail --error-code <code> --error <message> --note <text>` accepted.
- `cancel --note <text>` accepted.

## Operational Configuration

Runtime knobs can be set by JVM property or environment variable.

Scheduler:
- `todero.taskmanager.scheduler.scan-interval-ms`
- `TODERO_TASKMANAGER_SCHEDULER_SCAN_INTERVAL_MS`
- Default: `1000` (min `100`, max `60000`)

- `todero.taskmanager.scheduler.evaluate-limit`
- `TODERO_TASKMANAGER_SCHEDULER_EVALUATE_LIMIT`
- Default: `200` (min `1`, max `1000`)

- `todero.taskmanager.scheduler.dispatch-limit`
- `TODERO_TASKMANAGER_SCHEDULER_DISPATCH_LIMIT`
- Default: `200` (min `1`, max `1000`)

Dispatcher:
- `todero.taskmanager.dispatch.max-delivery-attempts`
- `TODERO_TASKMANAGER_DISPATCH_MAX_DELIVERY_ATTEMPTS`
- Default: `10` (min `1`, max `1000`)

- `todero.taskmanager.dispatch.target-command`
- `TODERO_TASKMANAGER_DISPATCH_TARGET_COMMAND`
- Default: `react`

## Deploy / Smoke

Build:
- `mvn -f todero-monorepo-components/pom.xml -pl task-manager-component -am clean install -DskipTests`

Install + reload:
- `com.shellaia.verbatim.component.aia.admin install --set service-a --coord com.example.todero:task-manager-component:0.1.0-SNAPSHOT --reload true`

Smoke:
- `help`
- `com.shellaia.verbatim.component.task.manager health --format text`
- `com.shellaia.verbatim.component.task.manager create --task-id smoke-1 --title "smoke task" --assigned smoke-agent --created-by smoke --format text`
- `com.shellaia.verbatim.component.task.manager list --status NEW,READY --limit 10 --format text`
- `com.shellaia.verbatim.component.task.manager evaluate --limit 100 --format text`
- `com.shellaia.verbatim.component.task.manager ack-event --agent smoke-agent --event-id smoke-event-id --format text`

Demo script:
- `task-manager-component/scripts/demo-task-manager-console.sh`

## Observability Notes

Use `metrics` to monitor:
- Scheduler cycles and failures.
- Dispatcher scan/ack/failure/no-subscriber/skipped-max-retries counters.

Noisy/redundant emission controls:
- Outbox retries are bounded by `max-delivery-attempts`.
- Missing subscribers increment attempts and eventually stop retrying.
