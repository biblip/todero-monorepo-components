#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root_pom="${script_dir}/pom.xml"
version_file="${script_dir}/version.txt"

usage() {
  cat <<'EOF'
Usage: ./sync-version.sh [--version <value>] [--dry-run]

Reads the monorepo version from version.txt by default and rewrites:
- the root pom.xml <version>
- every child pom.xml parent <version> that points at shellaia-components

Options:
  --version <value>  Override version.txt for this sync run.
  --dry-run          Print the files that would change without writing them.
EOF
}

version="${SYNC_VERSION:-}"
dry_run=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)
      [[ $# -ge 2 ]] || { echo "Missing value for --version" >&2; exit 1; }
      version="$2"
      shift 2
      ;;
    --dry-run)
      dry_run=true
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

if [[ -z "${version}" ]]; then
  if [[ ! -f "${version_file}" ]]; then
    echo "Missing version file: ${version_file}" >&2
    exit 1
  fi
  version="$(tr -d '[:space:]' < "${version_file}")"
fi

if [[ ! "${version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?$ ]]; then
  echo "Invalid version: ${version}" >&2
  exit 1
fi

export SYNC_VERSION="${version}"
export SYNC_ROOT_POM="${root_pom}"
export SYNC_DRY_RUN="${dry_run}"
export SYNC_REPO_DIR="${script_dir}"

python3 - <<'PY'
import os
import re
from pathlib import Path

version = os.environ["SYNC_VERSION"]
root_pom = Path(os.environ["SYNC_ROOT_POM"])
repo_dir = Path(os.environ["SYNC_REPO_DIR"])
dry_run = os.environ["SYNC_DRY_RUN"].lower() == "true"

version_pattern = re.compile(
    r'(<artifactId>shellaia-components</artifactId>\s*<version>)([^<]+)(</version>)',
    re.DOTALL,
)
internal_parent_pattern = re.compile(
    r'(<parent>\s*<groupId>com\.shellaia</groupId>\s*<artifactId>[^<]+</artifactId>\s*<version>)([^<]+)(</version>)',
    re.DOTALL,
)

def classify(path: Path) -> str:
    rel = path.relative_to(repo_dir)
    parts = rel.parts
    if rel == Path("pom.xml"):
        return "root"
    if parts[0] in {"components", "agents", "processors", "tutil"}:
        return parts[0]
    return "other"

managed = []
updated_paths = []
skipped = []

def update_file(path: Path, required: bool) -> bool:
    original = path.read_text()
    rewritten = original
    replacements = 0

    if path == root_pom:
        rewritten, count = re.subn(
            r'(<project[^>]*>.*?<version>)([^<]+)(</version>)',
            r'\g<1>' + version + r'\3',
            rewritten,
            count=1,
            flags=re.DOTALL,
        )
        replacements += count
        if count == 0 and required:
            raise SystemExit(f"Could not find root project version tag in {path}")

    rewritten, count = version_pattern.subn(
        r'\g<1>' + version + r'\3',
        rewritten,
        count=1,
    )
    replacements += count

    rewritten, count = internal_parent_pattern.subn(
        r'\g<1>' + version + r'\3',
        rewritten,
    )
    replacements += count

    if replacements == 0:
        if required:
            raise SystemExit(f"Could not find managed version tags in {path}")
        return False

    if rewritten == original:
        skipped.append(path)
        return False

    if dry_run:
        updated_paths.append(path)
        return True

    path.write_text(rewritten)
    updated_paths.append(path)
    return True

changed = False
changed |= update_file(root_pom, True)

for pom in sorted(root_pom.parent.rglob("pom.xml")):
    if pom == root_pom:
        continue
    managed.append(pom)
    changed |= update_file(pom, False)

managed.insert(0, root_pom)

print(f"Version sync target: {version}")
print(f"Managed POMs discovered: {len(managed)}")
for pom in managed:
    rel = pom.relative_to(repo_dir)
    role = classify(pom)
    if pom in updated_paths:
        print(f"UPDATED [{role}] {rel}")
    elif pom in skipped:
        print(f"UNCHANGED [{role}] {rel}")
    else:
        print(f"DISCOVERED [{role}] {rel}")

other_managed = [pom for pom in managed if classify(pom) == "other"]
if other_managed:
    print("Managed POMs outside the usual role folders:")
    for pom in other_managed:
        print(f"  - {pom.relative_to(repo_dir)}")

if not changed:
    print(f"No version changes required for {version}")
PY
