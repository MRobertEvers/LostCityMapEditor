package org.lostcitymapeditor;

import org.lostcitymapeditor.Renderer.OpenGLRenderer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class LostCityMapEditor {

    private static String[] launchArgs;

    public static String[] getLaunchArgs() {
        return launchArgs;
    }

    public static void setLaunchArgs(String[] args) {
        launchArgs = args;
    }

    public static void main(String[] args) throws Exception {
        launchArgs = args;

        boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        boolean forceDual = Boolean.getBoolean("editor.dual");

        if (isMac || forceDual) {
            launchDualProcess(args);
        } else {
            OpenGLRenderer.startRender();
        }
    }

    private static void launchDualProcess(String[] args) throws Exception {
        String serverPath = System.getProperty("server.dir");
        if ((serverPath == null || serverPath.isEmpty()) && args.length > 0
                && args[0] != null && !args[0].isEmpty()) {
            serverPath = args[0];
        }
        if (serverPath == null || serverPath.isEmpty()) {
            serverPath = chooseServerDirectoryViaSubprocess();
        }
        if (serverPath == null || serverPath.isEmpty()) {
            System.err.println("No server directory selected. Use -Dserver.dir=/path or: ./gradlew run --args=\"/path\"");
            return;
        }

        int port = findFreePort();

        Process rendererProcess = spawnRendererProcess(serverPath, port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (rendererProcess.isAlive()) rendererProcess.destroyForcibly();
        }));

        try {
            ConfigUIMain.start(serverPath, port);
        } finally {
            if (rendererProcess.isAlive()) {
                rendererProcess.destroyForcibly();
            }
            rendererProcess.waitFor();
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private static Process spawnRendererProcess(String serverPath, int port) throws IOException {
        String javaBin = System.getProperty("java.home") + "/bin/java";

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);

        cmd.add("-XstartOnFirstThread");

        String javaLibPath = System.getProperty("java.library.path");
        if (javaLibPath != null) cmd.add("-Djava.library.path=" + javaLibPath);
        String lwjglLibPath = System.getProperty("org.lwjgl.librarypath");
        if (lwjglLibPath != null) cmd.add("-Dorg.lwjgl.librarypath=" + lwjglLibPath);

        String modulePath = System.getProperty("jdk.module.path");
        String classPath = System.getProperty("java.class.path");

        if (modulePath != null && !modulePath.isEmpty()) {
            cmd.add("--module-path");
            cmd.add(modulePath);
            // Resources live in a separate module-path entry; patch them into our module.
            String resourcesDir = findResourcesDir(modulePath);
            if (resourcesDir != null) {
                cmd.add("--patch-module");
                cmd.add("org.lostcitymapeditor=" + resourcesDir);
            }
            cmd.add("--add-modules");
            cmd.add("org.lostcitymapeditor");
            cmd.add("--add-opens");
            cmd.add("javafx.graphics/javafx.scene.effect=com.gluonhq.attach.util");
            cmd.add("--add-exports");
            cmd.add("javafx.graphics/javafx.scene.effect=ALL-UNNAMED");
            cmd.add("-m");
            cmd.add("org.lostcitymapeditor/org.lostcitymapeditor.MapRendererMain");
        } else if (classPath != null && !classPath.isEmpty()) {
            cmd.add("-cp");
            cmd.add(classPath);
            cmd.add("org.lostcitymapeditor.MapRendererMain");
        } else {
            throw new IOException("Cannot determine module-path or classpath for child process");
        }

        cmd.add(serverPath);
        cmd.add(String.valueOf(port));

        System.out.println("[Launcher] Spawning renderer: " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        return pb.start();
    }

    private static String findResourcesDir(String modulePath) {
        String sep = System.getProperty("path.separator");
        for (String entry : modulePath.split(sep.isEmpty() ? ":" : java.util.regex.Pattern.quote(sep))) {
            if (entry.endsWith("resources/main") || entry.endsWith("resources\\main")
                    || entry.contains("/resources/main") || entry.contains("\\resources\\main")) {
                return entry;
            }
        }
        // Fallback: sibling of classes/java/main
        for (String entry : modulePath.split(sep.isEmpty() ? ":" : java.util.regex.Pattern.quote(sep))) {
            if (entry.contains("classes/java/main") || entry.contains("classes\\java\\main")) {
                java.io.File candidate = new java.io.File(new java.io.File(entry).getParentFile().getParentFile().getParentFile(),
                        "resources/main");
                if (candidate.isDirectory()) return candidate.getAbsolutePath();
            }
        }
        return null;
    }

    private static String chooseServerDirectoryViaSubprocess() {
        String scriptPath = System.getProperty("server.chooser.script");
        if (scriptPath != null && !scriptPath.isEmpty()) {
            try {
                ProcessBuilder pb = new ProcessBuilder("sh", scriptPath);
                pb.redirectErrorStream(false);
                Process p = pb.start();
                String path;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    path = reader.readLine();
                }
                if (p.waitFor() == 0 && path != null && !path.isEmpty()) {
                    return path;
                }
            } catch (Exception e) {
                System.err.println("Could not run directory chooser script: " + e.getMessage());
            }
        }
        String cp = System.getProperty("java.class.path");
        if (cp != null && !cp.isEmpty()) {
            String javaBin = System.getProperty("java.home") + "/bin/java";
            try {
                ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", cp,
                        "org.lostcitymapeditor.DirectoryChooserMain");
                pb.redirectErrorStream(false);
                Process p = pb.start();
                String path;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    path = reader.readLine();
                }
                if (p.waitFor() == 0 && path != null && !path.isEmpty()) {
                    return path;
                }
            } catch (Exception e) {
                System.err.println("Could not run directory chooser: " + e.getMessage());
            }
        }
        System.err.println("On macOS, provide the server path: ./gradlew run --args=\"/path/to/server\"");
        return null;
    }
}
