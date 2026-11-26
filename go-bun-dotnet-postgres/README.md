# HTTP & PostgreSQL Benchmark Matrix: Go vs Bun (`--compile` & `no-compile`) vs .NET 8 & .NET 10 (JIT & NativeAOT)

Containerized multi-configuration benchmark comparing **7 service builds** across **1 CPU vs 2 CPUs**, **Cold (no warmup) vs Warm (post-warmup)**, and **HTTP JSON (`GET`) vs PostgreSQL persistence (`POST`)** — **56 isolated benchmark runs** executed sequentially via **k6** inside **Podman** with full latency percentiles (`avg`, `p50`, `p90`, `p95`, `p99`) and live container CPU/RAM telemetry.

## TL;DR Summary

| Target | 1 CPU `GET` (cold → warm) | 2 CPU `GET` (cold → warm) | 1 CPU `POST` (cold → warm) | 2 CPU `POST` (cold → warm) | 2 CPU `POST` `p99` (warm) | RAM (`GET` / `POST`, 2 CPU warm) | Image Size |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **go** | **`71.8k → 67.8k`** | **`89.3k → 89.1k`** | **`30.1k → 30.1k`** | **`36.5k → 36.4k`** | `3.50 ms` | **`14.1 / 18.7 MB`** | **`14.9 MB`** |
| **dotnet10-jit** | `48.8k → 66.7k` | `73.7k → 78.8k` | `7.2k → 10.8k` | `24.6k →` **`34.4k`** | **`3.25 ms`** | `31.6 / 46.4 MB` | `236 MB` |
| **dotnet8-jit** | `42.9k → 55.0k` | `72.3k → 76.8k` | `7.5k → 9.8k` | `23.5k → 32.2k` | `4.39 ms` | `49.7 / 72.0 MB` | `223 MB` |
| **dotnet10-aot** | `62.8k → 62.4k` | `78.8k → 77.9k` | `23.5k → 21.9k` | `30.8k → 30.9k` | `3.72 ms` | `17.4 / 25.8 MB` | `143 MB` |
| **dotnet8-aot** | `70.1k → 66.9k` | `82.8k → 77.7k` | `22.5k → 23.4k` | `30.1k → 30.4k` | `3.71 ms` | `14.5 /` **`18.2 MB`** | `147 MB` |
| **bun-nocompile** | `63.6k → 60.6k` | `63.8k → 59.9k` | `18.6k → 20.0k` | `19.8k → 19.8k` | `4.07 ms` | `19.3 / 54.5 MB` | `209 MB` |
| **bun-compile** | `61.4k → 60.9k` | `60.9k → 60.8k` | `18.7k → 19.9k` | `19.6k → 19.4k` | `4.01 ms` | `17.2 / 51.7 MB` | `289 MB` |

