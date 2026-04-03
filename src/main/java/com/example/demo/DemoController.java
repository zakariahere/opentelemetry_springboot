package com.example.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    /** Fast path — the agent auto-instruments this HTTP span */
    @GetMapping("/hello")
    public Map<String, String> hello() {
        log.info("Handling /hello request");
        return Map.of("message", "Hello from demo1!", "status", "ok");
    }

    /** Slow path — 1500ms latency visible in Tempo flame graphs */
    @GetMapping("/slow")
    public Map<String, Object> slow() throws InterruptedException {
        log.info("Starting slow operation");
        Thread.sleep(1500);
        log.info("Slow operation completed");
        return Map.of("message", "Slow response after 1500ms", "duration_ms", 1500);
    }

    /** Error path — ERROR log + stack trace exported via agent log bridge */
    @GetMapping("/error")
    public ResponseEntity<Map<String, String>> error() {
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
