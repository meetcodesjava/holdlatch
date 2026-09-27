package com.holdlatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "holdlatch.mail")
public record MailProperties(
        String host,
        @DefaultValue("587") int port,
        String username,
        String password,
        String from,
        @DefaultValue("true") boolean starttls) {

    /** Email is switched on only once there is somewhere to connect, someone to log in as, and a from-address. */
    public boolean configured() {
        return notBlank(host) && notBlank(username) && notBlank(password) && notBlank(from);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
