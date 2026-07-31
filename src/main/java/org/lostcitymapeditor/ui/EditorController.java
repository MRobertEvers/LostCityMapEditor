package org.lostcitymapeditor.ui;

public interface EditorController {
    void setMap(String filename);
    void setLevel(int level);
    void setOverlay(int id);
    void setUnderlay(int id);
    void setShape(int id);
    void setFlag(int id);
    void setRotation(int degrees);
    void setHeight(String text);
    void setLoc(String name, int shape, int rotation);
    void setNpc(int id);
    void setObj(int id, int amount);
    void setDisplay(boolean locs, boolean npcs, boolean objs);
    void clearLocs();
    void clearNpcs();
    void clearObjs();
    void exportMap(String absolutePath);
}
