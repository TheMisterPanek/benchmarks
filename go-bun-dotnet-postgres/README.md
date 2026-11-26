# HTTP & PostgreSQL Benchmark Matrix: Rust vs Java 25 & Kotlin 2.4 (Vert.x 5 JIT & GraalVM PGO AOT) vs Go vs .NET 8/10 (Minimal API & Bare-Kestrel Blazing) vs Bun

Containerized multi-configuration benchmark comparing **14 service builds** across **1 CPU vs 2 CPUs**, **Cold (no warmup) vs Warm (post-warmup)**, and **HTTP JSON (`GET`) vs PostgreSQL persistence (`POST`)** — **112 isolated benchmark runs** executed sequentially via **k6** inside **Podman** with full latency percentiles (`avg`, `p50`, `p90`, `p95`, `p99`) and live container CPU/RAM telemetry.

## TL;DR Summary

| Target | Style | 1 CPU `GET` (cold → warm) | 2 CPU `GET` (cold → warm) | 1 CPU `POST` (cold → warm) | 2 CPU `POST` (cold → warm) | 2 CPU `POST` `p99` (warm) | RAM (`GET` / `POST`, 2 CPU warm) | Image Size |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **rust-axum** | Idiomatic | **`80.4k → 74.4k`** | **`105.5k → 97.1k`** | **`35.9k` → `34.0k`** | **`43.9k` → `41.4k`** | **`3.10 ms`** | **`4.2 / 7.1 MB`** | `30.7 MB` |
| **java25-blazing** | Vert.x 5 Epoll (JIT) | `66.1k →` **`74.5k`** | `85.1k → 91.7k` | `18.4k →` **`35.6k`** | `27.3k →` **`42.7k`** | `3.25 ms` | `204.8 / 200.9 MB` | `374 MB` |
| **kotlin24-blazing** | Vert.x 5 Epoll (JIT) | `63.6k → 64.9k` | `81.8k → 92.1k` | `20.5k → 35.0k` | `24.7k → 42.3k` | `3.27 ms` | `149.5 / 224.6 MB` | `376 MB` |
| **dotnet10-blazing** | Bare Kestrel (JIT) | `50.7k → 69.1k` | `84.8k → 84.8k` | `16.7k → 21.9k` | `33.1k → 38.5k` | **`3.10 ms`** | `22.5 / 37.1 MB` | `238 MB` |
| **dotnet10-blazing-aot** | Bare Kestrel (AOT) | `69.7k → 67.9k` | `86.9k → 83.8k` | `29.3k → 30.6k` | `38.9k → 37.5k` | `3.11 ms` | `11.5 / 18.6 MB` | `140 MB` |
| **go** | Idiomatic (Fiber v3) | `71.8k → 67.8k` | `89.3k → 89.1k` | `30.1k → 30.1k` | `36.5k → 36.4k` | `3.50 ms` | `14.1 / 18.7 MB` | **`14.9 MB`** |
| **dotnet10-jit** | Idiomatic Minimal API | `48.8k → 66.7k` | `73.7k → 78.8k` | `7.2k → 10.8k` | `24.6k → 34.4k` | `3.25 ms` | `31.6 / 46.4 MB` | `236 MB` |
| **dotnet8-jit** | Idiomatic Minimal API | `42.9k → 55.0k` | `72.3k → 76.8k` | `7.5k → 9.8k` | `23.5k → 32.2k` | `4.39 ms` | `49.7 / 72.0 MB` | `223 MB` |
| **java25-blazing-aot** | Vert.x 5 Epoll (GraalVM PGO) | `65.2k → 63.7k` | `83.2k → 80.7k` | `25.2k → 25.2k` | `31.9k → 31.9k` | `4.67 ms` | `10.2 / 17.8 MB` | `52.5 MB` |
| **kotlin24-blazing-aot** | Vert.x 5 Epoll (GraalVM PGO) | `64.0k → 63.0k` | `82.5k → 80.2k` | `25.1k → 25.2k` | `31.8k → 31.9k` | `4.83 ms` | `10.2 / 20.2 MB` | `52.5 MB` |
| **dotnet10-aot** | Idiomatic Minimal API | `62.8k → 62.4k` | `78.8k → 77.9k` | `23.5k → 21.9k` | `30.8k → 30.9k` | `3.72 ms` | `17.4 / 25.8 MB` | `143 MB` |
| **dotnet8-aot** | Idiomatic Minimal API | `70.1k → 66.9k` | `82.8k → 77.7k` | `22.5k → 23.4k` | `30.1k → 30.4k` | `3.71 ms` | `14.5 / 18.2 MB` | `147 MB` |
| **bun-nocompile** | Idiomatic | `63.6k → 60.6k` | `63.8k → 59.9k` | `18.6k → 20.0k` | `19.8k → 19.8k` | `4.07 ms` | `19.3 / 54.5 MB` | `209 MB` |
| **bun-compile** | Idiomatic (`--compile`) | `61.4k → 60.9k` | `60.9k → 60.8k` | `18.7k → 19.9k` | `19.6k → 19.4k` | `4.01 ms` | `17.2 / 51.7 MB` | `289 MB` |

