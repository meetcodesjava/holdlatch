package com.holdlatch.engine.aerokv;

/** A single AeroKV reply line, parsed into a typed result. */
record AeroKvResponse(Type type, String payload) {

    enum Type { OK, CONFLICT, CAPACITY, NOT_FOUND, NOT_HELD, VALUE, PONG, AUTH_FAILED, ERROR }

    private static final String VALUE_PREFIX = "VALUE,";

    static AeroKvResponse parse(String line) {
        String reply = line == null ? "" : line.trim();
        if (reply.startsWith(VALUE_PREFIX)) {
            return new AeroKvResponse(Type.VALUE, reply.substring(VALUE_PREFIX.length()).trim());
        }
        return switch (reply) {
            case "OK" -> new AeroKvResponse(Type.OK, null);
            case "PONG" -> new AeroKvResponse(Type.PONG, null);
            case "ERR_CONFLICT" -> new AeroKvResponse(Type.CONFLICT, null);
            case "ERR_CAPACITY" -> new AeroKvResponse(Type.CAPACITY, null);
            case "ERR_NOT_FOUND" -> new AeroKvResponse(Type.NOT_FOUND, null);
            case "ERR_NOT_HELD" -> new AeroKvResponse(Type.NOT_HELD, null);
            case "ERR_AUTH_FAILED", "ERR_NOT_AUTHENTICATED" -> new AeroKvResponse(Type.AUTH_FAILED, reply);
            default -> new AeroKvResponse(Type.ERROR, reply);
        };
    }
}
