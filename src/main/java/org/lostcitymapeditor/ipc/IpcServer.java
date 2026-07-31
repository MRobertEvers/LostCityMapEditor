package org.lostcitymapeditor.ipc;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class IpcServer {
    private final int port;
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private BufferedReader reader;
    private PrintWriter writer;
    private volatile boolean running;
    private final CommandHandler handler;

    public interface CommandHandler {
        String handleCommand(String command, String[] args);
    }

    public IpcServer(int port, CommandHandler handler) {
        this.port = port;
        this.handler = handler;
    }

    public void start() {
        running = true;
        Thread serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                System.out.println("[IPC] Server listening on port " + port);
                clientSocket = serverSocket.accept();
                System.out.println("[IPC] Client connected");
                reader = new BufferedReader(new InputStreamReader(
                        clientSocket.getInputStream(), StandardCharsets.UTF_8));
                writer = new PrintWriter(new OutputStreamWriter(
                        clientSocket.getOutputStream(), StandardCharsets.UTF_8), true);
                writer.println(IpcProtocol.READY);

                String line;
                while (running && (line = reader.readLine()) != null) {
                    String[] parts = line.split(IpcProtocol.SEP, -1);
                    String cmd = parts[0];
                    String[] args = new String[parts.length - 1];
                    System.arraycopy(parts, 1, args, 0, args.length);
                    try {
                        String response = handler.handleCommand(cmd, args);
                        if (response != null) {
                            writer.println(response);
                        }
                    } catch (Exception e) {
                        System.err.println("[IPC] Error handling command " + cmd + ": " + e.getMessage());
                        writer.println(IpcProtocol.ERR + IpcProtocol.SEP + IpcProtocol.escape(e.getMessage()));
                    }
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println("[IPC] Server error: " + e.getMessage());
                }
            }
        }, "IPC-Server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    public synchronized void sendEvent(String event) {
        if (writer != null) {
            writer.println(event);
        }
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        try { if (clientSocket != null) clientSocket.close(); } catch (IOException ignored) {}
    }

    public boolean isConnected() {
        return clientSocket != null && clientSocket.isConnected() && !clientSocket.isClosed();
    }
}
