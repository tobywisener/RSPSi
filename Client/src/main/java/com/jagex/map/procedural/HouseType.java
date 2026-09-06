package com.jagex.map.procedural;

import com.rspsi.misc.ArrayUtils;

public enum HouseType {
    NORMAL(WallType.CLASSIC, 108, 1535, 1793, 1640),
    VARROCK(WallType.VARROCK, 22, 11775, 15552, 15552),
    BARBARIAN(WallType.WOODEN, 108, 1535, 4242, 11586),
    CANAFIS(WallType.CANAFIS, 57, 24369, 15552, 15552);

    public WallType wallType;
    public int floorUnderlay;
    public int doorType;
    public int roofEdgeType;
    public int roofTopType;

    HouseType(WallType wallType, int floorUnderlay, int doorType, int roofEdgeType, int roofTopType) {
        this.wallType = wallType;
        this.floorUnderlay = floorUnderlay;
        this.doorType = doorType;
        this.roofEdgeType = roofEdgeType;
        this.roofTopType = roofTopType;
    }

    /**
     * A method returning all possible object ids spawned by this HouseType.
     * This is needed for Chunk loading and preloading object models.
     *
     * @return
     */
    public int[] getObjectIds() {
        return new int[] { this.wallType.wallId, this.wallType.wallCornerId, this.doorType, this.roofEdgeType, this.roofTopType };
    };
};