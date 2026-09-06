package com.jagex.map.procedural;

public enum WallType {
    CLASSIC(1902, 1631),
    VARROCK(23735, 23735, 1),
    WOODEN(11558),
    CANAFIS(24371, 24379);

    public int wallId;
    public int wallCornerId;
    public int wallCornerType = 3;

    WallType(int wallId) {
        this.wallId = wallId;
        this.wallCornerId = -1;
    }

    WallType(int wallId, int wallCornerId) {
        this.wallId = wallId;
        this.wallCornerId = wallCornerId;
    }

    WallType(int wallId, int wallCornerId, int wallCornerType) {
        this.wallId = wallId;
        this.wallCornerId = wallCornerId;
        this.wallCornerType = wallCornerType;
    }
}
