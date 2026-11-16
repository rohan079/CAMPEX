#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="${ROOT_DIR}/infrastructure"
MODE="native"
ACTION="up"
DETACH=0

usage() {
  cat <<'EOF'
Usage: ./run-local.sh [up|down|logs|status] [--docker] [--core-only] [--detach]

Default (no flags): start FastAPI, Spring Boot, and the React dashboard natively.
  ./run-local.sh

Options:
  --docker      Use Docker Compose and start the full stack, including HF models.
  --core-only   With --docker, omit the HF model containers.
  --detach      With --docker, run Compose in the background.
  down|logs|status  Compose management commands; use with --docker.

Native mode requires Maven, npm dependencies, the FastAPI .venv, and a reachable
PostgreSQL database configured by springboot-lead-service/local.properties or
DATABASE_URL/DATABASE_USER/DATABASE_PASSWORD. Press Ctrl+C to stop native services.

The local HF model profile needs Linux + NVIDIA Container Toolkit and HF_TOKEN.
On macOS, configure HF_SPEECH_WS_URL to a reachable Realtime endpoint if using Edesy.
EOF
}

while (($#)); do
  case "$1" in
    up|down|logs|status) ACTION="$1" ;;
    --docker) MODE="docker-full" ;;
    --core-only) MODE="docker-core" ;;
    --native) MODE="native" ;;
    --detach|-d) DETACH=1 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    return 1
  fi
}