- **Go (`Fiber v3 + pgx/v5`)** takes `#1` RPS across all 4 workloads (`89.3k` 2-CPU `GET`, `30.1k` 1-CPU `POST`, `36.5k` 2-CPU `POST`), with zero cold-start penalty, lowest CPU utilization, lowest memory (`11.6–18.7 MB`), and tiniest container (`14.9 MB`).
- **.NET 10 JIT** closes the 2-CPU warm `POST` gap to **5.4%** behind Go (`34.4k` vs `36.4k` RPS) while posting the **lowest warm `p95` (`2.43 ms`) and `p99` (`3.25 ms` vs Go's `3.50 ms`)** — and using **~36% less RAM than .NET 8 JIT** (`31.6–46.4 MB` vs `49.7–72.0 MB`). On **1 CPU**, however, JIT compilation under heavy `POST` load drops throughput to `7.2k–10.8k`.
- **.NET NativeAOT (8 & 10)** delivers full speed from second zero (`62–83k` `GET`, `22–23.5k` 1-CPU `POST`, `30.1–30.9k` 2-CPU `POST`) with Go-like RAM (`14.5–25.8 MB`), beating `.NET` JIT by **>2× on 1-CPU `POST`**.
- **Bun (`--compile` ≈ `no-compile`)** posts smooth tail latency (`p99 ~4.0 ms` on `POST`) and solid 1-CPU numbers (`61–64k` `GET`, `20k` `POST`), but **does not scale across 2 CPUs** (single-threaded event loop).

## Credits & Origin

This benchmark is based on and extends **Anton Putra's** original Go vs Bun benchmark from the [`antonputra/tutorials`](https://github.com/antonputra/tutorials) repository:

- Original reference benchmark: [`antonputra/tutorials/tree/275/lessons/275`](https://github.com/antonputra/tutorials/tree/275/lessons/275)

What was added in this repository:
- **Unified .NET implementation (`dotnet-app/`)** replicating the exact `bun-app`/`go-app` API, `config.yaml` format, JSON payloads, HTTP status codes, connection pool (`maxConnections: 100`), and PostgreSQL `INSERT ... RETURNING id` via `Npgsql` with prepared statement caching (`Max Auto Prepare=20`), direct `Utf8JsonWriter`, and `readonly record struct` models — built into 4 identical-code variants: **`.NET 8 JIT`**, **`.NET 8 NativeAOT`**, **`.NET 10 JIT`**, and **`.NET 10 NativeAOT`**.
- **Bun non-compiled variant (`bun-app/Dockerfile.nocompile`)** running `bun run src/app.ts` directly alongside Anton Putra's `--compile` binary (`bun-app/Dockerfile`).
- **Sterile automated matrix runner (`run.sh` + `k6/`)** testing every combination of **1 CPU / 2 CPUs** and **cold / warm** with a **brand-new isolated app container per test** and a **brand-new RAM-backed PostgreSQL 18.1 container per `POST` test**, collecting `avg / p50 / p90 / p95 / p99` latency plus live **App CPU (avg & peak)**, **App RAM**, and **PostgreSQL CPU** from the Podman REST API.

---

## Test Hardware & Environment

All containers ran locally on a single host under identical Podman resource limits:

| Component | Specification |
| --- | --- |
| **CPU** | AMD Ryzen 5 PRO 6650U with Radeon Graphics (`x86_64`, 6 cores / 12 threads, up to 4.59 GHz) |
| **RAM** | 14 GiB usable (16 GB system RAM shared with integrated GPU) |
| **Storage** | SK Hynix `HFS512GDE9X081N` (476.9 GB NVMe SSD); PostgreSQL data mounted on `2 GiB tmpfs` (RAM) |
| **OS / Kernel** | CachyOS — `Linux 7.2.11-1-cachyos #1 SMP PREEMPT_DYNAMIC x86_64` |
| **Container Engine** | Podman `6.1.4` (rootless) |
| **Load Generator** | Grafana `k6 v2.4.0` (containerized) |
| **Database** | `postgres:18.1` (`--tmpfs /var/lib/postgresql:rw,size=2g`, `max_connections=500`, `shared_buffers=1GB`, `wal_buffers=64MB`, `max_wal_size=4GB`, `autovacuum=off`, `fsync=off`, `synchronous_commit=off`, `full_page_writes=off`) |

### Service Matrix & Container Image Sizes

Every target was tested under `--memory=3g` with `--cpus=1` and `--cpus=2` on the isolated `bench275` Podman network:

| Target | Runtime / Framework | Build Mode | DB Driver | Image Size |
| --- | --- | --- | --- | ---: |
| `go` | Go `1.25.5` (`GOMAXPROCS=$cpus`) + Fiber `v3.0.0-rc.3` | Static binary (`CGO_ENABLED=0`, `scratch`) | `pgx/v5 v5.7.6` (auto statement cache) | `14.9 MB` |
| `bun-compile` | Bun `1.3.5` + `Bun.serve` | `bun build --compile --minify --sourcemap` (`oven/bun:1.3.5-slim`) | Built-in `Bun.sql` (prepared statements) | `289 MB` |
| `bun-nocompile` | Bun `1.3.5` + `Bun.serve` | Direct `bun run src/app.ts` (`oven/bun:1.3.5-slim`) | Built-in `Bun.sql` (prepared statements) | `209 MB` |
| `dotnet8-jit` | .NET `8.0` (LTS) ASP.NET Core (`CreateSlimBuilder`) | Release JIT + `System.Text.Json` Source Gen (`aspnet:8.0`) | `Npgsql 9.0.3` (`Max Auto Prepare=20`) | `223 MB` |
| `dotnet8-aot` | .NET `8.0` (LTS) ASP.NET Core (`CreateSlimBuilder`) | `<PublishAot>true</PublishAot>` (`OptimizationPreference=Speed`, `runtime-deps:8.0`) | `Npgsql 9.0.3` (`Max Auto Prepare=20`) | `147 MB` |
| `dotnet10-jit` | .NET `10.0` (LTS) ASP.NET Core (`CreateSlimBuilder`) | Release JIT + `System.Text.Json` Source Gen (`aspnet:10.0`) | `Npgsql 9.0.3` (`Max Auto Prepare=20`) | `236 MB` |
| `dotnet10-aot` | .NET `10.0` (LTS) ASP.NET Core (`CreateSlimBuilder`) | `<PublishAot>true</PublishAot>` (`OptimizationPreference=Speed`, `runtime-deps:10.0`) | `Npgsql 9.0.3` (`Max Auto Prepare=20`) | `143 MB` |

---

## Endpoints & Methodology

All 7 implementations expose the exact same API:
- `GET /healthz` → `200 OK` (`"OK"`)
- `GET /api/users` → constructs a 3-user array per request and serializes it to JSON (`200 OK`, no DB access)
- `POST /api/users` → parses JSON body (`name`, `address`, `phone`), generates UTC timestamp & image filename, executes `INSERT INTO <table> (...) VALUES (...) RETURNING id;` in PostgreSQL, returns `201 Created` JSON

### Isolation & Measurement Protocol
- **One app container at a time**: For every `(CPU, target, mode)` cell, `run.sh` starts a **brand-new fresh container**, runs `cold` (`0–10s` immediately after `/healthz`, **0s warmup**), immediately runs `warm` (`10–20s` on the same container, now warmed up by 10s of full load), then stops and removes the container before moving on.
- **Brand-new RAM-backed PostgreSQL per `POST` test**: Before every `POST` run, `bench-postgres` is destroyed and recreated on a `2 GiB tmpfs` mount with tuned buffers and disabled disk barriers (`fsync=off`, `synchronous_commit=off`, `full_page_writes=off`, `autovacuum=off`). This models a fast dedicated database server without NVMe `fsync` cache throttling or cross-target WAL/autovacuum interference, ensuring the benchmark measures **application runtimes and DB drivers** rather than local laptop SSD write cache decay.
- **Fair driver parity**: `pgx/v5` and `Bun.sql` cache prepared statements by default, whereas `Npgsql` defaults to `Max Auto Prepare=0`. Enabling `Max Auto Prepare=20; Auto Prepare Min Usages=2; No Reset On Close=true; Enlist=false; Ssl Mode=Disable` puts all three drivers on equal footing.
- **Load & Percentiles**: `50 VUs`, `10s` per phase (`http_req_failed` threshold `< 5%`; all 56 runs achieved **`0.00%` errors**), collecting `avg`, `p50` (`med`), `p90`, `p95`, and `p99` via `k6 --summary-trend-stats`.
- **Telemetry**: CPU % (`100%` = 1 full core, `200%` = 2 full cores) is computed from exact `cpu_stats.cpu_usage.total_usage` nanosecond deltas over the `k6` window via the Podman REST API, plus 1-second peak CPU and peak RAM sampling.
- **Raw dataset**: All 56 rows (with `avg`, `p50`, `p90`, `p95`, `p99`) are stored in [`results/matrix.tsv`](results/matrix.tsv).

---

## Full Benchmark Matrix Results (`50 VUs`, `10s` per phase, `0.00%` errors)

### 1. `GET /api/users` — 1 CPU (`--cpus=1`, Cold vs Warm)

| Target | Cold RPS (`0–10s`) | Cold `p50` / `p95` / `p99` | Warm RPS (`10–20s`) | Warm `p50` / `p95` / `p99` | Warmup Gain | App CPU (Cold / Warm) | App RAM (Warm) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **go** | **71,780** | `0.62` / `1.24` / **`1.82 ms`** | **67,835** | `0.66` / `1.31` / **`1.89 ms`** | `-5.5%` | `74.3%` / `75.1%` | **`11.6 MB`** |
| **dotnet8-aot** | **70,138** | **`0.56`** / **`1.04`** / `2.37 ms` | **66,887** | `0.57` / `1.09` / `2.57 ms` | `-4.6%` | `89.4%` / `87.6%` | `20.9 MB` |
| **dotnet10-jit** | 48,814 | `0.70` / `1.36` / `3.16 ms` | **66,727** | **`0.56`** / `1.12` / `2.73 ms` | **`+36.7%`** | `90.5%` / `89.3%` | `35.1 MB` |
| **dotnet10-aot** | 62,827 | `0.57` / `1.07` / `2.58 ms` | 62,388 | `0.58` / **`1.07`** / `2.38 ms` | `-0.7%` | `87.0%` / `82.5%` | `20.1 MB` |
| **bun-compile** | 61,447 | `0.65` / `1.40` / `2.04 ms` | 60,852 | `0.66` / `1.42` / `2.10 ms` | `-1.0%` | `83.8%` / `82.3%` | `17.3 MB` |
| **bun-nocompile** | 63,573 | `0.62` / `1.37` / `2.07 ms` | 60,585 | `0.66` / `1.44` / `2.12 ms` | `-4.7%` | `80.8%` / `81.0%` | `22.3 MB` |
| **dotnet8-jit** | 42,948 | `0.93` / `1.38` / `2.49 ms` | 54,961 | `0.64` / `1.20` / `2.44 ms` | **`+28.0%`** | `88.3%` / `86.0%` | `33.8 MB` |

---

### 2. `GET /api/users` — 2 CPUs (`--cpus=2`, Cold vs Warm)

| Target | Cold RPS (`0–10s`) | Cold `p50` / `p95` / `p99` | Warm RPS (`10–20s`) | Warm `p50` / `p95` / `p99` | Scaling vs 1 CPU (Warm) | App CPU (Cold / Warm) | App RAM (Warm) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **go** | **89,291** | **`0.41`** / **`1.12`** / `2.11 ms` | **89,134** | **`0.41`** / **`1.12`** / **`2.11 ms`** | `+31.4%` | `133.7%` / `133.6%` | **`14.1 MB`** |
| **dotnet10-jit** | 73,732 | `0.46` / `1.39` / `2.64 ms` | **78,834** | `0.43` / `1.39` / `2.52 ms` | `+18.1%` | `162.9%` / `160.9%` | `31.6 MB` |
| **dotnet10-aot** | 78,751 | `0.45` / `1.33` / `2.31 ms` | **77,874** | `0.46` / `1.35` / `2.34 ms` | `+24.8%` | `155.3%` / `162.3%` | `17.4 MB` |
| **dotnet8-aot** | **82,841** | `0.43` / `1.41` / `2.40 ms` | **77,698** | `0.46` / `1.49` / `2.45 ms` | `+16.2%` | `165.6%` / `159.6%` | `14.5 MB` |
| **dotnet8-jit** | 72,256 | `0.46` / `1.27` / `2.48 ms` | **76,782** | `0.44` / `1.37` / `2.60 ms` | **`+39.7%`** | `156.8%` / `166.2%` | `49.7 MB` |
| **bun-compile** | 60,945 | `0.66` / `1.42` / **`2.10 ms`** | 60,766 | `0.66` / `1.42` / `2.13 ms` | `-0.1%` (single-thread) | `83.1%` / `83.6%` | `17.2 MB` |
| **bun-nocompile** | 63,761 | `0.62` / `1.38` / `2.11 ms` | 59,922 | `0.66` / `1.45` / `2.15 ms` | `-1.1%` (single-thread) | `80.8%` / `80.9%` | `19.3 MB` |

---

### 3. `POST /api/users` (Fresh RAM-backed PostgreSQL `INSERT`) — 1 CPU (`--cpus=1`, Cold vs Warm)

| Target | Cold RPS | Cold `p50` / `p95` / `p99` | Warm RPS | Warm `p50` / `p95` / `p99` | App CPU (Cold / Warm) | App RAM (Warm) | Postgres CPU (Cold / Warm) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **go** | **30,125** | **`1.38`** / `2.83` / **`3.84 ms`** | **30,148** | **`1.39`** / `2.85` / **`3.87 ms`** | `77.7%` / `78.0%` | **`18.0 MB`** | `152.8%` / `148.5%` |
| **dotnet8-aot** | 22,536 | `1.68` / `2.70` / `24.67 ms` | **23,429** | `1.71` / `2.70` / `15.16 ms` | `90.3%` / `91.0%` | `25.3 MB` | `130.4%` / `130.4%` |
| **dotnet10-aot** | **23,475** | `1.72` / **`2.63`** / `6.25 ms` | **21,877** | `1.64` / **`2.69`** / `29.74 ms` | `88.5%` / `86.5%` | `24.4 MB` | `130.3%` / `125.5%` |
| **bun-nocompile** | 18,625 | `2.28` / `3.54` / `4.62 ms` | 19,969 | `2.29` / `3.45` / `4.14 ms` | `84.8%` / `86.7%` | `78.5 MB` | `76.3%` / `78.2%` |
| **bun-compile** | 18,666 | `2.27` / `3.39` / `4.28 ms` | 19,863 | `2.31` / `3.38` / `3.97 ms` | `83.1%` / `87.8%` | `62.5 MB` | `76.0%` / `78.8%` |
| **dotnet10-jit** | 7,208 | `5.05` / `27.84` / `62.93 ms` | 10,799 | `2.95` / `5.72` / `56.44 ms` | `86.7%` / `90.2%` | `44.1 MB` | `47.1%` / `60.6%` |
| **dotnet8-jit** | 7,479 | `5.21` / `12.78` / `66.84 ms` | 9,784 | `2.69` / `18.72` / `64.30 ms` | `87.4%` / `88.6%` | `46.9 MB` | `44.3%` / `57.9%` |

---

### 4. `POST /api/users` (Fresh RAM-backed PostgreSQL `INSERT`) — 2 CPUs (`--cpus=2`, Cold vs Warm)

| Target | Cold RPS | Cold `p50` / `p95` / `p99` | Warm RPS | Warm `p50` / `p95` / `p99` | Scaling vs 1 CPU (Warm) | App CPU (Cold / Warm) | App RAM (Warm) | Postgres CPU (Cold / Warm) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **go** | **36,468** | **`1.20`** / **`2.42`** / **`3.44 ms`** | **36,418** | **`1.20`** / `2.44` / `3.50 ms` | `+20.8%` | `145.2%` / `146.1%` | `18.7 MB` | `230.9%` / `226.8%` |
| **dotnet10-jit** | 24,561 | `1.35` / `3.14` / `7.25 ms` | **34,441** | `1.26` / **`2.43`** / **`3.25 ms`** | **`+218.9%`** | `162.8%` / `167.7%` | `46.4 MB` | `171.6%` / `232.7%` |
| **dotnet8-jit** | 23,491 | `1.35` / `3.48` / `15.17 ms` | **32,158** | `1.29` / `2.44` / `4.39 ms` | **`+228.7%`** | `174.0%` / `178.2%` | `72.0 MB` | `160.7%` / `222.1%` |
| **dotnet10-aot** | **30,839** | `1.39` / `2.69` / `4.42 ms` | **30,864** | `1.40` / `2.67` / `3.72 ms` | `+41.1%` | `180.2%` / `177.2%` | `25.8 MB` | `223.9%` / `217.7%` |
| **dotnet8-aot** | **30,082** | `1.37` / `2.87` / `4.49 ms` | **30,371** | `1.37` / `2.78` / `3.71 ms` | `+29.6%` | `173.6%` / `169.7%` | **`18.2 MB`** | `216.1%` / `212.9%` |
| **bun-nocompile** | 19,844 | `2.28` / `3.45` / `4.16 ms` | 19,818 | `2.31` / `3.43` / `4.07 ms` | `-0.8%` (single-thread) | `87.7%` / `88.0%` | `54.5 MB` | `78.8%` / `80.1%` |
| **bun-compile** | 19,564 | `2.33` / `3.42` / `4.08 ms` | 19,429 | `2.37` / `3.44` / `4.01 ms` | `-2.2%` (single-thread) | `90.1%` / `87.5%` | `51.7 MB` | `80.8%` / `77.7%` |

---

## Key Findings & Engineering Analysis

### 1. Go (`Fiber v3 + pgx/v5`) vs `.NET 10` (`Kestrel + Npgsql`): Much Closer Than Naive Benchmarks Suggest
- Once PostgreSQL NVMe `fsync` throttling is eliminated (fresh `tmpfs` DB per run) and `Npgsql` has statement auto-preparation enabled (`Max Auto Prepare=20`, matching `pgx/v5`), the perceived 2–3× gap disappears:
  - **2 CPU warm `POST`**: Go `36,418 RPS` vs `.NET 10 JIT` **`34,441 RPS`** (**only 5.4% gap**), while `.NET 10 JIT` achieves the **lowest `p90` (`1.96 ms`), `p95` (`2.43 ms`), and `p99` (`3.25 ms` vs Go's `3.50 ms`)**!
  - **2 CPU warm `GET`**: Go (`Fiber v3` / `fasthttp`) `89,134 RPS` vs `.NET 10 JIT` `78,834 RPS` (**~13% gap**, noting that Fiber uses the non-standard zero-alloc `fasthttp` engine rather than Go's standard `net/http`).
- **Where Go still wins decisively**:
  - **1-CPU `POST`**: `30.1k RPS` (`p99 3.87 ms`, `78%` CPU) vs `.NET` NativeAOT `21.9k–23.4k` and `.NET` JIT `9.8k–10.8k`.
  - **Resource efficiency**: `11.6–18.7 MB` RAM, `14.9 MB` image, and ~15–20% lower CPU consumption per request across all workloads.

### 2. Multi-Core Scaling (`1 CPU` → `2 CPUs`): Bun hits a single-thread wall, Go & .NET scale cleanly
- **Go** scales from `67.8k` → **`89.1k`** (`GET`, `+31.4%`) and `30.1k` → **`36.4k`** (`POST`, `+20.8%`).
- **All four `.NET` builds** scale cleanly on 2 CPUs:
  - `GET`: `55–67k` → **`76.8–78.8k`** (`+16%` to `+40%`), beating Bun by **+26% to +32%**.
  - `POST`: `.NET` JIT more than **triples** (`9.8–10.8k` → **`32.2–34.4k`**, `+219–229%`) once background JIT compilation gets headroom on the second core, and NativeAOT climbs from `21.9–23.4k` → **`30.4–30.9k`** (`+30–41%`).
- **Bun (`--compile` & `no-compile`)** is completely flat from 1 CPU to 2 CPUs (`~60.5k` `GET`, `~19.8k` `POST`, `App CPU` stuck at `81–88%`) because `Bun.serve` runs a single-threaded JS event loop. Scaling Bun across cores requires multi-process workers (`reusePort: true`).

### 3. Cold vs Warm & 1-CPU Containers: NativeAOT vs JIT
- On **1 CPU (`POST`)**, `.NET` JIT background Tiered Compilation & Dynamic PGO competes heavily with `Kestrel + Npgsql` on a single core (`7.2–7.5k` cold → `9.8–10.8k` warm, `p99 56–67 ms`).
- **NativeAOT (`dotnet8-aot` & `dotnet10-aot`)** completely eliminates JIT warmup contention: **>2× faster than JIT on 1-CPU `POST`** (`22.5–23.5k` immediately from second zero) and `78.8–82.8k` cold `GET` on 2 CPUs, at **`14.5–25.8 MB` RAM**.
- On **2 CPUs once warmed**, **Dynamic PGO in JIT overtakes NativeAOT** on both `POST` (`dotnet10-jit` `34.4k` vs `dotnet10-aot` `30.9k`, `+11.6%`, `p99 3.25 ms` vs `3.72 ms`) and `GET` (`78.8k` vs `77.9k`).

### 4. `.NET 8` vs `.NET 10` (LTS → LTS)
- **Lower RAM in `.NET 10 JIT` on 2 CPUs (DATAS Server GC by default)**:
  - `GET`: `31.6 MB` vs `49.7 MB` (**36% less RAM**)
  - `POST`: `46.4 MB` vs `72.0 MB` (**36% less RAM**; `54.3 MB` vs `115.6 MB` cold — **53% less RAM**)
- **Faster & tighter tail latency in `.NET 10 JIT`**:
  - Warm `1 CPU GET`: `66,727` vs `54,961` (**+21.4%**)
  - Warm `2 CPU GET`: `78,834` vs `76,782` (`+2.7%`)
  - Warm `2 CPU POST`: `34,441` (`p99 3.25 ms`) vs `32,158` (`p99 4.39 ms`) (**+7.1% RPS, 26% lower `p99`**)
- **NativeAOT**: `dotnet10-aot` leads `2 CPU POST` (`30.9k` vs `30.4k`, tighter `p95 2.67 ms` vs `2.78 ms`) with a slightly smaller image (`143 MB` vs `147 MB`), while `dotnet8-aot` edges ahead on 1-CPU `GET` (`66.9k` vs `62.4k`) and uses slightly less RAM (`14.5–18.2 MB` vs `17.4–25.8 MB`).

### 5. Bun `--compile` vs `no-compile` (`bun run src/app.ts`)
- **Performance is virtually identical** across every cell (`60–64k` `GET`, `18.6–20.0k` `POST`, `p99 3.97–4.16 ms`).
- **Tail latency on `POST`** is remarkably steady (`p99 ~4.0 ms` on both 1 and 2 CPUs, rivaling Go's `3.5–3.9 ms` and beating `.NET` on 1-CPU `POST` `p99`).
- **Image size**: `bun-nocompile` (`209 MB`) is **80 MB smaller** than `bun-compile` (`289 MB`) when both use `oven/bun:1.3.5-slim`, because `bun build --compile` bundles a second copy of the Bun runtime into `/bun-app`. (To shrink `--compile` images in production, copy `/bun-app` into `debian:bookworm-slim` or `distroless/cc` instead of `oven/bun:1.3.5-slim`.)

---

## How to Run

### Prerequisites
- Linux with **Podman** installed
- Optional (for live CPU & RAM metrics): enable the user Podman socket:
  ```bash
  systemctl --user start podman.socket
  ```

### Run the Full 56-Cell Matrix
```bash
cd go-bun-dotnet-postgres
chmod +x run.sh
./run.sh
```

### Run Custom Combinations
`run.sh` supports filtering any dimension via environment variables:

```bash
# Quick comparison: only 2 CPUs, only .NET 10 vs Go, skipping image rebuild
SKIP_BUILD=1 CPU_PROFILES="2" TARGETS="go dotnet10-jit dotnet10-aot" ./run.sh

# Test only Bun variants on 1 CPU, GET only, 30s runs
SKIP_BUILD=1 CPU_PROFILES="1" TARGETS="bun-compile bun-nocompile" MODES="get" K6_DURATION=30s ./run.sh

# Warm-only run (skips cold measurement, performs explicit 10s warmup before measuring)
SKIP_BUILD=1 PHASES="warm" K6_WARMUP=10s K6_DURATION=15s ./run.sh
```

### Cleanup
`run.sh` automatically removes `bench-target` and `bench-postgres` on exit. To also remove the network:
```bash
podman network rm bench275
```

---

## License

This repository is licensed under the [MIT License](../LICENSE). Original `go-app` and `bun-app` sources are derived from [`antonputra/tutorials`](https://github.com/antonputra/tutorials/tree/275/lessons/275).
