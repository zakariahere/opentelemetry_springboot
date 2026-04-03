package com.example.demo;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class Demo1Application {

     static void main(String[] args) {
        SpringApplication.run(Demo1Application.class, args);
    }

    @Bean
    OpenTelemetryAppender openTelemetryAppender(final OpenTelemetry openTelemetry) {
        OpenTelemetryAppender.install(openTelemetry);
        return new OpenTelemetryAppender();
    }

}
