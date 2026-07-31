module org.lostcitymapeditor {
    requires javafx.controls;

    requires java.desktop;
    requires javafx.swing;
    requires org.joml;
    requires org.lwjgl.glfw;
    requires org.lwjgl.opengl;

    exports org.lostcitymapeditor.Renderer to javafx.graphics;

    exports org.lostcitymapeditor;
    exports org.lostcitymapeditor.ipc;
    exports org.lostcitymapeditor.ui;

    opens org.lostcitymapeditor.ui to javafx.graphics;
}
