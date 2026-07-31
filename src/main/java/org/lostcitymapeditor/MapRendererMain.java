package org.lostcitymapeditor;

import org.lostcitymapeditor.Renderer.OpenGLRenderer;

import java.io.IOException;

/**
 * Entry point for the renderer child process (macOS dual-process mode).
 * Runs with -XstartOnFirstThread. Never touches AWT/JavaFX/Swing.
 * args: serverPath port
 */
public class MapRendererMain {

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: MapRendererMain <serverPath> <port>");
            System.exit(1);
        }
        String serverPath = args[0];
        int port;
        try {
            port = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("Invalid port: " + args[1]);
            System.exit(1);
            return;
        }
        LostCityMapEditor.setLaunchArgs(args);
        OpenGLRenderer.startRenderOnly(serverPath, port);
    }
}