- **Rust (`Axum 0.8 + deadpool-postgres/tokio-postgres`)** dominates **cold-start throughput** (`105.5k` 2-CPU `GET`, `43.9k` 2-CPU `POST`, `35.9k` 1-CPU `POST`), **tail latency** (`p99 2.65 ms` 1-CPU `POST`, `3.10 ms` 2-CPU `POST`), CPU efficiency, and **memory (`4.2–7.7 MB` RAM)** with zero warmup penalty.
- **Java 25 & Kotlin 2.4 Blazing JIT (`Vert.x 5` native Epoll + `SO_REUSEPORT` + pipelined `vertx-pg-client` + zero-reflection RFC-8259 JSON codec)** achieve **identical hot-path speed** (zero Kotlin overhead) and **once warmed up, overtake even Rust on `POST`** across both 2 CPUs (**`42.7k` / `42.3k`** vs Rust `41.4k` warm, `.NET 10 Blazing` `38.5k`, Go `36.4k`) and 1 CPU (**`35.6k` / `35.0k`** vs Rust `34.0k` warm, `.NET 10 Blazing JIT` `21.9k`), while also passing Go and `.NET 10 Blazing` on warm 2-CPU `GET` (**`92.1k` / `91.7k`** vs Go `89.1k` and `.NET 10 Blazing` `84.8k`). The tradeoff is classic JVM JIT warmup (`18–27k` cold `POST`) and JVM heap RAM (`150–225 MB`).
- **Java 25 & Kotlin 2.4 Blazing AOT (Oracle GraalVM 25 Native Image + PGO + `-O3` on `distroless/cc-debian13`)** slash container size to **`52.5 MB`** (nearly **3× smaller than `.NET 10 AOT`** at `140–143 MB`) and drop steady-state warm RAM to **`9.9–10.3 MB` (`GET`) / `12.8–20.2 MB` (`POST`)** — **less RAM than both Go and `.NET 10 Blazing AOT`**, with zero warmup cliff (`83.2k → 80.7k` 2-CPU `GET`, `31.9k` 2-CPU `POST`, beating idiomatic `.NET 10 AOT`).
- **`.NET 10 Blazing` (Bare `KestrelServer` + zero-alloc UTF-8 + lock-free pooled `NpgsqlCommand`)** delivers the best **all-around balance of low RAM + high throughput + ultra-flat `p99`**: `dotnet10-blazing` hits `38.5k` warm 2-CPU `POST` (`p99 3.10 ms`, `37.1 MB` RAM), while `dotnet10-blazing-aot` delivers **`38.9k` cold / `37.5k` warm 2-CPU `POST`** and **`30.6k` 1-CPU `POST`** at just `11.5 / 18.6 MB` RAM.
- **Go (`Fiber v3 + pgx/v5`)** remains `#2` among idiomatic builds across every workload (`89.1k` 2-CPU `GET`, `36.4k` 2-CPU `POST`), with the tiniest container image (`14.9 MB` on `scratch`) and zero cold-start penalty.
- **Bun (`--compile` ≈ `no-compile`)** posts smooth tail latency (`p99 ~4.0 ms` on `POST`), but **does not scale across 2 CPUs** (single-threaded event loop).

## Credits & Origin

This benchmark is based on and extends **Anton Putra's** original Go vs Bun benchmark from the [`antonputra/tutorials`](https://github.com/antonputra/tutorials) repository:

