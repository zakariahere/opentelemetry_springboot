package com.example.demo;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final Counter helloCounter;
    private final Counter errorCounter;
    private final AtomicInteger activeSlowRequests = new AtomicInteger(0);

    public DemoController(MeterRegistry registry) {
        this.helloCounter = Counter.builder("demo.hello.requests.total")
                .description("Total calls to /hello")
                .register(registry);
        this.errorCounter = Counter.builder("demo.error.requests.total")
                .description("Total calls to /error")
                .register(registry);
        registry.gauge("demo.slow.active", activeSlowRequests);
    }

    /** Fast endpoint — trace span + log line + counter increment */
    @GetMapping("/hello")
    public Map<String, String> hello() {
        helloCounter.increment();
        log.info("Handling /hello request");
        return Map.of("message", "Hello from demo1!", "status", "ok");
    }

    /** Slow endpoint — 1500ms latency so flame graphs show duration */
    @GetMapping("/slow")
    public Map<String, Object> slow() throws InterruptedException {
        activeSlowRequests.incrementAndGet();
        try {
            log.info("Starting slow operation");
            Thread.sleep(1500);
            log.info("Slow operation completed");
            return Map.of("message", "Slow response after 1500ms", "duration_ms", 1500);
        } finally {
            activeSlowRequests.decrementAndGet();
        }
    }

    /** Error endpoint — logs ERROR with stack trace, returns HTTP 500 */
    @GetMapping("/error")
    public ResponseEntity<Map<String, String>> error() {
        errorCounter.increment();
        log.warn("About to simulate an error");
        try {
            throw new IllegalStateException("Simulated error for observability demo");
        } catch (IllegalStateException e) {
            log.error("Caught simulated error", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage(), "status", "error"));
        }
    }
}
