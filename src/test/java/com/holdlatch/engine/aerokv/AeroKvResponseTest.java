package com.holdlatch.engine.aerokv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.holdlatch.engine.aerokv.AeroKvResponse.Type;
import org.junit.jupiter.api.Test;

class AeroKvResponseTest {

    @Test
    void parsesKnownReplies() {
        assertEquals(Type.OK, AeroKvResponse.parse("OK").type());
        assertEquals(Type.PONG, AeroKvResponse.parse("PONG").type());
        assertEquals(Type.CONFLICT, AeroKvResponse.parse("ERR_CONFLICT").type());
        assertEquals(Type.CAPACITY, AeroKvResponse.parse("ERR_CAPACITY").type());
        assertEquals(Type.NOT_FOUND, AeroKvResponse.parse("ERR_NOT_FOUND").type());
        assertEquals(Type.NOT_HELD, AeroKvResponse.parse("ERR_NOT_HELD").type());
        assertEquals(Type.AUTH_FAILED, AeroKvResponse.parse("ERR_AUTH_FAILED").type());
        assertEquals(Type.AUTH_FAILED, AeroKvResponse.parse("ERR_NOT_AUTHENTICATED").type());
    }

    @Test
    void parsesValueWithPayload() {
        AeroKvResponse reply = AeroKvResponse.parse("VALUE, user-42");
        assertEquals(Type.VALUE, reply.type());
        assertEquals("user-42", reply.payload());
        assertNull(AeroKvResponse.parse("OK").payload());
    }

    @Test
    void unknownOrMissingRepliesAreErrors() {
        assertEquals(Type.ERROR, AeroKvResponse.parse("ERR_SYNTAX_ERROR").type());
        assertEquals(Type.ERROR, AeroKvResponse.parse(null).type());
        assertEquals(Type.ERROR, AeroKvResponse.parse("").type());
    }
}
