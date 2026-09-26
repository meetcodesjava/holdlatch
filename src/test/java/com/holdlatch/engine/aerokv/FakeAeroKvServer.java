package com.holdlatch.engine.aerokv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-process stand-in for AeroKV's wire protocol (with real key expiry), for tests. */
public final class FakeAeroKvServer implements AutoCloseable {

    private record Entry(String value, long expiresAtNanos) {
        boolean live() {
            return expiresAtNanos == Long.MAX_VALUE || System.nanoTime() < expiresAtNanos;
        }
    }

    private final ServerSocket serverSocket;
    private final Map<String, Entry> store = new HashMap<>();
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final String password;
    private volatile boolean running = true;

    public FakeAeroKvServer(String password) throws IOException {
        this.serverSocket = new ServerSocket(0);
        this.password = password;
        Thread acceptor = new Thread(this::acceptLoop, "fake-aerokv-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public FakeAeroKvServer() throws IOException {
        this(null);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public List<String> received() {
        return received;
    }

    /** Live (unexpired) keys starting with prefix - lets tests prove a failed hold leaked nothing. */
    public synchronized long countLiveKeysWithPrefix(String prefix) {
        return store.entrySet().stream().filter(e -> e.getKey().startsWith(prefix) && e.getValue().live()).count();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                Thread.startVirtualThread(() -> serve(socket));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        boolean authenticated = password == null;
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            String line;
            while ((line = in.readLine()) != null) {
                received.add(line);
                String[] parts = line.split(",", 2);
                String command = parts[0].toUpperCase();
                if (command.equals("AUTH")) {
                    authenticated = parts.length > 1 && parts[1].equals(password);
                    out.println(authenticated ? "OK" : "ERR_AUTH_FAILED");
                } else if (!authenticated) {
                    out.println("ERR_NOT_AUTHENTICATED");
                } else {
                    out.println(handle(command, parts.length > 1 ? parts[1] : ""));
                }
            }
        } catch (IOException ignored) {
            // client went away
        }
    }

    private static long expiry(String ttlMillis) {
        long ttl = Long.parseLong(ttlMillis.trim());
        return ttl > 0 ? System.nanoTime() + ttl * 1_000_000L : Long.MAX_VALUE;
    }

    private synchronized String handle(String command, String rest) {
        switch (command) {
            case "PING":
                return "PONG";
            case "HOLD": {
                String[] f = rest.split(",");
                Entry current = store.get(f[0]);
                if (current != null && current.live()) {
                    return "ERR_CONFLICT";
                }
                store.put(f[0], new Entry(f[1], expiry(f[2])));
                return "OK";
            }
            case "MHOLD": {
                String[] f = rest.split(",");
                List<String> keys = Arrays.asList(f[0].split("\\|"));
                for (String key : keys) {
                    Entry current = store.get(key);
                    if (current != null && current.live()) {
                        return "ERR_CONFLICT";
                    }
                }
                long expiresAt = expiry(f[2]);
                keys.forEach(k -> store.put(k, new Entry(f[1], expiresAt)));
                return "OK";
            }
            case "RELEASEIF": {
                String[] f = rest.split(",");
                Entry current = store.get(f[0]);
                if (current != null && current.live() && current.value().equals(f[1])) {
                    store.remove(f[0]);
                    return "OK";
                }
                return "ERR_NOT_HELD";
            }
            case "GET": {
                Entry current = store.get(rest);
                return current == null || !current.live() ? "ERR_NOT_FOUND" : "VALUE, " + current.value();
            }
            default:
                return "ERR_UNKNOWN_COMMAND";
        }
    }

    @Override
    public void close() throws IOException {
        running = false;
        serverSocket.close();
    }
}
