#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "${repo_dir}/.env" ]]; then
  # shellcheck disable=SC1091
  source "${repo_dir}/.env"
fi
nexus_host="${NEXUS_HOST:-https://nexus.shellaia.com}"
credentials_file="${NEXUS_CREDENTIALS_FILE:-}"
run_tests="${RUN_TESTS:-false}"

usage() {
  cat <<'EOF'
Usage: ./publish-all-to-nexus.sh [--with-tests] [--version <x.y.z>] [--dry-run]

Publishes release modules in todero-monorepo-components to the Nexus server.
By default, the release version is derived from the root pom.xml version:
- if the current version is x.y.z-SNAPSHOT, it publishes x.y.z
- if the current version is x.y.z, it publishes x.y.z
After a successful publish, the script leaves the Maven project on the next patch snapshot locally, but it does not deploy snapshots.

Environment overrides:
  CI_NEXUS_USER                  Nexus username
  CI_NEXUS_PASSWORD              Nexus password
  NEXUS_HOST                     Defaults to https://nexus.shellaia.com
  NEXUS_CREDENTIALS_FILE         Optional file with username= and password= entries
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

if [[ -z "${publish_version}" ]]; then
  if [[ "${current_project_version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$ ]]; then
    publish_version="${current_project_version%-SNAPSHOT}"
  else
    publish_version="${current_project_version}"
  fi
fi

if [[ ! "${publish_version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Release version must look like 1.2.3. Resolved: ${publish_version}" >&2
  exit 1
fi

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
Next project snapshot version: ${next_snapshot_version}
Deploy target: release distributionManagement in pom.xml via ${nexus_host}
Credentials lookup path: ${credentials_file}
EOF
  exit 0
fi

read -r nexus_user nexus_secret < <(
  python3 - "${credentials_file}" <<'PY'
import os
import sys

credentials_path = sys.argv[1]
user = os.environ.get("CI_NEXUS_USER", "").strip()
secret = os.environ.get("CI_NEXUS_PASSWORD", "").strip()

if user and secret:
    print(user, secret)
    raise SystemExit(0)

values = {}
if os.path.exists(credentials_path):
    with open(credentials_path, "r", encoding="utf-8") as fh:
        for raw in fh:
            line = raw.strip()
            if not line or "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()

user = user or values.get("username", "")
secret = secret or values.get("password", "")
if not user or not secret:
    raise SystemExit(1)

print(user, secret)
PY
) || {
  echo "Unable to resolve Nexus credentials. Set CI_NEXUS_USER with CI_NEXUS_PASSWORD, or provision ${credentials_file}." >&2
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
      <username>__NEXUS_USER__</username>
      <password>__NEXUS_PASSWORD__</password>
    </server>
  </servers>
</settings>
EOF

python3 - "${tmp_settings}" "${nexus_user}" "${nexus_secret}" <<'PY'
import sys
from xml.sax.saxutils import escape

path, user, password = sys.argv[1:4]
with open(path, "r", encoding="utf-8") as fh:
    text = fh.read()
text = text.replace("__NEXUS_USER__", escape(user))
text = text.replace("__NEXUS_PASSWORD__", escape(password))
with open(path, "w", encoding="utf-8") as fh:
    fh.write(text)
PY

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
  -Dnexus.baseUrl="${nexus_host%/}" \
  "${deploy_goal[@]}"

set_version "${next_snapshot_version}"
restore_needed="false"

cat <<EOF
Published version: ${publish_version}
Project version set to: ${next_snapshot_version}
Deployment repository: ${nexus_host%/}/repository/maven-releases/
EOF
