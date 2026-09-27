package com.holdlatch.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stands in when no SMTP credentials are configured. Unlike {@code DisabledPaymentGateway} this
 * does not throw: nobody is waiting on a response, so a missing mail provider should not turn
 * into an outbox event that retries forever. It only logs, same as before email support existed.
 */
public class DisabledEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(DisabledEmailSender.class);

    @Override
    public void send(String to, String subject, String body) {
        log.info("Email not configured, would have sent to {}: {}", to, subject);
    }
}
