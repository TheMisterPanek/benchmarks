# Benchmarks

A collection of reproducible, containerized performance benchmarks comparing runtimes, frameworks, compilers, and configurations.

## Index

| Benchmark | Stack Compared | Workload | Key Highlights |
| --- | --- | --- | --- |
| [**Go vs Bun vs .NET (PostgreSQL)**](go-bun-dotnet-postgres/README.md) | Go `1.25.5` (Fiber v3), Bun `1.3.5` (`--compile` & `no-compile`), .NET `8.0` & `10.0` (JIT & NativeAOT) | HTTP JSON (`GET`) & fresh RAM-backed PostgreSQL `INSERT` (`POST`), `1 CPU` vs `2 CPUs`, `cold` vs `warm` (56 isolated runs via `k6` with `p50/p90/p95/p99` + Podman CPU/RAM telemetry) | Go leads RPS across all cells (`89.3k` 2-CPU `GET`, `36.5k` 2-CPU `POST`, `14–19 MB` RAM); .NET 10 JIT closes 2-CPU warm `POST` to within 5.4% (`34.4k`, best `p99 3.25 ms`) using ~36% less RAM than .NET 8; NativeAOT beats JIT >2× on 1-CPU `POST` with Go-like RAM; Bun hits single-thread limit on 2 CPUs. |

---

## Repository Layout

Each benchmark lives in its own top-level directory containing:
- `README.md` — test hardware, methodology, full results, and analysis
- Application source code & container `Dockerfile`s
- `run.sh` — self-contained runner script
- `results/` — raw machine-readable telemetry (`.tsv`/`.csv`)

## License

This repository is licensed under the [MIT License](LICENSE). Individual benchmarks note any upstream reference repositories they build upon.
