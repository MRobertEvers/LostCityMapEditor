package org.lostcitymapeditor.ipc;

public final class IpcProtocol {

    private IpcProtocol() {}

    // Client → Server commands
    public static final String SET_MAP = "SET_MAP";
    public static final String SET_LEVEL = "SET_LEVEL";
    public static final String SET_OVERLAY = "SET_OVERLAY";
    public static final String SET_UNDERLAY = "SET_UNDERLAY";
    public static final String SET_SHAPE = "SET_SHAPE";
    public static final String SET_FLAG = "SET_FLAG";
    public static final String SET_ROTATION = "SET_ROTATION";
    public static final String SET_HEIGHT = "SET_HEIGHT";
    public static final String SET_LOC = "SET_LOC";
    public static final String SET_NPC = "SET_NPC";
    public static final String SET_OBJ = "SET_OBJ";
    public static final String SET_DISPLAY = "SET_DISPLAY";
    public static final String CLEAR_LOCS = "CLEAR_LOCS";
    public static final String CLEAR_NPCS = "CLEAR_NPCS";
    public static final String CLEAR_OBJS = "CLEAR_OBJS";
    public static final String EXPORT = "EXPORT";
    public static final String PING = "PING";

    // Server → Client events
    public static final String READY = "READY";
    public static final String TILE = "TILE";
    public static final String TILE_CLEAR = "TILE_CLEAR";
    public static final String OK = "OK";
    public static final String ERR = "ERR";

    public static final String SEP = "\t";

    public static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }

    public static String unescape(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n' -> { sb.append('\n'); i++; }
                    case 't' -> { sb.append('\t'); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    default -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
