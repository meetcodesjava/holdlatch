package com.holdlatch.outbox;

import com.holdlatch.model.persistence.OutboxEventRecord;

/** Reacts to one kind of outbox event. Must be safe to run twice: delivery is at-least-once. */
public interface OutboxHandler {

    String eventType();

    void handle(OutboxEventRecord event);
}
