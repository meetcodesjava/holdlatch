package com.holdlatch;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.holdlatch.engine.aerokv.AeroKvClient;
import com.holdlatch.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class HoldLatchApplicationTest extends AbstractPostgresTest {

    @Autowired
    private AeroKvClient aeroKvClient;

    @Test
    void contextLoadsAndMigratesTheRealSchemaWithoutAeroKvRunning() {
        assertNotNull(aeroKvClient);
    }
}
