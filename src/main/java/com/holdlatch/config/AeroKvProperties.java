package com.holdlatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "aerokv")
public record AeroKvProperties(
        @DefaultValue("localhost") String host,
        @DefaultValue("8080") int port,
        String password,
        @DefaultValue("2000") int connectTimeoutMs,
        @DefaultValue("2000") int readTimeoutMs,
        @DefaultValue Pool pool) {

    public record Pool(
            @DefaultValue("64") int maxTotal,
            @DefaultValue("4") int minIdle,
            @DefaultValue("2000") long borrowTimeoutMs) {}

    public boolean authRequired() {
        return password != null && !password.isBlank();
    }
}
