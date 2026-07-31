# Lost City Map Editor

## Prerequisites

- **Java 21+** (JDK)
- **JavaFX** and **LWJGL** — the Gradle build downloads these automatically from Maven. If the project does not build or run, you can install them manually:

### JavaFX (if needed)

1. Download the JavaFX SDK for your OS: https://openjfx.io/
2. Unzip and set the path to the `lib` directory, e.g.:
   - macOS/Linux: `export PATH_TO_FX=/path/to/javafx-sdk-21/lib`
   - Windows: `set PATH_TO_FX=C:\path\to\javafx-sdk-21\lib`

### LWJGL (if needed)

1. Download from: https://www.lwjgl.org/download  
   Or get releases (including natives) from: https://github.com/LWJGL/lwjgl3/releases
2. Use the **Customize** build to pick your platform and get the required JARs and native libraries.

## Build and run

```bash
./gradlew build
./gradlew run
```

On first run you will be asked to select the **Server Data Source Directory** (game data with `pack/` and `maps/`).

### macOS

On macOS the app must run with the main thread as the first thread (for GLFW). To avoid relying on the directory chooser (which may not appear), pass the server path explicitly:

```bash
./gradlew run --args="/full/path/to/your/server"
```

Example: `./gradlew run --args="/Users/you/Documents/LostCity_Server/content"`

If the config or map window does not appear, try **Cmd+Tab** to switch to the app and bring it to the front.

---

## Controls

W,A,S,D to move camera.

Q,E to zoom in and out.

Right click to rotate.

Left click on tile to see information.

Control + left click tile to update the tile.

L + left click to place selected loc.

O + left click to place selected object.

N + left click to place selected npc.

Ctrl + C to copy selected tile.

Ctrl + V to paste copied tile on current selected tile.

Ctrl + Z to undo

For height values, 0 is perlin noise generated. 1 is floor height.

```
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
./gradlew run --args="/Users/matthewevers/Documents/git_repos/LostCity_Server/content"
```
