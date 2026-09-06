package com.rspsi.tools;

import com.jagex.map.procedural.HouseType;

public enum BuildingStyle {
    VARROCK("Varrock", HouseType.VARROCK, null),
    NORMAL("Classic", HouseType.NORMAL, null),
    BARBARIAN("Barbarian", HouseType.BARBARIAN, null),
    CANAFIS("Canifis", HouseType.CANAFIS, null),
    FALADOR("Falador", null, "house-styles/falador.json"),
    YANILLE("Yanille", null, "house-styles/yanille.json"),
    POLLNIVNEACH("Pollnivneach", null, "house-styles/pollnivneach.json");

    private final String displayName;
    private final HouseType houseType;
    private final String resourcePath;

    BuildingStyle(String displayName, HouseType houseType, String resourcePath) {
        this.displayName = displayName;
        this.houseType = houseType;
        this.resourcePath = resourcePath;
    }

    public String getDisplayName() {
        return displayName;
    }

    public HouseType getHouseType() {
        return houseType;
    }

    public String getResourcePath() {
        return resourcePath;
    }

    public boolean usesHouseType() {
        return houseType != null;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