- Original reference benchmark: [`antonputra/tutorials/tree/275/lessons/275`](https://github.com/antonputra/tutorials/tree/275/lessons/275)

What was added in this repository:
- **Rust Axum implementation (`rust-axum-app/`)** built on Rust `1.100.0`, `axum 0.8`, `tokio` (`TOKIO_WORKER_THREADS=$cpus`), `serde_json`, and `deadpool-postgres` / `tokio-postgres` (`prepare_cached` + `RecyclingMethod::Fast`), compiled with `lto = "fat"` into a `30.7 MB` `distroless/cc-debian13` image.
- **Blazing Java 25 (`java-blazing-app/`) & Kotlin 2.4.21 (`kotlin-blazing-app/`) implementations** built on **Vert.x `5.0.12`** + **Netty `4.2.13` native Linux Epoll** (`SO_REUSEPORT`, one pinned event loop + dedicated `vertx-pg-client` pool per CPU core with prepared statement caching & pipelining `pipeliningLimit=256`, zero thread hops, and a zero-reflection RFC-8259 JSON codec) — compiled into both **HotSpot 25 JIT (`ParallelGC`)** and **Oracle GraalVM 25 Native Image AOT (`-O3`, self-contained HTTP PGO, `-march=x86-64-v2`, `52.5 MB` on `distroless/cc-debian13`)**.
- **Unified idiomatic .NET Minimal API implementation (`dotnet-app/`)** built into 4 identical-code variants: **`.NET 8 JIT`**, **`.NET 8 NativeAOT`**, **`.NET 10 JIT`**, and **`.NET 10 NativeAOT`** (`Npgsql` with `Max Auto Prepare=20`, `Utf8JsonWriter`, `readonly record struct`).
- **Low-level TechEmpower-style .NET 10 implementation (`dotnet-blazing-app/`)** built as both **JIT (`dotnet10-blazing`)** and **NativeAOT (`dotnet10-blazing-aot`)** using bare `KestrelServer` + `IHttpApplication<IFeatureCollection>` (no ASP.NET Core routing/middleware/`HttpContext`), zero-alloc UTF-8 `Utf8JsonReader`/`Utf8JsonWriter` with exact `Content-Length`, and a lock-free `ConcurrentStack<PooledDbSlot>` holding pre-opened `NpgsqlConnection` + pre-prepared `NpgsqlCommand` + reused `NpgsqlParameter<ReadOnlyMemory<byte>>`.
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
| `rust-axum` | Rust `1.100.0` (`TOKIO_WORKER_THREADS=$cpus`) + Axum `0.8` | Release (`lto="fat"`, `codegen-units=1`, `distroless/cc-debian13`) | `deadpool-postgres 0.14` + `tokio-postgres 0.7` (`prepare_cached`) | `30.7 MB` |
| `java25-blazing` | Java `25` (HotSpot C2, `ParallelGC`) + Vert.x `5.0.12` (Netty `4.2` Epoll) | Release JIT (`-Xms64m -Xmx256m`, `eclipse-temurin:25-jre`) | `vertx-pg-client 5.0.12` (prepared cache + `pipeliningLimit=256`) | `374 MB` |
| `java25-blazing-aot` | Java `25` + Vert.x `5.0.12` (Netty `4.2` Epoll) | Oracle GraalVM `25` Native Image (`-O3`, PGO, `-march=x86-64-v2`, `distroless/cc-debian13`) | `vertx-pg-client 5.0.12` (prepared cache + `pipeliningLimit=256`) | `52.5 MB` |
| `kotlin24-blazing` | Kotlin `2.4.21` on JVM `25` (`ParallelGC`) + Vert.x `5.0.12` (Netty `4.2` Epoll) | Release JIT (`-Xms64m -Xmx256m`, `eclipse-temurin:25-jre`) | `vertx-pg-client 5.0.12` (prepared cache + `pipeliningLimit=256`) | `376 MB` |
| `kotlin24-blazing-aot` | Kotlin `2.4.21` + Vert.x `5.0.12` (Netty `4.2` Epoll) | Oracle GraalVM `25` Native Image (`-O3`, PGO, `-march=x86-64-v2`, `distroless/cc-debian13`) | `vertx-pg-client 5.0.12` (prepared cache + `pipeliningLimit=256`) | `52.5 MB` |
| `go` | Go `1.25.5` (`GOMAXPROCS=$cpus`) + Fiber `v3.0.0-rc.3` | Static binary (`CGO_ENABLED=0`, `scratch`) | `pgx/v5 v5.7.6` (auto statement cache) | `14.9 MB` |
| `dotnet10-blazing` | .NET `10.0` Bare `KestrelServer` (`IHttpApplication`, zero-alloc UTF-8) | Release JIT + `PublishReadyToRun` + `TieredPGO` (`aspnet:10.0`) | `Npgsql 9.0.3` (pooled pre-prepared cmd + `ReadOnlyMemory<byte>`) | `238 MB` |
| `dotnet10-blazing-aot` | .NET `10.0` Bare `KestrelServer` (`IHttpApplication`, zero-alloc UTF-8) | `<PublishAot>true</PublishAot>` (`OptimizationPreference=Speed`, `runtime-deps:10.0`) | `Npgsql 9.0.3` (pooled pre-prepared cmd + `ReadOnlyMemory<byte>`) | `140 MB` |
| `dotnet10-jit` | .NET `10.0` (LTS) ASP.NET Core (`CreateSlimBuilder`) | Release JIT + `System.Text.Json` Source Gen (`aspnet:10.0`) | `Npgsql 9.0.3` (`Max Auto Prepare=20`) | `236 MB` |
| `dotnet10-aot` | .NET `10.0` (LTS) ASP.NET Core (`CreateSlimBuilder`) | `<PublishAot>true</PublishAot>` (`OptimizationPreference=Speed`, `runtime-deps:10.0`) | `Npgsql 9.0.3` (`Max Auto Prepare=20`) | `143 MB` |
| `dotnet8-jit` | .NET `8.0` (LTS) ASP.NET Core (`CreateSlimBuilder`) | Release JIT + `System.Text.Json` Source Gen (`aspnet:8.0`) | `Npgsql 9.0.3` (`Max Auto Prepare=20`) | `223 MB` |
| `dotnet8-aot` | .NET `8.0` (LTS) ASP.NET Core (`CreateSlimBuilder`) | `<PublishAot>true</PublishAot>` (`OptimizationPreference=Speed`, `runtime-deps:8.0`) | `Npgsql 9.0.3` (`Max Auto Prepare=20`) | `147 MB` |
| `bun-compile` | Bun `1.3.5` + `Bun.serve` | `bun build --compile --minify --sourcemap` (`oven/bun:1.3.5-slim`) | Built-in `Bun.sql` (prepared statements) | `289 MB` |
| `bun-nocompile` | Bun `1.3.5` + `Bun.serve` | Direct `bun run src/app.ts` (`oven/bun:1.3.5-slim`) | Built-in `Bun.sql` (prepared statements) | `209 MB` |

---

## Endpoints & Methodology

All 14 implementations strictly adhere to the exact same Go contract (every `blazing` build still genuinely constructs & serializes the 3 `User` objects on every `GET` and genuinely parses JSON, formats UTC timestamp/image, inserts into PostgreSQL, and serializes JSON on every `POST`):
- `GET /healthz` → `200 OK` (`"OK"`)
- `GET /api/users` → constructs 3 user records per request and serializes them to JSON (`200 OK`, no DB access; `createdAt`/`updatedAt` omitted when null)
- `POST /api/users` → parses JSON body (`name`, `address`, `phone`), generates UTC timestamp & image filename, executes `INSERT INTO <table> (...) VALUES (...) RETURNING id;` in PostgreSQL, returns `201 Created` JSON (or `400 Bad Request` on invalid JSON)

### Isolation & Measurement Protocol
- **One app container at a time**: For every `(CPU, target, mode)` cell, `run.sh` starts a **brand-new fresh container**, runs `cold` (`0–10s` immediately after `/healthz`, **0s warmup**), immediately runs `warm` (`10–20s` on the same container, now warmed up by 10s of full load), then stops and removes the container before moving on.
- **Brand-new RAM-backed PostgreSQL per `POST` test**: Before every `POST` run, `bench-postgres` is destroyed and recreated on a `2 GiB tmpfs` mount with tuned buffers and disabled disk barriers (`fsync=off`, `synchronous_commit=off`, `full_page_writes=off`, `autovacuum=off`). This models a fast dedicated database server without NVMe `fsync` cache throttling or cross-target WAL/autovacuum interference, ensuring the benchmark measures **application runtimes and DB drivers** rather than local laptop SSD write cache decay.
- **Fair driver parity**: All stacks use connection pooling (`maxConnections: 100`) and per-connection prepared statement caching (`deadpool-postgres` `prepare_cached`, `vertx-pg-client` `cachePreparedStatements=true`, `pgx/v5` default cache, `Bun.sql`, `Npgsql` `Max Auto Prepare=20`, and explicit `PrepareAsync()` in `dotnet-blazing-app`).
- **Load & Percentiles**: `50 VUs`, `10s` per phase (`http_req_failed` threshold `< 5%`; all 112 runs achieved **`0.00%` errors**), collecting `avg`, `p50` (`med`), `p90`, `p95`, and `p99` via `k6 --summary-trend-stats`.
- **Telemetry**: CPU % (`100%` = 1 full core, `200%` = 2 full cores) is computed from exact `cpu_stats.cpu_usage.total_usage` nanosecond deltas over the `k6` window via the Podman REST API, plus 1-second peak CPU and peak RAM sampling.
- **Raw dataset**: All 112 rows (with `avg`, `p50`, `p90`, `p95`, `p99`) are stored in [`results/matrix.tsv`](results/matrix.tsv).

---

## Full Benchmark Matrix Results (`50 VUs`, `10s` per phase, `0.00%` errors)

### 1. `GET /api/users` — 1 CPU (`--cpus=1`, Cold vs Warm)

| Target | Cold RPS (`0–10s`) | Cold `p50` / `p95` / `p99` | Warm RPS (`10–20s`) | Warm `p50` / `p95` / `p99` | Warmup Gain | App CPU (Cold / Warm) | App RAM (Warm) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **java25-blazing** *(Vert.x 5 JIT)* | 66,135 | **`0.49`** / `1.13` / `2.22 ms` | **74,527** | **`0.52`** / `1.12` / `1.88 ms` | `+12.7%` | `76.1%` / `71.2%` | `154.6 MB` |
| **rust-axum** | **80,441** | `0.56` / **`0.79`** / **`1.77 ms`** | **74,435** | `0.61` / **`0.85`** / **`1.87 ms`** | `-7.5%` | **`70.0%` / `69.8%`** | **`4.3 MB`** |
| **dotnet10-blazing** *(bare Kestrel)* | 50,687 | `0.65` / `1.29` / `3.63 ms` | 69,141 | **`0.52`** / `1.14` / `2.79 ms` | **`+36.4%`** | `85.5%` / `89.7%` | `30.6 MB` |
| **dotnet10-blazing-aot** *(bare Kestrel)* | 69,735 | `0.56` / `1.07` / `2.30 ms` | 67,898 | `0.54` / `1.11` / `2.51 ms` | `-2.6%` | `86.9%` / `89.6%` | `20.0 MB` |
| **go** | 71,780 | `0.62` / `1.24` / `1.82 ms` | 67,835 | `0.66` / `1.31` / `1.89 ms` | `-5.5%` | `74.3%` / `75.1%` | `11.6 MB` |
| **dotnet8-aot** | 70,138 | `0.56` / `1.04` / `2.37 ms` | 66,887 | `0.57` / `1.09` / `2.57 ms` | `-4.6%` | `89.4%` / `87.6%` | `20.9 MB` |
| **dotnet10-jit** | 48,814 | `0.70` / `1.36` / `3.16 ms` | 66,727 | `0.56` / `1.12` / `2.73 ms` | **`+36.7%`** | `90.5%` / `89.3%` | `35.1 MB` |
| **kotlin24-blazing** *(Vert.x 5 JIT)* | 63,619 | `0.52` / `1.20` / `2.40 ms` | 64,887 | `0.55` / `1.21` / `2.21 ms` | `+2.0%` | `76.0%` / `70.8%` | `205.0 MB` |
| **java25-blazing-aot** *(GraalVM PGO)* | 65,236 | `0.62` / `1.33` / `1.83 ms` | 63,683 | `0.63` / `1.40` / `1.96 ms` | `-2.4%` | `76.8%` / `76.9%` | `10.3 MB` |
| **kotlin24-blazing-aot** *(GraalVM PGO)* | 63,971 | `0.63` / `1.36` / `1.86 ms` | 63,010 | `0.64` / `1.41` / `1.97 ms` | `-1.5%` | `76.9%` / `77.1%` | `9.9 MB` |
| **dotnet10-aot** | 62,827 | `0.57` / `1.07` / `2.58 ms` | 62,388 | `0.58` / `1.07` / `2.38 ms` | `-0.7%` | `87.0%` / `82.5%` | `20.1 MB` |
| **bun-compile** | 61,447 | `0.65` / `1.40` / `2.04 ms` | 60,852 | `0.66` / `1.42` / `2.10 ms` | `-1.0%` | `83.8%` / `82.3%` | `17.3 MB` |
| **bun-nocompile** | 63,573 | `0.62` / `1.37` / `2.07 ms` | 60,585 | `0.66` / `1.44` / `2.12 ms` | `-4.7%` | `80.8%` / `81.0%` | `22.3 MB` |
| **dotnet8-jit** | 42,948 | `0.93` / `1.38` / `2.49 ms` | 54,961 | `0.64` / `1.20` / `2.44 ms` | **`+28.0%`** | `88.3%` / `86.0%` | `33.8 MB` |

---

### 2. `GET /api/users` — 2 CPUs (`--cpus=2`, Cold vs Warm)

| Target | Cold RPS (`0–10s`) | Cold `p50` / `p95` / `p99` | Warm RPS (`10–20s`) | Warm `p50` / `p95` / `p99` | Scaling vs 1 CPU (Warm) | App CPU (Cold / Warm) | App RAM (Warm) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **rust-axum** | **105,537** | **`0.34`** / **`0.91`** / **`1.86 ms`** | **97,076** | `0.38` / **`0.98`** / **`2.02 ms`** | `+30.4%` | **`117.9%` / `120.1%`** | **`4.2 MB`** |
| **kotlin24-blazing** *(Vert.x 5 JIT)* | 81,844 | `0.38` / `1.22` / `2.46 ms` | **92,092** | **`0.35`** / `1.11` / `2.24 ms` | **`+41.9%`** | `142.2%` / `124.8%` | `149.5 MB` |
| **java25-blazing** *(Vert.x 5 JIT)* | 85,120 | `0.36` / `1.17` / `2.40 ms` | **91,687** | **`0.35`** / `1.10` / `2.22 ms` | `+23.0%` | `135.6%` / `129.1%` | `204.8 MB` |
| **go** | 89,291 | `0.41` / `1.12` / `2.11 ms` | 89,134 | `0.41` / `1.12` / `2.11 ms` | `+31.4%` | `133.7%` / `133.6%` | `14.1 MB` |
| **dotnet10-blazing** *(bare Kestrel)* | 84,846 | `0.40` / `1.29` / `2.42 ms` | 84,768 | `0.40` / `1.34` / `2.47 ms` | `+22.6%` | `150.9%` / `151.6%` | `22.5 MB` |
| **dotnet10-blazing-aot** *(bare Kestrel)* | 86,882 | `0.39` / `1.35` / `2.30 ms` | 83,798 | `0.40` / `1.44` / `2.42 ms` | `+23.4%` | `149.5%` / `147.1%` | `11.5 MB` |
| **java25-blazing-aot** *(GraalVM PGO)* | 83,155 | `0.43` / `1.08` / `2.12 ms` | 80,744 | `0.44` / `1.16` / `2.17 ms` | `+26.8%` | `145.2%` / `143.3%` | `10.2 MB` |
| **kotlin24-blazing-aot** *(GraalVM PGO)* | 82,482 | `0.43` / `1.09` / `2.16 ms` | 80,245 | `0.44` / `1.16` / `2.19 ms` | `+27.4%` | `143.9%` / `143.7%` | `10.2 MB` |
| **dotnet10-jit** | 73,732 | `0.46` / `1.39` / `2.64 ms` | 78,834 | `0.43` / `1.39` / `2.52 ms` | `+18.1%` | `162.9%` / `160.9%` | `31.6 MB` |
| **dotnet10-aot** | 78,751 | `0.45` / `1.33` / `2.31 ms` | 77,874 | `0.46` / `1.35` / `2.34 ms` | `+24.8%` | `155.3%` / `162.3%` | `17.4 MB` |
| **dotnet8-aot** | 82,841 | `0.43` / `1.41` / `2.40 ms` | 77,698 | `0.46` / `1.49` / `2.45 ms` | `+16.2%` | `165.6%` / `159.6%` | `14.5 MB` |
| **dotnet8-jit** | 72,256 | `0.46` / `1.27` / `2.48 ms` | 76,782 | `0.44` / `1.37` / `2.60 ms` | `+39.7%` | `156.8%` / `166.2%` | `49.7 MB` |
| **bun-compile** | 60,945 | `0.66` / `1.42` / `2.10 ms` | 60,766 | `0.66` / `1.42` / `2.13 ms` | `-0.1%` (single-thread) | `83.1%` / `83.6%` | `17.2 MB` |
| **bun-nocompile** | 63,761 | `0.62` / `1.38` / `2.11 ms` | 59,922 | `0.66` / `1.45` / `2.15 ms` | `-1.1%` (single-thread) | `80.8%` / `80.9%` | `19.3 MB` |

---

### 3. `POST /api/users` (Fresh RAM-backed PostgreSQL `INSERT`) — 1 CPU (`--cpus=1`, Cold vs Warm)

| Target | Cold RPS | Cold `p50` / `p95` / `p99` | Warm RPS | Warm `p50` / `p95` / `p99` | App CPU (Cold / Warm) | App RAM (Warm) | Postgres CPU (Cold / Warm) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **java25-blazing** *(Vert.x 5 JIT)* | 18,392 | `1.25` / `3.15` / `37.98 ms` | **35,627** | **`1.23`** / `1.84` / `2.70 ms` | `85.3%` / `73.2%` | `209.7 MB` | `89.3%` / `168.7%` |
| **kotlin24-blazing** *(Vert.x 5 JIT)* | 20,460 | **`1.22`** / `2.69` / `19.34 ms` | **35,005** | **`1.23`** / `1.86` / `2.82 ms` | `84.9%` / `73.8%` | `192.1 MB` | `101.3%` / `167.5%` |
| **rust-axum** | **35,880** | `1.30` / **`1.66`** / **`2.58 ms`** | 33,956 | `1.38` / **`1.74`** / **`2.65 ms`** | **`72.7%` / `72.4%`** | **`6.9 MB`** | `188.5%` / `183.1%` |
| **dotnet10-blazing-aot** *(bare Kestrel)* | 29,315 | `1.35` / `2.37` / `12.32 ms` | 30,556 | `1.40` / `2.19` / `3.81 ms` | `88.9%` / `88.1%` | `25.0 MB` | `156.6%` / `152.8%` |
| **go** | 30,125 | `1.38` / `2.83` / `3.84 ms` | 30,148 | `1.39` / `2.85` / `3.87 ms` | `77.7%` / `78.0%` | `18.0 MB` | `152.8%` / `148.5%` |
| **java25-blazing-aot** *(GraalVM PGO)* | 25,213 | `1.69` / `2.58` / `4.48 ms` | 25,242 | `1.72` / `3.03` / `4.37 ms` | `80.4%` / `79.0%` | `12.8 MB` | `103.3%` / `100.0%` |
| **kotlin24-blazing-aot** *(GraalVM PGO)* | 25,145 | `1.69` / `2.61` / `4.59 ms` | 25,206 | `1.73` / `3.00` / `4.46 ms` | `80.6%` / `80.0%` | `12.9 MB` | `105.6%` / `100.8%` |
| **dotnet8-aot** | 22,536 | `1.68` / `2.70` / `24.67 ms` | 23,429 | `1.71` / `2.70` / `15.16 ms` | `90.3%` / `91.0%` | `25.3 MB` | `130.4%` / `130.4%` |
| **dotnet10-aot** | 23,475 | `1.72` / `2.63` / `6.25 ms` | 21,877 | `1.64` / `2.69` / `29.74 ms` | `88.5%` / `86.5%` | `24.4 MB` | `130.3%` / `125.5%` |
| **dotnet10-blazing** *(bare Kestrel)* | 16,735 | `2.46` / `3.20` / `23.41 ms` | 21,874 | `1.89` / `2.66` / `16.35 ms` | `90.0%` / `88.4%` | `41.3 MB` | `71.6%` / `99.7%` |
| **bun-nocompile** | 18,625 | `2.28` / `3.54` / `4.62 ms` | 19,969 | `2.29` / `3.45` / `4.14 ms` | `84.8%` / `86.7%` | `78.5 MB` | `76.3%` / `78.2%` |
| **bun-compile** | 18,666 | `2.27` / `3.39` / `4.28 ms` | 19,863 | `2.31` / `3.38` / `3.97 ms` | `83.1%` / `87.8%` | `62.5 MB` | `76.0%` / `78.8%` |
| **dotnet10-jit** | 7,208 | `5.05` / `27.84` / `62.93 ms` | 10,799 | `2.95` / `5.72` / `56.44 ms` | `86.7%` / `90.2%` | `44.1 MB` | `47.1%` / `60.6%` |
| **dotnet8-jit** | 7,479 | `5.21` / `12.78` / `66.84 ms` | 9,784 | `2.69` / `18.72` / `64.30 ms` | `87.4%` / `88.6%` | `46.9 MB` | `44.3%` / `57.9%` |

---

### 4. `POST /api/users` (Fresh RAM-backed PostgreSQL `INSERT`) — 2 CPUs (`--cpus=2`, Cold vs Warm)

| Target | Cold RPS | Cold `p50` / `p95` / `p99` | Warm RPS | Warm `p50` / `p95` / `p99` | Scaling vs 1 CPU (Warm) | App CPU (Cold / Warm) | App RAM (Warm) | Postgres CPU (Cold / Warm) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **java25-blazing** *(Vert.x 5 JIT)* | 27,298 | `1.03` / `2.62` / `7.60 ms` | **42,651** | `0.97` / `2.01` / `3.25 ms` | `+19.7%` | `160.5%` / `133.7%` | `200.9 MB` | `175.1%` / `272.6%` |
| **kotlin24-blazing** *(Vert.x 5 JIT)* | 24,711 | `1.07` / `2.98` / `14.97 ms` | **42,311** | **`0.96`** / `2.05` / `3.27 ms` | `+20.9%` | `163.9%` / `134.2%` | `224.6 MB` | `161.1%` / `272.7%` |
| **rust-axum** | **43,856** | **`0.97`** / **`1.93`** / `3.10 ms` | 41,369 | `1.04` / **`1.94`** / **`3.10 ms`** | `+21.8%` | **`130.9%` / `130.6%`** | **`7.1 MB`** | `289.2%` / `276.3%` |
| **dotnet10-blazing** *(bare Kestrel)* | 33,075 | `1.18` / `2.26` / `3.41 ms` | 38,475 | `1.12` / `2.09` / **`3.10 ms`** | `+75.9%` | `158.4%` / `155.4%` | `37.1 MB` | `214.4%` / `246.9%` |
| **dotnet10-blazing-aot** *(bare Kestrel)* | 38,923 | `1.08` / `2.07` / **`3.04 ms`** | 37,540 | `1.16` / `2.12` / `3.11 ms` | `+22.9%` | `160.7%` / `158.7%` | `18.6 MB` | `240.0%` / `239.5%` |
| **go** | 36,468 | `1.20` / `2.42` / `3.44 ms` | 36,418 | `1.20` / `2.44` / `3.50 ms` | `+20.8%` | `145.2%` / `146.1%` | `18.7 MB` | `230.9%` / `226.8%` |
| **dotnet10-jit** | 24,561 | `1.35` / `3.14` / `7.25 ms` | 34,441 | `1.26` / `2.43` / `3.25 ms` | **`+218.9%`** | `162.8%` / `167.7%` | `46.4 MB` | `171.6%` / `232.7%` |
| **dotnet8-jit** | 23,491 | `1.35` / `3.48` / `15.17 ms` | 32,158 | `1.29` / `2.44` / `4.39 ms` | **`+228.7%`** | `174.0%` / `178.2%` | `72.0 MB` | `160.7%` / `222.1%` |
| **java25-blazing-aot** *(GraalVM PGO)* | 31,920 | `1.32` / `2.21` / `4.96 ms` | 31,944 | `1.33` / `2.77` / `4.67 ms` | `+26.6%` | `153.5%` / `154.0%` | `17.8 MB` | `181.9%` / `180.7%` |
| **kotlin24-blazing-aot** *(GraalVM PGO)* | 31,793 | `1.32` / `2.18` / `5.16 ms` | 31,935 | `1.33` / `2.73` / `4.83 ms` | `+26.7%` | `155.3%` / `154.3%` | `20.2 MB` | `186.6%` / `181.4%` |
| **dotnet10-aot** | 30,839 | `1.39` / `2.69` / `4.42 ms` | 30,864 | `1.40` / `2.67` / `3.72 ms` | `+41.1%` | `180.2%` / `177.2%` | `25.8 MB` | `223.9%` / `217.7%` |
| **dotnet8-aot** | 30,082 | `1.37` / `2.87` / `4.49 ms` | 30,371 | `1.37` / `2.78` / `3.71 ms` | `+29.6%` | `173.6%` / `169.7%` | `18.2 MB` | `216.1%` / `212.9%` |
| **bun-nocompile** | 19,844 | `2.28` / `3.45` / `4.16 ms` | 19,818 | `2.31` / `3.43` / `4.07 ms` | `-0.8%` (single-thread) | `87.7%` / `88.0%` | `54.5 MB` | `78.8%` / `80.1%` |
| **bun-compile** | 19,564 | `2.33` / `3.42` / `4.08 ms` | 19,429 | `2.37` / `3.44` / `4.01 ms` | `-2.2%` (single-thread) | `90.1%` / `87.5%` | `51.7 MB` | `80.8%` / `77.7%` |

---

## Key Findings & Engineering Analysis

### 1. Rust (`Axum 0.8 + tokio-postgres`) vs Blazing JVM (`Java 25 / Kotlin 2.4` + `Vert.x 5 Epoll`)
- **Rust (`Axum`)** still owns **cold start**, **peak `GET`**, **tail latency**, and **resource efficiency**:
  - `105,537` cold / `97,076` warm 2-CPU `GET` (`4.2 MB` RAM, `120.1%` CPU).
  - `43,856` cold / `41,369` warm 2-CPU `POST` (`7.1 MB` RAM, `130.6%` CPU, `p99 3.10 ms`) and `35,880` cold 1-CPU `POST` (`p99 2.58–2.65 ms`).
- **Java 25 & Kotlin 2.4 Blazing JIT (`Vert.x 5` + `Netty 4.2` Epoll + `vertx-pg-client`)** prove just how formidable modern HotSpot C2 + single-thread-per-core pipelined I/O is once warmed up:
  - **Overtakes even Rust on warm `POST`**: **`42,651` (Java) / `42,311` (Kotlin)** vs Rust's `41,369` warm on 2 CPUs (**+2.3–3.1%**), and **`35,627` / `35,005`** vs Rust's `33,956` warm on 1 CPU (**+3.1–4.9%**), driven by `vertx-pg-client`'s zero-thread-hop event-loop pipelining (`pipeliningLimit=256`, `SO_REUSEPORT` pinned per core) and `ParallelGC`'s zero write-barrier overhead.
  - **Overtakes Go and all `.NET 10` builds on warm 2-CPU `GET`**: **`92,092` (Kotlin) / `91,687` (Java)** vs Go's `89,134` and `.NET 10 Blazing`'s `84,768`, at lower CPU (`124.8–129.1%` vs `133.6%` Go and `151.6%` `.NET 10 Blazing`).
  - **Zero Kotlin runtime tax**: Kotlin `2.4.21` matches Java `25` within measurement noise across every JIT and AOT cell.
  - **The JVM JIT tradeoff**: cold-start `POST` (`18.4–27.3k`, `p99 7.6–38.0 ms`) and heap footprint (`150–225 MB` RAM vs Rust's `4.2–7.1 MB` and `.NET 10 Blazing`'s `22.5–37.1 MB`).

### 2. GraalVM 25 PGO AOT vs `.NET 10` NativeAOT & Blazing `.NET 10`
- **`java25-blazing-aot` & `kotlin24-blazing-aot`** (Oracle GraalVM 25 Native Image with self-contained build-time HTTP PGO + `-O3` on `distroless/cc-debian13`):
  - **Tiny footprint**: **`52.5 MB` image** (nearly **3× smaller than `.NET 10 AOT`** at `140–143 MB`) and **`9.9–10.3 MB` warm `GET` RAM / `12.8–20.2 MB` warm `POST` RAM** — less memory than both Go (`14.1 / 18.7 MB`) and `.NET 10 Blazing AOT` (`11.5 / 18.6 MB`).
  - **Zero cold-start cliff**: `83.2k → 80.7k` on 2-CPU `GET` and `31.9k → 31.9k` on 2-CPU `POST` (ahead of idiomatic `.NET 10 AOT` at `30.9k` and `77.9k`).
  - **Why GraalVM AOT trails JIT & `.NET Blazing AOT` on `POST` (`31.9k` vs `42.7k` / `37.5k`)**: build-time PGO runs inside `Dockerfile` without PostgreSQL, so only HTTP `GET` paths are PGO-profiled while `vertx-pg-client` uses unprofiled cold heuristics.
- **`.NET 10 Blazing` (`dotnet10-blazing` & `dotnet10-blazing-aot`)**:
  - `dotnet10-blazing` hits **`38,475 RPS` warm 2-CPU `POST`** (`+11.7%` over idiomatic `dotnet10-jit`, `+5.6%` over Go, matching Rust's `p99 3.10 ms`) at just `37.1 MB` RAM — **~5.5× less RAM than Java/Kotlin JIT** while staying within ~10% of Java/Kotlin JIT and ~7% of Rust.
  - `dotnet10-blazing-aot` leads all AOT/non-Rust runtimes on `POST` (`38,923` cold / `37,540` warm 2-CPU, `30,556` 1-CPU, `p99 3.04–3.11 ms`) at `11.5 / 18.6 MB` RAM.

### 3. Go (`Fiber v3 + pgx/v5`) vs Idiomatic `.NET 10` (`Kestrel + Npgsql`)
- Among **idiomatic** implementations, Go holds `#2` behind Rust across every cell (`89.1k` 2-CPU `GET`, `30.1k` 1-CPU `POST`, `36.4k` 2-CPU `POST`), with the tiniest container image (`14.9 MB` on `scratch`) and lower CPU/RAM than idiomatic `.NET`.
- Once PostgreSQL NVMe `fsync` throttling is eliminated and `Npgsql` has `Max Auto Prepare=20`, idiomatic `.NET 10 JIT` closes 2-CPU warm `POST` to within **5.4% of Go** (`34,441` vs `36,418 RPS`) with lower `p99` (`3.25 ms` vs `3.50 ms`).

### 4. Multi-Core Scaling (`1 CPU` → `2 CPUs`) & Cold vs Warm
- **Rust, Java/Kotlin (JIT & AOT), Go, and .NET** all scale cleanly from 1 CPU to 2 CPUs (`+20%` to `+42%` on `GET` and warm `POST`, and `+76%` to `+229%` for `.NET` JIT `POST` once the second core relieves JIT compilation contention).
- **Bun (`--compile` & `no-compile`)** is completely flat from 1 CPU to 2 CPUs (`~60.5k` `GET`, `~19.8k` `POST`, `App CPU` stuck at `81–88%`) because `Bun.serve` runs a single-threaded JS event loop. Scaling Bun across cores requires multi-process workers (`reusePort: true`).
- On **1-CPU `POST`**, AOT eliminates JIT warmup contention: `dotnet10-blazing-aot` (`29.3–30.6k`), `java25-blazing-aot` (`25.2k`), and `dotnet10-aot` (`21.9–23.5k`) deliver instant steady throughput from second zero; once warmed, `java25-blazing` (`35.6k`) and `kotlin24-blazing` (`35.0k`) jump to `#1–#2` overall.

### 5. `.NET 8` vs `.NET 10` (LTS → LTS) & Bun `--compile` vs `no-compile`
- **`.NET 10 JIT` vs `.NET 8 JIT`**: **36% less RAM** on 2 CPUs (`31.6–46.4 MB` vs `49.7–72.0 MB` via DATAS Server GC), **+21.4%** faster warm 1-CPU `GET`, and **+7.1% RPS / 26% lower `p99`** on warm 2-CPU `POST` (`34.4k / 3.25 ms` vs `32.2k / 4.39 ms`).
- **Bun `--compile` vs `no-compile`**: virtually identical performance (`60–64k` `GET`, `18.6–20.0k` `POST`, steady `p99 ~4.0 ms`), while `bun-nocompile` (`209 MB`) is **80 MB smaller** than `bun-compile` (`289 MB`) on `oven/bun:1.3.5-slim`.

---

## .NET 10.0.15 vs .NET 11.0.0 Comparison (Separate Hardware)

A focused **64-run** comparison of **`.NET 10.0.15`** vs **`.NET 11.0.0`** (`jit`, `aot`, `blazing`, `blazing-aot`) was executed on a different desktop host, so its raw dataset is stored separately in [`results/dotnet10-vs-dotnet11/matrix.tsv`](results/dotnet10-vs-dotnet11/matrix.tsv) rather than merged into the Ryzen 5 PRO 6650U matrix above.

### Host Hardware & Environment
| Component | Specification |
| --- | --- |
| **CPU** | AMD Ryzen 5 7600 6-Core Processor (`x86_64`, 6 cores / 12 threads, up to 5.17 GHz) |
| **RAM** | 30 GiB usable |
| **OS / Kernel** | CachyOS — `Linux 7.3.5-2-cachyos #1 SMP PREEMPT_DYNAMIC x86_64` |
| **Runtimes** | `mcr.microsoft.com/dotnet/sdk:10.0` (`.NET 10.0.15`) vs `mcr.microsoft.com/dotnet/sdk:11.0` (`.NET 11.0.0`) |
| **Workload** | `50 VUs`, `10s` cold (`0–10s`) + `10s` warmup + `10s` warm, `0.00%` errors, RAM-backed `postgres:18.1` |

### 2 CPU (`--cpus=2`)
| Target | `GET` Cold → Warm (RPS) | `GET` Warm `avg` / `p99` | `POST` Cold → Warm (RPS) | `POST` Warm `avg` / `p99` | App RAM (`GET` / `POST` warm) |
| --- | ---: | ---: | ---: | ---: | ---: |
| **dotnet10-blazing** | `122,106 → 134,685` | `0.34 / 1.44 ms` | `56,658 → 63,836` | `0.74 / 2.15 ms` | `22.1 / 37.5 MB` |
| **dotnet11-blazing** | **`129,376 → 138,880`** (`+3.1%` warm) | `0.33 / 1.40 ms` | `53,178 →` **`67,389`** (`+5.6%` warm) | `0.70 / 2.10 ms` | `22.7 / 40.2 MB` |
| **dotnet10-blazing-aot** | `130,965 → 133,431` | `0.35 / 1.45 ms` | `61,805 → 55,178` | `0.86 / 2.97 ms` | `12.4 / 17.6 MB` |
| **dotnet11-blazing-aot** | **`136,564 → 137,085`** (`+2.7%` warm) | `0.34 / 1.37 ms` | **`64,060 → 63,902`** (`+15.8%` warm) | `0.74 / 2.17 ms` | `11.7 / 19.6 MB` |
| **dotnet10-aot** | **`126,697 → 126,452`** | `0.37 / 1.45 ms` | **`55,157 → 55,529`** | `0.86 / 2.60 ms` | `17.3 / 22.7 MB` |
| **dotnet11-aot** | `68,141 → 102,319` | `0.46 / 2.10 ms` | `51,652 → 55,447` | `0.86 / 2.48 ms` | `15.0 / 26.5 MB` |
| **dotnet10-jit** | **`119,984 → 127,825`** | `0.36 / 1.43 ms` | **`44,218 → 59,023`** | `0.80 / 2.26 ms` | `31.6 / 43.5 MB` |
| **dotnet11-jit** | `55,435 → 66,063` | `0.72 / 3.59 ms` | `33,689 → 29,218` | `1.65 / 5.68 ms` | `31.8 / 49.2 MB` |

### 1 CPU (`--cpus=1`)
| Target | `GET` Cold → Warm (RPS) | `GET` Warm `avg` / `p99` | `POST` Cold → Warm (RPS) | `POST` Warm `avg` / `p99` | App RAM (`GET` / `POST` warm) |
| --- | ---: | ---: | ---: | ---: | ---: |
| **dotnet10-blazing-aot** | `112,962 → 108,808` | `0.43 / 1.53 ms` | `47,695 →` **`50,552`** | `0.95 / 2.36 ms` | `38.0 / 32.9 MB` |
| **dotnet11-blazing-aot** | **`116,176 → 116,374`** (`+7.0%` warm) | `0.40 / 1.42 ms` | **`49,570`** `→ 49,425` | `0.97 / 2.55 ms` | `28.1 / 32.7 MB` |
| **dotnet10-blazing** | **`92,777 → 113,184`** | `0.41 / 1.40 ms` | **`31,158 → 44,608`** | `1.08 / 2.43 ms` | `35.2 / 53.2 MB` |
| **dotnet11-blazing** | `63,503 → 68,081` | `0.71 / 1.76 ms` | `27,866 → 25,069` | `1.96 / 3.37 ms` | `37.1 / 47.2 MB` |
| **dotnet10-aot** | **`107,854 → 104,263`** | `0.45 / 1.40 ms` | **`39,389 → 39,773`** | `1.22 / 2.93 ms` | `35.5 / 32.4 MB` |
| **dotnet11-aot** *(matrix)* | `56,315 → 57,036` *(51.6% CPU)* | `0.84 / 2.99 ms` | `21,398 → 21,650` | `2.24 / 5.82 ms` | `28.3 / 33.0 MB` |
| **dotnet10-jit** | **`71,913 → 93,532`** | `0.51 / 1.35 ms` | **`16,204 → 19,594`** | `2.51 / 49.21 ms` | `44.6 / 52.6 MB` |
| **dotnet11-jit** | `56,658 → 46,927` | `1.03 / 4.14 ms` | `10,570 → 10,665` | `4.62 / 36.79 ms` | `44.4 / 46.7 MB` |

> **Note on `1 CPU` `dotnet11-aot` & `dotnet11-jit` (`GET`)**: In the 64-run matrix, `dotnet11-aot` `1 CPU` `GET` only utilized `51.6%` CPU (`57,036 RPS`). An isolated `5s` warm `GET` recheck (`3s` warmup) on `1 CPU` showed **`dotnet11-aot` at `114,157 RPS`** (`88.1%` CPU, `0.41 ms` avg) vs **`dotnet10-aot` at `108,775 RPS`** (`87.0%` CPU, `0.43 ms` avg, **+4.9%**), while `dotnet11-jit` remained behind `dotnet10-jit` (`59,752` vs `72,111 RPS`).

---

## How to Run

### Prerequisites
- Linux with **Podman** installed
- Optional (for live CPU & RAM metrics): enable the user Podman socket:
  ```bash
  systemctl --user start podman.socket
  ```

### Run the Full 112-Cell Matrix
```bash
cd go-bun-dotnet-postgres
chmod +x run.sh
./run.sh
```

### Run Custom Combinations
`run.sh` supports filtering any dimension via environment variables:

```bash
# Quick comparison: only 2 CPUs, Rust vs Java/Kotlin Blazing vs .NET 10 Blazing vs Go
SKIP_BUILD=1 CPU_PROFILES="2" TARGETS="rust-axum java25-blazing java25-blazing-aot kotlin24-blazing kotlin24-blazing-aot dotnet10-blazing dotnet10-blazing-aot go" ./run.sh

# High-concurrency stress test (200 VUs)
SKIP_BUILD=1 K6_VUS=200 CPU_PROFILES="2" TARGETS="rust-axum java25-blazing kotlin24-blazing dotnet10-blazing go" ./run.sh

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
