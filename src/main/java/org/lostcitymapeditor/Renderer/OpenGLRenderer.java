package org.lostcitymapeditor.Renderer;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import org.joml.*;
import org.lostcitymapeditor.DataObjects.*;
import org.lostcitymapeditor.OriginalCode.*;
import org.lostcitymapeditor.LostCityMapEditor;
import org.lostcitymapeditor.Loaders.FileLoader;
import org.lostcitymapeditor.Loaders.MapDataLoader;
import org.lostcitymapeditor.Transformers.MapDataTransformer;
import org.lostcitymapeditor.ipc.IpcProtocol;
import org.lostcitymapeditor.ipc.IpcServer;
import org.lostcitymapeditor.ui.ConfigUI;
import org.lostcitymapeditor.ui.EditorController;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.Math;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.lostcitymapeditor.DataObjects.newTriangle.getTriangles;
import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import org.lwjgl.system.MemoryStack;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;

import javax.swing.*;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

public class OpenGLRenderer {
    private static final int LEVELS = 4;
    private static final int REGION_SIZE = 64;
    private long window;
    private int shaderProgram;
    private int vao;
    private int vbo;
    private Camera camera;
    private float deltaTime = 0.0f;
    private float lastFrame = 0.0f;
    private float lastX = 400, lastY = 300;
    private boolean firstMouse = true;
    private List<newTriangle> triangleList;
    private TileData selectedTile = null;

    public static String currentMapFileName = "m50_50.jm2";
    public static Map<String, Integer> underlayMap;
    public static Map<String, Object> overlayMap;
    public static Map<String, Object> npcMap;
    public static Map<String, Object> objMap;
    public static Map<Integer, javafx.scene.image.Image> shapeImages;
    public static int currentLevel = 0;
    private static MapData currentMapData;
    public static World world;
    public static World3D world3D;
    public static OpenGLRenderer renderer;
    private final BlockingQueue<Runnable> glQueue = new LinkedBlockingQueue<>();
    public static int[] distance;

    // Tile-editing state (set by ConfigUI or IPC)
    public static Integer selectedOverlayID = 0;
    public static Integer selectedUnderlayID = 0;
    public static Integer selectedShape = -1;
    public static Integer selectedFlag = -1;
    private int selectedRotation = -1;
    private String heightText = "";

    // Loc placement state
    private String selectedLocName;
    private int selectedLocRotation = 0;
    private int selectedLocShape = 10;

    // NPC/OBJ placement state
    private int selectedNpcId = -1;
    private int selectedObjId = -1;
    private int selectedObjAmount = 1;

    // Display flags
    private boolean displayLocs = true;
    private boolean displayNpcs = true;
    private boolean displayObjs = true;

    private Set<Integer> hoveredTileTriangleIndices = new HashSet<>();
    private final TextureManager textureManager = new TextureManager();
    private VertexDataHandler vertexDataHandler = new VertexDataHandler();
    private static String serverDirectoryPath;
    private CopiedTileData copiedTileData = null;
    private final Deque<MapData> historyStack = new ArrayDeque<>();
    private static final int MAX_HISTORY_SIZE = 30;
    private int isHoveredVBO = -1;

    // Dual-process / IPC state
    private boolean ipcMode = false;
    private int ipcPort = -1;
    private IpcServer ipcServer;
    private boolean fxAvailable = false;
    private ConfigUI configUI;

    private void updateHoveredTriangle(double mouseX, double mouseY) {
        hoveredTileTriangleIndices.clear();

        newTriangle foundTriangle = pickTriangle((int)mouseX, (int)mouseY);
        if (foundTriangle != null) {
            int tileX = foundTriangle.tileData.tileX;
            int tileZ = foundTriangle.tileData.tileZ;
            for (int i = 0; i < triangleList.size(); i++) {
                newTriangle triangle = triangleList.get(i);
                if (triangle.tileData.tileX == tileX && triangle.tileData.tileZ == tileZ) {
                    hoveredTileTriangleIndices.add(i);
                }
            }
        }

        updateHoveredVBO();
    }

