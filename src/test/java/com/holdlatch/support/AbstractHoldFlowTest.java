package com.holdlatch.support;

import com.holdlatch.engine.aerokv.FakeAeroKvServer;
import java.io.IOException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real Postgres plus an in-process AeroKV stand-in, shared by all hold-flow integration tests. */
public abstract class AbstractHoldFlowTest extends AbstractPostgresTest {

    protected static final FakeAeroKvServer AEROKV = startAeroKv();

    private static FakeAeroKvServer startAeroKv() {
        try {
            FakeAeroKvServer server = new FakeAeroKvServer();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.close();
                } catch (IOException ignored) {
                    // exiting anyway
                }
            }));
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start fake AeroKV", e);
        }
    }

    @DynamicPropertySource
    static void aeroKv(DynamicPropertyRegistry registry) {
        registry.add("aerokv.host", () -> "localhost");
        registry.add("aerokv.port", AEROKV::port);
    }
}
