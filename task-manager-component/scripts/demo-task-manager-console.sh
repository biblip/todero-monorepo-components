#!/usr/bin/env bash
set -euo pipefail

HOST="${1:-brumor.pbxkey.com}"
SNI="${2:-$HOST}"
CONSOLE_JAR="${CONSOLE_JAR:-/Users/arturoportilla/IdeaProjects/todero-ecosystem/todero/playground/console.jar}"
COMPONENT="com.shellaia.verbatim.component.task.manager"

if [[ ! -f "$CONSOLE_JAR" ]]; then
  echo "console.jar not found at: $CONSOLE_JAR" >&2
  exit 1
fi

now_utc() {
  python3 - <<'PY'
from datetime import datetime, timezone
print(datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"))
PY
}

plus_seconds_utc() {
  local seconds="$1"
  python3 - "$seconds" <<'PY'
from datetime import datetime, timezone, timedelta
import sys
s = int(sys.argv[1])
print((datetime.now(timezone.utc) + timedelta(seconds=s)).replace(microsecond=0).isoformat().replace("+00:00", "Z"))
PY
}

SCHEDULED_AT="$(plus_seconds_utc 10)"
WINDOW_END="$(plus_seconds_utc 5)"
NOW_AT="$(now_utc)"

echo "Running demo against aia://$HOST (SNI=$SNI)"
echo "scheduled_for=$SCHEDULED_AT window_end=$WINDOW_END now=$NOW_AT"

{
  echo "help"
  echo "$COMPONENT subscribe --agent demo-agent-a --subscription-id demo-sub-a --format json"
  echo "$COMPONENT subscribe --agent demo-agent-b --subscription-id demo-sub-b --format json"
  echo "$COMPONENT create --task-id demo-immediate --title \"Immediate task\" --assigned demo-agent-a,demo-agent-b --created-by demo --format json"
  echo "$COMPONENT create --task-id demo-scheduled --title \"Scheduled in 10s\" --assigned demo-agent-a,demo-agent-b --scheduled-for $SCHEDULED_AT --created-by demo --format json"
  echo "$COMPONENT create --task-id demo-expiring --title \"Window expires quickly\" --assigned demo-agent-a,demo-agent-b --scheduled-for $NOW_AT --window-end $WINDOW_END --created-by demo --format json"
  echo "$COMPONENT evaluate --limit 100 --dispatch-mode subscribers --format json"
  sleep 12
  echo "$COMPONENT evaluate --limit 100 --dispatch-mode subscribers --format json"
  echo "$COMPONENT list --status READY,EXPIRED --limit 50 --format json"
  echo "$COMPONENT metrics --format json"
  echo "$COMPONENT unsubscribe --agent demo-agent-a --all true --format json"
  echo "$COMPONENT unsubscribe --agent demo-agent-b --all true --format json"
} | java -jar "$CONSOLE_JAR" --host="aia://$HOST" --sni="$SNI"

