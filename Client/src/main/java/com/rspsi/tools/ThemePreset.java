package com.rspsi.tools;

import java.util.Locale;

public enum ThemePreset {
    GRASSLAND("Grassland"),
    WOODLAND("Woodland"),
    MARSH("Marsh"),
    LAKELAND("Lakeland"),
    ARID("Arid");

    private final String displayName;

    ThemePreset(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static ThemePreset fromSelection(Object selection) {
        if (selection == null) {
            return null;
        }
        if (selection instanceof ThemePreset preset) {
            return preset;
        }
        return fromSelectionName(selection.toString());
    }

    public static ThemePreset fromSelectionName(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        return switch (rawValue.trim().toUpperCase(Locale.ROOT)) {
            case "GRASSLAND", "WILDERNESS", "PLAINS" -> GRASSLAND;
            case "WOODLAND", "FOREST" -> WOODLAND;
            case "MARSH", "SWAMP" -> MARSH;
            case "LAKELAND", "FREMMINIK_LAKES", "LAKES" -> LAKELAND;
            case "ARID", "DESERT" -> ARID;
            default -> null;
        };
    }
}
