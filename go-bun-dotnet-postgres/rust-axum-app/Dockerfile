FROM docker.io/library/rust:1.100.0-slim-trixie AS build

WORKDIR /app

COPY Cargo.toml ./
COPY src ./src

RUN cargo build --release

FROM gcr.io/distroless/cc-debian13

WORKDIR /app

COPY --from=build /app/target/release/rust-axum-app /app/rust-axum-app

ENTRYPOINT ["/app/rust-axum-app"]
