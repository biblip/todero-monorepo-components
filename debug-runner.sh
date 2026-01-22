#!/usr/bin/env bash
set -euo pipefail

workspace_dir="${1:-./workspace}"
component_name="${2:-com.shellaia.verbatim.agent.dj}"
command_name="${3:-process}"
body_value="${4:-get status}"
modules_to_copy=(
  "aia-protocol-component"
  "contacts-component"
  "dashboard-planner-component"
  "email-component"
  "gmail-component"
  "simple-component"
  "ssh-component"
  "todero-gmail-agent"
  "todero-spotify-component"
  "todero-spotify-agent"
  "vlc-component"
)

latest_jar() {
  local module_dir="$1"
  ls -t "${module_dir}/target/${module_dir}-"*.jar 2>/dev/null | head -n 1 || true
}

build_modules() {
  local modules_csv
  modules_csv="$(IFS=,; echo "${modules_to_copy[*]}")"
  mvn -pl "${modules_csv}" -am clean package
}

copy_module_artifacts() {
  local module_dir="$1"
  local jar_path
  jar_path="$(latest_jar "${module_dir}")"
  if [[ -z "${jar_path}" ]]; then
    echo "No JAR found in ${module_dir}/target/. Build failed." >&2
    exit 1
  fi
  mkdir -p "${workspace_dir}/components/${module_dir}"
  cp "${jar_path}" "${workspace_dir}/components/${module_dir}/${module_dir}.jar"
  echo "Copied ${jar_path} -> ${workspace_dir}/components/${module_dir}/${module_dir}.jar"
  if [[ -f "${module_dir}/.env" ]]; then
    cp "${module_dir}/.env" "${workspace_dir}/components/${module_dir}/.env"
    echo "Copied ${module_dir}/.env -> ${workspace_dir}/components/${module_dir}/.env"
  fi
}

needs_build=false
for module_dir in "${modules_to_copy[@]}"; do
  if [[ -z "$(latest_jar "${module_dir}")" ]]; then
    needs_build=true
    break
  fi
done

if [[ "${needs_build}" == "true" ]]; then
  build_modules
fi

for module_dir in "${modules_to_copy[@]}"; do
  copy_module_artifacts "${module_dir}"
done

shift 4 || true

java "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005" \
  -jar ./todero-runner.jar \
  --workspace-dir "${workspace_dir}" \
  --server-type AI \
  --component "${component_name}" \
  --command "${command_name}" \
  --body "${body_value}" \
  "$@"
