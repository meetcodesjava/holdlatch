package com.holdlatch.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.holdlatch.email.EmailSender;
import com.holdlatch.model.persistence.OutboxEventRecord;
import com.holdlatch.model.persistence.UserRecord;
import com.holdlatch.repository.PersistentUserRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends the "your booking is confirmed" email. The event is delivered at-least-once by the
 * outbox worker, so if SMTP is briefly down this throws and the same event is retried with
 * backoff; if email just isn't configured, {@code EmailSender} itself swallows it (see
 * {@code DisabledEmailSender}) so there is nothing to retry.
 */
@Component
public class OrderConfirmedHandler implements OutboxHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmedHandler.class);

    private final PersistentUserRepository users;
    private final EmailSender emailSender;
    private final ObjectMapper objectMapper;

    OrderConfirmedHandler(PersistentUserRepository users, EmailSender emailSender, ObjectMapper objectMapper) {
        this.users = users;
        this.emailSender = emailSender;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return "OrderConfirmed";
    }

    @Override
    public void handle(OutboxEventRecord event) {
        log.info("Order {} confirmed: {}", event.getAggregateId(), event.getPayload());

        JsonNode payload;
        try {
            payload = objectMapper.readTree(event.getPayload());
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable OrderConfirmed payload " + event.getId(), e);
        }

        UUID userId = UUID.fromString(payload.path("userId").asText());
        UserRecord user = users.findById(userId).orElse(null);
        if (user == null) {
            log.warn("OrderConfirmed event {} refers to a user {} that no longer exists, skipping email", event.getId(), userId);
            return;
        }

        long totalCents = payload.path("totalCents").asLong();
        String currency = payload.path("currency").asText();
        String amount = BigDecimal.valueOf(totalCents, 2).setScale(2, RoundingMode.UNNECESSARY) + " " + currency;

        emailSender.send(user.getEmail(), "Your HoldLatch order is confirmed",
                "Hi " + user.getDisplayName() + ",\n\n"
                        + "Your order " + event.getAggregateId() + " is confirmed. Total: " + amount + ".\n\n"
                        + "See you at the event!");
    }
}
