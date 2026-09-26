package com.holdlatch.engine.aerokv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.holdlatch.config.AeroKvProperties;
import com.holdlatch.engine.aerokv.AeroKvClient.HoldOutcome;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class AeroKvClientTest {

    private static final Duration TTL = Duration.ofMinutes(5);

    private static AeroKvProperties props(int port, String password) {
        return new AeroKvProperties("localhost", port, password, 500, 500, new AeroKvProperties.Pool(8, 1, 500));
    }

    @Test
    void holdThenConflictThenReleaseThenHoldAgain() throws Exception {
        try (FakeAeroKvServer server = new FakeAeroKvServer(null)) {
            AeroKvClient client = new AeroKvClient(props(server.port(), null));
            try {
                assertEquals(HoldOutcome.ACQUIRED, client.hold("evt1:A1", "user1", TTL));
                assertEquals(HoldOutcome.CONFLICT, client.hold("evt1:A1", "user2", TTL));
                assertEquals(Optional.of("user1"), client.get("evt1:A1"));

                client.release("evt1:A1");
                assertEquals(Optional.empty(), client.get("evt1:A1"));
                assertEquals(HoldOutcome.ACQUIRED, client.hold("evt1:A1", "user2", TTL));
            } finally {
                client.close();
            }
        }
    }

    @Test
    void multiHoldSendsSortedKeysAndIsAllOrNothing() throws Exception {
        try (FakeAeroKvServer server = new FakeAeroKvServer(null)) {
            AeroKvClient client = new AeroKvClient(props(server.port(), null));
            try {
                assertEquals(HoldOutcome.ACQUIRED, client.multiHold(List.of("S3", "S1", "S2"), "u1", TTL));
                assertTrue(server.received().contains("MHOLD,S1|S2|S3,u1,300000"));

                assertEquals(HoldOutcome.CONFLICT, client.multiHold(List.of("S9", "S2"), "u2", TTL));
                assertEquals(Optional.empty(), client.get("S9"));
            } finally {
                client.close();
            }
        }
    }

    @Test
    void sendsPasswordAndRejectsWrongOne() throws Exception {
        try (FakeAeroKvServer server = new FakeAeroKvServer("s3cret")) {
            AeroKvClient good = new AeroKvClient(props(server.port(), "s3cret"));
            AeroKvClient bad = new AeroKvClient(props(server.port(), "wrong"));
            try {
                assertTrue(good.ping());
                assertFalse(bad.ping());
                assertThrows(AeroKvUnavailableException.class, () -> bad.hold("A1", "u", TTL));
            } finally {
                good.close();
                bad.close();
            }
        }
    }

    @Test
    void reportsUnavailableWhenServerIsDown() throws Exception {
        int deadPort;
        try (FakeAeroKvServer server = new FakeAeroKvServer(null)) {
            deadPort = server.port();
        }
        AeroKvClient client = new AeroKvClient(props(deadPort, null));
        try {
            assertFalse(client.ping());
            assertThrows(AeroKvUnavailableException.class, () -> client.hold("A1", "u", TTL));
        } finally {
            client.close();
        }
    }

    @Test
    void rejectsValuesThatCouldInjectProtocolCommands() throws Exception {
        try (FakeAeroKvServer server = new FakeAeroKvServer(null)) {
            AeroKvClient client = new AeroKvClient(props(server.port(), null));
            try {
                assertThrows(IllegalArgumentException.class, () -> client.hold("A1,B1", "u", TTL));
                assertThrows(IllegalArgumentException.class, () -> client.hold("A1", "u\nDEL,A1", TTL));
                assertThrows(IllegalArgumentException.class, () -> client.multiHold(List.of("A|B"), "u", TTL));
                assertThrows(IllegalArgumentException.class, () -> client.multiHold(List.of("A1", "A1"), "u", TTL));
                assertThrows(IllegalArgumentException.class, () -> client.hold("A1", "u", Duration.ZERO));
                assertTrue(server.received().isEmpty());
            } finally {
                client.close();
            }
        }
    }

    @Test
    void manyConcurrentCallersShareAPoolSafelyAndExactlyOneWinsTheSeat() throws Exception {
        try (FakeAeroKvServer server = new FakeAeroKvServer(null)) {
            AeroKvClient client = new AeroKvClient(props(server.port(), null));
            ExecutorService pool = Executors.newFixedThreadPool(32);
            try {
                AtomicInteger winners = new AtomicInteger();
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < 200; i++) {
                    String user = "user" + i;
                    futures.add(pool.submit(() -> {
                        if (client.hold("HOT", user, TTL) == HoldOutcome.ACQUIRED) {
                            winners.incrementAndGet();
                        }
                    }));
                }
                for (Future<?> f : futures) {
                    f.get();
                }
                assertEquals(1, winners.get());
            } finally {
                pool.shutdownNow();
                client.close();
            }
        }
    }
}
