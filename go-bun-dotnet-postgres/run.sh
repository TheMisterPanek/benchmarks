#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NETWORK="${NETWORK:-bench275}"
CPU_PROFILES="${CPU_PROFILES:-1 2}"
TARGETS="${TARGETS:-go bun-compile bun-nocompile dotnet8-jit dotnet8-aot dotnet10-jit dotnet10-aot}"
MODES="${MODES:-get post}"
PHASES="${PHASES:-cold,warm}"
K6_VUS="${K6_VUS:-50}"
K6_DURATION="${K6_DURATION:-10s}"
K6_WARMUP="${K6_WARMUP:-10s}"
MEMORY_LIMIT="${MEMORY_LIMIT:-3g}"
PG_TMPFS_SIZE="${PG_TMPFS_SIZE:-2g}"
SKIP_BUILD="${SKIP_BUILD:-0}"
PODMAN_SOCK="${PODMAN_SOCK:-/run/user/$(id -u)/podman/podman.sock}"
RESULTS_DIR="${RESULTS_DIR:-$ROOT_DIR/results}"
RESULTS_FILE="$RESULTS_DIR/matrix.tsv"

mkdir -p "$RESULTS_DIR"
printf "CPU\tPHASE\tTARGET\tMODE\tRPS\tAVG\tP50\tP90\tP95\tP99\tERR\tAPP_CPU\tPEAK_CPU\tAPP_RAM\tPG_CPU\n" > "$RESULTS_FILE"

if [ "$SKIP_BUILD" != "1" ]; then
  echo ">>> Building all container images with Podman..."
  podman build -t localhost/go-app-275:local "$ROOT_DIR/go-app"
  podman build -t localhost/bun-compile-275:local -f "$ROOT_DIR/bun-app/Dockerfile" "$ROOT_DIR/bun-app"
  podman build -t localhost/bun-nocompile-275:local -f "$ROOT_DIR/bun-app/Dockerfile.nocompile" "$ROOT_DIR/bun-app"
  podman build -t localhost/dotnet8-jit-275:local --build-arg DOTNET_CHANNEL=8.0 -f "$ROOT_DIR/dotnet-app/Dockerfile.jit" "$ROOT_DIR/dotnet-app"
  podman build -t localhost/dotnet8-aot-275:local --build-arg DOTNET_CHANNEL=8.0 -f "$ROOT_DIR/dotnet-app/Dockerfile.aot" "$ROOT_DIR/dotnet-app"
  podman build -t localhost/dotnet10-jit-275:local --build-arg DOTNET_CHANNEL=10.0 -f "$ROOT_DIR/dotnet-app/Dockerfile.jit" "$ROOT_DIR/dotnet-app"
  podman build -t localhost/dotnet10-aot-275:local --build-arg DOTNET_CHANNEL=10.0 -f "$ROOT_DIR/dotnet-app/Dockerfile.aot" "$ROOT_DIR/dotnet-app"
  podman build -t localhost/k6-bench-275:local "$ROOT_DIR/k6"
fi

echo ">>> Setting up Podman network '$NETWORK'..."
podman network exists "$NETWORK" || podman network create "$NETWORK"

podman rm -f bench-postgres bench-target go-app bun-app dotnet-jit-app dotnet-aot-app 2>/dev/null || true

start_fresh_postgres() {
  echo ">>> Starting fresh RAM-backed PostgreSQL 18.1 (tmpfs=${PG_TMPFS_SIZE})..."
  podman rm -f bench-postgres >/dev/null 2>&1 || true

  podman run -d --name bench-postgres --network "$NETWORK" --network-alias postgres \
    --tmpfs "/var/lib/postgresql:rw,size=${PG_TMPFS_SIZE}" \
    -e POSTGRES_USER=admin -e POSTGRES_PASSWORD=devops123 -e POSTGRES_DB=store \
    -v "$ROOT_DIR/k6/configs/init.sql:/docker-entrypoint-initdb.d/init.sql:ro,Z" \
    docker.io/library/postgres:18.1 \
    -c max_connections=500 \
    -c shared_buffers=1GB \
    -c wal_buffers=64MB \
    -c max_wal_size=4GB \
    -c checkpoint_timeout=30min \
    -c autovacuum=off \
    -c fsync=off \
    -c synchronous_commit=off \
    -c full_page_writes=off >/dev/null

  for i in $(seq 1 60); do
    if podman exec bench-postgres pg_isready -U admin -d store >/dev/null 2>&1 && \
       podman exec bench-postgres psql -U admin -d store -tAc \
         "SELECT count(*) FROM pg_tables WHERE tablename IN ('go_app','bun_app','dotnet8_jit_app','dotnet8_aot_app','dotnet10_jit_app','dotnet10_aot_app')" 2>/dev/null | grep -q '^6$'; then
      echo "PostgreSQL ready (fresh RAM-backed instance with all 6 tables)."
      return 0
    fi
    sleep 0.5
  done

  echo "ERROR: PostgreSQL did not become ready in time" >&2
  exit 1
}

ensure_postgres_running() {
  if ! podman container exists bench-postgres || [ "$(podman inspect -f '{{.State.Running}}' bench-postgres 2>/dev/null || true)" != "true" ]; then
    start_fresh_postgres
  fi
}

SOCK_ARGS=()
if [ -S "$PODMAN_SOCK" ]; then
  SOCK_ARGS+=(--security-opt label=disable -v "$PODMAN_SOCK:/run/podman/podman.sock:ro")
