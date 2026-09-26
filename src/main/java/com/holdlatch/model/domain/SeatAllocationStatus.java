package com.holdlatch.model.domain;

/** HELD exists only in AeroKV (with a TTL); the database only ever stores AVAILABLE or BOOKED. */
public enum SeatAllocationStatus { AVAILABLE, HELD, BOOKED }
