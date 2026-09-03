#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "$(uname -s)" != "Linux" ]]; then
  echo 'This smoke test must run on a Linux Docker host.' >&2
  exit 1
fi

for required_variable in PMS_API_IMAGE PMS_WEB_IMAGE PMS_MYSQL_IMAGE PMS_REDIS_IMAGE; do
  if [[ -z "${!required_variable:-}" ]]; then
    echo "$required_variable is required." >&2
    exit 1
  fi
done

server_os="$(docker version --format '{{.Server.Os}}')"
server_arch="$(docker version --format '{{.Server.Arch}}')"
if [[ "$server_os" != 'linux' ]]; then
  echo "The target Docker daemon must run Linux containers, found: $server_os" >&2
  exit 1
fi
case "$server_arch" in
  amd64|x86_64) target_arch='amd64' ;;
  arm64|aarch64) target_arch='arm64' ;;
  386|i386|i686) target_arch='386' ;;
  *)
    echo "Unsupported Docker server architecture: $server_arch" >&2
    exit 1
    ;;
esac
export PMS_TARGET_PLATFORM="linux/$target_arch"

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
compose_file="$repository_root/deploy/docker-compose.production.example.yml"
temporary_parent="${RUNNER_TEMP:-/tmp}"
fixture_root="$(mktemp -d "$temporary_parent/pms3-production-smoke.XXXXXX")"
secret_directory="$fixture_root/secrets"
project="pms3-security-smoke-${GITHUB_RUN_ID:-$$}-${GITHUB_RUN_ATTEMPT:-1}"

if [[ ! "$project" =~ ^pms3-security-smoke-[0-9]+-[0-9]+$ ]]; then
  echo "Refusing unsafe Compose project name: $project" >&2
  exit 1
fi
case "$fixture_root" in
  "$temporary_parent"/pms3-production-smoke.*) ;;
  *)
    echo "Refusing unsafe fixture path: $fixture_root" >&2
    exit 1
    ;;
esac

compose=(docker compose --project-name "$project" --file "$compose_file")
egress_probe_network=''
egress_probe_container=''
cleanup() {
  local status=$?
  trap - EXIT
  set +e
  if (( status != 0 )); then
    "${compose[@]}" ps
    "${compose[@]}" logs --no-color --tail 200
  fi
  if [[ -n "$egress_probe_container" ]]; then
    docker rm --force "$egress_probe_container" >/dev/null 2>&1
  fi
  if [[ -n "$egress_probe_network" ]]; then
    docker network rm "$egress_probe_network" >/dev/null 2>&1
  fi
  "${compose[@]}" down --volumes --remove-orphans --timeout 10
  case "$fixture_root" in
    "$temporary_parent"/pms3-production-smoke.*) rm -rf -- "$fixture_root" ;;
  esac
  exit "$status"
}
trap cleanup EXIT

