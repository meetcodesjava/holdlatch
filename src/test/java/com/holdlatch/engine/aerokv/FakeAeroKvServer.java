package com.holdlatch.engine.aerokv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Minimal in-process stand-in for AeroKV's wire protocol, for client tests. */
final class FakeAeroKvServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final Map<String, String> store = new ConcurrentHashMap<>();
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final String password;
    private volatile boolean running = true;

    FakeAeroKvServer(String password) throws IOException {
        this.serverSocket = new ServerSocket(0);
        this.password = password;
        Thread acceptor = new Thread(this::acceptLoop, "fake-aerokv-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    List<String> received() {
        return received;
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

    private String handle(String command, String rest) {
        switch (command) {
            case "PING":
                return "PONG";
            case "HOLD": {
                String[] f = rest.split(",");
                return store.putIfAbsent(f[0], f[1]) == null ? "OK" : "ERR_CONFLICT";
            }
            case "MHOLD": {
                String[] f = rest.split(",");
                List<String> keys = Arrays.asList(f[0].split("\\|"));
                synchronized (store) {
                    if (keys.stream().anyMatch(store::containsKey)) {
                        return "ERR_CONFLICT";
                    }
                    keys.forEach(k -> store.put(k, f[1]));
                }
                return "OK";
            }
            case "RELEASE":
                store.remove(rest);
                return "OK";
            case "GET": {
                String value = store.get(rest);
                return value == null ? "ERR_NOT_FOUND" : "VALUE, " + value;
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