    private void updateHoveredVBO() {
        if (vao == 0) return;

        float[] isHovered = new float[triangleList.size() * 3];
        for (int triangleIndex = 0; triangleIndex < triangleList.size(); triangleIndex++) {
            boolean isHoveredTriangle = hoveredTileTriangleIndices.contains(triangleIndex);
            for (int i = 0; i < 3; i++) {
                isHovered[triangleIndex * 3 + i] = isHoveredTriangle ? 1.0f : 0.0f;
            }
        }

        glBindVertexArray(vao);

        if (isHoveredVBO == -1) {
            isHoveredVBO = glGenBuffers();
        }

        glBindBuffer(GL_ARRAY_BUFFER, isHoveredVBO);

        FloatBuffer isHoveredBuffer = BufferUtils.createFloatBuffer(isHovered.length);
        isHoveredBuffer.put(isHovered).flip();
        glBufferData(GL_ARRAY_BUFFER, isHoveredBuffer, GL_DYNAMIC_DRAW);

        glVertexAttribPointer(5, 1, GL_FLOAT, false, Float.BYTES, 0);
        glEnableVertexAttribArray(5);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    public void drawMapLevel() {
        enqueueGLTask(() -> {
            newTriangle.clearCollectedTriangles();
            world = new World(REGION_SIZE, REGION_SIZE);
            world.loadGround(currentMapData);
            world3D = new World3D(world.levelHeightmap, REGION_SIZE, LEVELS, REGION_SIZE);
            world.loadLocations(world3D, currentMapData);
            world.loadNpcs(world3D, currentMapData);
            world.loadObjs(world3D, currentMapData);
            world.build(world3D);
            world3D.draw(currentLevel);
            List<newTriangle> triangleList = getTriangles();
            setTriangles(triangleList);
            setupVertexDataWithTriangles(triangleList);
        });
    }

    private newTriangle pickTriangle(int mouseX, int mouseY) {
        float x = (2.0f * mouseX) / 800 - 1.0f;
        float y = 1.0f - (2.0f * mouseY) / 600;

        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(camera.getZoom()),
                800.0f / 600.0f,
                0.1f,
                15000.0f
        );

        Matrix4f view = camera.getViewMatrix();
        Matrix4f model = new Matrix4f().identity();

        Matrix4f inverse = new Matrix4f();
        projection.mul(view, inverse);
        inverse.mul(model);
        inverse.invert();

        Vector4f nearPointNDC = new Vector4f(x, y, -1.0f, 1.0f);
        Vector4f farPointNDC = new Vector4f(x, y, 1.0f, 1.0f);

        Vector4f nearPointWorld = inverse.transform(nearPointNDC);
        Vector4f farPointWorld = inverse.transform(farPointNDC);

        nearPointWorld.div(nearPointWorld.w);
        farPointWorld.div(farPointWorld.w);

        Vector3f rayOrigin = new Vector3f(nearPointWorld.x, nearPointWorld.y, nearPointWorld.z);
        Vector3f rayDirection = new Vector3f(
                farPointWorld.x - nearPointWorld.x,
                farPointWorld.y - nearPointWorld.y,
                farPointWorld.z - nearPointWorld.z
        ).normalize();

        for (int i = 0; i < triangleList.size(); i++) {
            newTriangle triangle = triangleList.get(i);

            Vector3f vertex0 = new Vector3f(triangle.vertices[0], triangle.vertices[1], triangle.vertices[2]);
            Vector3f vertex1 = new Vector3f(triangle.vertices[3], triangle.vertices[4], triangle.vertices[5]);
            Vector3f vertex2 = new Vector3f(triangle.vertices[6], triangle.vertices[7], triangle.vertices[8]);

            Vector3f edge1 = new Vector3f(vertex1).sub(vertex0);
            Vector3f edge2 = new Vector3f(vertex2).sub(vertex0);

            Vector3f h = new Vector3f(rayDirection).cross(edge2);
            float a = edge1.dot(h);

            if (a > -0.00001 && a < 0.00001)
                continue;

            float f = 1.0f / a;
            Vector3f s = new Vector3f(rayOrigin).sub(vertex0);
            float u = f * s.dot(h);

            if (u < 0.0 || u > 1.0)
                continue;

            Vector3f q = new Vector3f(s).cross(edge1);
            float v = f * rayDirection.dot(q);

            if (v < 0.0 || u + v > 1.0)
                continue;

            float t = f * edge2.dot(q);

            if (t > 0.00001)
            {
                return triangle;
            }
        }

        return null;
    }

    public void drawNewMap(MapData mapData) {
        enqueueGLTask(() -> {
            world = new World(REGION_SIZE, REGION_SIZE);
            world.loadGround(mapData);
            world3D = new World3D(world.levelHeightmap, REGION_SIZE, LEVELS, REGION_SIZE);
            if (displayLocs) {
                world.loadLocations(world3D, mapData);
            }
            if (displayNpcs) {
                world.loadNpcs(world3D, mapData);
            }
            if (displayObjs) {
                world.loadObjs(world3D, mapData);
            }
            world.build(world3D);
            newTriangle.clearCollectedTriangles();
            world3D.draw(currentLevel);
            List<newTriangle> triangleList = getTriangles();
            setTriangles(triangleList);
            setupVertexDataWithTriangles(triangleList);
        });
    }

    public void enqueueGLTask(Runnable task) {
        glQueue.add(task);
    }

    // --- Tile info formatting for ConfigUI / IPC ---

    private String formatTilePosition(TileData tile) {
        return String.format("Position: X=%d, Z=%d Level=%d", tile.x, tile.z, tile.level);
    }

    private String formatTileOverlay(TileData tile) {
        if (tile.overlay == null) return "Overlay ID: None";
        String overlayName = FileLoader.getFloMap().get(tile.overlay.id);
        String text = "Overlay ID: " + tile.overlay.id;
        if (overlayName != null) text += " - " + overlayName;
        return text;
    }

    private String formatTileUnderlay(TileData tile) {
        if (tile.underlay == null) return "Underlay ID: None";
        String underlayName = FileLoader.getFloMap().get(tile.underlay.id);
        String text = "Underlay ID: " + tile.underlay.id;
        if (underlayName != null) text += " - " + underlayName;
        return text;
    }

    private String formatTileHeight(TileData tile) {
        if (tile.perlin && tile.level == 0) return "Height: Perlin Generated";
        return String.format("Height: %d", (tile.height / -8));
    }

    private String formatTileFlag(TileData tile) {
        return "Flag ID: " + (tile.flag != null ? tile.flag : "None");
    }

    private String formatTileShape(TileData tile) {
        return "Shape ID: " + (tile.shape != null ? tile.shape : 0);
    }

    private String formatTileRotation(TileData tile) {
        return "Shape Rotation: " + (tile.rotation != null ? tile.rotation * 90 : 0);
    }

    private String formatTileTexture(TileData tile) {
        return "Texture Name: " + (tile.overlay != null && tile.overlay.texture != null
                ? tile.overlay.texture : "None");
    }

    private String formatLocDetails(TileData tile) {
        if (currentMapData == null) return "";
        List<LocData> locs = currentMapData.getLocData(tile.level, tile.x, tile.z);
        StringBuilder details = new StringBuilder();
        details.append("--------------------\n");
        if (!locs.isEmpty()) {
            for (int i = 0; i < locs.size(); i++) {
                LocData loc = locs.get(i);
                details.append(String.format("Name: %s\nID: %d\nShape: %d\nRotation: %s\n",
                        FileLoader.getLocMap().get(loc.id), loc.id, loc.shape, loc.rotation * 90));
                if (i < locs.size() - 1) details.append("--------------------\n");
            }
        } else {
            details.append("No LocData found for this tile.");
        }
        return details.toString();
    }

    private String formatNpcDetails(TileData tile) {
        if (currentMapData == null) return "";
        List<NpcData> npcs = currentMapData.getNpcData(tile.level, tile.x, tile.z);
        StringBuilder details = new StringBuilder();
        details.append("--------------------\n");
        if (!npcs.isEmpty()) {
            for (int i = 0; i < npcs.size(); i++) {
                NpcData npc = npcs.get(i);
                details.append(String.format("Name: %s\nID: %d\n", FileLoader.getNpcMap().get(npc.id), npc.id));
                if (i < npcs.size() - 1) details.append("--------------------\n");
            }
        } else {
            details.append("No NpcData found for this tile.");
        }
        return details.toString();
    }

