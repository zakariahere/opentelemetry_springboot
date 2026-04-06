# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build
mvn clean package

# Run tests
mvn test

# Start all infrastructure (OTel Collector, Tempo, Mimir, Loki, Grafana)
docker compose up -d

# Stop infrastructure
docker compose down

# Run the app with the OTel Java Agent (the primary way to run this)
java -javaagent:./opentelemetry-javaagent.jar \
  -Dotel.service.name=demo1 \
  -Dotel.exporter.otlp.endpoint=http://localhost:4318 \
  -Dotel.logs.exporter=otlp \
  -jar target/demo1-0.0.1-SNAPSHOT.jar
```

The IntelliJ run config at `.run/Demo1Application.run.xml` has the VM options pre-configured.

Download the agent (one-time):
```bash
curl -L -o opentelemetry-javaagent.jar \
  https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar
```

## Architecture

**Stack:** Spring Boot 4.0.5 / Java 25 / OTel Java Agent 2.26.1 → LGTM (Loki, Grafana, Tempo, Mimir)

The core idea is **zero-code-change auto-instrumentation**: the application has no OTel SDK dependencies. The Java Agent instruments the JVM at bytecode level and ships all three signals (traces, metrics, logs) via OTLP to the collector.

```
Spring Boot App (port 8080)
    │ OTLP/HTTP (injected by Java Agent)
    ▼
OTel Collector (port 4317 gRPC / 4318 HTTP)
    ├──▶ Tempo   (port 3200) — traces
    ├──▶ Mimir   (port 9009) — metrics (Prometheus-compatible)
    └──▶ Loki    (port 3100) — logs
              ▼
          Grafana (port 3000) — visualization + cross-signal correlation
```

### Signal details

- **Traces**: HTTP handler spans auto-created per request; child spans for outbound HTTP, JDBC, etc.
- **Metrics**: JVM heap/GC/threads + HTTP server request duration histograms; pushed every 60s. Tempo also generates RED metrics and service graphs from trace data.
- **Logs**: Agent injects a Logback appender at startup — all `log.info/warn/error()` calls are captured and enriched with the current `traceId`/`spanId` automatically.

### Cross-signal correlation (configured in Grafana)

Grafana datasources are provisioned via `config/grafana/provisioning/datasources/datasources.yaml` with these links pre-wired:
- Tempo span → Loki (service_name match)
- Tempo span → Mimir metrics (service.name match)
- Loki log line → Tempo trace (traceId regex extraction)
- Mimir exemplar → Tempo trace

### Key configuration files

| File | Purpose |
|------|---------|
| `compose.yaml` | Orchestrates the 5-service observability stack |
| `config/otelcol-config.yaml` | Collector: receives OTLP, batches, exports to Tempo/Mimir/Loki |
| `config/tempo-config.yaml` | Trace storage + RED metrics generator from spans |
| `config/mimir-config.yaml` | Monolithic-mode Prometheus-compatible metrics backend |
| `config/loki-config.yaml` | Log storage; `allow_structured_metadata: true` is required for OTLP log ingestion |
| `config/grafana/provisioning/datasources/datasources.yaml` | Auto-provisioned datasources with cross-signal links |

### Storage

All backends store data under `/tmp` inside containers — **ephemeral by design**. Data is lost on container restart. No persistent volumes are configured.

## Demo Endpoints

| Endpoint | Behavior | Observability purpose |
|----------|----------|-----------------------|
| `GET /hello` | Returns `{"message": "Hello from demo1!"}` | Baseline fast span |
| `GET /slow` | Sleeps 1500ms | Demonstrates latency in flame graphs |
| `GET /error` | Throws internally, returns 500 | Demonstrates error spans + log correlation |

Grafana is accessible at `http://localhost:3000` with anonymous admin access (`GF_AUTH_ANONYMOUS_ENABLED=true`).