install -d -m 0700 "$secret_directory"
printf '%s' 'SmokeMysqlApp-7vK3mN8q' > "$secret_directory/MYSQL_PASSWORD"
printf '%s' 'SmokeMysqlRoot-4xJ9pQ2w' > "$secret_directory/MYSQL_ROOT_PASSWORD"
printf '%s' 'SmokeRedis-6tR2cW8y' > "$secret_directory/REDIS_PASSWORD"
printf '%s' 'SmokeJwt-Only-5nH8qL2zV7mC4pR9xK6wD3sA' > "$secret_directory/JWT_SECRET"
printf '%s' 'SmokeCallback-Only-8qB3vN7mK2xW6pR4tY9cH5sL' > "$secret_directory/PMS_CALLBACK_SIGNING_SECRET"
printf '%s' 'SmokeAdmin-Only-9wF4mT7q' > "$secret_directory/PMS_BOOTSTRAP_ADMIN_PASSWORD"
chmod 0700 "$secret_directory"
chmod 0444 "$secret_directory"/*

if [[ "$(stat -c '%a' "$secret_directory")" != '700' ]]; then
  echo 'Secret fixture directory is not mode 0700.' >&2
  exit 1
fi
for secret_file in "$secret_directory"/*; do
  if [[ "$(stat -c '%a' "$secret_file")" != '444' ]]; then
    echo "Secret fixture file is not mode 0444: $secret_file" >&2
    exit 1
  fi
done

export PMS_SECRET_DIR="$secret_directory"
export MYSQL_DATABASE='pms3_security_smoke'
export MYSQL_USER='pms3_smoke'
export PMS_BOOTSTRAP_ADMIN_USERNAME='smoke_admin'
if [[ -n "${PMS_SMOKE_WEB_PORT:-}" ]]; then
  smoke_web_port="$PMS_SMOKE_WEB_PORT"
elif command -v python3 >/dev/null 2>&1; then
  smoke_web_port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
else
  smoke_web_port="$((49152 + ($$ % 10000)))"
fi
if [[ ! "$smoke_web_port" =~ ^[0-9]+$ ]] || (( smoke_web_port < 1 || smoke_web_port > 65535 )); then
  echo "Invalid smoke Web port: $smoke_web_port" >&2
  exit 1
fi
export PMS_WEB_PORT="$smoke_web_port"

"${compose[@]}" config --quiet
"${compose[@]}" up --detach --pull never --wait --wait-timeout 300

declare -A container_id_by_service=()
for service in mysql redis api web; do
  mapfile -t container_ids < <("${compose[@]}" ps --all --quiet "$service")
  if [[ ${#container_ids[@]} -ne 1 || -z "${container_ids[0]}" ]]; then
    echo "Expected exactly one isolated container for $service." >&2
    exit 1
  fi
  state="$(docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' "${container_ids[0]}")"
  if [[ "$state" != 'running healthy' ]]; then
    echo "Isolated $service container is not running and healthy: $state" >&2
    exit 1
  fi
  container_id_by_service["$service"]="${container_ids[0]}"
done

for service in mysql redis api web; do
  pid_one_uid="$(docker exec "${container_id_by_service[$service]}" sh -ec "awk '/^Uid:/{print \$2; exit}' /proc/1/status")"
  if [[ ! "$pid_one_uid" =~ ^[0-9]+$ ]] || [[ "$pid_one_uid" == '0' ]]; then
    echo "Isolated $service container PID 1 must run as non-root, found UID: ${pid_one_uid:-invalid}." >&2
    exit 1
  fi
done

for service in mysql redis api web; do
  image_id="$(docker inspect --format '{{.Image}}' "${container_id_by_service[$service]}")"
  actual_platform="$(docker image inspect --format '{{.Os}}/{{.Architecture}}{{if .Variant}}/{{.Variant}}{{end}}' "$image_id")"
  if [[ "$actual_platform" != "$PMS_TARGET_PLATFORM" ]]; then
    echo "Unexpected runtime image platform for $service: expected $PMS_TARGET_PLATFORM, found $actual_platform." >&2
    exit 1
  fi
done

network_name_for_logical() {
  local logical_name="$1"
  local -a network_ids=()
  mapfile -t network_ids < <(docker network ls \
    --filter "label=com.docker.compose.project=$project" \
    --filter "label=com.docker.compose.network=$logical_name" \
    --format '{{.ID}}')
  if [[ ${#network_ids[@]} -ne 1 || -z "${network_ids[0]}" ]]; then
    echo "Expected exactly one $logical_name network for project $project." >&2
    return 1
  fi
  docker network inspect --format '{{.Name}}' "${network_ids[0]}"
}

assert_container_networks() {
  local service="$1"
  shift
  local -a expected=("$@")
  local -a actual=()
  local -a expected_sorted=()
  mapfile -t actual < <(docker inspect \
    --format '{{range $name, $_ := .NetworkSettings.Networks}}{{println $name}}{{end}}' \
    "${container_id_by_service[$service]}" | sed '/^[[:space:]]*$/d' | sort)
  mapfile -t expected_sorted < <(printf '%s\n' "${expected[@]}" | sort)
  if [[ "${actual[*]}" != "${expected_sorted[*]}" ]]; then
    echo "Unexpected networks for $service: expected [${expected_sorted[*]}], found [${actual[*]}]." >&2
    exit 1
  fi
}

mapfile -t project_network_ids < <(docker network ls \
  --filter "label=com.docker.compose.project=$project" \
  --format '{{.ID}}')
if [[ ${#project_network_ids[@]} -ne 3 ]]; then
  echo "Expected exactly ingress, frontend, and data networks for project $project." >&2
  exit 1
fi

ingress_network="$(network_name_for_logical ingress)"
frontend_network="$(network_name_for_logical frontend)"
data_network="$(network_name_for_logical data)"
if [[ "$(docker network inspect --format '{{.Internal}}' "$data_network")" != 'true' ]]; then
  echo 'The production data network must be internal.' >&2
  exit 1
fi
if [[ "$(docker network inspect --format '{{.Internal}}' "$frontend_network")" != 'true' ]]; then
  echo 'The production frontend network must be internal.' >&2
  exit 1
fi
if [[ "$(docker network inspect --format '{{.Internal}}' "$ingress_network")" != 'false' ]]; then
  echo 'The production ingress network must support the published Web entry point.' >&2
  exit 1
fi

assert_container_networks web "$ingress_network" "$frontend_network"
assert_container_networks api "$frontend_network" "$data_network"
assert_container_networks mysql "$data_network"
assert_container_networks redis "$data_network"

"${compose[@]}" exec --no-TTY web sh -ec \
  'wget -q -O - http://api:8088/actuator/health | grep -q UP'

"${compose[@]}" exec --no-TTY redis sh -ec '
  redis_password="$(cat /run/secrets/REDIS_PASSWORD)"
  export REDISCLI_AUTH="$redis_password"
  unset redis_password

  test "$(redis-cli --raw ping)" = PONG
  test "$(redis-cli --raw set pms3:smoke:acl verified)" = OK
  test "$(redis-cli --raw get pms3:smoke:acl)" = verified
  test "$(redis-cli --raw del pms3:smoke:acl)" = 1

  assert_denied() {
    denied_output="$("$@" 2>&1 || true)"
    case "$denied_output" in
      *NOPERM*) ;;
      *)
        echo "Redis unexpectedly allowed a forbidden ACL operation." >&2
        return 1
        ;;
    esac
  }

  assert_denied redis-cli --raw set outside:smoke blocked
  assert_denied redis-cli --raw flushall
  assert_denied redis-cli --raw config get '*'
  assert_denied redis-cli --raw publish pms3:smoke blocked
'

"${compose[@]}" exec --no-TTY api sh -ec '
  if awk '\''NR > 1 && $2 == "00000000" { found = 1 } END { exit(found ? 0 : 1) }'\'' /proc/net/route; then
    echo "API has an IPv4 default route." >&2
    exit 1
  fi
  if test -r /proc/net/ipv6_route && awk '\''$1 == "00000000000000000000000000000000" && $2 == "00" && $10 != "lo" { found = 1 } END { exit(found ? 0 : 1) }'\'' /proc/net/ipv6_route; then
    echo "API has a non-loopback IPv6 default route." >&2
    exit 1
  fi
'

egress_probe_network="${project}-external-probe"
egress_probe_container="${project}-external-probe"
docker network create "$egress_probe_network" >/dev/null
docker run --detach \
  --name "$egress_probe_container" \
  --network "$egress_probe_network" \
  --entrypoint redis-server \
  "$PMS_REDIS_IMAGE" \
  --save '' \
  --appendonly no \
  --protected-mode no \
  --bind 0.0.0.0 >/dev/null
probe_ready='false'
for _ in {1..20}; do
  if docker exec "$egress_probe_container" redis-cli ping 2>/dev/null | grep -q PONG; then
    probe_ready='true'
    break
  fi
  sleep 0.5
done
if [[ "$probe_ready" != 'true' ]]; then
  echo 'The external egress probe did not become ready.' >&2
  exit 1
fi
egress_probe_ip="$(docker inspect --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$egress_probe_container")"
if [[ ! "$egress_probe_ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo 'Unable to resolve the external egress probe IP.' >&2
  exit 1
fi
"${compose[@]}" exec --no-TTY api sh -ec \
  'command -v nc >/dev/null; if nc -z -w 3 "$1" 6379; then echo "API unexpectedly reached the external egress probe." >&2; exit 1; fi' \
  sh "$egress_probe_ip"

"${compose[@]}" exec --no-TTY --user 999:999 mysql sh -ec \
  'test -r /run/secrets/MYSQL_PASSWORD && test -s /run/secrets/MYSQL_PASSWORD && test -r /run/secrets/MYSQL_ROOT_PASSWORD && test -s /run/secrets/MYSQL_ROOT_PASSWORD'
"${compose[@]}" exec --no-TTY --user redis redis sh -ec \
  'test -r /run/secrets/REDIS_PASSWORD && test -s /run/secrets/REDIS_PASSWORD'
"${compose[@]}" exec --no-TTY --user app api sh -ec \
  'for name in MYSQL_PASSWORD REDIS_PASSWORD JWT_SECRET PMS_CALLBACK_SIGNING_SECRET PMS_BOOTSTRAP_ADMIN_PASSWORD; do test -r "/run/secrets/$name" && test -s "/run/secrets/$name"; done'
"${compose[@]}" exec --no-TTY web sh -ec \
  'test ! -e /run/secrets/MYSQL_PASSWORD && test ! -e /run/secrets/REDIS_PASSWORD && test ! -e /run/secrets/JWT_SECRET'

printf '%s\n' "Linux production Compose smoke passed: $PMS_TARGET_PLATFORM runtime, Redis namespace ACL, three-network isolation, blocked API egress, and file-backed Secret access verified."