    private String formatObjDetails(TileData tile) {
        if (currentMapData == null) return "";
        List<ObjData> objs = currentMapData.getObjData(tile.level, tile.x, tile.z);
        StringBuilder details = new StringBuilder();
        details.append("--------------------\n");
        if (!objs.isEmpty()) {
            for (int i = 0; i < objs.size(); i++) {
                ObjData obj = objs.get(i);
                details.append(String.format("Name: %s\nCount: %d\nID: %d\n",
                        FileLoader.getObjMap().get(obj.id), obj.count, obj.id));
                if (i < objs.size() - 1) details.append("--------------------\n");
            }
        } else {
            details.append("No ObjData found for this tile.");
        }
        return details.toString();
    }

    private void notifyTileSelected() {
        if (selectedTile == null) {
            notifyTileCleared();
            return;
        }
        TileData tile = selectedTile;
        String pos = formatTilePosition(tile);
        String locInfo = formatLocDetails(tile);
        String npcInfo = formatNpcDetails(tile);
        String objInfo = formatObjDetails(tile);

        if (configUI != null) {
            configUI.updateTileDisplay(pos,
                    formatTileOverlay(tile), formatTileUnderlay(tile),
                    formatTileHeight(tile), formatTileFlag(tile),
                    formatTileShape(tile), formatTileRotation(tile),
                    formatTileTexture(tile));
            configUI.updateLocDisplay(pos, locInfo);
            configUI.updateNpcDisplay(pos, npcInfo);
            configUI.updateObjDisplay(pos, objInfo);
        }

        if (ipcServer != null && ipcServer.isConnected()) {
            String overlayId = tile.overlay != null ? String.valueOf(tile.overlay.id) : "none";
            String overlayName = tile.overlay != null
                    ? (FileLoader.getFloMap().get(tile.overlay.id) != null ? FileLoader.getFloMap().get(tile.overlay.id) : "none")
                    : "none";
            String underlayId = tile.underlay != null ? String.valueOf(tile.underlay.id) : "none";
            String underlayName = tile.underlay != null
                    ? (FileLoader.getFloMap().get(tile.underlay.id) != null ? FileLoader.getFloMap().get(tile.underlay.id) : "none")
                    : "none";
            String height = String.valueOf(tile.height);
            String perlin = String.valueOf(tile.perlin && tile.level == 0);
            String flag = tile.flag != null ? String.valueOf(tile.flag) : "none";
            String shape = tile.shape != null ? String.valueOf(tile.shape) : "none";
            String rotation = tile.rotation != null ? String.valueOf(tile.rotation) : "none";
            String texture = (tile.overlay != null && tile.overlay.texture != null)
                    ? tile.overlay.texture : "none";

            ipcServer.sendEvent(String.join(IpcProtocol.SEP,
                    IpcProtocol.TILE,
                    String.valueOf(tile.x), String.valueOf(tile.z), String.valueOf(tile.level),
                    overlayId, overlayName, underlayId, underlayName,
                    height, perlin, flag, shape, rotation, texture,
                    IpcProtocol.escape(locInfo), IpcProtocol.escape(npcInfo), IpcProtocol.escape(objInfo)));
        }
    }

    private void notifyTileCleared() {
        if (configUI != null) {
            configUI.clearInspectors();
        }
        if (ipcServer != null && ipcServer.isConnected()) {
            ipcServer.sendEvent(IpcProtocol.TILE_CLEAR);
        }
    }

    public void run() {
        if (!ipcMode && !isMac()) {
            try {
                SwingUtilities.invokeAndWait(() -> {
                    configUI = new ConfigUI(serverDirectoryPath, createDirectController());
                    configUI.show();
                    fxAvailable = true;
                });
            } catch (Exception e) {
                System.err.println("Failed to create config UI: " + e.getMessage());
                e.printStackTrace();
            }
        }

        init();
        enableTextureRendering();
        setupVertexDataWithTriangles(triangleList);

        if (ipcMode && ipcPort > 0) {
            startIpcServer();
        }

        if (fxAvailable && configUI != null) {
            try {
                java.awt.Dimension screenSize = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
                JFrame frame = configUI.getFrame();
                if (frame != null) {
                    int screenHeight = screenSize.height;
                    int frameX = 0;
                    int frameY = (screenHeight - frame.getHeight()) / 2;
                    frame.setLocation(frameX, frameY);
                    int windowHeight = 600;
                    int windowX = frameX + frame.getWidth();
                    int windowY = (screenHeight - windowHeight) / 2;
                    final int wx = windowX;
                    final int wy = windowY + 10;
                    enqueueGLTask(() -> glfwSetWindowPos(window, wx, wy));
                }
            } catch (Exception ignored) {}
        }

        loop();

        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
        GLFWErrorCallback cb = glfwSetErrorCallback(null);
        if (cb != null) cb.free();
    }