else
  echo "WARNING: Podman socket not found at $PODMAN_SOCK. CPU/RAM metrics will show as n/a."
  echo "         Enable it with: systemctl --user start podman.socket"
fi

start_target() {
  local target="$1"
  local cpus="$2"
  local cname="bench-target"
  podman rm -f "$cname" 2>/dev/null || true

  case "$target" in
    go)
      podman run -d --name "$cname" --network "$NETWORK" --cpus="$cpus" --memory="$MEMORY_LIMIT" \
        -e GOMAXPROCS="$cpus" \
        -v "$ROOT_DIR/k6/configs/go-config.yaml:/config.yaml:ro,Z" \
        localhost/go-app-275:local >/dev/null
      ;;
    bun-compile)
      podman run -d --name "$cname" --network "$NETWORK" --cpus="$cpus" --memory="$MEMORY_LIMIT" -w / \
        -v "$ROOT_DIR/k6/configs/bun-config.yaml:/config.yaml:ro,Z" \
        localhost/bun-compile-275:local >/dev/null
      ;;
    bun-nocompile)
      podman run -d --name "$cname" --network "$NETWORK" --cpus="$cpus" --memory="$MEMORY_LIMIT" \
        -v "$ROOT_DIR/k6/configs/bun-config.yaml:/app/config.yaml:ro,Z" \
        localhost/bun-nocompile-275:local >/dev/null
      ;;
    dotnet8-jit)
      podman run -d --name "$cname" --network "$NETWORK" --cpus="$cpus" --memory="$MEMORY_LIMIT" \
        -e APP_NAME=dotnet8-jit \
        -v "$ROOT_DIR/k6/configs/dotnet8-jit-config.yaml:/app/config.yaml:ro,Z" \
        localhost/dotnet8-jit-275:local >/dev/null
      ;;
    dotnet8-aot)
      podman run -d --name "$cname" --network "$NETWORK" --cpus="$cpus" --memory="$MEMORY_LIMIT" \
        -e APP_NAME=dotnet8-aot \
        -v "$ROOT_DIR/k6/configs/dotnet8-aot-config.yaml:/app/config.yaml:ro,Z" \
        localhost/dotnet8-aot-275:local >/dev/null
      ;;
    dotnet10-jit)
      podman run -d --name "$cname" --network "$NETWORK" --cpus="$cpus" --memory="$MEMORY_LIMIT" \
        -e APP_NAME=dotnet10-jit \
        -v "$ROOT_DIR/k6/configs/dotnet10-jit-config.yaml:/app/config.yaml:ro,Z" \
        localhost/dotnet10-jit-275:local >/dev/null
      ;;
    dotnet10-aot)
      podman run -d --name "$cname" --network "$NETWORK" --cpus="$cpus" --memory="$MEMORY_LIMIT" \
        -e APP_NAME=dotnet10-aot \
        -v "$ROOT_DIR/k6/configs/dotnet10-aot-config.yaml:/app/config.yaml:ro,Z" \
        localhost/dotnet10-aot-275:local >/dev/null
      ;;
    *)
      echo "Unknown target: $target" >&2
      exit 1
      ;;
  esac
}

trap 'podman rm -f bench-target bench-postgres 2>/dev/null || true' EXIT

for cpus in $CPU_PROFILES; do
  for target in $TARGETS; do
    for mode in $MODES; do
      if [ "$mode" = "post" ]; then
        # Brand-new RAM-backed PostgreSQL container per POST test to eliminate WAL/disk drift and autovacuum noise
        start_fresh_postgres
      else
        ensure_postgres_running
      fi

      # Fresh app container per (cpus, target, mode) so cold is truly cold and isolated
      start_target "$target" "$cpus"

      podman run --rm --name k6-bench --network "$NETWORK" \
        "${SOCK_ARGS[@]}" \
        -v "$RESULTS_DIR:/results:Z" \
        -e TARGETS="${target}=http://bench-target:8080" \
        -e MODES="$mode" \
        -e PHASES="$PHASES" \
        -e CPU_LABEL="$cpus" \
        -e K6_VUS="$K6_VUS" \
        -e K6_DURATION="$K6_DURATION" \
        -e K6_WARMUP="$K6_WARMUP" \
        -e RESULT_FILE="/results/matrix.tsv" \
        -e PRINT_SUMMARY="0" \
        localhost/k6-bench-275:local

      podman rm -f bench-target >/dev/null 2>&1 || true
    done
  done
done

echo ""
echo "========================================================================================================================================="
echo "FULL MATRIX SUMMARY (VUs=$K6_VUS, Duration=$K6_DURATION, Results saved to $RESULTS_FILE)"
echo "========================================================================================================================================="
printf "%-4s %-6s %-15s %-5s %8s %9s %9s %9s %9s %9s %7s %9s %9s %9s %9s\n" \
  "CPU" "PHASE" "TARGET" "MODE" "RPS" "AVG" "P50" "P90" "P95" "P99" "ERR" "APP_CPU" "PEAK_CPU" "APP_RAM" "PG_CPU"
echo "-----------------------------------------------------------------------------------------------------------------------------------------"
tail -n +2 "$RESULTS_FILE" | while IFS=$'\t' read -r c ph t m r a p50 p90 p95 p99 e ca cp mem pg; do
  printf "%-4s %-6s %-15s %-5s %8s %9s %9s %9s %9s %9s %7s %9s %9s %9s %9s\n" \
    "$c" "$ph" "$t" "$m" "$r" "$a" "$p50" "$p90" "$p95" "$p99" "$e" "$ca" "$cp" "$mem" "$pg"
done
echo "========================================================================================================================================="
