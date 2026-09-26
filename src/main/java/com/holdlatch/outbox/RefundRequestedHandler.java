package com.holdlatch.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.holdlatch.model.persistence.OutboxEventRecord;
import com.holdlatch.service.SettlementTransactionService;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Performs the refund that a late or unfulfillable payment is owed. Retried with backoff until the provider accepts it. */
@Component
public class RefundRequestedHandler implements OutboxHandler {

    private final SettlementTransactionService settlement;
    private final ObjectMapper objectMapper;

    RefundRequestedHandler(SettlementTransactionService settlement, ObjectMapper objectMapper) {
        this.settlement = settlement;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return "RefundRequested";
    }

    @Override
    public void handle(OutboxEventRecord event) {
        try {
            UUID paymentId = UUID.fromString(objectMapper.readTree(event.getPayload()).path("paymentTransactionId").asText());
            settlement.executeRefund(paymentId);
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable RefundRequested payload " + event.getId(), e);
        }
    }
}
