#!/usr/bin/env bash
set -euo pipefail

workspace_dir="${1:-./workspace}"
component_name="${2:-com.shellaia.agent.dj}"
command_name="${3:-process}"
body_value="${4:-get status}"
#modules_to_copy=(
#  "components/aia-protocol-component"
#  "components/contacts-component"
#  "components/dashboard-planner-component"
#  "components/email-component"
#  "components/simple-component"
#  "components/ssh-component"
#  "components/todero-spotify-component"
#  "components/vlc-component"
#  "agents/todero-spotify-agent"
#)

modules_to_copy=(
  "components/aia-admin-component"
)

latest_jar() {
  local module_path="$1"
  find "${module_path}/target" -maxdepth 1 -type f -name '*.jar' ! -name 'original-*' | sort | tail -n 1 || true
}

build_modules() {
  local modules_csv
  modules_csv="$(IFS=,; echo "${modules_to_copy[*]}")"
  mvn -f ./pom.xml -pl "${modules_csv}" -am clean package
}

copy_module_artifacts() {
  local module_path="$1"
  local module_dir jar_path
  module_dir="$(basename "${module_path}")"
  jar_path="$(latest_jar "${module_path}")"
  if [[ -z "${jar_path}" ]]; then
    echo "No JAR found in ${module_path}/target/. Build failed." >&2
    exit 1
  fi
  mkdir -p "${workspace_dir}/components/${module_dir}"
  cp "${jar_path}" "${workspace_dir}/components/${module_dir}/${module_dir}.jar"
  echo "Copied ${jar_path} -> ${workspace_dir}/components/${module_dir}/${module_dir}.jar"
  if [[ -f "${module_path}/.env" ]]; then
    cp "${module_path}/.env" "${workspace_dir}/components/${module_dir}/.env"
    echo "Copied ${module_path}/.env -> ${workspace_dir}/components/${module_dir}/.env"
  fi
}

needs_build=false
for module_path in "${modules_to_copy[@]}"; do
  if [[ -z "$(latest_jar "${module_path}")" ]]; then
    needs_build=true
    break
  fi
done

if [[ "${needs_build}" == "true" ]]; then
  build_modules
fi

for module_path in "${modules_to_copy[@]}"; do
  copy_module_artifacts "${module_path}"
done

shift 4 || true

java -jar ./todero-runner.jar \
  --workspace-dir "${workspace_dir}" \
  --server-type AI \
  --component "${component_name}" \
  --command "${command_name}" \
  --body "${body_value}" \
  "$@"
