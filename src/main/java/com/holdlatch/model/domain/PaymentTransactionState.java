package com.holdlatch.model.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of one payment attempt. SUCCEEDED means the bank took the money;
 * CONFIRMED means we also secured the seats. SUCCEEDED -> REFUND_PENDING is
 * the compensating path when the hold expired before the payment landed.
 */
public enum PaymentTransactionState {
    PENDING, SUCCEEDED, CONFIRMED, FAILED, REFUND_PENDING, REFUNDED;

    public Set<PaymentTransactionState> allowedNext() {
        return switch (this) {
            case PENDING -> EnumSet.of(SUCCEEDED, FAILED);
            case SUCCEEDED -> EnumSet.of(CONFIRMED, REFUND_PENDING);
            case REFUND_PENDING -> EnumSet.of(REFUNDED);
            case CONFIRMED, FAILED, REFUNDED -> EnumSet.noneOf(PaymentTransactionState.class);
        };
    }

    public boolean canTransitionTo(PaymentTransactionState next) {
        return allowedNext().contains(next);
    }
}
