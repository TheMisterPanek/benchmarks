FROM grafana/k6:latest AS k6bin

FROM alpine:3.21
RUN apk add --no-cache bash curl jq coreutils

COPY --from=k6bin /usr/bin/k6 /usr/bin/k6
WORKDIR /benchmarks

COPY script.js run-benchmarks.sh ./
RUN chmod +x /benchmarks/run-benchmarks.sh

ENTRYPOINT ["/benchmarks/run-benchmarks.sh"]
