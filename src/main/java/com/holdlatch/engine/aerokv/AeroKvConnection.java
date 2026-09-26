package com.holdlatch.engine.aerokv;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** One open, request/response TCP connection to AeroKV. Not thread-safe; the pool lends it to one caller at a time. */
final class AeroKvConnection implements Closeable {

    private final Socket socket;
    private final BufferedReader in;
    private final BufferedWriter out;
    // Monotonic clock: unaffected by system clock changes.
    private volatile long lastUsedNanos = System.nanoTime();

    AeroKvConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    String send(String line) throws IOException {
        out.write(line);
        out.write('\n');
        out.flush();
        String reply = in.readLine();
        if (reply == null) {
            throw new IOException("AeroKV closed the connection");
        }
        lastUsedNanos = System.nanoTime();
        return reply;
    }

    long idleNanos() {
        return System.nanoTime() - lastUsedNanos;
    }

    boolean isUsable() {
        return !socket.isClosed() && socket.isConnected() && !socket.isInputShutdown() && !socket.isOutputShutdown();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing useful to do on a failed close
        }
    }
}
