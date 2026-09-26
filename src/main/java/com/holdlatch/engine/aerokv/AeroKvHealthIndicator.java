package com.holdlatch.engine.aerokv;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("aerokv")
public class AeroKvHealthIndicator implements HealthIndicator {

    private final AeroKvClient client;

    public AeroKvHealthIndicator(AeroKvClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        return client.ping() ? Health.up().build() : Health.down().withDetail("reason", "AeroKV did not answer PING").build();
    }
}
