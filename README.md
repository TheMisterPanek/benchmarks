# Benchmarks

A collection of reproducible, containerized performance benchmarks comparing runtimes, frameworks, compilers, and configurations.

## Index

| Benchmark | Stack Compared | Workload | Key Highlights |
| --- | --- | --- | --- |
| [**Rust vs Java 25 vs Kotlin 2.4 vs Go vs .NET vs Bun (PostgreSQL)**](go-bun-dotnet-postgres/README.md) | Rust `1.100.0` (Axum `0.8` + `tokio-postgres`), Java `25` & Kotlin `2.4.21` (Vert.x `5` Epoll + `vertx-pg-client`, JIT & GraalVM `25` PGO AOT), Go `1.25.5` (Fiber v3), .NET `8.0` & `10.0` (Minimal API + bare-Kestrel `blazing`, JIT & NativeAOT), Bun `1.3.5` (`--compile` & `no-compile`) | HTTP JSON (`GET`) & fresh RAM-backed PostgreSQL `INSERT` (`POST`), `1 CPU` vs `2 CPUs`, `cold` vs `warm` (112 isolated runs via `k6` with `p50/p90/p95/p99` + Podman CPU/RAM telemetry) | **Rust (`axum`)** leads cold/overall `GET` (`105.5k → 97.1k` 2-CPU, `4.2–7.7 MB` RAM) and cold `POST` (`43.9k`); **Java 25 / Kotlin 2.4 Blazing JIT** (`Vert.x 5` Epoll + pipelined `vertx-pg-client`) overtake even Rust on **warm `POST`** (`42.7k` / `42.3k` 2-CPU, `35.6k` / `35.0k` 1-CPU) and claim `#2` on warm 2-CPU `GET` (`92.1k` / `91.7k`, ahead of Go `89.1k` and `.NET 10 Blazing` `84.8k`); **GraalVM 25 PGO AOT** shrinks JVM/Kotlin to `52.5 MB` images and `9.9–10.3 MB` warm `GET` RAM (less than Go & `.NET AOT`); bare-Kestrel `.NET 10 blazing` hits `38.5k` JIT / `37.5k` AOT on 2-CPU `POST` (`p99 3.10 ms`). |

---

## Repository Layout

Each benchmark lives in its own top-level directory containing:
- `README.md` — test hardware, methodology, full results, and analysis
- Application source code & container `Dockerfile`s
- `run.sh` — self-contained runner script
- `results/` — raw machine-readable telemetry (`.tsv`/`.csv`)

## License

This repository is licensed under the [MIT License](LICENSE). Individual benchmarks note any upstream reference repositories they build upon.
