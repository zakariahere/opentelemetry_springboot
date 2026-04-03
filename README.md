# Spring Boot 4 × OpenTelemetry — LGTM Stack Showcase

A minimal but complete observability demo: a **Spring Boot 4** application that emits **traces**, **metrics**, and **logs** via the OTLP protocol to an **OpenTelemetry Collector**, which fans them out to the full **Grafana LGTM stack** (Loki · Grafana · Tempo · Mimir).

```
Spring Boot App  ──OTLP/HTTP──▶  OTel Collector
                                   ├──▶ Tempo   (traces)
                                   ├──▶ Mimir   (metrics)
                                   └──▶ Loki    (logs)
                                         ▲
                                      Grafana
```

---

## Stack

| Component | Role | Port |
|-----------|------|------|
| Spring Boot 4.0.5 | Demo application | 8080 |
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

## Running

```bash
# Clone and enter the project
git clone <repo-url>
cd demo1

# Start the app — Spring Boot auto-starts the Docker Compose stack first
./mvnw.cmd spring-boot:run        # Windows
./mvnw spring-boot:run            # macOS / Linux
```

Spring Boot's docker-compose integration starts all five containers (Collector, Tempo, Mimir, Loki, Grafana), waits for them to be healthy, then starts the application.

---

## Demo Endpoints

Hit these to generate all three signal types:

```bash
# Fast path — trace + INFO log + counter increment
curl http://localhost:8080/hello

# Slow path — 1500ms sleep, gauge tracks active requests
curl http://localhost:8080/slow

# Error path — ERROR log with stack trace, HTTP 500 response
curl http://localhost:8080/error
```

---

## Exploring in Grafana

Open **http://localhost:3000** — anonymous admin access, no login needed.

### Traces (Tempo)
`Explore` → **Tempo** → Search tab → Service Name: `demo1` → Run query

- `/slow` spans show ~1500ms duration
- `/error` spans carry an error status
- Click any span to open the flame graph

### Metrics (Mimir)
`Explore` → **Mimir** → try these queries:

```promql
# Custom counters from DemoController
demo_hello_requests_total
demo_error_requests_total
demo_slow_active

# Spring MVC auto-instrumentation
rate(http_server_requests_seconds_count[1m])
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))
```

### Logs (Loki)
`Explore` → **Loki** → Label filter: `service_name = demo1`

### Cross-signal correlation
All three datasources are pre-wired:

| From | To | How |
|------|----|-----|
| Tempo trace span | Loki logs | Click the **Loki** button on any span |
| Tempo trace span | Mimir metrics | Click the **Mimir** button on any span |
| Loki log line | Tempo trace | Click **View Trace in Tempo** on any log with a `traceId` |
| Mimir metric | Tempo trace | Exemplars link histogram data points back to traces |

---

## Project Structure

```
demo1/
├── compose.yaml                        # Full infrastructure stack
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
    │   ├── Demo1Application.java       # @SpringBootApplication + OTel log bridge wiring
    │   └── DemoController.java         # /hello · /slow · /error endpoints
    └── resources/
        ├── application.properties      # OTLP endpoints for all three signals
        └── logback-spring.xml          # Adds OpenTelemetryAppender to Logback
```

---

## How the Signals Are Exported

### Traces
`spring-boot-starter-opentelemetry` includes `micrometer-tracing-bridge-otel`, which auto-instruments all incoming HTTP requests. Spans are exported via OTLP/HTTP to the Collector, which forwards them to Tempo via OTLP/gRPC.

### Metrics
Micrometer's OTLP registry pushes metrics every 30s via OTLP/HTTP to the Collector, which forwards them to Mimir's native OTLP ingestion endpoint (`/otlp/v1/metrics`).

Tempo's `metrics_generator` additionally derives **RED metrics** (Rate / Error / Duration) and **service graph** edges from trace data — these are also queryable in Mimir/Grafana.

### Logs
The Logback→OTel bridge requires two pieces working together:

1. **`logback-spring.xml`** — adds `OpenTelemetryAppender` to Logback's appender chain
2. **`Demo1Application`** — calls `OpenTelemetryAppender.install(openTelemetry)` once the Spring `ApplicationContext` is ready, wiring the appender to the configured OTel SDK instance

This two-step approach is necessary because Logback initializes before the Spring context, so the appender exists but has no SDK reference until explicitly installed. Log records are exported via OTLP/HTTP to the Collector, which forwards them to Loki's native OTLP endpoint (`/otlp/v1/logs`).

> **Key Loki config**: `allow_structured_metadata: true` is required for OTLP log ingestion — OTel resource and scope attributes arrive as structured metadata, not flat stream labels.

---

## Key Dependencies

```xml
<!-- Auto-instruments HTTP, configures OTel SDK, exports traces+metrics via OTLP -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-opentelemetry</artifactId>
</dependency>

<!-- Logback → OTel SDK bridge (not in Spring Boot BOM, version must be explicit) -->
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-logback-appender-1.0</artifactId>
    <version>2.16.0-alpha</version>
</dependency>
```

---

## Configuration Reference

### `application.properties`

```properties
# Traces
management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:4318/v1/traces
management.tracing.sampling.probability=1.0   # 100% sampling — demo only

# Metrics
management.otlp.metrics.export.url=http://localhost:4318/v1/metrics
management.otlp.metrics.export.step=30s

# Logs
management.opentelemetry.logging.export.otlp.endpoint=http://localhost:4318/v1/logs
```

All three signals target the Collector's OTLP/HTTP port on `localhost:4318`. The Collector then routes them to the appropriate backend using Docker's internal DNS (`tempo`, `mimir`, `loki`).

---

## Notes

- All backend storage is under `/tmp` inside containers — **ephemeral by design**. Data is lost on container restart.
- `sampling.probability=1.0` captures every request. Reduce this in any non-demo environment.
- Grafana is configured with anonymous Admin access — remove `GF_AUTH_ANONYMOUS_*` from `compose.yaml` before any non-local deployment.
- The `otelcol-contrib` image is distroless (no shell), so Docker healthchecks using `wget`/`curl` will not work against it.
