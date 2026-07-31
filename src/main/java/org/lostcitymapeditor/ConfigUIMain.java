package org.lostcitymapeditor;

import org.lostcitymapeditor.Loaders.FileLoader;
import org.lostcitymapeditor.OriginalCode.Pix3D;
import org.lostcitymapeditor.ipc.IpcClient;
import org.lostcitymapeditor.ipc.IpcProtocol;
import org.lostcitymapeditor.ui.ConfigUI;
import org.lostcitymapeditor.ui.EditorController;

import javax.swing.*;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Entry point for the config UI process (macOS dual-process mode).
 * Runs without -XstartOnFirstThread. Uses JavaFX/Swing freely.
 */
public class ConfigUIMain {

    public static void start(String serverPath, int port) throws Exception {
        FileLoader.loadFiles(serverPath);

        Pix3D.loadTextures(serverPath);
        Pix3D.setBrightness(0.8);
        Pix3D.initPool(20);
        Pix3D.init3D(800, 600);

        IpcClient client = new IpcClient("localhost", port);
        CountDownLatch readyLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);

        EditorController controller = new IpcEditorController(client);
        ConfigUI configUI = new ConfigUI(serverPath, controller);
        configUI.setCloseLatch(closeLatch);

        connectWithRetry(client, readyLatch, configUI);

        if (!readyLatch.await(30, TimeUnit.SECONDS)) {
            System.err.println("Timed out waiting for renderer READY");
            client.close();
            return;
        }

        SwingUtilities.invokeAndWait(configUI::show);

        closeLatch.await();
        client.close();
    }

    private static void connectWithRetry(IpcClient client, CountDownLatch readyLatch, ConfigUI configUI)
            throws InterruptedException {
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                client.connect((event, args) -> {
                    if (IpcProtocol.READY.equals(event)) {
                        readyLatch.countDown();
                    } else {
                        configUI.handleIpcEvent(event, args);
                    }
                });
                return;
            } catch (IOException e) {
                Thread.sleep(1000);
            }
        }
        throw new RuntimeException("Could not connect to renderer after 30 attempts");
    }

    private static class IpcEditorController implements EditorController {
        private final IpcClient client;

        IpcEditorController(IpcClient client) {
            this.client = client;
        }

        @Override
        public void setMap(String filename) {
            client.send(IpcProtocol.SET_MAP + IpcProtocol.SEP + filename);
        }

        @Override
        public void setLevel(int level) {
            client.send(IpcProtocol.SET_LEVEL + IpcProtocol.SEP + level);
        }

        @Override
        public void setOverlay(int id) {
            client.send(IpcProtocol.SET_OVERLAY + IpcProtocol.SEP + id);
        }

        @Override
        public void setUnderlay(int id) {
            client.send(IpcProtocol.SET_UNDERLAY + IpcProtocol.SEP + id);
        }

        @Override
        public void setShape(int id) {
            client.send(IpcProtocol.SET_SHAPE + IpcProtocol.SEP + id);
        }

        @Override
        public void setFlag(int id) {
            client.send(IpcProtocol.SET_FLAG + IpcProtocol.SEP + id);
        }

        @Override
        public void setRotation(int degrees) {
            client.send(IpcProtocol.SET_ROTATION + IpcProtocol.SEP + degrees);
        }

        @Override
        public void setHeight(String text) {
            client.send(IpcProtocol.SET_HEIGHT + IpcProtocol.SEP + IpcProtocol.escape(text));
        }

        @Override
        public void setLoc(String name, int shape, int rotation) {
            client.send(IpcProtocol.SET_LOC + IpcProtocol.SEP + name + IpcProtocol.SEP + shape + IpcProtocol.SEP + rotation);
        }

        @Override
        public void setNpc(int id) {
            client.send(IpcProtocol.SET_NPC + IpcProtocol.SEP + id);
        }

        @Override
        public void setObj(int id, int amount) {
            client.send(IpcProtocol.SET_OBJ + IpcProtocol.SEP + id + IpcProtocol.SEP + amount);
        }

        @Override
        public void setDisplay(boolean locs, boolean npcs, boolean objs) {
            client.send(IpcProtocol.SET_DISPLAY + IpcProtocol.SEP +
                    (locs ? 1 : 0) + IpcProtocol.SEP +
                    (npcs ? 1 : 0) + IpcProtocol.SEP +
                    (objs ? 1 : 0));
        }

        @Override
        public void clearLocs() {
            client.send(IpcProtocol.CLEAR_LOCS);
        }

        @Override
        public void clearNpcs() {
            client.send(IpcProtocol.CLEAR_NPCS);
        }

        @Override
        public void clearObjs() {
            client.send(IpcProtocol.CLEAR_OBJS);
        }

        @Override
        public void exportMap(String absolutePath) {
            client.send(IpcProtocol.EXPORT + IpcProtocol.SEP + absolutePath);
        }
    }
}