if [[ "${MODE}" == "native" ]]; then
  if [[ "${ACTION}" != "up" ]]; then
    echo "Native mode runs in the foreground; stop it with Ctrl+C. Use --docker for down/logs/status." >&2
    exit 2
  fi

  require_command mvn
  require_command npm
  require_command curl

  SPRING_DIR="${ROOT_DIR}/springboot-lead-service"
  VOICE_DIR="${ROOT_DIR}/fastapi-voice-service"
  DASHBOARD_DIR="${ROOT_DIR}/dashboard"
  UVICORN="${VOICE_DIR}/.venv/bin/uvicorn"
  if [[ ! -x "${UVICORN}" ]]; then
    echo "FastAPI virtualenv is missing. Create it with: python3 -m venv ${VOICE_DIR}/.venv && ${VOICE_DIR}/.venv/bin/pip install -r ${VOICE_DIR}/requirements.txt" >&2
    exit 2
  fi
  if [[ ! -x "${DASHBOARD_DIR}/node_modules/.bin/vite" ]]; then
    echo "Dashboard dependencies are missing. Install them with: cd ${DASHBOARD_DIR} && npm ci" >&2
    exit 2
  fi
  if [[ -n "${DATABASE_URL:-}" ]]; then
    if [[ "${DATABASE_URL}" != jdbc:postgresql://* ]]; then
      echo "DATABASE_URL must start with jdbc:postgresql://. Keep DATABASE_USER and DATABASE_PASSWORD separate." >&2
      exit 2
    fi
    if [[ -z "${DATABASE_USER:-}" || -z "${DATABASE_PASSWORD:-}" ]]; then
      echo "When using DATABASE_URL, also export DATABASE_USER and DATABASE_PASSWORD." >&2
      exit 2
    fi
  elif [[ -f "${SPRING_DIR}/local.properties" ]]; then
    if ! grep -Eq '^[[:space:]]*spring\.datasource\.url[[:space:]]*=[[:space:]]*jdbc:postgresql://' "${SPRING_DIR}/local.properties" \
      || ! grep -Eq '^[[:space:]]*spring\.datasource\.username[[:space:]]*=[[:space:]]*[^[:space:]]+' "${SPRING_DIR}/local.properties" \
      || ! grep -Eq '^[[:space:]]*spring\.datasource\.password[[:space:]]*=[[:space:]]*[^[:space:]]+' "${SPRING_DIR}/local.properties" \
      || grep -Eq '=[[:space:]]*<[^>]+>' "${SPRING_DIR}/local.properties"; then
      echo "${SPRING_DIR}/local.properties must set a jdbc:postgresql:// URL, username, and password on separate lines." >&2
      exit 2
    fi
  else
    echo "Missing ${SPRING_DIR}/local.properties (not local.properties.example). Copy the example there and fill its three datasource properties, or export DATABASE_URL, DATABASE_USER, and DATABASE_PASSWORD." >&2
    exit 2
  fi
  if [[ ! -f "${VOICE_DIR}/.env" ]]; then
    cp "${VOICE_DIR}/.env.example" "${VOICE_DIR}/.env"
    echo "Created ${VOICE_DIR}/.env. Fill Edesy/HF/AI settings there before testing those features."
  fi

  DASHBOARD_ALREADY_RUNNING=0
  if command -v lsof >/dev/null 2>&1; then
    for port in 8000 8080; do
      if lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
        echo "Port ${port} is already in use. Stop that service or free the port, then retry." >&2
        exit 2
      fi
    done
    if lsof -nP -iTCP:5173 -sTCP:LISTEN >/dev/null 2>&1; then
      if curl -fsS --max-time 2 http://127.0.0.1:5173/ 2>/dev/null | grep -q "Campex"; then
        DASHBOARD_ALREADY_RUNNING=1
        echo "Reusing the existing Campex/Vite dashboard on port 5173."
      else
        echo "Port 5173 is in use by another application. Stop it or free the port, then retry." >&2
        exit 2
      fi
    fi
  fi

  LOG_DIR="${ROOT_DIR}/.run-local"
  mkdir -p "${LOG_DIR}"
  : >"${LOG_DIR}/voice.log"
  : >"${LOG_DIR}/lead.log"
  : >"${LOG_DIR}/dashboard.log"
  SERVICE_PIDS=()
  TAIL_PID=""

  cleanup() {
    trap - EXIT INT TERM
    if [[ -n "${TAIL_PID}" ]] && kill -0 "${TAIL_PID}" 2>/dev/null; then
      kill "${TAIL_PID}" 2>/dev/null || true
    fi
    for pid in "${SERVICE_PIDS[@]}"; do
      if kill -0 "${pid}" 2>/dev/null; then
        kill "${pid}" 2>/dev/null || true
      fi
    done
    for pid in "${SERVICE_PIDS[@]}"; do wait "${pid}" 2>/dev/null || true; done
    if [[ -n "${TAIL_PID}" ]]; then wait "${TAIL_PID}" 2>/dev/null || true; fi
    echo "Local application services stopped. Logs remain in ${LOG_DIR}."
  }
  trap cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM

  echo "Starting FastAPI voice service..."
  (cd "${VOICE_DIR}" && exec "${UVICORN}" main:app --host 127.0.0.1 --port 8000) >"${LOG_DIR}/voice.log" 2>&1 &
  SERVICE_PIDS+=("$!")

  echo "Starting Spring Boot lead service..."
  (cd "${SPRING_DIR}" && exec mvn spring-boot:run) >"${LOG_DIR}/lead.log" 2>&1 &
  SERVICE_PIDS+=("$!")

  tail -n 0 -F "${LOG_DIR}/voice.log" "${LOG_DIR}/lead.log" "${LOG_DIR}/dashboard.log" &
  TAIL_PID="$!"

  wait_for_http() {
    local name="$1" url="$2" pid="$3" timeout="$4"
    local second
    for ((second = 0; second < timeout; second++)); do
      if ! kill -0 "${pid}" 2>/dev/null; then
        echo "${name} exited before becoming ready. See ${LOG_DIR}/${name}.log." >&2
        return 1
      fi
      if curl -fsS --max-time 2 "${url}" >/dev/null 2>&1; then
        echo "${name} is ready at ${url}"
        return 0
      fi
      sleep 1
    done
    echo "Timed out waiting for ${name} at ${url}. See ${LOG_DIR}/${name}.log." >&2
    return 1
  }

  wait_for_http voice http://127.0.0.1:8000/health "${SERVICE_PIDS[0]}" 60
  wait_for_http lead http://127.0.0.1:8080/health "${SERVICE_PIDS[1]}" 180

  if ((DASHBOARD_ALREADY_RUNNING)); then
    echo "Dashboard remains on http://127.0.0.1:5173 and will now proxy to the started Spring service."
  else
    echo "Starting React dashboard..."
    (cd "${DASHBOARD_DIR}" && exec npm run dev -- --host 127.0.0.1 --port 5173) >"${LOG_DIR}/dashboard.log" 2>&1 &
    SERVICE_PIDS+=("$!")
    wait_for_http dashboard http://127.0.0.1:5173 "${SERVICE_PIDS[2]}" 60
  fi

  cat <<EOF

Local application is running:
  Dashboard:    http://127.0.0.1:5173
  Spring API:   http://127.0.0.1:8080
  FastAPI:      http://127.0.0.1:8000
  Logs:         ${LOG_DIR}

This native mode does not start the HF model containers. Edesy AI calls require
HF_SPEECH_WS_URL in fastapi-voice-service/.env to point to a reachable HF Realtime server.
Press Ctrl+C to stop the services.
EOF

  while :; do
    for pid in "${SERVICE_PIDS[@]}"; do
      if ! kill -0 "${pid}" 2>/dev/null; then
        echo "A local service exited; check ${LOG_DIR}." >&2
        exit 1
      fi
    done
    sleep 2
  done
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required for Docker mode." >&2
  exit 127
fi
if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose v2 is required (docker compose)." >&2
  exit 127
fi

ensure_env_file() {
  local target="$1" template="$2"
  if [[ ! -f "${INFRA_DIR}/${target}" ]]; then
    cp "${INFRA_DIR}/${template}" "${INFRA_DIR}/${target}"
    echo "Created ${INFRA_DIR}/${target} from ${template}; review its values before using external services."
  fi
}

ensure_env_file "lead-service.env" "lead-service.env.example"
ensure_env_file "voice-service.env" "voice-service.env.example"
if [[ "${MODE}" == "docker-full" && "${ACTION}" == "up" ]]; then
  ensure_env_file "hf-model.env" "hf-model.env.example"
fi

compose=(docker compose)
if [[ "${MODE}" == "docker-full" ]]; then
  compose+=(--profile hf)
  if [[ "${ACTION}" == "up" ]]; then
    if [[ "$(uname -s)" != "Linux" ]] || ! command -v nvidia-smi >/dev/null 2>&1; then
      echo "Full Docker mode requires Linux + NVIDIA Container Toolkit. Use native mode or --core-only on this host." >&2
      exit 2
    fi
    if ! grep -Eq '^HF_TOKEN=[^[:space:]]+' "${INFRA_DIR}/hf-model.env"; then
      echo "Set HF_TOKEN in infrastructure/hf-model.env after accepting access to the gated Indic Parler model." >&2
      exit 2
    fi
  fi
fi

cd "${INFRA_DIR}"
case "${ACTION}" in
  up)
    command=("${compose[@]}" up --build)
    if ((DETACH)); then command+=(-d); fi
    echo "Starting AI Voice Platform using Docker Compose (${MODE})..."
    "${command[@]}"
    ;;
  down)
    "${compose[@]}" down
    echo "Containers stopped. Persistent PostgreSQL and model-cache volumes were kept."
    ;;
  logs)
    "${compose[@]}" logs --follow --tail=100
    ;;
  status)
    "${compose[@]}" ps
    ;;
esac
