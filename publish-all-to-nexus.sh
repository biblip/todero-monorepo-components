#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
version_file="${repo_dir}/version.txt"
nexus_root_default="$(cd "${repo_dir}/.." && pwd)/nexus"
nexus_root="${NEXUS_PROJECT_DIR:-${nexus_root_default}}"
nexus_host="${NEXUS_HOST:-http://localhost:8081}"
token_file="${NEXUS_TOKEN_FILE:-${nexus_root}/provisioning/output/ci-publisher.token}"
run_tests="${RUN_TESTS:-false}"
deploy_repo_id="${NEXUS_DEPLOY_REPOSITORY_ID:-nexus-releases}"
deploy_repo_url="${NEXUS_DEPLOY_REPOSITORY_URL:-${nexus_host%/}/repository/maven-releases/}"

usage() {
  cat <<'EOF'
Usage: ./publish-all-to-nexus.sh [--with-tests] [--version <x.y.z>] [--dry-run]

Publishes all modules in todero-monorepo-components to the local Nexus instance.
The publish version is read from version.txt unless --version is provided.
After a successful publish, the script increments the patch version in version.txt
and leaves the Maven project on the next patch snapshot.

Environment overrides:
  CI_NEXUS_USER                  Nexus username
  CI_NEXUS_TOKEN                 Nexus user token
  NEXUS_HOST                     Defaults to http://localhost:8081
  NEXUS_PROJECT_DIR              Defaults to ../nexus
  NEXUS_TOKEN_FILE               Defaults to ../nexus/provisioning/output/ci-publisher.token
  NEXUS_DEPLOY_REPOSITORY_ID     Defaults to nexus-releases
  NEXUS_DEPLOY_REPOSITORY_URL    Defaults to http://localhost:8081/repository/maven-releases/
  RUN_TESTS=true                 Same as --with-tests
EOF
}

publish_version=""
dry_run="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-tests)
      run_tests="true"
      shift
      ;;
    --version)
      publish_version="${2:-}"
      shift 2
      ;;
    --dry-run)
      dry_run="true"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

require_cmd mvn
require_cmd python3
require_cmd curl

if [[ ! -f "${version_file}" ]]; then
  echo "Missing version file: ${version_file}" >&2
  exit 1
fi

if [[ -z "${publish_version}" ]]; then
  publish_version="$(tr -d '[:space:]' < "${version_file}")"
fi

if [[ ! "${publish_version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "version.txt must contain a release version like 1.2.3. Got: ${publish_version}" >&2
  exit 1
fi

current_project_version="$(
  python3 - "${repo_dir}/pom.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET

ns = {"m": "http://maven.apache.org/POM/4.0.0"}
root = ET.parse(sys.argv[1]).getroot()
version = root.findtext("m:version", namespaces=ns)
if version is None:
    raise SystemExit(1)
print(version.strip())
PY
)"

next_version="$(
  python3 - "${publish_version}" <<'PY'
import sys
major, minor, patch = map(int, sys.argv[1].split("."))
print(f"{major}.{minor}.{patch + 1}")
PY
)"
next_snapshot_version="${next_version}-SNAPSHOT"

if [[ "${dry_run}" == "true" ]]; then
  cat <<EOF
Dry run only.
Current project version: ${current_project_version}
Publish version: ${publish_version}
Next version.txt value: ${next_version}
Next project snapshot version: ${next_snapshot_version}
Deploy target: ${deploy_repo_id} -> ${deploy_repo_url}
Token lookup path: ${token_file}
EOF
  exit 0
fi

read -r nexus_user nexus_secret < <(
  python3 - "${token_file}" <<'PY'
import os
import sys

token_path = sys.argv[1]
user = os.environ.get("CI_NEXUS_USER", "").strip()
secret = (
    os.environ.get("CI_NEXUS_TOKEN", "").strip()
    or os.environ.get("CI_NEXUS_PASSWORD", "").strip()
)

if user and secret:
    print(user, secret)
    raise SystemExit(0)

values = {}
if os.path.exists(token_path):
    with open(token_path, "r", encoding="utf-8") as fh:
        for raw in fh:
            line = raw.strip()
            if not line or "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()

user = user or values.get("username", "")
secret = secret or values.get("token", "") or values.get("password", "")
if not user or not secret:
    raise SystemExit(1)

print(user, secret)
PY
) || {
  echo "Unable to resolve Nexus credentials. Set CI_NEXUS_USER with CI_NEXUS_TOKEN/CI_NEXUS_PASSWORD, or provision ${token_file}." >&2
  exit 1
}

curl -fsSL "${nexus_host%/}/service/rest/v1/status" >/dev/null || {
  echo "Nexus is not reachable at ${nexus_host}." >&2
  exit 1
}

tmp_settings="$(mktemp "${TMPDIR:-/tmp}/todero-nexus-settings.XXXXXX.xml")"
cleanup() {
  rm -f "${tmp_settings}"
}
trap cleanup EXIT

cat > "${tmp_settings}" <<EOF
<settings>
  <servers>
    <server>
      <id>nexus-releases</id>
      <username>${nexus_user}</username>
      <password>${nexus_secret}</password>
    </server>
    <server>
      <id>nexus-snapshots</id>
      <username>${nexus_user}</username>
      <password>${nexus_secret}</password>
    </server>
    <server>
      <id>nexus-public</id>
      <username>${nexus_user}</username>
      <password>${nexus_secret}</password>
    </server>
  </servers>
</settings>
EOF

set_version() {
  local version="$1"
  mvn -f "${repo_dir}/pom.xml" \
    -s "${tmp_settings}" \
    -DgenerateBackupPoms=false \
    -DnewVersion="${version}" \
    versions:set
}

restore_original_version() {
  if [[ "${restore_needed}" == "true" && "${current_project_version}" != "${next_snapshot_version}" ]]; then
    set +e
    set_version "${current_project_version}" >/dev/null 2>&1
    set -e
  fi
}

restore_needed="true"
trap 'restore_original_version; cleanup' EXIT

set_version "${publish_version}"

deploy_goal=(clean deploy)
if [[ "${run_tests}" != "true" ]]; then
  deploy_goal+=(-DskipTests)
fi

mvn -f "${repo_dir}/pom.xml" \
  -s "${tmp_settings}" \
  "${deploy_goal[@]}" \
  -DaltDeploymentRepository="${deploy_repo_id}::${deploy_repo_url}"

printf '%s\n' "${next_version}" > "${version_file}"
set_version "${next_snapshot_version}"
restore_needed="false"

cat <<EOF
Published version: ${publish_version}
Next version.txt: ${next_version}
Project version set to: ${next_snapshot_version}
Deployment repository: ${deploy_repo_url}
EOF