    private void init() {
        GLFWErrorCallback.createPrint(System.err).set();

        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_DEPTH_BITS, 24);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);

        window = glfwCreateWindow(800, 600, "LostCity Map Renderer", NULL, NULL);
        if (window == NULL)
            throw new RuntimeException("Failed to create the GLFW window");

        glfwSetKeyCallback(window, (window, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                glfwSetWindowShouldClose(window, true);
            }

            if ((mods & GLFW_MOD_CONTROL) != 0 && key == GLFW_KEY_Z && action == GLFW_RELEASE) {
                performUndo();
            }

            if ((mods & GLFW_MOD_CONTROL) != 0) {
                if (key == GLFW_KEY_C && action == GLFW_RELEASE) {
                    copyTileDataToMemory();
                } else if (key == GLFW_KEY_V && action == GLFW_RELEASE) {
                    saveStateBeforeChange();
                    pasteTileDataFromMemory();
                    notifyTileSelected();
                }
            }
        });


        glfwSetCursorPosCallback(window, (window, xpos, ypos) -> {
            if (glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_RIGHT) == GLFW_PRESS) {
                if (firstMouse) {
                    lastX = (float) xpos;
                    lastY = (float) ypos;
                    firstMouse = false;
                }

                float xOffset = (float) xpos - lastX;
                float yOffset = lastY - (float) ypos;

                lastX = (float) xpos;
                lastY = (float) ypos;

                camera.processMouseMovement(xOffset, yOffset, true);
            } else {
                firstMouse = true;
            }
        });

        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        glfwSetMouseButtonCallback(window, (window, button, action, mods) -> {
            if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                if (action == GLFW_PRESS) {
                    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                    firstMouse = true;
                } else if (action == GLFW_RELEASE) {
                    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
                }
            }
            if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
                handleLeftClick(mods);
            }
        });
        try (MemoryStack stack = stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);
            glfwGetWindowSize(window, pWidth, pHeight);
            GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            glfwSetWindowPos(
                    window,
                    (vidmode.width() - pWidth.get(0)) / 2,
                    (vidmode.height() - pHeight.get(0)) / 2
            );
        }
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwShowWindow(window);
        glfwFocusWindow(window);
        GL.createCapabilities();
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glClearColor(0.2f, 0.3f, 0.3f, 1.0f);
        camera = new Camera(new Vector3f(4400.0f, -7000.0f, 4000.0f));
        setupShaders();
    }

    private void handleLeftClick(int mods) {
        double[] xpos = new double[1];
        double[] ypos = new double[1];
        glfwGetCursorPos(window, xpos, ypos);

        int mouseX = (int) xpos[0];
        int mouseY = (int) ypos[0];

        boolean ctrlPressed = (mods & GLFW_MOD_CONTROL) != 0;
        boolean OPressed = glfwGetKey(window, GLFW_KEY_O) == GLFW_PRESS;
        boolean LPressed = glfwGetKey(window, GLFW_KEY_L) == GLFW_PRESS;
        boolean NPressed = glfwGetKey(window, GLFW_KEY_N) == GLFW_PRESS;
        newTriangle clickedTriangle = pickTriangle(mouseX, mouseY);

        if (clickedTriangle != null) {
            int tileX = clickedTriangle.tileData.tileX;
            int tileZ = clickedTriangle.tileData.tileZ;

            TileData tile = currentMapData.mapTiles[currentLevel][tileX][tileZ];
            if (tile == null)
                tile = new TileData(currentLevel, tileX, tileZ);
            selectedTile = tile;
        } else {
            Vector2i tileCoords = pickTile(mouseX, mouseY);

            if (tileCoords != null) {
                int tileX = tileCoords.x;
                int tileZ = tileCoords.y;
                TileData tile = currentMapData.mapTiles[currentLevel][tileX][tileZ];
                if (tile == null)
                    tile = new TileData(currentLevel, tileX, tileZ);
                selectedTile = tile;
            } else {
                selectedTile = null;
            }
        }

        notifyTileSelected();

        if (ctrlPressed && selectedTile != null) {
            handleCtrlClick();
        }

        if (LPressed && selectedTile != null) {
            handleLocPlace();
        }

        if (NPressed && selectedTile != null) {
            handleNpcPlace();
        }

        if (OPressed && selectedTile != null) {
            handleObjPlace();
        }
    }

    private void handleCtrlClick() {
        saveStateBeforeChange();
        TileData newTile = new TileData(selectedTile.level, selectedTile.x, selectedTile.z);

        if (selectedOverlayID == null || selectedOverlayID == -1) {
            newTile.overlay = null;
        } else if (selectedOverlayID == 0) {
            newTile.overlay = selectedTile.overlay;
        } else {
            newTile.overlay = new OverlayData(selectedOverlayID);
        }
        if (selectedUnderlayID == null || selectedUnderlayID == -1) {
            newTile.underlay = null;
        } else if (selectedUnderlayID == 0) {
            newTile.underlay = selectedTile.underlay;
        } else {
            newTile.underlay = new UnderlayData(selectedUnderlayID);
        }
        if (heightText != null && !heightText.isEmpty()) {
            try {
                double newHeight = Double.parseDouble(heightText);
                if (newHeight == 0 && currentLevel == 0) {
                    int baseX = 0;
                    int baseY = 0;
                    Pattern fileNamePattern = Pattern.compile("m(\\d+)_(\\d+)\\.jm2");
                    Matcher fileNameMatcher = fileNamePattern.matcher(currentMapFileName);
                    if (fileNameMatcher.find()) {
                        baseX = Integer.parseInt(fileNameMatcher.group(1));
                        baseY = Integer.parseInt(fileNameMatcher.group(2));
                    }
                    int worldX = newTile.x + baseX + 932731;
                    int worldZ = newTile.z + baseY + 556238;
                    newTile.perlin = true;
                    newTile.height = World.perlinNoise(worldX, worldZ) * -8;
                } else {
                    newTile.height = (int) Math.floor(newHeight) * -8;
                }
            } catch (NumberFormatException e) {
                System.err.println("Invalid height value: " + heightText);
            }
        } else {
            if (selectedTile.perlin)
                newTile.perlin = true;
            newTile.height = selectedTile.height;
        }
        if (newTile.overlay != null) {
            if (selectedRotation == -1) {
                newTile.rotation = selectedTile.rotation;
            } else if (selectedRotation == 0) {
                newTile.rotation = null;
            } else if (selectedRotation == 90) {
                newTile.rotation = 1;
            } else if (selectedRotation == 180) {
                newTile.rotation = 2;
            } else if (selectedRotation == 270) {
                newTile.rotation = 3;
            }
            if (selectedShape == -1) {
                newTile.shape = selectedTile.shape;
            } else {
                newTile.shape = selectedShape;
            }
            if (selectedFlag == -1) {
                newTile.flag = selectedTile.flag;
            } else {
                newTile.flag = selectedFlag;
            }
        }
        currentMapData.mapTiles[selectedTile.level][selectedTile.x][selectedTile.z] = newTile;
        drawNewMap(currentMapData);
        selectedTile = newTile;
        notifyTileSelected();
    }

    private void handleLocPlace() {
        String locName = selectedLocName;
        if (locName == null) {
            System.err.println("No model selected.");
            return;
        }
        int locId = -1;
        for (Integer id : FileLoader.getLocMap().keySet()) {
            if (FileLoader.getLocMap().get(id).equals(locName)) {
                locId = id;
                break;
            }
        }
        if (locId == -1) {
            System.out.println("Couldn't find Loc ID for Loc: " + locName);
            return;
        }
        for (LocData loc : currentMapData.getLocData(selectedTile.level, selectedTile.x, selectedTile.z)) {
            if (loc.shape == selectedLocShape) {
                System.out.println("Tile already contains Loc with Locshape: " + selectedLocShape);
                return;
            }
        }
        saveStateBeforeChange();
        LocData newLoc = new LocData(selectedTile.level, selectedTile.x, selectedTile.z, locId, selectedLocShape);
        newLoc.rotation = selectedLocRotation / 90;
        currentMapData.locations.add(newLoc);
        drawNewMap(currentMapData);
        notifyTileSelected();
    }

    private void handleNpcPlace() {
        if (selectedNpcId == -1) {
            System.err.println("No NPC selected.");
            return;
        }
        saveStateBeforeChange();
        NpcData newNpc = new NpcData(selectedTile.level, selectedTile.x, selectedTile.z, selectedNpcId);
        currentMapData.npcs.add(newNpc);
        drawNewMap(currentMapData);
        notifyTileSelected();
    }

    private void handleObjPlace() {
        if (selectedObjId == -1) {
            System.err.println("No OBJ selected.");
            return;
        }
        int amount = Math.max(1, selectedObjAmount);
        saveStateBeforeChange();
        ObjData newObj = new ObjData(selectedTile.level, selectedTile.x, selectedTile.z, selectedObjId, amount);
        currentMapData.objects.add(newObj);
        drawNewMap(currentMapData);
        notifyTileSelected();
    }

    private void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();

            float currentFrame = (float) glfwGetTime();
            deltaTime = currentFrame - lastFrame;
            lastFrame = currentFrame;

            processInput();

            double[] xpos = new double[1];
            double[] ypos = new double[1];
            glfwGetCursorPos(window, xpos, ypos);
            if (glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_RIGHT) != GLFW_PRESS) {
                updateHoveredTriangle(xpos[0], ypos[0]);
            }

            Runnable task;
            while ((task = glQueue.poll()) != null) {
                task.run();
            }

            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glUseProgram(shaderProgram);

            Matrix4f model = new Matrix4f().identity();
            Matrix4f view = camera.getViewMatrix();
            Matrix4f projection = new Matrix4f().perspective(
                    (float) Math.toRadians(camera.getZoom()),
                    800.0f / 600.0f,
                    10f,
                    15000.0f
            );

            int modelLoc = glGetUniformLocation(shaderProgram, "model");
            int viewLoc = glGetUniformLocation(shaderProgram, "view");
            int projectionLoc = glGetUniformLocation(shaderProgram, "projection");

            try (MemoryStack stack = stackPush()) {
                FloatBuffer modelBuffer = stack.mallocFloat(16);
                model.get(modelBuffer);
                glUniformMatrix4fv(modelLoc, false, modelBuffer);

                FloatBuffer viewBuffer = stack.mallocFloat(16);
                view.get(viewBuffer);
                glUniformMatrix4fv(viewLoc, false, viewBuffer);

                FloatBuffer projectionBuffer = stack.mallocFloat(16);
                projection.get(projectionBuffer);
                glUniformMatrix4fv(projectionLoc, false, projectionBuffer);
            }

            Map<Integer, List<Integer>> textureGroups = groupTrianglesByTexture();
            glBindVertexArray(vao);

            int expectedTextureIDLocation = glGetUniformLocation(shaderProgram, "expectedTextureID");

            for (Map.Entry<Integer, List<Integer>> entry : textureGroups.entrySet()) {
                int triangleTexId = entry.getKey();
                List<Integer> triangleIndices = entry.getValue();

                if (triangleTexId < 0) {
                    if (expectedTextureIDLocation != -1) {
                        glUniform1i(expectedTextureIDLocation, -1);
                    }

                    for (int triangleIndex : triangleIndices) {
                        glDrawArrays(GL_TRIANGLES, triangleIndex * 3, 3);
                    }
                    continue;
                }

                Integer openglTexId = org.lostcitymapeditor.Loaders.TextureLoader.textureIdMap.get(triangleTexId);
                if (openglTexId == null) {
                    System.err.println("No OpenGL texture ID found for triangle texture ID: " + triangleTexId);
                    continue;
                }

                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, openglTexId);

                if (expectedTextureIDLocation != -1) {
                    glUniform1i(expectedTextureIDLocation, triangleTexId);
                }

                for (int triangleIndex : triangleIndices) {
                    glDrawArrays(GL_TRIANGLES, triangleIndex * 3, 3);
                }
            }

            glBindVertexArray(0);
            glBindTexture(GL_TEXTURE_2D, 0);

            int[] width = new int[1];
            int[] height = new int[1];
            glfwGetWindowSize(window, width, height);
            glfwSwapBuffers(window);
        }

        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        glDeleteProgram(shaderProgram);
    }

    // --- IPC server ---

    private void startIpcServer() {
        ipcServer = new IpcServer(ipcPort, (cmd, args) -> {
            switch (cmd) {
                case IpcProtocol.SET_MAP -> {
                    if (args.length > 0) {
                        currentMapFileName = args[0];
                        currentMapData = MapDataTransformer.parseJM2File(
                                serverDirectoryPath + "/maps/" + currentMapFileName);
                        historyStack.clear();
                        selectedTile = null;
                        drawNewMap(currentMapData);
                    }
                    return IpcProtocol.OK;
                }
                case IpcProtocol.SET_LEVEL -> {
                    if (args.length > 0) {
                        currentLevel = Integer.parseInt(args[0]);
                        historyStack.clear();
                        if (currentMapData != null) drawMapLevel();
                    }
                    return IpcProtocol.OK;
                }
                case IpcProtocol.SET_OVERLAY -> {
                    if (args.length > 0) selectedOverlayID = Integer.parseInt(args[0]);
                    return IpcProtocol.OK;
                }
                case IpcProtocol.SET_UNDERLAY -> {
                    if (args.length > 0) selectedUnderlayID = Integer.parseInt(args[0]);
                    return IpcProtocol.OK;
                }
                case IpcProtocol.SET_SHAPE -> {
                    if (args.length > 0) selectedShape = Integer.parseInt(args[0]);
                    return IpcProtocol.OK;
                }
                case IpcProtocol.SET_FLAG -> {
                    if (args.length > 0) selectedFlag = Integer.parseInt(args[0]);
                    return IpcProtocol.OK;
                }
                case IpcProtocol.SET_ROTATION -> {
                    if (args.length > 0) selectedRotation = Integer.parseInt(args[0]);
                    return IpcProtocol.OK;
                }
                case IpcProtocol.SET_HEIGHT -> {
                    heightText = (args.length > 0) ? IpcProtocol.unescape(args[0]) : "";
                    return IpcProtocol.OK;
                }
                case IpcProtocol.SET_LOC -> {
                    if (args.length >= 3) {
                        selectedLocName = args[0];
                        selectedLocShape = Integer.parseInt(args[1]);
                        selectedLocRotation = Integer.parseInt(args[2]);
                    }
                    return IpcProtocol.OK;
                }
                case IpcProtocol.SET_NPC -> {
                    if (args.length > 0) selectedNpcId = Integer.parseInt(args[0]);
                    return IpcProtocol.OK;
                }
                case IpcProtocol.SET_OBJ -> {
                    if (args.length >= 2) {
                        selectedObjId = Integer.parseInt(args[0]);
                        selectedObjAmount = Integer.parseInt(args[1]);
                    } else if (args.length == 1) {
                        selectedObjId = Integer.parseInt(args[0]);
                    }
                    return IpcProtocol.OK;
                }
                case IpcProtocol.SET_DISPLAY -> {
                    if (args.length >= 3) {
                        displayLocs = "1".equals(args[0]);
                        displayNpcs = "1".equals(args[1]);
                        displayObjs = "1".equals(args[2]);
                        if (currentMapData != null) drawNewMap(currentMapData);
                    }
                    return IpcProtocol.OK;
                }
                case IpcProtocol.CLEAR_LOCS -> {
                    if (selectedTile != null && currentMapData != null) {
                        saveStateBeforeChange();
                        currentMapData.removeLocData(selectedTile.level, selectedTile.x, selectedTile.z);
                        drawNewMap(currentMapData);
                        notifyTileSelected();
                    }
                    return IpcProtocol.OK;
                }
                case IpcProtocol.CLEAR_NPCS -> {
                    if (selectedTile != null && currentMapData != null) {
                        saveStateBeforeChange();
                        currentMapData.removeNpcData(selectedTile.level, selectedTile.x, selectedTile.z);
                        drawNewMap(currentMapData);
                        notifyTileSelected();
                    }
                    return IpcProtocol.OK;
                }
                case IpcProtocol.CLEAR_OBJS -> {
                    if (selectedTile != null && currentMapData != null) {
                        saveStateBeforeChange();
                        currentMapData.removeObjData(selectedTile.level, selectedTile.x, selectedTile.z);
                        drawNewMap(currentMapData);
                        notifyTileSelected();
                    }
                    return IpcProtocol.OK;
                }
                case IpcProtocol.EXPORT -> {
                    if (args.length > 0 && currentMapData != null) {
                        MapDataTransformer.writeJM2File(currentMapData, args[0]);
                    }
                    return IpcProtocol.OK;
                }
                case IpcProtocol.PING -> {
                    return IpcProtocol.OK;
                }
                default -> {
                    return IpcProtocol.ERR + IpcProtocol.SEP + "Unknown command: " + cmd;
                }
            }
        });
        ipcServer.start();
    }

    // --- Direct EditorController for combined mode ---

    private EditorController createDirectController() {
        return new EditorController() {
            @Override
            public void setMap(String filename) {
                currentMapFileName = filename;
                currentMapData = MapDataTransformer.parseJM2File(
                        serverDirectoryPath + "/maps/" + filename);
                historyStack.clear();
                selectedTile = null;
                drawNewMap(currentMapData);
                notifyTileCleared();
            }

            @Override
            public void setLevel(int level) {
                currentLevel = level;
                historyStack.clear();
                if (currentMapData != null) drawMapLevel();
            }

            @Override
            public void setOverlay(int id) { selectedOverlayID = id; }

            @Override
            public void setUnderlay(int id) { selectedUnderlayID = id; }

            @Override
            public void setShape(int id) { selectedShape = id; }

            @Override
            public void setFlag(int id) { selectedFlag = id; }

            @Override
            public void setRotation(int degrees) { selectedRotation = degrees; }

            @Override
            public void setHeight(String text) { heightText = text != null ? text : ""; }

            @Override
            public void setLoc(String name, int shape, int rotation) {
                selectedLocName = name;
                selectedLocShape = shape;
                selectedLocRotation = rotation;
            }

            @Override
            public void setNpc(int id) { selectedNpcId = id; }

            @Override
            public void setObj(int id, int amount) {
                selectedObjId = id;
                selectedObjAmount = amount;
            }

            @Override
            public void setDisplay(boolean locs, boolean npcs, boolean objs) {
                displayLocs = locs;
                displayNpcs = npcs;
                displayObjs = objs;
                if (currentMapData != null) drawNewMap(currentMapData);
            }

            @Override
            public void clearLocs() {
                if (selectedTile != null && currentMapData != null) {
                    saveStateBeforeChange();
                    currentMapData.removeLocData(selectedTile.level, selectedTile.x, selectedTile.z);
                    drawNewMap(currentMapData);
                    notifyTileSelected();
                }
            }

            @Override
            public void clearNpcs() {
                if (selectedTile != null && currentMapData != null) {
                    saveStateBeforeChange();
                    currentMapData.removeNpcData(selectedTile.level, selectedTile.x, selectedTile.z);
                    drawNewMap(currentMapData);
                    notifyTileSelected();
                }
            }

            @Override
            public void clearObjs() {
                if (selectedTile != null && currentMapData != null) {
                    saveStateBeforeChange();
                    currentMapData.removeObjData(selectedTile.level, selectedTile.x, selectedTile.z);
                    drawNewMap(currentMapData);
                    notifyTileSelected();
                }
            }

            @Override
            public void exportMap(String absolutePath) {
                if (currentMapData != null) {
                    MapDataTransformer.writeJM2File(currentMapData, absolutePath);
                }
            }
        };
    }

    // --- Startup methods ---

    public static void startRender() throws IOException {
        serverDirectoryPath = System.getProperty("server.dir");
        if (serverDirectoryPath == null || serverDirectoryPath.isEmpty()) {
            String[] args = LostCityMapEditor.getLaunchArgs();
            if (args != null && args.length > 0 && args[0] != null && !args[0].isEmpty()) {
                serverDirectoryPath = args[0];
            }
        }
        if (serverDirectoryPath == null || serverDirectoryPath.isEmpty()) {
            serverDirectoryPath = chooseServerDirectory();
            if ((serverDirectoryPath == null || serverDirectoryPath.isEmpty()) && isMac()) {
                serverDirectoryPath = chooseServerDirectoryViaSubprocess();
            }
        }
        if (serverDirectoryPath == null || serverDirectoryPath.isEmpty()) {
            System.err.println("No server directory selected. Exiting. Use -Dserver.dir=/path or: ./gradlew run --args=\"/path/to/server\"");
            return;
        }
        loadDataAndCreateRenderer(serverDirectoryPath, -1, false);
    }

    public static void startRenderOnly(String serverPath, int port) throws IOException {
        serverDirectoryPath = serverPath;
        loadDataAndCreateRenderer(serverPath, port, true);
    }

    private static void loadDataAndCreateRenderer(String serverPath, int port, boolean rendererOnly) throws IOException {
        FileLoader.loadFiles(serverPath);
        underlayMap = FileLoader.getUnderlayMap();
        overlayMap = FileLoader.getOverlayMap();
        shapeImages = FileLoader.getShapeImages();
        npcMap = FileLoader.getAllNpcMap();
        objMap = FileLoader.getAllObjMap();

        currentMapData = MapDataTransformer.parseJM2File(serverPath + "/maps/" + currentMapFileName);

        Pix3D.loadTextures(serverPath);
        Pix3D.setBrightness(0.8);
        Pix3D.initPool(20);
        Pix3D.init3D(800, 600);
        world = new World(REGION_SIZE, REGION_SIZE);
        world.loadGround(currentMapData);
        world3D = new World3D(world.levelHeightmap, REGION_SIZE, LEVELS, REGION_SIZE);
        world.loadLocations(world3D, currentMapData);
        world.loadNpcs(world3D, currentMapData);
        world.loadObjs(world3D, currentMapData);
        world.build(world3D);

        distance = new int[9];
        for (int x = 0; x < 9; x++) {
            int angle = x * 32 + 128 + 15;
            int offset = angle * 3 + 600;
            int sin = Pix3D.sinTable[angle];
            distance[x] = offset * sin >> 16;
        }
        newTriangle.clearCollectedTriangles();
        world3D.draw(currentLevel);
        List<newTriangle> triangleList = getTriangles();

        renderer = new OpenGLRenderer();
        renderer.ipcMode = rendererOnly;
        renderer.ipcPort = port;
        renderer.setTriangles(triangleList);
        renderer.run();
    }

    private void setupShaders() {
        ShaderManager shaderManager = new ShaderManager();
        shaderProgram = shaderManager.createProgram();

        textureManager.initializeTextures(serverDirectoryPath);

        glUseProgram(shaderProgram);

        int currentTextureLocation = glGetUniformLocation(shaderProgram, "currentTexture");
        if (currentTextureLocation != -1) {
            glUniform1i(currentTextureLocation, 0);
        } else {
            System.err.println("ERROR: Could not find uniform 'currentTexture' in shader.");
        }

        glUseProgram(0);
    }

    private void setupVertexDataWithTriangles(List<newTriangle> triangles) {
        int[] vaoAndVbo = {vao, vbo};
        vertexDataHandler.setupVertexDataWithTriangles(triangles, vaoAndVbo);
        vao = vaoAndVbo[0];
        vbo = vaoAndVbo[1];
    }

    private void processInput() {
        camera.processKeyboardInput(window, deltaTime);
    }

    public void setTriangles(List<newTriangle> triangles) {
        this.triangleList = triangles;
    }

    private Map<Integer, List<Integer>> groupTrianglesByTexture() {
        Map<Integer, List<Integer>> groups = new HashMap<>();

        for (int i = 0; i < triangleList.size(); i++) {
            int texId = triangleList.get(i).textureId;

            if (!groups.containsKey(texId)) {
                groups.put(texId, new ArrayList<>());
            }

            groups.get(texId).add(i);
        }

        return groups;
    }

    private void enableTextureRendering() {
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    }

    private Vector2i pickTile(int mouseX, int mouseY) {
        float x = (2.0f * mouseX) / 800 - 1.0f;
        float y = 1.0f - (2.0f * mouseY) / 600;

        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(camera.getZoom()),
                800.0f / 600.0f,
                0.1f,
                15000.0f
        );

        Matrix4f view = camera.getViewMatrix();
        Matrix4f model = new Matrix4f().identity();

        Matrix4f inverse = new Matrix4f();
        projection.mul(view, inverse);
        inverse.mul(model);
        inverse.invert();

        Vector4f nearPointNDC = new Vector4f(x, y, -1.0f, 1.0f);
        Vector4f farPointNDC = new Vector4f(x, y, 1.0f, 1.0f);

        Vector4f nearPointWorld = inverse.transform(nearPointNDC);
        Vector4f farPointWorld = inverse.transform(farPointNDC);

        nearPointWorld.div(nearPointWorld.w);
        farPointWorld.div(farPointWorld.w);

        Vector3f rayOrigin = new Vector3f(nearPointWorld.x, nearPointWorld.y, nearPointWorld.z);
        Vector3f rayDirection = new Vector3f(
                farPointWorld.x - nearPointWorld.x,
                farPointWorld.y - nearPointWorld.y,
                farPointWorld.z - nearPointWorld.z
        ).normalize();

        float t = -rayOrigin.y / rayDirection.y;

        if (t >= 0) {
            float intersectionX = rayOrigin.x + t * rayDirection.x;
            float intersectionZ = rayOrigin.z + t * rayDirection.z;

            int tileX = (int) Math.floor(intersectionX / (REGION_SIZE * 2));
            int tileZ = (int) Math.floor(intersectionZ / (REGION_SIZE * 2));

            if (tileX > REGION_SIZE - 1 || tileZ > REGION_SIZE - 1 || tileZ < 0 || tileX < 0)
                return null;

            return new Vector2i(tileX, tileZ);
        } else {
            return null;
        }
    }

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
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
                ProcessBuilder pb = new ProcessBuilder(
                    javaBin,
                    "-cp", cp,
                    "org.lostcitymapeditor.DirectoryChooserMain"
                );
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
        System.err.println("On macOS, provide the server path: ./gradlew run --args=\"/path/to/server\" or -Dserver.dir=/path");
        return null;
    }

    public static String chooseServerDirectory() {
        final String[] resultPath = new String[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                javax.swing.JFileChooser fileChooser = new javax.swing.JFileChooser();
                fileChooser.setDialogTitle("Select Server Data Source Directory");
                fileChooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);

                javax.swing.JOptionPane.showMessageDialog(null,
                        "Please select the root directory containing your server's 'pack' and 'maps' folders.",
                        "Directory Selection",
                        javax.swing.JOptionPane.INFORMATION_MESSAGE);

                int result = fileChooser.showDialog(null, "Select Directory");

                if (result == javax.swing.JFileChooser.APPROVE_OPTION) {
                    resultPath[0] = fileChooser.getSelectedFile().getAbsolutePath();
                } else {
                    resultPath[0] = null;
                }
            });
        } catch (Exception e) {
            System.err.println("Failed to show directory chooser: " + e.getMessage());
            return null;
        }
        return resultPath[0];
    }

    private void copyTileDataToMemory() {
        if (selectedTile == null || currentMapData == null) {
            copiedTileData = null;
            return;
        }
        List<LocData> locsOnTile = currentMapData.getLocData(selectedTile.level, selectedTile.x, selectedTile.z);
        List<NpcData> npcsOnTile = currentMapData.getNpcData(selectedTile.level, selectedTile.x, selectedTile.z);
        List<ObjData> objsOnTile = currentMapData.getObjData(selectedTile.level, selectedTile.x, selectedTile.z);

        copiedTileData = new CopiedTileData(selectedTile, locsOnTile, npcsOnTile, objsOnTile);

        System.out.println("Tile data copied");
    }

    private void pasteTileDataFromMemory() {
        if (copiedTileData == null) {
            System.out.println("Paste Error: No tile data copied to memory (use Ctrl+C).");
            return;
        }
        if (selectedTile == null) {
            System.out.println("Paste Error: No target tile selected.");
            return;
        }

        int targetLevel = selectedTile.level;
        int targetX = selectedTile.x;
        int targetZ = selectedTile.z;

        TileData newTargetTile = new TileData(targetLevel, targetX, targetZ);
        newTargetTile.height = copiedTileData.height;
        newTargetTile.perlin = copiedTileData.perlin;
        newTargetTile.overlay = copiedTileData.overlayId != null ? new OverlayData(copiedTileData.overlayId) : null;
        newTargetTile.underlay = copiedTileData.underlayId != null ? new UnderlayData(copiedTileData.underlayId) : null;
        newTargetTile.shape = copiedTileData.shape;
        newTargetTile.rotation = copiedTileData.rotation;
        newTargetTile.flag = copiedTileData.flag;

        currentMapData.mapTiles[targetLevel][targetX][targetZ] = newTargetTile;

        currentMapData.locations.removeIf(loc -> loc.level == targetLevel && loc.x == targetX && loc.z == targetZ);
        for (LocData copiedLoc : copiedTileData.locs) {
            LocData newLoc = new LocData(targetLevel, targetX, targetZ, copiedLoc.id, copiedLoc.shape);
            if (copiedLoc.rotation != null) {
                newLoc.rotation = copiedLoc.rotation;
            } else {
                newLoc.rotation = 0;
            }
            currentMapData.locations.add(newLoc);
        }

        currentMapData.npcs.removeIf(npc -> npc.level == targetLevel && npc.x == targetX && npc.z == targetZ);
        for (NpcData copiedNpc : copiedTileData.npcs) {
            NpcData newNpc = new NpcData(targetLevel, targetX, targetZ, copiedNpc.id);
            currentMapData.npcs.add(newNpc);
        }

        currentMapData.objects.removeIf(obj -> obj.level == targetLevel && obj.x == targetX && obj.z == targetZ);
        for (ObjData copiedObj : copiedTileData.objs) {
            ObjData newObj = new ObjData(targetLevel, targetX, targetZ, copiedObj.id, copiedObj.count);
            currentMapData.objects.add(newObj);
        }

        drawNewMap(currentMapData);
        selectedTile = newTargetTile;
    }

    private void saveStateBeforeChange() {
        if (currentMapData == null) return;

        MapData stateToSave = currentMapData.deepCopy();
        historyStack.push(stateToSave);

        if (historyStack.size() > MAX_HISTORY_SIZE) {
            historyStack.removeLast();
        }
    }

    private void performUndo() {
        if (historyStack.isEmpty()) {
            System.out.println("Nothing to undo.");
            return;
        }
        currentMapData = historyStack.pop();
        drawNewMap(currentMapData);
        selectedTile = null;
        notifyTileCleared();
    }

    public static String getServerDirectoryPath() {
        return serverDirectoryPath;
    }
}
