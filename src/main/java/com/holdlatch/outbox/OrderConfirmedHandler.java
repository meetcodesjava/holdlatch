package com.holdlatch.outbox;

import com.holdlatch.model.persistence.OutboxEventRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Where "your booking is confirmed" notifications would be sent (email, SMS,
 * a message bus). There is no mail provider wired in yet, so today this only
 * logs; the point is that the event is delivered reliably and exactly this
 * class is the single place to extend.
 */
@Component
public class OrderConfirmedHandler implements OutboxHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmedHandler.class);

    @Override
    public String eventType() {
        return "OrderConfirmed";
    }

    @Override
    public void handle(OutboxEventRecord event) {
        log.info("Order {} confirmed: {}", event.getAggregateId(), event.getPayload());
    }
}
