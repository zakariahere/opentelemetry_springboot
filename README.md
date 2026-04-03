# Spring Boot 4 × OpenTelemetry Java Agent — LGTM Stack Showcase

A zero-code-change observability demo: a **Spring Boot 4** application instrumented entirely via the **OpenTelemetry Java Agent** (no SDK dependencies, no code modifications), emitting **traces**, **metrics**, and **logs** via OTLP to an **OpenTelemetry Collector**, which fans them out to the full **Grafana LGTM stack** (Loki · Grafana · Tempo · Mimir).

```
Spring Boot App
  + OTel Java Agent (attached at JVM startup)
        │  OTLP/HTTP → localhost:4318
        ▼
   OTel Collector
     ├──▶ Tempo   (traces)
     ├──▶ Mimir   (metrics)
     └──▶ Loki    (logs)
               ▲
            Grafana
```

> **Branch context:** This is the `feat/auto-instrumentation` branch. The `main` branch uses the Spring Boot OTel SDK bridge approach (explicit dependencies + `logback-spring.xml`). See the comparison section below for trade-offs.

---

## Stack

| Component | Role | Port |
|-----------|------|------|
| Spring Boot 4.0.5 | Demo application | 8080 |
| OTel Java Agent 2.26.1 | Bytecode instrumentation | — |
| OTel Collector contrib 0.123.0 | Signal router | 4317 (gRPC) · 4318 (HTTP) |
| Grafana Tempo 2.7.2 | Trace backend | 3200 |
| Grafana Mimir 2.15.0 | Metrics backend | 9009 |
| Grafana Loki 3.4.2 | Log backend | 3100 |
| Grafana 11.6.0 | Visualization | 3000 |

---

## Prerequisites

- Java 25+
- Docker + Docker Compose

---

## Setup

### 1. Download the Java Agent

```bash
curl -L -o opentelemetry-javaagent.jar \
  https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar
```

The jar is gitignored — each developer downloads it once.

### 2. Start the infrastructure

```bash
docker compose up -d
```

This starts the OTel Collector, Tempo, Mimir, Loki, and Grafana. Alternatively, just run the app — Spring Boot's docker-compose integration starts the stack automatically.

### 3. Run the app with the agent

#### IntelliJ IDEA

`Run` → `Edit Configurations` → `Demo1Application` → **VM options**:

```
-javaagent:C:/path/to/demo1/opentelemetry-javaagent.jar
-Dotel.service.name=demo1
-Dotel.exporter.otlp.endpoint=http://localhost:4318
-Dotel.logs.exporter=otlp
```

> The run configuration is also committed at `.run/Demo1Application.run.xml` — IntelliJ picks it up automatically.

#### Command line

```bash
java \
  -javaagent:./opentelemetry-javaagent.jar \
  -Dotel.service.name=demo1 \
  -Dotel.exporter.otlp.endpoint=http://localhost:4318 \
  -Dotel.logs.exporter=otlp \
  -jar target/demo1-0.0.1-SNAPSHOT.jar
```

---

## Agent Configuration

The agent is configured entirely via JVM system properties — no `application.properties` changes needed.

| Property | Value | Description |
|----------|-------|-------------|
| `otel.service.name` | `demo1` | Service name shown in all signals |
| `otel.exporter.otlp.endpoint` | `http://localhost:4318` | Base OTLP/HTTP endpoint (agent appends `/v1/traces`, `/v1/metrics`, `/v1/logs`) |
| `otel.logs.exporter` | `otlp` | Enables log export (disabled by default) |

> **Protocol note:** Agent 2.x defaults to `http/protobuf` — use port **4318**. Port `4317` is gRPC only and will cause a protocol error.

### What the agent instruments automatically

Out of the box, with zero code changes:

- **Traces** — every incoming HTTP request, outgoing HTTP call, JDBC query, scheduled task, and more (~50 frameworks supported)
- **Metrics** — JVM internals (heap, GC, threads, classes), HTTP server request durations, connection pool stats
- **Logs** — Logback log records bridged into the OTel SDK via the agent's built-in log appender

---

## Demo Endpoints

```bash
# Fast path — auto-instrumented HTTP span + INFO log
curl http://localhost:8080/hello

# Slow path — 1500ms span duration visible in Tempo flame graphs
curl http://localhost:8080/slow

# Error path — span with error status + ERROR log with stack trace
curl http://localhost:8080/error
```

---

## Exploring in Grafana

Open **http://localhost:3000** — anonymous admin, no login required.

### Traces (Tempo)
`Explore` → **Tempo** → Search tab → Service Name: `demo1` → Run query

- `/slow` spans show ~1500ms duration in the flame graph
- `/error` spans are marked with an error status
- The agent automatically creates child spans for any outbound calls

### Metrics (Mimir)
`Explore` → **Mimir** → try these queries:

```promql
# HTTP server request rate (auto-instrumented by agent)
rate(http_server_request_duration_seconds_count{http_route="/hello"}[1m])

# P95 latency across all endpoints
histogram_quantile(0.95, rate(http_server_request_duration_seconds_bucket[5m]))

# JVM heap usage (auto-collected by agent)
jvm_memory_used_bytes{jvm_memory_type="heap"}

# JVM GC pause time
rate(jvm_gc_duration_seconds_sum[1m])

# Active HTTP connections
http_server_active_requests
```

### Logs (Loki)
`Explore` → **Loki** → Label filter: `service_name = demo1`

The agent bridges Logback output into OTel automatically — every `log.info(...)`, `log.error(...)` etc. is exported without any configuration in the app.

### Cross-signal correlation
All three datasources are pre-wired for correlation:

| From | To | How |
|------|----|-----|
| Tempo trace span | Loki logs | Click the **Loki** button on any span |
| Tempo trace span | Mimir metrics | Click the **Mimir** button on any span |
| Loki log line | Tempo trace | Click **View Trace in Tempo** on any log line |
| Mimir metric | Tempo trace | Exemplars on histogram metrics link back to traces |

---

## Project Structure

```
demo1/
├── compose.yaml                        # Full infrastructure stack (5 services)
├── opentelemetry-javaagent.jar         # Downloaded locally, gitignored
├── .run/
│   └── Demo1Application.run.xml        # IntelliJ run config with agent VM options
├── config/
│   ├── otelcol-config.yaml             # Collector: receive OTLP, route to backends
│   ├── tempo-config.yaml               # Trace storage + RED metrics generator
│   ├── mimir-config.yaml               # Metrics storage (monolithic mode)
│   ├── loki-config.yaml                # Log storage (OTLP-compatible)
│   └── grafana/
│       └── provisioning/
│           └── datasources/
│               └── datasources.yaml    # Auto-provisioned Tempo + Mimir + Loki
└── src/main/
    ├── java/com/example/demo/
    │   ├── Demo1Application.java       # Plain @SpringBootApplication, no OTel code
    │   └── DemoController.java         # /hello · /slow · /error endpoints
    └── resources/
        └── application.properties      # Only spring.application.name, nothing OTel
```

Notice what's **absent**: no OTel dependencies in `pom.xml`, no `logback-spring.xml`, no `OpenTelemetryAppender.install()` call, no `management.otlp.*` properties. The agent handles everything.

---

## How the Signals Are Exported

### Traces
The agent instruments bytecode at JVM startup. Every `@GetMapping` handler becomes a root span automatically. The span lifecycle, attributes (`http.method`, `http.route`, `http.status_code`, etc.), and context propagation are all handled by the agent with no annotations required.

### Metrics
The agent registers an OTel metrics SDK and collects:
- **JVM metrics** — memory pools, GC activity, thread counts, class loading
- **HTTP server metrics** — request duration histograms, active request gauges
- **HTTP client metrics** — if the app makes outbound calls

All metrics are pushed via OTLP/HTTP to the Collector every 60s (default), which forwards them to Mimir. Tempo's `metrics_generator` also derives **RED metrics** and **service graph** edges from trace data.

### Logs
The agent installs its own Logback appender at startup (no `logback-spring.xml` needed). Every log record is captured, enriched with the active `traceId` and `spanId` from the current span context, and exported via OTLP/HTTP to the Collector, which forwards them to Loki.

> **Key Loki config**: `allow_structured_metadata: true` is required for OTLP log ingestion — OTel resource and scope attributes arrive as structured metadata, not flat stream labels.

---

## Approach Comparison

This branch uses the Java Agent. The `main` branch uses the Spring Boot SDK bridge. Here's when to choose each:

| | Java Agent (this branch) | SDK Bridge (main branch) |
|---|---|---|
| `pom.xml` changes | None | 2 dependencies |
| Code changes | None | `logback-spring.xml` + `install()` call |
| Custom spans | Needs `@WithSpan` or API import | Natural via `MeterRegistry`, OTel API |
| Custom metrics | Via OTel API only | Full Micrometer integration |
| Startup time | Slower (bytecode weaving) | Faster |
| Library coverage | ~50 frameworks auto-detected | Explicit per framework |
| Configuration | JVM flags / env vars | Spring `application.properties` |
| Best for | Legacy apps, drop-in observability | Greenfield, fine-grained control |

---

## Notes

- All backend storage is under `/tmp` inside containers — **ephemeral by design**. Data is lost on container restart.
- Grafana is configured with anonymous Admin access — remove `GF_AUTH_ANONYMOUS_*` from `compose.yaml` before any non-local deployment.
- The `otelcol-contrib` image is distroless (no shell), so Docker healthchecks using `wget`/`curl` will not work against it.
- The agent jar (~24MB) is excluded from git via `.gitignore`. The download `curl` command above fetches the latest stable release.
