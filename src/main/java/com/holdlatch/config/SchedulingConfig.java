package com.holdlatch.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background jobs (outbox delivery, abandoned-checkout sweep). Switched off in tests, which run them by hand. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "holdlatch.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {}
