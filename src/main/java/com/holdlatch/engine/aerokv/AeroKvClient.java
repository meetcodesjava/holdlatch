package com.holdlatch.engine.aerokv;

import com.holdlatch.config.AeroKvProperties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.stereotype.Component;

/**
 * Pooled client for AeroKV's text protocol. Every call borrows a connection,
 * does one request/response, and returns it; a connection that fails
 * mid-call is discarded so a broken socket never re-enters the pool.
 */
@Component
public class AeroKvClient {

    public enum HoldOutcome { ACQUIRED, CONFLICT, CAPACITY_EXCEEDED }

    // The wire protocol is comma/pipe/newline delimited and unescaped, so
    // anything outside this set could smuggle in extra commands or fields.
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9._:\\-]{1,200}");

    private final GenericObjectPool<AeroKvConnection> pool;

    public AeroKvClient(AeroKvProperties props) {
        GenericObjectPoolConfig<AeroKvConnection> config = new GenericObjectPoolConfig<>();
        config.setMaxTotal(props.pool().maxTotal());
        config.setMaxIdle(props.pool().maxTotal());
        config.setMinIdle(props.pool().minIdle());
        config.setBlockWhenExhausted(true);
        config.setMaxWait(Duration.ofMillis(props.pool().borrowTimeoutMs()));
        config.setTestOnBorrow(true);
        config.setTestWhileIdle(true);
        config.setTimeBetweenEvictionRuns(Duration.ofSeconds(15));
        config.setMinEvictableIdleDuration(Duration.ofSeconds(30));
        this.pool = new GenericObjectPool<>(new AeroKvConnectionFactory(props), config);
    }

    /** Atomically holds one key for {@code owner}; the hold expires by itself after {@code ttl}. */
    public HoldOutcome hold(String key, String owner, Duration ttl) {
        requireSafe(key, "key");
        requireSafe(owner, "owner");
        return toOutcome(execute("HOLD," + key + "," + owner + "," + positiveMillis(ttl)));
    }

    /** Atomically holds every key or none of them. Keys are sorted here so concurrent callers always lock in the same order. */
    public HoldOutcome multiHold(List<String> keys, String owner, Duration ttl) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("keys must not be empty");
        }
        requireSafe(owner, "owner");
        List<String> sorted = new ArrayList<>(keys);
        for (String key : sorted) {
            requireSafe(key, "key");
        }
        if (new HashSet<>(sorted).size() != sorted.size()) {
            throw new IllegalArgumentException("keys must be unique");
        }
        sorted.sort(String::compareTo);
        return toOutcome(execute("MHOLD," + String.join("|", sorted) + "," + owner + "," + positiveMillis(ttl)));
    }

    /**
     * Releases the key only if it is still held by {@code owner}. Returns false
     * if the hold already expired or now belongs to someone else - in which
     * case it must be left alone, never freed on the new holder's behalf.
     */
    public boolean releaseIfOwner(String key, String owner) {
        requireSafe(key, "key");
        requireSafe(owner, "owner");
        AeroKvResponse reply = execute("RELEASEIF," + key + "," + owner);
        return switch (reply.type()) {
            case OK -> true;
            case NOT_HELD -> false;
            default -> throw new AeroKvUnavailableException("Unexpected reply to RELEASEIF: " + reply);
        };
    }

    public Optional<String> get(String key) {
        requireSafe(key, "key");
        AeroKvResponse reply = execute("GET," + key);
        return switch (reply.type()) {
            case VALUE -> Optional.of(reply.payload());
            case NOT_FOUND -> Optional.empty();
            default -> throw new AeroKvUnavailableException("Unexpected reply to GET: " + reply);
        };
    }

    public boolean ping() {
        try {
            return execute("PING").type() == AeroKvResponse.Type.PONG;
        } catch (AeroKvUnavailableException e) {
            return false;
        }
    }

    @PreDestroy
    public void close() {
        pool.close();
    }

    private AeroKvResponse execute(String command) {
        AeroKvConnection connection;
        try {
            connection = pool.borrowObject();
        } catch (Exception e) {
            throw new AeroKvUnavailableException("Could not obtain an AeroKV connection", e);
        }
        try {
            AeroKvResponse reply = AeroKvResponse.parse(connection.send(command));
            pool.returnObject(connection);
            if (reply.type() == AeroKvResponse.Type.AUTH_FAILED) {
                throw new AeroKvUnavailableException("AeroKV authentication failed");
            }
            return reply;
        } catch (IOException e) {
            invalidate(connection);
            throw new AeroKvUnavailableException("AeroKV request failed: " + e.getMessage(), e);
        }
    }

    private void invalidate(AeroKvConnection connection) {
        try {
            pool.invalidateObject(connection);
        } catch (Exception ignored) {
            connection.close();
        }
    }

    private static HoldOutcome toOutcome(AeroKvResponse reply) {
        return switch (reply.type()) {
            case OK -> HoldOutcome.ACQUIRED;
            case CONFLICT -> HoldOutcome.CONFLICT;
            case CAPACITY -> HoldOutcome.CAPACITY_EXCEEDED;
            default -> throw new AeroKvUnavailableException("Unexpected reply to hold: " + reply);
        };
    }

    private static long positiveMillis(Duration ttl) {
        long millis = ttl.toMillis();
        // AeroKV treats a non-positive TTL as "never expires" - a hold that
        // can never expire would lock a seat forever.
        if (millis <= 0) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        return millis;
    }

    private static void requireSafe(String value, String field) {
        if (value == null || !SAFE_TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " contains characters not allowed on the AeroKV wire");
        }
    }
}
