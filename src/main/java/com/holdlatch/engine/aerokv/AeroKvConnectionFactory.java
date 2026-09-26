package com.holdlatch.engine.aerokv;

import com.holdlatch.config.AeroKvProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;

/** Creates, health-checks and destroys pooled AeroKV connections. */
final class AeroKvConnectionFactory extends BasePooledObjectFactory<AeroKvConnection> {

    // AeroKV drops idle sockets after 60s; only pay for a PING round trip
    // when a connection has sat unused long enough to plausibly be dead.
    private static final long REVALIDATE_AFTER_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final AeroKvProperties props;

    AeroKvConnectionFactory(AeroKvProperties props) {
        this.props = props;
    }

    @Override
    public AeroKvConnection create() throws IOException {
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(props.readTimeoutMs());
            socket.connect(new InetSocketAddress(props.host(), props.port()), props.connectTimeoutMs());
            AeroKvConnection connection = new AeroKvConnection(socket);
            if (props.authRequired()) {
                AeroKvResponse reply = AeroKvResponse.parse(connection.send("AUTH," + props.password()));
                if (reply.type() != AeroKvResponse.Type.OK) {
                    connection.close();
                    throw new IOException("AeroKV rejected the configured password");
                }
            }
            return connection;
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // already failing; the original error is the one that matters
            }
            throw e;
        }
    }

    @Override
    public PooledObject<AeroKvConnection> wrap(AeroKvConnection connection) {
        return new DefaultPooledObject<>(connection);
    }

    @Override
    public boolean validateObject(PooledObject<AeroKvConnection> pooled) {
        AeroKvConnection connection = pooled.getObject();
        if (!connection.isUsable()) {
            return false;
        }
        if (connection.idleNanos() < REVALIDATE_AFTER_NANOS) {
            return true;
        }
        try {
            return AeroKvResponse.parse(connection.send("PING")).type() == AeroKvResponse.Type.PONG;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void destroyObject(PooledObject<AeroKvConnection> pooled) {
        pooled.getObject().close();
    }
}
