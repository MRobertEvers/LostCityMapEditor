package org.lostcitymapeditor;

import org.lostcitymapeditor.Renderer.OpenGLRenderer;

import java.io.IOException;

public class LostCityMapEditor {

    private static String[] launchArgs;

    public static String[] getLaunchArgs() {
        return launchArgs;
    }

    public static void main(String[] args) throws IOException {
        launchArgs = args;
        OpenGLRenderer.startRender();
    }
}