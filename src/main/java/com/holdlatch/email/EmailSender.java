package com.holdlatch.email;

/**
 * Everything HoldLatch needs to send a notification email, kept provider-neutral
 * (mirrors {@code PaymentGateway}) so callers don't care whether it is SMTP, a
 * transactional-email API, or nothing at all.
 */
public interface EmailSender {

    /** Sends a plain-text email. Implementations that can't deliver should throw, so the outbox worker retries. */
    void send(String to, String subject, String body);
}
