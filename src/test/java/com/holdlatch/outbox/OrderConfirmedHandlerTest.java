package com.holdlatch.outbox;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.holdlatch.email.EmailSender;
import com.holdlatch.model.domain.UserRole;
import com.holdlatch.model.persistence.OutboxEventRecord;
import com.holdlatch.model.persistence.UserRecord;
import com.holdlatch.repository.PersistentUserRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderConfirmedHandlerTest {

    @Mock private PersistentUserRepository users;
    @Mock private EmailSender emailSender;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OrderConfirmedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OrderConfirmedHandler(users, emailSender, objectMapper);
    }

    private OutboxEventRecord event(UUID orderId, UUID userId, long totalCents, String currency) {
        String payload = writeJson(Map.of("orderId", orderId, "userId", userId, "eventId", UUID.randomUUID(),
                "totalCents", totalCents, "currency", currency));
        return new OutboxEventRecord("Order", orderId.toString(), "OrderConfirmed", payload, Instant.now());
    }

    private String writeJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void emailsTheBuyerWithTheOrderTotal() {
        UUID userId = UUID.randomUUID();
        UserRecord user = new UserRecord("buyer@example.com", "hash", "Buyer", UserRole.CUSTOMER, Instant.now());
        when(users.findById(userId)).thenReturn(Optional.of(user));

        handler.handle(event(UUID.randomUUID(), userId, 2500, "USD"));

        verify(emailSender).send(eq("buyer@example.com"), contains("confirmed"), contains("25.00 USD"));
    }

    @Test
    void skipsSilentlyWhenTheUserNoLongerExists() {
        UUID userId = UUID.randomUUID();
        when(users.findById(userId)).thenReturn(Optional.empty());

        handler.handle(event(UUID.randomUUID(), userId, 2500, "USD"));

        verify(emailSender, never()).send(any(), any(), any());
    }

    @Test
    void anUnreadablePayloadIsRetriedNotSwallowed() {
        OutboxEventRecord bad = new OutboxEventRecord("Order", "x", "OrderConfirmed", "not json", Instant.now());
        assertThrows(IllegalStateException.class, () -> handler.handle(bad));
    }
}
