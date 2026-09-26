package com.holdlatch.dto;

import com.holdlatch.model.domain.OrderStatus;
import com.holdlatch.model.domain.PaymentTransactionState;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SettlementDtos {

    private SettlementDtos() {}

    public record CheckoutRequest(@NotBlank @Size(max = 16_384) String holdToken) {}

    /** The client passes clientSecret to the payment provider's front-end library to collect the card. */
    public record CheckoutResponse(UUID paymentId, String clientSecret, long amountCents, String currency, Instant holdExpiresAt) {}

    public record PaymentStatusResponse(UUID paymentId, PaymentTransactionState state, UUID orderId, String failureReason) {}

    public record OrderItemView(UUID sectionId, UUID seatId, int quantity, long unitPriceCents) {}

    public record OrderView(UUID id, UUID eventId, OrderStatus status, long totalCents, String currency, Instant createdAt,
                            List<OrderItemView> items) {}
}
