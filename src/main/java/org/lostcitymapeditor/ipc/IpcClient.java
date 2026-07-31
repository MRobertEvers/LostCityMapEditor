package org.lostcitymapeditor.ipc;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class IpcClient {
    private final String host;
    private final int port;
    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private volatile boolean running;
    private EventHandler eventHandler;

    public interface EventHandler {
        void onEvent(String eventType, String[] args);
    }

    public IpcClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void connect(EventHandler handler) throws IOException {
        this.eventHandler = handler;
        socket = new Socket(host, port);
        reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.UTF_8));
        writer = new PrintWriter(new OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.UTF_8), true);

        running = true;
        Thread readerThread = new Thread(() -> {
            try {
                String line;
                while (running && (line = reader.readLine()) != null) {
                    String[] parts = line.split(IpcProtocol.SEP, -1);
                    String event = parts[0];
                    String[] args = new String[parts.length - 1];
                    System.arraycopy(parts, 1, args, 0, args.length);
                    if (eventHandler != null) {
                        eventHandler.onEvent(event, args);
                    }
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println("[IPC] Client reader error: " + e.getMessage());
                }
            }
        }, "IPC-Client-Reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    public synchronized void send(String command) {
        if (writer != null) {
            writer.println(command);
        }
    }

    public void close() {
        running = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }
}
