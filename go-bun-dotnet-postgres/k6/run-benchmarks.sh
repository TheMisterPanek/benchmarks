#!/usr/bin/env bash
set -euo pipefail

TARGETS="${TARGETS:-go=http://go-app:8080}"
MODES="${MODES:-get,post}"
PHASES="${PHASES:-cold,warm}"
CPU_LABEL="${CPU_LABEL:-1}"
export K6_VUS="${K6_VUS:-50}"
export K6_DURATION="${K6_DURATION:-10s}"
K6_WARMUP="${K6_WARMUP:-10s}"
PODMAN_SOCK="${PODMAN_SOCK:-/run/podman/podman.sock}"
DB_CONTAINER="${DB_CONTAINER:-bench-postgres}"
RESULT_FILE="${RESULT_FILE:-}"
PRINT_SUMMARY="${PRINT_SUMMARY:-1}"

HAS_STATS=0
if [ -S "$PODMAN_SOCK" ]; then
  if curl -sf --unix-socket "$PODMAN_SOCK" "http://d/v4.0.0/libpod/_ping" >/dev/null 2>&1; then
    HAS_STATS=1
  fi
fi

get_container_stats() {
  local cname="$1"
  curl -sf --unix-socket "$PODMAN_SOCK" \
    "http://d/v4.0.0/containers/${cname}/stats?stream=false&one-shot=true" 2>/dev/null \
    | jq -r '[(.cpu_stats.cpu_usage.total_usage // 0), (.memory_stats.usage // 0)] | @tsv' 2>/dev/null || echo -e "0\t0"
}

calc_cpu_pct() {
  local cpu_start="$1"
  local cpu_end="$2"
  local t_start="$3"
  local t_end="$4"
  awk -v cs="$cpu_start" -v ce="$cpu_end" -v ts="$t_start" -v te="$t_end" \
    'BEGIN { dt = te - ts; if (dt > 0 && ce >= cs) printf "%.1f", ((ce - cs) / dt) * 100.0; else printf "0.0" }'
}

bytes_to_mb() {
  awk -v b="$1" 'BEGIN { printf "%.1f", b / 1048576.0 }'
}

LOCAL_SUMMARY="/tmp/bench_summary.tsv"
: > "$LOCAL_SUMMARY"

IFS=',' read -ra TARGET_LIST <<< "$TARGETS"
IFS=',' read -ra MODE_LIST <<< "$MODES"
IFS=',' read -ra PHASE_LIST <<< "$PHASES"

