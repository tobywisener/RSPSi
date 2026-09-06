package com.rspsi.tools;

import java.util.List;
import java.util.Map;

public class HouseStyleDefinition {

    public String extendsStyle;
    public String id;
    public String label;
    public Source source;
    public CoordinateModel coordinateModel;
    public Constraints constraints;
    public ProceduralRecipe proceduralRecipe;
    public TemplateObservation templateObservation;
    public TemplateSection groundShell;
    public TemplateSection roofParapet;

    public static class Source {
        public String kind;
        public String exportedFile;
        public List<String> notes;
    }

    public static class CoordinateModel {
        public String basis;
        public String description;
    }

    public static class Constraints {
        public int minInteriorWidth;
        public int minInteriorHeight;
        public int maxFloors;
        public boolean supportsRectangularOnly;
        public String roofKind;
    }

    public static class ProceduralRecipe {
        public String kind;
        public boolean tieredFlooring;
        public int tierFrontInset;
        public int tierSideInset;
        public int balconyOpeningWidth;
        public Footprints footprints;
        public TileLayer groundFloor;
        public Ladder ladder;
        public Village village;
        public Roof roof;
        public Shell shell;
        public WallDecor wallDecor;
    }

    public static class Village {
        public List<String> houseStyles;
        public TileLayer path;
        public TileLayer centrepiece;
        public Map<String, Role> objectRoles;
    }

    public static class Footprints {
        public List<FootprintShape> allowedShapes;
        public int rectWeight;
        public int lWeight;
        public int uWeight;
        public int minWingWidth;
        public int minWingDepth;
        public int minCourtyardWidth;
        public int minCourtyardDepth;
    }

    public enum FootprintShape {
        RECT,
        L,
        U
    }

    public static class Ladder {
        public int groundFloorId;
        public int midFloorId;
        public int topFloorId;
    }

    public static class TileLayer {
        public int overlayId;
        public int overlayShape;
        public int tileFlag;
    }

    public static class Roof {
        public int overlayId;
        public int overlayShape;
        public Role edgeSide;
        public Role edgeCorner;
        public Role topSide;
        public Role topCorner;
        public Role topFlat;
        public Role parapetWall;
        public Role parapetCorner;
        public Role parapetInnerCorner;
    }

    public static class Shell {
        public Role wall;
        public Role corner;
        public Role innerCorner;
        public Role door;
        public List<DoubleDoor> doubleDoors;
    }

    public static class WallDecor {
        public Role ground;
        public Role parapet;
        public Window window;
    }

    public static class Role {
        public int id;
        public int type;
    }

    public static class DoubleDoor {
        public int leftDoorId;
        public int rightDoorId;
        public int minFrontageSpan;
    }

    public static class Window extends Role {
        public int mirrorId;
        public int mirrorType;
        public int minCount;
        public int areaPerWindow;
    }

    public static class TemplateObservation {
        public Bounds outerWallBounds;
        public Bounds groundInteriorBounds;
        public LayerBounds roofOverlayBounds;
        public FlagBounds roofSupportFlags;
        public List<OverlayHint> unfinishedRoofHints;
    }

    public static class Bounds {
        public int minX;
        public int maxX;
        public int minY;
        public int maxY;
    }

    public static class LayerBounds extends Bounds {
        public int plane;
    }

    public static class FlagBounds extends LayerBounds {
        public int tileFlag;
    }

    public static class OverlayHint {
        public int x;
        public int y;
        public int z;
        public int overlayId;
    }

    public static class TemplateSection {
        public List<WallTile> walls;
        public List<WallDecorTile> wallDecor;
        public List<OverlayTile> roofOverlayTiles;
    }

    public static class WallTile {
        public int x;
        public int y;
        public int plane;
        public int id;
        public int type;
        public int orientation;
    }

    public static class WallDecorTile {
        public int x;
        public int y;
        public int plane;
        public int id;
        public int type;
        public int orientation;
    }

    public static class OverlayTile {
        public int x;
        public int y;
        public int plane;
        public int overlayId;
        public int overlayShape;
        public int orientation;
        public int tileFlag;
    }
}