for entry in "${TARGET_LIST[@]}"; do
  name="${entry%%=*}"
  url="${entry#*=}"
  host_port="${url#*://}"
  container_name="${host_port%%:*}"
  container_name="${container_name%%/*}"

  echo "============================================================"
  echo "Waiting for $name ($url/healthz) [cpus=${CPU_LABEL}]..."
  for i in $(seq 1 30); do
    if curl -sf "$url/healthz" >/dev/null 2>&1; then
      echo "$name is ready."
      break
    fi
    if [ "$i" -eq 30 ]; then
      echo "ERROR: $name did not become ready in time" >&2
      exit 1
    fi
    sleep 1
  done

  for mode in "${MODE_LIST[@]}"; do
    cold_ran=0
    for phase in "${PHASE_LIST[@]}"; do
      if [ "$phase" = "warm" ] && [ "$cold_ran" -eq 0 ] && [ -n "$K6_WARMUP" ] && [ "$K6_WARMUP" != "0" ] && [ "$K6_WARMUP" != "0s" ]; then
        echo "Warming up $name ($mode) for $K6_WARMUP..."
        TARGET_URL="$url" TEST_MODE="$mode" K6_DURATION="$K6_WARMUP" k6 run --quiet /benchmarks/script.js >/dev/null
      fi

      echo "------------------------------------------------------------"
      echo "Running k6: target=$name mode=$mode phase=$phase cpus=$CPU_LABEL vus=$K6_VUS duration=$K6_DURATION"
      echo "------------------------------------------------------------"

      PEAK_FILE="/tmp/peak_${name}_${mode}_${phase}.txt"
      echo -e "0.0\t0" > "$PEAK_FILE"
      SAMPLER_PID=""

      if [ "$HAS_STATS" -eq 1 ]; then
        read -r app_cpu0 app_mem0 <<< "$(get_container_stats "$container_name")"
        read -r db_cpu0 _ <<< "$(get_container_stats "$DB_CONTAINER")"
        t0="$(date +%s%N)"

        (
          prev_cpu="$app_cpu0"
          prev_t="$t0"
          peak_cpu="0.0"
          peak_mem="$app_mem0"
          while sleep 1; do
            read -r cur_cpu cur_mem <<< "$(get_container_stats "$container_name")"
            cur_t="$(date +%s%N)"
            inst_cpu="$(calc_cpu_pct "$prev_cpu" "$cur_cpu" "$prev_t" "$cur_t")"
            peak_cpu="$(awk -v a="$peak_cpu" -v b="$inst_cpu" 'BEGIN { print (b > a) ? b : a }')"
            if [ "$cur_mem" -gt "$peak_mem" ] 2>/dev/null; then
              peak_mem="$cur_mem"
            fi
            echo -e "${peak_cpu}\t${peak_mem}" > "$PEAK_FILE"
            prev_cpu="$cur_cpu"
            prev_t="$cur_t"
          done
        ) &
        SAMPLER_PID=$!
      fi

      K6_JSON="/tmp/k6_${name}_${mode}_${phase}.json"
      TARGET_URL="$url" TEST_MODE="$mode" k6 run --quiet \
        --summary-trend-stats="avg,min,med,max,p(90),p(95),p(99)" \
        --summary-export="$K6_JSON" /benchmarks/script.js

      app_cpu_avg="n/a"
      app_cpu_peak="n/a"
      app_mem_mb="n/a"
      db_cpu_avg="n/a"

      if [ "$HAS_STATS" -eq 1 ]; then
        t1="$(date +%s%N)"
        read -r app_cpu1 app_mem1 <<< "$(get_container_stats "$container_name")"
        read -r db_cpu1 _ <<< "$(get_container_stats "$DB_CONTAINER")"
        if [ -n "$SAMPLER_PID" ]; then
          kill "$SAMPLER_PID" 2>/dev/null || true
          wait "$SAMPLER_PID" 2>/dev/null || true
        fi

        read -r peak_cpu_val peak_mem_val < "$PEAK_FILE" || true
        if [ "${app_mem1:-0}" -gt "${peak_mem_val:-0}" ] 2>/dev/null; then
          peak_mem_val="$app_mem1"
        fi

        avg_val="$(calc_cpu_pct "$app_cpu0" "$app_cpu1" "$t0" "$t1")"
        app_cpu_avg="${avg_val}%"
        app_cpu_peak="$(awk -v a="${peak_cpu_val:-0}" -v b="$avg_val" 'BEGIN { printf "%.1f%%", (a > b) ? a : b }')"
        app_mem_mb="$(bytes_to_mb "${peak_mem_val:-0}") MB"
        db_cpu_avg="$(calc_cpu_pct "$db_cpu0" "$db_cpu1" "$t0" "$t1")%"
      fi

      rps="$(jq -r '.metrics.http_reqs.rate // 0' "$K6_JSON" | awk '{ printf "%.0f", $1 }')"
      avg_ms="$(jq -r '.metrics.http_req_duration.avg // 0' "$K6_JSON" | awk '{ printf "%.2f ms", $1 }')"
      p50_ms="$(jq -r '.metrics.http_req_duration.med // 0' "$K6_JSON" | awk '{ printf "%.2f ms", $1 }')"
      p90_ms="$(jq -r '.metrics.http_req_duration["p(90)"] // 0' "$K6_JSON" | awk '{ printf "%.2f ms", $1 }')"
      p95_ms="$(jq -r '.metrics.http_req_duration["p(95)"] // 0' "$K6_JSON" | awk '{ printf "%.2f ms", $1 }')"
      p99_ms="$(jq -r '.metrics.http_req_duration["p(99)"] // 0' "$K6_JSON" | awk '{ printf "%.2f ms", $1 }')"
      err_pct="$(jq -r '(.metrics.http_req_failed.value // 0) * 100' "$K6_JSON" | awk '{ printf "%.2f%%", $1 }')"

      echo ">>> [$CPU_LABEL CPU | $phase | $name | $mode] RPS=$rps | avg=$avg_ms | p50=$p50_ms | p90=$p90_ms | p95=$p95_ms | p99=$p99_ms | err=$err_pct | AppCPU=$app_cpu_avg (peak $app_cpu_peak) | RAM=$app_mem_mb | PG_CPU=$db_cpu_avg"

      row="$(printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s" \
        "$CPU_LABEL" "$phase" "$name" "$mode" "$rps" "$avg_ms" "$p50_ms" "$p90_ms" "$p95_ms" "$p99_ms" "$err_pct" "$app_cpu_avg" "$app_cpu_peak" "$app_mem_mb" "$db_cpu_avg")"
      echo "$row" >> "$LOCAL_SUMMARY"
      if [ -n "$RESULT_FILE" ]; then
        echo "$row" >> "$RESULT_FILE"
      fi

      if [ "$phase" = "cold" ]; then
        cold_ran=1
      fi
    done
  done
done

if [ "$PRINT_SUMMARY" = "1" ]; then
  echo ""
  echo "========================================================================================================================================="
  printf "%-4s %-6s %-15s %-5s %8s %9s %9s %9s %9s %9s %7s %9s %9s %9s %9s\n" \
    "CPU" "PHASE" "TARGET" "MODE" "RPS" "AVG" "P50" "P90" "P95" "P99" "ERR" "APP_CPU" "PEAK_CPU" "APP_RAM" "PG_CPU"
  echo "-----------------------------------------------------------------------------------------------------------------------------------------"
  while IFS=$'\t' read -r c ph t m r a p50 p90 p95 p99 e ca cp mem pg; do
    printf "%-4s %-6s %-15s %-5s %8s %9s %9s %9s %9s %9s %7s %9s %9s %9s %9s\n" \
      "$c" "$ph" "$t" "$m" "$r" "$a" "$p50" "$p90" "$p95" "$p99" "$e" "$ca" "$cp" "$mem" "$pg"
  done < "$LOCAL_SUMMARY"
  echo "========================================================================================================================================="
fi
