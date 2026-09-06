package com.rspsi.tools;

import com.jagex.Client;
import com.jagex.cache.def.ObjectDefinition;
import com.jagex.cache.loader.object.ObjectDefinitionLoader;
import com.jagex.map.MapRegion;
import com.jagex.map.SceneGraph;
import com.jagex.map.procedural.HouseType;
import com.jagex.map.tile.SceneTile;
import com.rspsi.options.Options;
import org.major.map.RenderFlags;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class BuildingGenerator {

    private static final int FULL_TILE_OVERLAY_SHAPE = 1;
    private static final long MODEL_WARMUP_TIMEOUT_MS = 2000L;
    private static final long MODEL_WARMUP_POLL_MS = 20L;
    private static final int UPPER_PLANE_HEIGHT_OFFSET = 240;
    private static final int MINIMUM_USABLE_FLOOR_SPAN = 3;
    private static final int DEFAULT_GROUND_LADDER_ID = 16683;
    private static final int DEFAULT_MID_LADDER_ID = 16684;
    private static final int DEFAULT_TOP_LADDER_ID = 16679;
    private static final int LADDER_OBJECT_TYPE = 10;
    private static final int LADDER_ORIENTATION = 0;
    private static final int WINDOW_END_MARGIN_TILES = 1;
    private static final int WINDOW_SPACING_TILES = 3;
    private static final int WINDOW_RUN_TILES_PER_WINDOW = 4;

    // Roof object types
    private static final int roofEdgeSide = 18;
    private static final int roofEdgeCorner = 21;
    private static final int roofTopCorner = 16;
    private static final int roofTopFlat = 17;
    private static final int roofTopSide = 12;

    private static BuildingStyle selectedStyle = BuildingStyle.VARROCK;

    public static void setSelectedStyle(BuildingStyle style) {
        selectedStyle = style == null ? BuildingStyle.VARROCK : style;
    }

    public static BuildingStyle getSelectedStyle() {
        return selectedStyle;
    }

    static Set<Long> previewFootprintTiles(BuildingStyle style,
                                           int minX, int minY, int maxX, int maxY,
                                           int frontOrientation,
                                           HouseStyleDefinition.FootprintShape forcedFootprintShape) {
        return previewFootprintTiles(style, minX, minY, maxX, maxY, frontOrientation, forcedFootprintShape, null);
    }

    static Set<Long> previewFootprintTiles(BuildingStyle style,
                                           int minX, int minY, int maxX, int maxY,
                                           int frontOrientation,
                                           HouseStyleDefinition.FootprintShape forcedFootprintShape,
                                           CacheBiomeProfileMiner.SettlementPieceDefinition piece) {
        BuildingStyle activeStyle = style == null ? BuildingStyle.VARROCK : style;
        BuildArea buildArea = new BuildArea(List.of(), 0, minX, minY, maxX, maxY);
        if (!isUsableFloorArea(buildArea)) {
            return Set.of();
        }
        if (activeStyle.usesHouseType()) {
            return new LinkedHashSet<>(createRectFootprintPlan(buildArea).occupiedTiles);
        }
        HouseStyleDefinition definition = HouseStyleRepository.load(activeStyle);
        if (definition == null || definition.proceduralRecipe == null) {
            return new LinkedHashSet<>(createRectFootprintPlan(buildArea).occupiedTiles);
        }
        FootprintPlan footprintPlan = createBaseFootprintPlan(
                buildArea,
                frontOrientation,
                definition.proceduralRecipe.footprints,
                forcedFootprintShape,
                piece
        );
        if (!isUsableFootprintPlan(footprintPlan)) {
            footprintPlan = createRectFootprintPlan(buildArea);
        }
        return new LinkedHashSet<>(footprintPlan.occupiedTiles);
    }

    static boolean isStampableFootprintSpan(int width, int height) {
        return width >= minimumStampableFootprintSpan() && height >= minimumStampableFootprintSpan();
    }

    static int minimumStampableFootprintSpan() {
        return MINIMUM_USABLE_FLOOR_SPAN + 4;
    }

    public static void stampBuilding(MapRegion mapRegion, SceneGraph sceneGraph, BuildingStyle style,
                                     int minX, int minY, int maxX, int maxY,
                                     int tileHeight, int requestedFloors,
                                     int doorOrientation, int doorCoord) {
        stampBuilding(mapRegion, sceneGraph, style, minX, minY, maxX, maxY, tileHeight, requestedFloors, doorOrientation, doorCoord, null, null);
    }

    public static void stampBuilding(MapRegion mapRegion, SceneGraph sceneGraph, BuildingStyle style,
                                     int minX, int minY, int maxX, int maxY,
                                     int tileHeight, int requestedFloors,
                                     int doorOrientation, int doorCoord,
                                     HouseStyleDefinition.FootprintShape forcedFootprintShape) {
        stampBuilding(mapRegion, sceneGraph, style, minX, minY, maxX, maxY, tileHeight, requestedFloors, doorOrientation, doorCoord, forcedFootprintShape, null);
    }

    public static void stampBuilding(MapRegion mapRegion, SceneGraph sceneGraph, BuildingStyle style,
                                     int minX, int minY, int maxX, int maxY,
                                     int tileHeight, int requestedFloors,
                                     int doorOrientation, int doorCoord,
                                     HouseStyleDefinition.FootprintShape forcedFootprintShape,
                                     CacheBiomeProfileMiner.SettlementPieceDefinition piece) {
        if (mapRegion == null || sceneGraph == null || sceneGraph.tiles == null) {
            return;
        }

        int safeMinX = Math.max(0, Math.min(sceneGraph.width - 1, minX));
        int safeMinY = Math.max(0, Math.min(sceneGraph.length - 1, minY));
        int safeMaxX = Math.max(safeMinX, Math.min(sceneGraph.width - 1, maxX));
        int safeMaxY = Math.max(safeMinY, Math.min(sceneGraph.length - 1, maxY));
        int maxFloors = Math.max(1, sceneGraph.tiles.length - 1);
        int floors = Math.max(1, Math.min(requestedFloors, maxFloors));

        BuildArea buildArea = new BuildArea(List.of(), tileHeight, safeMinX, safeMinY, safeMaxX, safeMaxY);
        if (!isUsableFloorArea(buildArea)) {
            return;
        }
        DoorPlacement doorPlacement = new DoorPlacement(
                doorOrientation & 3,
                clampDoorCoord(buildArea, doorOrientation, doorCoord)
        );
        BuildingStyle activeStyle = style == null ? BuildingStyle.VARROCK : style;
        HouseStyleDefinition activeDefinition = activeStyle.usesHouseType() ? null : HouseStyleRepository.load(activeStyle);
        warmUpStyleModels(activeStyle, activeDefinition);

        if (activeStyle.usesHouseType()) {
            List<SceneTile> baseTiles = collectBaseTiles(sceneGraph, buildArea);
            flattenTileRectangle(mapRegion, sceneGraph, 0,
                    buildArea.minimumX,
                    buildArea.minimumY,
                    buildArea.maximumX,
                    buildArea.maximumY,
                    buildArea.averageTileHeight);
            generatePitchedRoofHouse(mapRegion, sceneGraph, activeStyle.getHouseType(), buildArea, doorPlacement, baseTiles, floors);
        } else {
            generateJsonHouse(mapRegion, sceneGraph, activeStyle, activeDefinition, buildArea, doorPlacement, floors, forcedFootprintShape, piece);
        }
    }

    public static void generateBuilding(int requestedFloors) throws Exception {
        Client client = Client.getSingleton();
        if (client.loadState != Client.LoadState.ACTIVE) {
            return;
        }
        if (Options.currentHeight.get() != 0) {
            throw new Exception("Invalid height");
        }

        List<SceneTile> selectedTiles = client.sceneGraph.getSelectedTiles();
        if (selectedTiles == null || selectedTiles.isEmpty()) {
            throw new Exception("No tiles selected");
        }

        int maxFloors = Math.max(1, client.sceneGraph.tiles.length - 1);
        int floors = Math.min(requestedFloors, maxFloors);

        int oldHeight = Options.tileHeightLevel.get();
        boolean oldAllHeightsVisible = Options.allHeightsVisible.get();

        try {
            Options.allHeightsVisible.set(true);

            BuildArea buildArea = resolveBuildArea(client.mapRegion, client.sceneGraph, selectedTiles);
            Options.tileHeightLevel.set(buildArea.averageTileHeight);

            DoorPlacement doorPlacement = resolveDoorPlacement(client.sceneGraph, buildArea);
            BuildingStyle activeStyle = selectedStyle == null ? BuildingStyle.VARROCK : selectedStyle;
            stampBuilding(
                    client.mapRegion,
                    client.sceneGraph,
                    activeStyle,
                    buildArea.minimumX,
                    buildArea.minimumY,
                    buildArea.maximumX,
                    buildArea.maximumY,
                    buildArea.averageTileHeight,
                    floors,
                    doorPlacement.orientation,
                    doorPlacement.coord
            );

            client.sceneGraph.updateHeights(0, 0, client.sceneGraph.width, client.sceneGraph.length);
            client.mapRegion.updateTiles();
            Client.updateChunkTiles();
            com.jagex.map.SceneGraph.minimapUpdate = true;
            client.sceneGraph.tileQueue.clear();
        } finally {
            Options.tileHeightLevel.set(oldHeight);
            Options.allHeightsVisible.set(oldAllHeightsVisible);
        }
    }

    static Set<Integer> collectStyleObjectIds(BuildingStyle style) {
        if (style == null) {
            return Set.of();
        }
        HouseStyleDefinition definition = style.usesHouseType() ? null : HouseStyleRepository.load(style);
        return collectStyleObjectIds(style, definition);
    }

    private static BuildArea resolveBuildArea(MapRegion mapRegion, SceneGraph sceneGraph, List<SceneTile> selectedTiles) {
        int lowerZ = Options.currentHeight.get();
        List<SceneTile> tilesAround = selectedTiles.stream()
                .flatMap(tile -> IntStream.rangeClosed(-2, 2)
                        .boxed()
                        .flatMap(xOffset -> IntStream.rangeClosed(-2, 2)
                                .mapToObj(yOffset -> sceneGraph.tiles[tile.plane][tile.positionX + xOffset][tile.positionY + yOffset])))
                .distinct()
                .collect(Collectors.toList());

        int averageTileHeight = (int) Math.round(tilesAround.stream()
                .mapToInt(tile -> -mapRegion.tileHeights[lowerZ][tile.positionX][tile.positionY])
                .average()
                .orElse(Options.tileHeightLevel.get()));

        int minimumX = tilesAround.stream().mapToInt(tile -> tile.positionX).min().orElseThrow(IllegalStateException::new);
        int minimumY = tilesAround.stream().mapToInt(tile -> tile.positionY).min().orElseThrow(IllegalStateException::new);
        int maximumX = tilesAround.stream().mapToInt(tile -> tile.positionX).max().orElseThrow(IllegalStateException::new);
        int maximumY = tilesAround.stream().mapToInt(tile -> tile.positionY).max().orElseThrow(IllegalStateException::new);

        return new BuildArea(tilesAround, averageTileHeight, minimumX, minimumY, maximumX, maximumY);
    }

    private static DoorPlacement resolveDoorPlacement(SceneGraph sceneGraph, BuildArea buildArea) {
        int cameraX = sceneGraph.absoluteCameraX;
        int cameraY = sceneGraph.absoluteCameraY;

        int doorOrientation = 1;
        if (cameraY > buildArea.maximumY - 1) {
            doorOrientation = 3;
        } else if (cameraX > buildArea.maximumX - 1) {
            doorOrientation = 0;
        } else if (cameraX < buildArea.minimumX + 1) {
            doorOrientation = 2;
        }

        int doorCoord;
        if (doorOrientation == 0 || doorOrientation == 2) {
            doorCoord = randomBetweenInclusive(buildArea.minimumY + 2, buildArea.maximumY - 2);
        } else {
            doorCoord = randomBetweenInclusive(buildArea.minimumX + 2, buildArea.maximumX - 2);
        }
        return new DoorPlacement(doorOrientation, doorCoord);
    }

    private static int clampDoorCoord(BuildArea buildArea, int doorOrientation, int requestedCoord) {
        int safeMin;
        int safeMax;
        if ((doorOrientation & 3) == 0 || (doorOrientation & 3) == 2) {
            safeMin = buildArea.minimumY + 2;
            safeMax = buildArea.maximumY - 2;
        } else {
            safeMin = buildArea.minimumX + 2;
            safeMax = buildArea.maximumX - 2;
        }
        if (safeMin > safeMax) {
            return (safeMin + safeMax) / 2;
        }
        return Math.max(safeMin, Math.min(safeMax, requestedCoord));
    }

    private static List<SceneTile> collectBaseTiles(SceneGraph sceneGraph, BuildArea buildArea) {
        List<SceneTile> tiles = new ArrayList<>();
        int minX = buildArea.minimumX + 2;
        int minY = buildArea.minimumY + 2;
        int maxX = buildArea.maximumX - 2;
        int maxY = buildArea.maximumY - 2;
        if (minX > maxX || minY > maxY) {
            int centerX = Math.max(buildArea.minimumX, Math.min(sceneGraph.width - 1, (buildArea.minimumX + buildArea.maximumX) / 2));
            int centerY = Math.max(buildArea.minimumY, Math.min(sceneGraph.length - 1, (buildArea.minimumY + buildArea.maximumY) / 2));
            tiles.add(sceneGraph.getTile(0, centerX, centerY));
            return tiles;
        }
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                tiles.add(sceneGraph.getTile(0, x, y));
            }
        }
        return tiles;
    }

    private static int randomBetweenInclusive(int minimum, int maximum) {
        if (maximum <= minimum) {
            return minimum;
        }
        return ThreadLocalRandom.current().nextInt(minimum, maximum + 1);
    }

    private static void warmUpStyleModels(BuildingStyle style, HouseStyleDefinition definition) {
        Set<Integer> objectIds = collectStyleObjectIds(style, definition);
        if (objectIds.isEmpty()) {
            return;
        }

        requestObjectModels(objectIds);
        long deadline = System.currentTimeMillis() + MODEL_WARMUP_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (areObjectModelsReady(objectIds)) {
                return;
            }
            sleepQuietly(MODEL_WARMUP_POLL_MS);
        }
        requestObjectModels(objectIds);
    }

    private static Set<Integer> collectStyleObjectIds(BuildingStyle style, HouseStyleDefinition definition) {
        Set<Integer> objectIds = new LinkedHashSet<>();
        if (style != null && style.usesHouseType()) {
            for (int objectId : style.getHouseType().getObjectIds()) {
                addObjectId(objectIds, objectId);
            }
            return objectIds;
        }

        if (definition == null || definition.proceduralRecipe == null) {
            return objectIds;
        }

        HouseStyleDefinition.ProceduralRecipe recipe = definition.proceduralRecipe;
        if (recipe.shell != null) {
            addRoleId(objectIds, recipe.shell.wall);
            addRoleId(objectIds, recipe.shell.corner);
            addRoleId(objectIds, recipe.shell.innerCorner);
            addRoleId(objectIds, recipe.shell.door);
            if (recipe.shell.doubleDoors != null) {
                for (HouseStyleDefinition.DoubleDoor doubleDoor : recipe.shell.doubleDoors) {
                    if (doubleDoor == null) {
                        continue;
                    }
                    addObjectId(objectIds, doubleDoor.leftDoorId);
                    addObjectId(objectIds, doubleDoor.rightDoorId);
                }
            }
        }
        if (recipe.roof != null) {
            addRoleId(objectIds, recipe.roof.edgeSide);
            addRoleId(objectIds, recipe.roof.edgeCorner);
            addRoleId(objectIds, recipe.roof.topSide);
            addRoleId(objectIds, recipe.roof.topCorner);
            addRoleId(objectIds, recipe.roof.topFlat);
            addRoleId(objectIds, recipe.roof.parapetWall);
            addRoleId(objectIds, recipe.roof.parapetCorner);
            addRoleId(objectIds, recipe.roof.parapetInnerCorner);
        }
        if (recipe.wallDecor != null) {
            addRoleId(objectIds, recipe.wallDecor.ground);
            addRoleId(objectIds, recipe.wallDecor.parapet);
            addRoleId(objectIds, recipe.wallDecor.window);
            if (recipe.wallDecor.window != null) {
                addObjectId(objectIds, recipe.wallDecor.window.mirrorId);
            }
        }
        LadderSpec ladderSpec = resolveLadderSpec(recipe.ladder);
        addObjectId(objectIds, ladderSpec.groundFloorId);
        addObjectId(objectIds, ladderSpec.midFloorId);
        addObjectId(objectIds, ladderSpec.topFloorId);
        return objectIds;
    }

    private static void addRoleId(Set<Integer> objectIds, HouseStyleDefinition.Role role) {
        if (role == null) {
            return;
        }
        addObjectId(objectIds, role.id);
    }

    private static void addObjectId(Set<Integer> objectIds, int objectId) {
        if (objectId > 0) {
            objectIds.add(objectId);
        }
    }

    private static void requestObjectModels(Set<Integer> objectIds) {
        for (int objectId : objectIds) {
            ObjectDefinition definition = ObjectDefinitionLoader.lookup(objectId);
            if (definition != null) {
                definition.ready();
            }
        }
    }

    private static boolean areObjectModelsReady(Set<Integer> objectIds) {
        boolean allReady = true;
        for (int objectId : objectIds) {
            ObjectDefinition definition = ObjectDefinitionLoader.lookup(objectId);
            if (definition != null && !definition.ready()) {
                allReady = false;
            }
        }
        return allReady;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void generatePitchedRoofHouse(MapRegion mapRegion, SceneGraph sceneGraph, HouseType houseType, BuildArea buildArea,
                                                 DoorPlacement doorPlacement, List<SceneTile> selectedTiles, int floors) {
        for (int floor = 0; floor < floors; floor++) {
            boolean isTopFloor = floor == floors - 1;

            for (int y = buildArea.minimumY + 2; y < buildArea.maximumY - 1; y++) {
                if (floor == 0 && doorPlacement.orientation == 2 && y == doorPlacement.coord) {
                    mapRegion.spawnObjectToWorld(sceneGraph, houseType.doorType, buildArea.minimumX + 1, y, floor, 0, 2, false);
                } else {
                    mapRegion.spawnObjectToWorld(sceneGraph, houseType.wallType.wallId, buildArea.minimumX + 1, y, floor, 0, 2, false);
                }

                if (floor == 0 && doorPlacement.orientation == 0 && y == doorPlacement.coord) {
                    mapRegion.spawnObjectToWorld(sceneGraph, houseType.doorType, buildArea.maximumX - 1, y, floor, 0, 0, false);
                } else {
                    mapRegion.spawnObjectToWorld(sceneGraph, houseType.wallType.wallId, buildArea.maximumX - 1, y, floor, 0, 0, false);
                }

                if (isTopFloor) {
                    mapRegion.spawnObjectToWorld(sceneGraph, houseType.roofEdgeType, buildArea.minimumX + 1, y, floor + 1, roofEdgeSide, 2, false);
                    if (y >= buildArea.minimumY + 3 && y <= buildArea.maximumY - 3) {
                        mapRegion.spawnObjectToWorld(sceneGraph, houseType.roofTopType, buildArea.minimumX + 2, y, floor + 1, roofTopSide, 2, false);
                    }

                    mapRegion.spawnObjectToWorld(sceneGraph, houseType.roofEdgeType, buildArea.maximumX - 1, y, floor + 1, roofEdgeSide, 0, false);
                    if (y >= buildArea.minimumY + 3 && y <= buildArea.maximumY - 3) {
                        mapRegion.spawnObjectToWorld(sceneGraph, houseType.roofTopType, buildArea.maximumX - 2, y, floor + 1, roofTopSide, 0, false);
                    }
                }
            }

            for (int x = buildArea.minimumX + 2; x < buildArea.maximumX - 1; x++) {
                if (floor == 0 && doorPlacement.orientation == 1 && x == doorPlacement.coord) {
                    mapRegion.spawnObjectToWorld(sceneGraph, houseType.doorType, x, buildArea.minimumY + 1, floor, 0, 1, false);
                } else {
                    mapRegion.spawnObjectToWorld(sceneGraph, houseType.wallType.wallId, x, buildArea.minimumY + 1, floor, 0, 1, false);
                }

                if (floor == 0 && doorPlacement.orientation == 3 && x == doorPlacement.coord) {
                    mapRegion.spawnObjectToWorld(sceneGraph, houseType.doorType, x, buildArea.maximumY - 1, floor, 0, 3, false);
                } else {
                    mapRegion.spawnObjectToWorld(sceneGraph, houseType.wallType.wallId, x, buildArea.maximumY - 1, floor, 0, 3, false);
                }

                if (isTopFloor) {
                    mapRegion.spawnObjectToWorld(sceneGraph, houseType.roofEdgeType, x, buildArea.maximumY - 1, floor + 1, roofEdgeSide, 3, false);
                    if (x >= buildArea.minimumX + 3 && x <= buildArea.maximumX - 3) {
                        mapRegion.spawnObjectToWorld(sceneGraph, houseType.roofTopType, x, buildArea.maximumY - 2, floor + 1, roofTopSide, 3, false);
                    }

                    mapRegion.spawnObjectToWorld(sceneGraph, houseType.roofEdgeType, x, buildArea.minimumY + 1, floor + 1, roofEdgeSide, 1, false);
                    if (x >= buildArea.minimumX + 3 && x <= buildArea.maximumX - 3) {
                        mapRegion.spawnObjectToWorld(sceneGraph, houseType.roofTopType, x, buildArea.minimumY + 2, floor + 1, roofTopSide, 1, false);
                    }
                }
            }

            if (isTopFloor) {
                mapRegion.spawnObjectToWorld(sceneGraph, houseType.roofEdgeType, buildArea.minimumX + 1, buildArea.minimumY + 1, floor + 1, roofEdgeCorner, 1, false);
                mapRegion.spawnObjectToWorld(sceneGraph, houseType.roofTopType, buildArea.minimumX + 2, buildArea.minimumY + 2, floor + 1, roofTopCorner, 1, false);

                mapRegion.spawnObjectToWorld(sceneGraph, houseType.roofEdgeType, buildArea.minimumX + 1, buildArea.maximumY - 1, floor + 1, roofEdgeCorner, 2, false);
                mapRegion.spawnObjectToWorld(sceneGraph, houseType.roofTopType, buildArea.minimumX + 2, buildArea.maximumY - 2, floor + 1, roofTopCorner, 2, false);

                mapRegion.spawnObjectToWorld(sceneGraph, houseType.roofEdgeType, buildArea.maximumX - 1, buildArea.maximumY - 1, floor + 1, roofEdgeCorner, 3, false);
                mapRegion.spawnObjectToWorld(sceneGraph, houseType.roofTopType, buildArea.maximumX - 2, buildArea.maximumY - 2, floor + 1, roofTopCorner, 3, false);

                mapRegion.spawnObjectToWorld(sceneGraph, houseType.roofEdgeType, buildArea.maximumX - 1, buildArea.minimumY + 1, floor + 1, roofEdgeCorner, 0, false);
                mapRegion.spawnObjectToWorld(sceneGraph, houseType.roofTopType, buildArea.maximumX - 2, buildArea.minimumY + 2, floor + 1, roofTopCorner, 0, false);

                for (SceneTile sceneTile : selectedTiles) {
                    if (sceneTile.positionX < buildArea.minimumX + 3 || sceneTile.positionX > buildArea.maximumX - 3) {
                        continue;
                    }
                    if (sceneTile.positionY < buildArea.minimumY + 3 || sceneTile.positionY > buildArea.maximumY - 3) {
                        continue;
                    }
                    mapRegion.spawnObjectToWorld(sceneGraph, houseType.roofTopType, sceneTile.positionX, sceneTile.positionY, floor + 1, roofTopFlat, 0, false);
                }
            }

            if (houseType.wallType.wallCornerId != -1) {
                mapRegion.spawnObjectToWorld(sceneGraph, houseType.wallType.wallCornerId, buildArea.minimumX + 1, buildArea.minimumY + 1, floor, houseType.wallType.wallCornerType, 1, false);
                mapRegion.spawnObjectToWorld(sceneGraph, houseType.wallType.wallCornerId, buildArea.minimumX + 1, buildArea.maximumY - 1, floor, houseType.wallType.wallCornerType, 2, false);
                mapRegion.spawnObjectToWorld(sceneGraph, houseType.wallType.wallCornerId, buildArea.maximumX - 1, buildArea.maximumY - 1, floor, houseType.wallType.wallCornerType, 3, false);
                mapRegion.spawnObjectToWorld(sceneGraph, houseType.wallType.wallCornerId, buildArea.maximumX - 1, buildArea.minimumY + 1, floor, houseType.wallType.wallCornerType, 0, false);
            }
        }

        applyTileFlag(mapRegion, sceneGraph, selectedTiles, 0, 1, RenderFlags.FORCE_LOWEST_PLANE.getBit());
        paintOverlay(mapRegion, sceneGraph, selectedTiles, 0, floors, houseType.floorUnderlay, FULL_TILE_OVERLAY_SHAPE, 0);
    }

    private static void generateJsonHouse(MapRegion mapRegion, SceneGraph sceneGraph, BuildingStyle style, HouseStyleDefinition definition,
                                          BuildArea buildArea, DoorPlacement doorPlacement, int requestedFloors,
                                          HouseStyleDefinition.FootprintShape forcedFootprintShape,
                                          CacheBiomeProfileMiner.SettlementPieceDefinition piece) {
        HouseStyleDefinition.ProceduralRecipe recipe = definition.proceduralRecipe;
        if (recipe == null || recipe.shell == null || recipe.roof == null) {
            throw new IllegalStateException("Incomplete house style definition: " + definition.id);
        }

        int frontOrientation = doorPlacement == null ? -1 : doorPlacement.orientation;
        boolean tieredFlooring = recipe.tieredFlooring;
        int tierFrontInset = recipe.tierFrontInset > 0 ? recipe.tierFrontInset : 2;
        int tierSideInset = recipe.tierSideInset > 0 ? recipe.tierSideInset : 1;
        int balconyOpeningWidth = recipe.balconyOpeningWidth > 0 ? recipe.balconyOpeningWidth : 2;
        List<FootprintPlan> floorPlans = resolveFloorPlans(
                buildArea,
                requestedFloors,
                frontOrientation,
                tieredFlooring,
                tierFrontInset,
                tierSideInset,
                recipe.footprints,
                forcedFootprintShape,
                piece
        );
        if (floorPlans.isEmpty()) {
            return;
        }
        flattenFootprintGround(mapRegion, sceneGraph, floorPlans.get(0), buildArea.averageTileHeight);

        int floors = floorPlans.size();
        DoorPlacement resolvedDoorPlacement = resolveDoorPlacementForFootprint(floorPlans.get(0), doorPlacement, recipe.shell);
        int pieceRotationSteps = resolvePieceRotationSteps(piece, resolvedDoorPlacement);
        List<DoorPlacement> floorDoorPlacements = resolvePieceDoorPlacements(piece, buildArea, floorPlans, pieceRotationSteps);
        if (!hasExplicitDoorMarkers(piece) && (floorDoorPlacements == null || floorDoorPlacements.isEmpty())) {
            floorDoorPlacements = resolveFloorDoorPlacements(floorPlans, resolvedDoorPlacement, recipe.shell, tieredFlooring);
        }
        LadderSpec ladderSpec = floors > 1 && !hasExplicitLadderMarkers(piece) ? resolveLadderSpec(recipe.ladder) : null;
        LadderPlacement ladderPlacement = ladderSpec == null ? null : resolvePieceLadderPlacement(piece, buildArea, pieceRotationSteps);
        if (!hasExplicitLadderMarkers(piece) && ladderPlacement == null && ladderSpec != null) {
            ladderPlacement = resolveLadderPlacement(floorPlans, resolvedDoorPlacement, tieredFlooring);
        }
        int roofPlane = Math.min(floors, sceneGraph.tiles.length - 1);
        applyUpperPlaneSupport(mapRegion, sceneGraph, buildArea, roofPlane);

        for (int floor = 0; floor < floors; floor++) {
            FootprintPlan floorPlan = floorPlans.get(floor);
            spawnFlatRoofShell(
                    mapRegion,
                    sceneGraph,
                    recipe.shell,
                    floorPlan,
                    floor,
                    floorDoorPlacements.get(floor)
            );
            paintOverlay(
                    mapRegion,
                    sceneGraph,
                    collectSceneTiles(sceneGraph, floorPlan.occupiedTiles),
                    floor,
                    1,
                    recipe.groundFloor.overlayId,
                    recipe.groundFloor.overlayShape,
                    0
            );
        }

        stampLadders(mapRegion, sceneGraph, ladderPlacement, ladderSpec, floors);

        if (usesPitchedRoof(definition)) {
            spawnPitchedRoof(mapRegion, sceneGraph, recipe.roof, floorPlans.get(floors - 1), Math.min(floors, sceneGraph.tiles.length - 1));
        } else {
            for (int floor = 0; floor < floors; floor++) {
                FootprintPlan floorPlan = floorPlans.get(floor);
                FootprintPlan upperFloorPlan = floor + 1 < floors ? floorPlans.get(floor + 1) : null;
                FootprintPlan roofDeckPlan = deriveVisibleRoofDeckPlan(floorPlan, upperFloorPlan);
                if (!isUsableRoofDeck(roofDeckPlan)) {
                    continue;
                }

                int deckPlane = Math.min(floor + 1, sceneGraph.tiles.length - 1);
                spawnParapet(mapRegion, sceneGraph, recipe.roof, roofDeckPlan, upperFloorPlan, deckPlane);
                spawnParapetDecor(mapRegion, sceneGraph, recipe.wallDecor, roofDeckPlan, upperFloorPlan, deckPlane);

                paintOverlay(
                        mapRegion,
                        sceneGraph,
                        collectSceneTiles(sceneGraph, roofDeckPlan.occupiedTiles),
                        deckPlane,
                        1,
                        recipe.roof.overlayId,
                        recipe.roof.overlayShape,
                        0
                );
            }
        }

        spawnJsonWallDecor(mapRegion, sceneGraph, recipe.wallDecor, floorPlans, floorDoorPlacements, buildArea, piece, pieceRotationSteps);

        applyTileFlag(mapRegion, sceneGraph, collectSceneTiles(sceneGraph, floorPlans.get(0).occupiedTiles), 0, 1, recipe.groundFloor.tileFlag);
    }

    private static void spawnFlatRoofShell(MapRegion mapRegion, SceneGraph sceneGraph, HouseStyleDefinition.Shell shell, FootprintPlan footprintPlan,
                                           int plane, DoorPlacement doorPlacement) {
        for (BoundaryPlacement placement : collectBoundaryPlacements(footprintPlan, footprintPlan)) {
            if (placement.cornerKind == CornerKind.OUTER && shell.corner != null && shell.corner.id > 0) {
                spawnWallSlotObject(mapRegion, sceneGraph, shell.corner.id, placement.x, placement.y, plane, shell.corner.type, placement.orientation);
                continue;
            }
            if (placement.cornerKind == CornerKind.INNER && shell.innerCorner != null && shell.innerCorner.id > 0) {
                spawnWallSlotObject(mapRegion, sceneGraph, shell.innerCorner.id, placement.x, placement.y, plane, shell.innerCorner.type, placement.orientation);
                continue;
            }
            if (placement.cornerKind != CornerKind.NONE) {
                spawnStraightBoundaryRole(mapRegion, sceneGraph, shell.wall, placement, plane);
                continue;
            }
            if (doorPlacement != null && placement.matchesDoor(doorPlacement)) {
                int doorObjectId = resolveDoorObjectId(shell, doorPlacement, placement);
                if (doorObjectId > 0) {
                    spawnWallSlotObject(mapRegion, sceneGraph, doorObjectId, placement.x, placement.y, plane, shell.door.type, placement.orientation);
                }
            } else {
                spawnWallSlotObject(mapRegion, sceneGraph, shell.wall.id, placement.x, placement.y, plane, shell.wall.type, placement.orientation);
            }
        }
    }

    private static void spawnParapet(MapRegion mapRegion, SceneGraph sceneGraph, HouseStyleDefinition.Roof roof,
                                     FootprintPlan roofDeckPlan, FootprintPlan upperFloorPlan, int plane) {
        Set<Long> blockedUpperBoundaryTiles = collectBoundaryTileKeys(upperFloorPlan);
        for (BoundaryPlacement placement : collectBoundaryPlacements(roofDeckPlan, mergeFootprints(roofDeckPlan, upperFloorPlan))) {
            if (blockedUpperBoundaryTiles.contains(tileKey(placement.x, placement.y))) {
                continue;
            }
            if (placement.cornerKind == CornerKind.OUTER && roof.parapetCorner != null && roof.parapetCorner.id > 0) {
                spawnWallSlotObject(mapRegion, sceneGraph, roof.parapetCorner.id, placement.x, placement.y, plane, roof.parapetCorner.type, placement.orientation);
            } else if (placement.cornerKind == CornerKind.INNER && roof.parapetInnerCorner != null && roof.parapetInnerCorner.id > 0) {
                spawnWallSlotObject(mapRegion, sceneGraph, roof.parapetInnerCorner.id, placement.x, placement.y, plane, roof.parapetInnerCorner.type, placement.orientation);
            } else {
                spawnStraightBoundaryRole(mapRegion, sceneGraph, roof.parapetWall, placement, plane);
            }
        }
    }

    private static void spawnStraightBoundaryRole(MapRegion mapRegion, SceneGraph sceneGraph, HouseStyleDefinition.Role role,
                                                  BoundaryPlacement placement, int plane) {
        if (role == null || role.id <= 0 || placement == null) {
            return;
        }
        spawnWallSlotObject(mapRegion, sceneGraph, role.id, placement.x, placement.y, plane, role.type, placement.orientation);
        if (placement.hasSecondaryOrientation()) {
            spawnWallSlotObject(mapRegion, sceneGraph, role.id, placement.x, placement.y, plane, role.type, placement.secondaryOrientation);
        }
    }

    private static void spawnWallSlotObject(MapRegion mapRegion, SceneGraph sceneGraph,
                                            int id, int x, int y, int plane, int type, int orientation) {
        if (id <= 0 || mapRegion == null || sceneGraph == null) {
            return;
        }
        if (x < 0 || y < 0 || plane < 0 || plane >= sceneGraph.tiles.length || x >= sceneGraph.width || y >= sceneGraph.length) {
            return;
        }
        SceneTile tile = sceneGraph.getTile(plane, x, y);
        if (tile != null && tile.wall != null) {
            return;
        }
        mapRegion.spawnObjectToWorld(sceneGraph, id, x, y, plane, type, orientation, false);
    }

    private static void spawnObject(MapRegion mapRegion, SceneGraph sceneGraph,
                                    HouseStyleDefinition.Role role, int x, int y, int plane, int orientation) {
        if (role == null || role.id <= 0 || mapRegion == null || sceneGraph == null) {
            return;
        }
        if (x < 0 || y < 0 || plane < 0 || plane >= sceneGraph.tiles.length || x >= sceneGraph.width || y >= sceneGraph.length) {
            return;
        }
        mapRegion.spawnObjectToWorld(sceneGraph, role.id, x, y, plane, role.type, orientation, false);
    }

    private static void spawnJsonWallDecor(MapRegion mapRegion, SceneGraph sceneGraph, HouseStyleDefinition.WallDecor wallDecor,
                                           List<FootprintPlan> floorPlans, List<DoorPlacement> floorDoorPlacements,
                                           BuildArea buildArea, CacheBiomeProfileMiner.SettlementPieceDefinition piece,
                                           int pieceRotationSteps) {
        if (wallDecor == null) {
            return;
        }

        if (wallDecor.window != null && wallDecor.window.id > 0) {
            for (int plane = 0; plane < floorPlans.size(); plane++) {
                FootprintPlan floorPlan = floorPlans.get(plane);
                boolean spawnedPieceWindows = spawnPieceWindowsOnFloor(
                        mapRegion,
                        sceneGraph,
                        wallDecor.window,
                        floorPlan,
                        plane,
                        buildArea,
                        piece,
                        pieceRotationSteps
                );
                if (!hasExplicitWindowMarkers(piece) && !spawnedPieceWindows) {
                    spawnWindowsOnFloor(
                            mapRegion,
                            sceneGraph,
                            wallDecor.window,
                            floorPlan,
                            plane,
                            floorDoorPlacements == null || plane >= floorDoorPlacements.size() ? null : floorDoorPlacements.get(plane)
                    );
                }
            }
        }
    }

    private static int resolvePieceRotationSteps(CacheBiomeProfileMiner.SettlementPieceDefinition piece, DoorPlacement resolvedDoorPlacement) {
        if (piece == null || resolvedDoorPlacement == null) {
            return 0;
        }
        return Math.floorMod((resolvedDoorPlacement.orientation & 3) - normalizePieceFrontOrientation(piece.frontOrientation), 4);
    }

    private static int normalizePieceFrontOrientation(int frontOrientation) {
        return frontOrientation >= 0 ? (frontOrientation & 3) : 1;
    }

    private static List<DoorPlacement> resolvePieceDoorPlacements(
            CacheBiomeProfileMiner.SettlementPieceDefinition piece,
            BuildArea buildArea,
            List<FootprintPlan> floorPlans,
            int rotationSteps
    ) {
        if (piece == null || piece.doors == null || piece.doors.isEmpty() || buildArea == null || floorPlans == null || floorPlans.isEmpty()) {
            return null;
        }
        List<DoorPlacement> placements = new ArrayList<>(floorPlans.size());
        boolean anyExplicitDoor = false;
        for (int floor = 0; floor < floorPlans.size(); floor++) {
            List<BoundaryPlacement> boundaryPlacements = collectBoundaryPlacements(floorPlans.get(floor), floorPlans.get(floor)).stream()
                    .filter(placement -> placement.cornerKind == CornerKind.NONE)
                    .sorted((left, right) -> {
                        int orientationCompare = Integer.compare(left.orientation, right.orientation);
                        if (orientationCompare != 0) {
                            return orientationCompare;
                        }
                        return Integer.compare(left.boundaryCoord(), right.boundaryCoord());
                    })
                    .toList();
            DoorPlacement placement = resolvePieceDoorPlacementForFloor(piece, buildArea, rotationSteps, floor, boundaryPlacements);
            placements.add(placement);
            anyExplicitDoor |= placement != null;
        }
        return anyExplicitDoor ? placements : null;
    }

    private static DoorPlacement resolvePieceDoorPlacementForFloor(
            CacheBiomeProfileMiner.SettlementPieceDefinition piece,
            BuildArea buildArea,
            int rotationSteps,
            int floor,
            List<BoundaryPlacement> boundaryPlacements
    ) {
        List<BoundaryPlacement> markerPlacements = resolvePieceBoundaryMarkers(piece == null ? null : piece.doors, buildArea, piece, rotationSteps, floor, boundaryPlacements);
        if (markerPlacements.isEmpty()) {
            return null;
        }
        BoundaryPlacement first = markerPlacements.get(0);
        int width = 1;
        if (markerPlacements.size() >= 2) {
            BoundaryPlacement second = markerPlacements.get(1);
            if (second.orientation == first.orientation && second.boundaryCoord() == first.boundaryCoord() + 1) {
                width = 2;
            }
        }
        return new DoorPlacement(first.orientation, first.boundaryCoord(), width);
    }

    private static boolean hasExplicitDoorMarkers(CacheBiomeProfileMiner.SettlementPieceDefinition piece) {
        return piece != null && piece.doors != null;
    }

    private static boolean hasExplicitWindowMarkers(CacheBiomeProfileMiner.SettlementPieceDefinition piece) {
        return piece != null && piece.windows != null;
    }

    private static boolean hasExplicitLadderMarkers(CacheBiomeProfileMiner.SettlementPieceDefinition piece) {
        return piece != null && piece.ladders != null;
    }

    private static LadderPlacement resolvePieceLadderPlacement(
            CacheBiomeProfileMiner.SettlementPieceDefinition piece,
            BuildArea buildArea,
            int rotationSteps
    ) {
        if (piece == null || piece.ladders == null || piece.ladders.isEmpty() || buildArea == null) {
            return null;
        }
        CacheBiomeProfileMiner.SettlementPieceMarker marker = piece.ladders.stream()
                .filter(Objects::nonNull)
                .sorted((left, right) -> {
                    int zCompare = Integer.compare(left.z, right.z);
                    if (zCompare != 0) {
                        return zCompare;
                    }
                    int yCompare = Integer.compare(left.y, right.y);
                    if (yCompare != 0) {
                        return yCompare;
                    }
                    return Integer.compare(left.x, right.x);
                })
                .findFirst()
                .orElse(null);
        if (marker == null) {
            return null;
        }
        RotatedPieceMarker rotated = rotatePieceMarker(marker.x, marker.y, piece.width, piece.height, rotationSteps);
        return new LadderPlacement(buildArea.minimumX + 2 + rotated.x, buildArea.minimumY + 2 + rotated.y);
    }

    private static boolean spawnPieceWindowsOnFloor(
            MapRegion mapRegion,
            SceneGraph sceneGraph,
            HouseStyleDefinition.Window window,
            FootprintPlan floorPlan,
            int plane,
            BuildArea buildArea,
            CacheBiomeProfileMiner.SettlementPieceDefinition piece,
            int rotationSteps
    ) {
        if (piece == null || piece.windows == null || piece.windows.isEmpty() || buildArea == null) {
            return false;
        }
        List<BoundaryPlacement> boundaryPlacements = collectBoundaryPlacements(floorPlan, floorPlan).stream()
                .filter(placement -> placement.cornerKind == CornerKind.NONE)
                .toList();
        List<BoundaryPlacement> markerPlacements = resolvePieceBoundaryMarkers(piece.windows, buildArea, piece, rotationSteps, plane, boundaryPlacements);
        if (markerPlacements.isEmpty()) {
            return false;
        }
        int mirrorType = window.mirrorType > 0 ? window.mirrorType : (window.type == 5 ? 4 : window.type);
        for (BoundaryPlacement placement : markerPlacements) {
            WindowSegment segment = createWindowSegment(placement);
            mapRegion.spawnObjectToWorld(sceneGraph, window.id, segment.outerX, segment.outerY, plane, window.type, segment.outerOrientation, false);
            mapRegion.spawnObjectToWorld(sceneGraph, resolveWindowMirrorId(window), segment.innerX, segment.innerY, plane, mirrorType, segment.innerOrientation, false);
        }
        return true;
    }

    private static List<BoundaryPlacement> resolvePieceBoundaryMarkers(
            List<CacheBiomeProfileMiner.SettlementPieceMarker> markers,
            BuildArea buildArea,
            CacheBiomeProfileMiner.SettlementPieceDefinition piece,
            int rotationSteps,
            int floor,
            List<BoundaryPlacement> boundaryPlacements
    ) {
        if (markers == null || markers.isEmpty() || buildArea == null || piece == null || boundaryPlacements == null || boundaryPlacements.isEmpty()) {
            return List.of();
        }
        List<BoundaryPlacement> resolved = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (CacheBiomeProfileMiner.SettlementPieceMarker marker : markers) {
            if (marker == null || marker.z != floor) {
                continue;
            }
            RotatedPieceMarker rotated = rotatePieceMarker(marker.x, marker.y, piece.width, piece.height, rotationSteps);
            int worldX = buildArea.minimumX + 2 + rotated.x;
            int worldY = buildArea.minimumY + 2 + rotated.y;
            for (BoundaryPlacement placement : boundaryPlacements) {
                if (placement.x == worldX && placement.y == worldY) {
                    long key = boundaryPlacementKey(placement.orientation, placement.fixedCoord(), placement.boundaryCoord());
                    if (seen.add(key)) {
                        resolved.add(placement);
                    }
                    break;
                }
            }
        }
        resolved.sort((left, right) -> {
            int orientationCompare = Integer.compare(left.orientation, right.orientation);
            if (orientationCompare != 0) {
                return orientationCompare;
            }
            return Integer.compare(left.boundaryCoord(), right.boundaryCoord());
        });
        return resolved;
    }

    private static RotatedPieceMarker rotatePieceMarker(int x, int y, int width, int height, int rotationSteps) {
        int rotatedX = x;
        int rotatedY = y;
        int rotatedWidth = width;
        int rotatedHeight = height;
        for (int step = 0; step < rotationSteps; step++) {
            int nextX = rotatedHeight - 1 - rotatedY;
            int nextY = rotatedX;
            rotatedX = nextX;
            rotatedY = nextY;
            int nextWidth = rotatedHeight;
            rotatedHeight = rotatedWidth;
            rotatedWidth = nextWidth;
        }
        return new RotatedPieceMarker(rotatedX, rotatedY);
    }

    private static void spawnParapetDecor(MapRegion mapRegion, SceneGraph sceneGraph, HouseStyleDefinition.WallDecor wallDecor,
                                          FootprintPlan roofDeckPlan, FootprintPlan upperFloorPlan, int plane) {
        if (wallDecor == null || wallDecor.parapet == null || wallDecor.parapet.id <= 0) {
            return;
        }

        Set<Long> blockedUpperBoundaryTiles = collectBoundaryTileKeys(upperFloorPlan);
        for (BoundaryPlacement placement : collectBoundaryPlacements(roofDeckPlan, mergeFootprints(roofDeckPlan, upperFloorPlan))) {
            if (blockedUpperBoundaryTiles.contains(tileKey(placement.x, placement.y))) {
                continue;
            }
            if (placement.cornerKind == CornerKind.NONE) {
                mapRegion.spawnObjectToWorld(sceneGraph, wallDecor.parapet.id, placement.x, placement.y, plane, wallDecor.parapet.type, placement.orientation, false);
            }
        }
    }

    private static boolean usesPitchedRoof(HouseStyleDefinition definition) {
        if (definition == null || definition.constraints == null || definition.constraints.roofKind == null) {
            return false;
        }
        String roofKind = definition.constraints.roofKind.trim().toLowerCase();
        return roofKind.contains("pitched");
    }

    private static void spawnPitchedRoof(MapRegion mapRegion, SceneGraph sceneGraph, HouseStyleDefinition.Roof roof,
                                         FootprintPlan footprintPlan, int plane) {
        if (roof == null || footprintPlan == null || footprintPlan.occupiedTiles == null || footprintPlan.occupiedTiles.isEmpty()) {
            return;
        }
        RectangleBounds occupiedBounds = computeOccupiedBounds(footprintPlan);
        if (occupiedBounds == null || occupiedBounds.area() != footprintPlan.occupiedTiles.size()) {
            return;
        }

        int minX = occupiedBounds.minX;
        int minY = occupiedBounds.minY;
        int maxX = occupiedBounds.maxX;
        int maxY = occupiedBounds.maxY;

        for (int y = minY + 1; y <= maxY - 1; y++) {
            spawnObject(mapRegion, sceneGraph, roof.topSide, minX, y, plane, 2);
            spawnObject(mapRegion, sceneGraph, roof.topSide, maxX, y, plane, 0);
        }
        for (int x = minX + 1; x <= maxX - 1; x++) {
            spawnObject(mapRegion, sceneGraph, roof.topSide, x, minY, plane, 1);
            spawnObject(mapRegion, sceneGraph, roof.topSide, x, maxY, plane, 3);
        }

        for (int y = minY; y <= maxY; y++) {
            spawnObject(mapRegion, sceneGraph, roof.edgeSide, minX - 1, y, plane, 2);
            spawnObject(mapRegion, sceneGraph, roof.edgeSide, maxX + 1, y, plane, 0);
        }
        for (int x = minX; x <= maxX; x++) {
            spawnObject(mapRegion, sceneGraph, roof.edgeSide, x, minY - 1, plane, 1);
            spawnObject(mapRegion, sceneGraph, roof.edgeSide, x, maxY + 1, plane, 3);
        }

        spawnObject(mapRegion, sceneGraph, roof.edgeCorner, minX - 1, minY - 1, plane, 1);
        spawnObject(mapRegion, sceneGraph, roof.edgeCorner, minX - 1, maxY + 1, plane, 2);
        spawnObject(mapRegion, sceneGraph, roof.edgeCorner, maxX + 1, maxY + 1, plane, 3);
        spawnObject(mapRegion, sceneGraph, roof.edgeCorner, maxX + 1, minY - 1, plane, 0);

        spawnObject(mapRegion, sceneGraph, roof.topCorner, minX, minY, plane, 1);
        spawnObject(mapRegion, sceneGraph, roof.topCorner, minX, maxY, plane, 2);
        spawnObject(mapRegion, sceneGraph, roof.topCorner, maxX, maxY, plane, 3);
        spawnObject(mapRegion, sceneGraph, roof.topCorner, maxX, minY, plane, 0);

        if (roof.topFlat != null && roof.topFlat.id > 0) {
            for (int x = minX + 1; x <= maxX - 1; x++) {
                for (int y = minY + 1; y <= maxY - 1; y++) {
                    spawnObject(mapRegion, sceneGraph, roof.topFlat, x, y, plane, 0);
                }
            }
        }
    }

    private static List<FootprintPlan> resolveFloorPlans(BuildArea baseArea, int requestedFloors, int frontOrientation,
                                                         boolean tieredFlooring, int tierFrontInset, int tierSideInset,
                                                         HouseStyleDefinition.Footprints footprintConfig,
                                                         HouseStyleDefinition.FootprintShape forcedFootprintShape,
                                                         CacheBiomeProfileMiner.SettlementPieceDefinition piece) {
        List<FootprintPlan> floorPlans = new ArrayList<>();
        if (!isUsableFloorArea(baseArea)) {
            return floorPlans;
        }
        FootprintPlan currentPlan = createBaseFootprintPlan(baseArea, frontOrientation, footprintConfig, forcedFootprintShape, piece);
        if (!isUsableFootprintPlan(currentPlan)) {
            return floorPlans;
        }
        floorPlans.add(currentPlan);
        if (requestedFloors <= 1) {
            return floorPlans;
        }

        BuildArea currentArea = baseArea;
        for (int floor = 1; floor < requestedFloors; floor++) {
            BuildArea nextArea = tieredFlooring
                    ? insetBuildAreaForTieredUpperFloor(currentArea, frontOrientation, tierFrontInset, tierSideInset)
                    : currentArea;
            if (!isUsableFloorArea(nextArea)) {
                break;
            }
            FootprintPlan nextPlan = clipFootprintPlan(currentPlan, nextArea);
            if (!isUsableFootprintPlan(nextPlan) || !isConnectedFootprint(nextPlan)) {
                nextPlan = createRectFootprintPlan(nextArea);
            }
            if (!isUsableFootprintPlan(nextPlan)) {
                break;
            }
            floorPlans.add(nextPlan);
            currentArea = nextArea;
            currentPlan = nextPlan;
        }
        return floorPlans;
    }

    private static LadderSpec resolveLadderSpec(HouseStyleDefinition.Ladder ladder) {
        return new LadderSpec(
                ladder == null || ladder.groundFloorId <= 0 ? DEFAULT_GROUND_LADDER_ID : ladder.groundFloorId,
                ladder == null || ladder.midFloorId <= 0 ? DEFAULT_MID_LADDER_ID : ladder.midFloorId,
                ladder == null || ladder.topFloorId <= 0 ? DEFAULT_TOP_LADDER_ID : ladder.topFloorId
        );
    }

    private static FootprintPlan createBaseFootprintPlan(BuildArea buildArea, int frontOrientation,
                                                         HouseStyleDefinition.Footprints footprintConfig,
                                                         HouseStyleDefinition.FootprintShape forcedFootprintShape,
                                                         CacheBiomeProfileMiner.SettlementPieceDefinition piece) {
        FootprintPlan maskedPlan = createMaskedFootprintPlan(buildArea, piece, frontOrientation);
        if (isUsableFootprintPlan(maskedPlan)) {
            return maskedPlan;
        }
        FootprintFrame frame = new FootprintFrame(buildArea, frontOrientation);
        HouseStyleDefinition.FootprintShape shape = forcedFootprintShape != null
                ? forcedFootprintShape
                : resolveFootprintShape(frame, footprintConfig);
        return switch (shape) {
            case L -> createLFootprintPlan(frame, footprintConfig);
            case U -> createUFootprintPlan(frame, footprintConfig);
            case RECT -> createRectFootprintPlan(buildArea);
        };
    }

    private static FootprintPlan createMaskedFootprintPlan(BuildArea buildArea,
                                                           CacheBiomeProfileMiner.SettlementPieceDefinition piece,
                                                           int frontOrientation) {
        if (buildArea == null || piece == null || piece.mask == null || piece.mask.isEmpty()) {
            return null;
        }
        int rotationSteps = Math.floorMod(frontOrientation - normalizePieceFrontOrientation(piece.frontOrientation), 4);
        LinkedHashSet<Long> occupiedTiles = new LinkedHashSet<>();
        int originX = buildArea.minimumX + 2;
        int originY = buildArea.minimumY + 2;
        for (int rowIndex = 0; rowIndex < piece.mask.size(); rowIndex++) {
            String row = piece.mask.get(rowIndex);
            if (row == null) {
                continue;
            }
            for (int columnIndex = 0; columnIndex < row.length(); columnIndex++) {
                if (row.charAt(columnIndex) != '1') {
                    continue;
                }
                RotatedPieceMarker rotated = rotatePieceMarker(columnIndex, rowIndex, piece.width, piece.height, rotationSteps);
                occupiedTiles.add(tileKey(originX + rotated.x, originY + rotated.y));
            }
        }
        return occupiedTiles.isEmpty() ? null : new FootprintPlan(buildArea, occupiedTiles);
    }

    private static FootprintPlan createRectFootprintPlan(BuildArea buildArea) {
        LinkedHashSet<Long> occupiedTiles = new LinkedHashSet<>();
        for (int x = buildArea.minimumX + 2; x <= buildArea.maximumX - 2; x++) {
            for (int y = buildArea.minimumY + 2; y <= buildArea.maximumY - 2; y++) {
                occupiedTiles.add(tileKey(x, y));
            }
        }
        return new FootprintPlan(buildArea, occupiedTiles);
    }

    private static FootprintPlan createLFootprintPlan(FootprintFrame frame, HouseStyleDefinition.Footprints footprintConfig) {
        int minWingWidth = resolveMinWingWidth(footprintConfig);
        int minWingDepth = resolveMinWingDepth(footprintConfig);
        int minNotchWidth = resolveMinCourtyardWidth(footprintConfig);
        int minNotchDepth = resolveMinCourtyardDepth(footprintConfig);
        if (frame.acrossSize < minWingWidth + minNotchWidth || frame.depthSize < minWingDepth + minNotchDepth) {
            return createRectFootprintPlan(frame.buildArea);
        }

        int seed = footprintSeed(frame.buildArea, frame.frontOrientation, 17);
        int cutAcross = deterministicRange(
                seed,
                scaledMinimum(minNotchWidth, frame.acrossSize, 0.35, frame.acrossSize - minWingWidth),
                frame.acrossSize - minWingWidth
        );
        int cutDepth = deterministicRange(
                seed * 31 + 7,
                scaledMinimum(minNotchDepth, frame.depthSize, 0.45, frame.depthSize - minWingDepth),
                frame.depthSize - minWingDepth
        );
        boolean cutNearStart = (seed & 1) == 0;

        LinkedHashSet<Long> occupiedTiles = new LinkedHashSet<>();
        int cutAcrossStart = cutNearStart ? 0 : frame.acrossSize - cutAcross;
        int cutAcrossEnd = cutAcrossStart + cutAcross - 1;
        for (int across = 0; across < frame.acrossSize; across++) {
            for (int depth = 0; depth < frame.depthSize; depth++) {
                boolean removed = across >= cutAcrossStart
                        && across <= cutAcrossEnd
                        && depth < cutDepth;
                if (!removed) {
                    occupiedTiles.add(frame.tileKey(across, depth));
                }
            }
        }
        return new FootprintPlan(frame.buildArea, occupiedTiles);
    }

    private static FootprintPlan createUFootprintPlan(FootprintFrame frame, HouseStyleDefinition.Footprints footprintConfig) {
        int minWingWidth = resolveMinWingWidth(footprintConfig);
        int minWingDepth = resolveMinWingDepth(footprintConfig);
        int minCourtyardWidth = resolveMinCourtyardWidth(footprintConfig);
        int minCourtyardDepth = resolveMinCourtyardDepth(footprintConfig);
        if (frame.acrossSize < minWingWidth * 2 + minCourtyardWidth
                || frame.depthSize < minWingDepth + minCourtyardDepth) {
            return createRectFootprintPlan(frame.buildArea);
        }

        int seed = footprintSeed(frame.buildArea, frame.frontOrientation, 29);
        int maxCourtyardWidth = frame.acrossSize - minWingWidth * 2;
        int maxCourtyardDepth = frame.depthSize - minWingDepth;
        int courtyardWidth = deterministicRange(
                seed,
                scaledMinimum(minCourtyardWidth, frame.acrossSize, 0.30, maxCourtyardWidth),
                maxCourtyardWidth
        );
        int courtyardDepth = deterministicRange(
                seed * 37 + 11,
                scaledMinimum(minCourtyardDepth, frame.depthSize, 0.45, maxCourtyardDepth),
                maxCourtyardDepth
        );
        int courtyardStartAcross = (frame.acrossSize - courtyardWidth) / 2;
        int courtyardEndAcross = courtyardStartAcross + courtyardWidth - 1;

        LinkedHashSet<Long> occupiedTiles = new LinkedHashSet<>();
        for (int across = 0; across < frame.acrossSize; across++) {
            for (int depth = 0; depth < frame.depthSize; depth++) {
                boolean removed = across >= courtyardStartAcross
                        && across <= courtyardEndAcross
                        && depth < courtyardDepth;
                if (!removed) {
                    occupiedTiles.add(frame.tileKey(across, depth));
                }
            }
        }
        return new FootprintPlan(frame.buildArea, occupiedTiles);
    }

    private static HouseStyleDefinition.FootprintShape resolveFootprintShape(FootprintFrame frame,
                                                                             HouseStyleDefinition.Footprints footprintConfig) {
        List<WeightedShape> candidates = new ArrayList<>();
        int rectWeight = resolveShapeWeight(footprintConfig, HouseStyleDefinition.FootprintShape.RECT);
        if (isShapeAllowed(footprintConfig, HouseStyleDefinition.FootprintShape.RECT) && rectWeight > 0) {
            candidates.add(new WeightedShape(HouseStyleDefinition.FootprintShape.RECT, rectWeight));
        }
        int lWeight = resolveShapeWeight(footprintConfig, HouseStyleDefinition.FootprintShape.L);
        if (isShapeAllowed(footprintConfig, HouseStyleDefinition.FootprintShape.L)
                && lWeight > 0
                && canSupportLFootprint(frame, footprintConfig)) {
            candidates.add(new WeightedShape(HouseStyleDefinition.FootprintShape.L, lWeight));
        }
        int uWeight = resolveShapeWeight(footprintConfig, HouseStyleDefinition.FootprintShape.U);
        if (isShapeAllowed(footprintConfig, HouseStyleDefinition.FootprintShape.U)
                && uWeight > 0
                && canSupportUFootprint(frame, footprintConfig)) {
            candidates.add(new WeightedShape(HouseStyleDefinition.FootprintShape.U, uWeight));
        }
        if (candidates.isEmpty()) {
            return HouseStyleDefinition.FootprintShape.RECT;
        }

        int totalWeight = candidates.stream().mapToInt(candidate -> candidate.weight).sum();
        int roll = Math.floorMod(footprintSeed(frame.buildArea, frame.frontOrientation, 3), totalWeight);
        for (WeightedShape candidate : candidates) {
            if (roll < candidate.weight) {
                return candidate.shape;
            }
            roll -= candidate.weight;
        }
        return HouseStyleDefinition.FootprintShape.RECT;
    }

    private static boolean isShapeAllowed(HouseStyleDefinition.Footprints footprintConfig, HouseStyleDefinition.FootprintShape shape) {
        return footprintConfig != null
                && footprintConfig.allowedShapes != null
                && footprintConfig.allowedShapes.contains(shape);
    }

    private static int resolveShapeWeight(HouseStyleDefinition.Footprints footprintConfig, HouseStyleDefinition.FootprintShape shape) {
        if (footprintConfig == null) {
            return shape == HouseStyleDefinition.FootprintShape.RECT ? 1 : 0;
        }
        int configuredWeight = switch (shape) {
            case RECT -> footprintConfig.rectWeight;
            case L -> footprintConfig.lWeight;
            case U -> footprintConfig.uWeight;
        };
        return Math.max(0, configuredWeight);
    }

    private static boolean canSupportLFootprint(FootprintFrame frame, HouseStyleDefinition.Footprints footprintConfig) {
        return frame.acrossSize >= resolveMinWingWidth(footprintConfig) + resolveMinCourtyardWidth(footprintConfig)
                && frame.depthSize >= resolveMinWingDepth(footprintConfig) + resolveMinCourtyardDepth(footprintConfig);
    }

    private static boolean canSupportUFootprint(FootprintFrame frame, HouseStyleDefinition.Footprints footprintConfig) {
        return frame.acrossSize >= resolveMinWingWidth(footprintConfig) * 2 + resolveMinCourtyardWidth(footprintConfig)
                && frame.depthSize >= resolveMinWingDepth(footprintConfig) + resolveMinCourtyardDepth(footprintConfig);
    }

    private static int resolveMinWingWidth(HouseStyleDefinition.Footprints footprintConfig) {
        return footprintConfig != null && footprintConfig.minWingWidth > 0 ? footprintConfig.minWingWidth : MINIMUM_USABLE_FLOOR_SPAN;
    }

    private static int resolveMinWingDepth(HouseStyleDefinition.Footprints footprintConfig) {
        return footprintConfig != null && footprintConfig.minWingDepth > 0 ? footprintConfig.minWingDepth : MINIMUM_USABLE_FLOOR_SPAN;
    }

    private static int resolveMinCourtyardWidth(HouseStyleDefinition.Footprints footprintConfig) {
        return footprintConfig != null && footprintConfig.minCourtyardWidth > 0 ? footprintConfig.minCourtyardWidth : MINIMUM_USABLE_FLOOR_SPAN;
    }

    private static int resolveMinCourtyardDepth(HouseStyleDefinition.Footprints footprintConfig) {
        return footprintConfig != null && footprintConfig.minCourtyardDepth > 0 ? footprintConfig.minCourtyardDepth : MINIMUM_USABLE_FLOOR_SPAN;
    }

    private static int footprintSeed(BuildArea buildArea, int frontOrientation, int salt) {
        int seed = 17;
        seed = seed * 31 + buildArea.minimumX;
        seed = seed * 31 + buildArea.minimumY;
        seed = seed * 31 + buildArea.maximumX;
        seed = seed * 31 + buildArea.maximumY;
        seed = seed * 31 + frontOrientation;
        seed = seed * 31 + salt;
        return seed;
    }

    private static int deterministicRange(int seed, int minimum, int maximum) {
        if (maximum <= minimum) {
            return minimum;
        }
        return minimum + Math.floorMod(seed, maximum - minimum + 1);
    }

    private static int scaledMinimum(int configuredMinimum, int size, double fraction, int absoluteMaximum) {
        int scaled = Math.max(configuredMinimum, (int) Math.ceil(size * fraction));
        return Math.min(Math.max(configuredMinimum, scaled), absoluteMaximum);
    }

    private static BuildArea insetBuildArea(BuildArea buildArea, int amount) {
        return new BuildArea(
                List.of(),
                buildArea.averageTileHeight,
                buildArea.minimumX + amount,
                buildArea.minimumY + amount,
                buildArea.maximumX - amount,
                buildArea.maximumY - amount
        );
    }

    private static BuildArea insetBuildAreaForTieredUpperFloor(BuildArea buildArea, int frontOrientation, int frontInset, int sideInset) {
        int minimumX = buildArea.minimumX;
        int minimumY = buildArea.minimumY;
        int maximumX = buildArea.maximumX;
        int maximumY = buildArea.maximumY;

        switch (frontOrientation & 3) {
            case 0 -> {
                maximumX -= frontInset;
                minimumY += sideInset;
                maximumY -= sideInset;
            }
            case 1 -> {
                minimumY += frontInset;
                minimumX += sideInset;
                maximumX -= sideInset;
            }
            case 2 -> {
                minimumX += frontInset;
                minimumY += sideInset;
                maximumY -= sideInset;
            }
            case 3 -> {
                maximumY -= frontInset;
                minimumX += sideInset;
                maximumX -= sideInset;
            }
            default -> {
                return insetBuildArea(buildArea, 1);
            }
        }

        return new BuildArea(List.of(), buildArea.averageTileHeight, minimumX, minimumY, maximumX, maximumY);
    }

    private static boolean isUsableFloorArea(BuildArea buildArea) {
        return interiorFloorWidth(buildArea) >= MINIMUM_USABLE_FLOOR_SPAN
                && interiorFloorHeight(buildArea) >= MINIMUM_USABLE_FLOOR_SPAN;
    }

    private static boolean isUsableFootprintPlan(FootprintPlan footprintPlan) {
        return footprintPlan != null
                && footprintPlan.occupiedTiles != null
                && !footprintPlan.occupiedTiles.isEmpty()
                && footprintPlan.occupiedTiles.size() >= MINIMUM_USABLE_FLOOR_SPAN * MINIMUM_USABLE_FLOOR_SPAN;
    }

    private static int interiorFloorWidth(BuildArea buildArea) {
        return Math.max(0, buildArea.maximumX - buildArea.minimumX - 3);
    }

    private static int interiorFloorHeight(BuildArea buildArea) {
        return Math.max(0, buildArea.maximumY - buildArea.minimumY - 3);
    }

    private static boolean isConnectedFootprint(FootprintPlan footprintPlan) {
        if (!isUsableFootprintPlan(footprintPlan)) {
            return false;
        }
        ArrayDeque<Long> queue = new ArrayDeque<>();
        Set<Long> visited = new LinkedHashSet<>();
        long start = footprintPlan.occupiedTiles.iterator().next();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            long tile = queue.removeFirst();
            int x = decodeX(tile);
            int y = decodeY(tile);
            for (long neighbour : List.of(tileKey(x - 1, y), tileKey(x + 1, y), tileKey(x, y - 1), tileKey(x, y + 1))) {
                if (footprintPlan.occupiedTiles.contains(neighbour) && visited.add(neighbour)) {
                    queue.addLast(neighbour);
                }
            }
        }
        return visited.size() == footprintPlan.occupiedTiles.size();
    }

    private static FootprintPlan clipFootprintPlan(FootprintPlan sourcePlan, BuildArea clippedArea) {
        LinkedHashSet<Long> clippedTiles = new LinkedHashSet<>();
        int minX = clippedArea.minimumX + 2;
        int maxX = clippedArea.maximumX - 2;
        int minY = clippedArea.minimumY + 2;
        int maxY = clippedArea.maximumY - 2;
        for (long tile : sourcePlan.occupiedTiles) {
            int x = decodeX(tile);
            int y = decodeY(tile);
            if (x >= minX && x <= maxX && y >= minY && y <= maxY) {
                clippedTiles.add(tile);
            }
        }
        return new FootprintPlan(clippedArea, clippedTiles);
    }

    private static FootprintPlan deriveVisibleRoofDeckPlan(FootprintPlan floorPlan, FootprintPlan upperFloorPlan) {
        LinkedHashSet<Long> visibleTiles = new LinkedHashSet<>(floorPlan.occupiedTiles);
        if (upperFloorPlan != null) {
            visibleTiles.removeAll(upperFloorPlan.occupiedTiles);
        }
        return new FootprintPlan(floorPlan.bounds, visibleTiles);
    }

    private static boolean isUsableRoofDeck(FootprintPlan roofDeckPlan) {
        return roofDeckPlan != null && roofDeckPlan.occupiedTiles != null && !roofDeckPlan.occupiedTiles.isEmpty();
    }

    private static FootprintPlan mergeFootprints(FootprintPlan primary, FootprintPlan secondary) {
        LinkedHashSet<Long> mergedTiles = new LinkedHashSet<>();
        if (primary != null && primary.occupiedTiles != null) {
            mergedTiles.addAll(primary.occupiedTiles);
        }
        if (secondary != null && secondary.occupiedTiles != null) {
            mergedTiles.addAll(secondary.occupiedTiles);
        }
        return new FootprintPlan(primary == null ? (secondary == null ? null : secondary.bounds) : primary.bounds, mergedTiles);
    }

    private static LadderPlacement resolveLadderPlacement(List<FootprintPlan> floorPlans, DoorPlacement doorPlacement, boolean tieredFlooring) {
        if (floorPlans == null || floorPlans.size() <= 1) {
            return null;
        }

        FootprintPlan ladderPlan = tieredFlooring ? floorPlans.get(floorPlans.size() - 1) : floorPlans.get(0);
        List<LadderPlacement> candidates = collectInteriorPlacements(ladderPlan);
        if (candidates.isEmpty()) {
            return null;
        }

        LadderPlacement blockedPlacement = tieredFlooring ? null : resolveBlockedBehindDoor(ladderPlan.bounds, doorPlacement);
        LadderPlacement targetPlacement = resolveTargetLadderPlacement(ladderPlan, doorPlacement, tieredFlooring);

        LadderPlacement bestPlacement = null;
        int bestScore = Integer.MAX_VALUE;
        for (LadderPlacement candidate : candidates) {
            if (candidate.matches(blockedPlacement)) {
                continue;
            }
            int score = Math.abs(candidate.x - targetPlacement.x) + Math.abs(candidate.y - targetPlacement.y);
            if (bestPlacement == null || score < bestScore) {
                bestPlacement = candidate;
                bestScore = score;
            }
        }

        return bestPlacement != null ? bestPlacement : candidates.get(0);
    }

    private static LadderPlacement resolveTargetLadderPlacement(FootprintPlan ladderPlan, DoorPlacement doorPlacement, boolean tieredFlooring) {
        int centerX = (ladderPlan.bounds.minimumX + ladderPlan.bounds.maximumX) / 2;
        int centerY = (ladderPlan.bounds.minimumY + ladderPlan.bounds.maximumY) / 2;
        if (!tieredFlooring || doorPlacement == null) {
            return new LadderPlacement(centerX, centerY);
        }

        return switch (doorPlacement.orientation & 3) {
            case 0 -> new LadderPlacement(centerX - 1, centerY);
            case 1 -> new LadderPlacement(centerX, centerY + 1);
            case 2 -> new LadderPlacement(centerX + 1, centerY);
            case 3 -> new LadderPlacement(centerX, centerY - 1);
            default -> new LadderPlacement(centerX, centerY);
        };
    }

    private static List<LadderPlacement> collectInteriorPlacements(FootprintPlan footprintPlan) {
        List<LadderPlacement> placements = new ArrayList<>();
        for (long tile : footprintPlan.occupiedTiles) {
            placements.add(new LadderPlacement(decodeX(tile), decodeY(tile)));
        }
        return placements;
    }

    private static LadderPlacement resolveBlockedBehindDoor(BuildArea buildArea, DoorPlacement doorPlacement) {
        if (buildArea == null || doorPlacement == null) {
            return null;
        }
        int centreCoord = doorPlacement.coord + Math.max(0, doorPlacement.width - 1) / 2;
        return switch (doorPlacement.orientation & 3) {
            case 0 -> new LadderPlacement(buildArea.maximumX - 2, centreCoord);
            case 1 -> new LadderPlacement(centreCoord, buildArea.minimumY + 2);
            case 2 -> new LadderPlacement(buildArea.minimumX + 2, centreCoord);
            case 3 -> new LadderPlacement(centreCoord, buildArea.maximumY - 2);
            default -> null;
        };
    }

    private static void stampLadders(MapRegion mapRegion, SceneGraph sceneGraph, LadderPlacement ladderPlacement,
                                     LadderSpec ladderSpec, int floors) {
        if (ladderPlacement == null || ladderSpec == null || floors <= 1) {
            return;
        }

        for (int floor = 0; floor < floors; floor++) {
            int objectId = resolveLadderObjectIdForFloor(ladderSpec, floor, floors);
            if (objectId <= 0) {
                continue;
            }
            if (floor > 0) {
                clearOverlay(mapRegion, sceneGraph, floor, ladderPlacement.x, ladderPlacement.y);
            }
            mapRegion.spawnObjectToWorld(
                    sceneGraph,
                    objectId,
                    ladderPlacement.x,
                    ladderPlacement.y,
                    floor,
                    LADDER_OBJECT_TYPE,
                    LADDER_ORIENTATION,
                    false
            );
        }
    }

    private static int resolveLadderObjectIdForFloor(LadderSpec ladderSpec, int floor, int floors) {
        if (floor <= 0) {
            return ladderSpec.groundFloorId;
        }
        if (floor == floors - 1) {
            return ladderSpec.topFloorId;
        }
        return ladderSpec.midFloorId;
    }

    private static DoorPlacement resolveDoorPlacementForFootprint(FootprintPlan footprintPlan, DoorPlacement requestedDoorPlacement,
                                                                  HouseStyleDefinition.Shell shell) {
        if (footprintPlan == null || requestedDoorPlacement == null) {
            return requestedDoorPlacement;
        }
        int orientation = requestedDoorPlacement.orientation & 3;
        int preferredWidth = shouldUseDoubleDoor(footprintPlan, orientation, shell) ? 2 : 1;
        DoorPlacement bestPlacement = findBestDoorPlacement(footprintPlan, orientation, requestedDoorPlacement.coord, preferredWidth);
        if (bestPlacement != null) {
            return bestPlacement;
        }
        return findBestDoorPlacement(footprintPlan, orientation, requestedDoorPlacement.coord, 1);
    }

    private static DoorPlacement findBestDoorPlacement(FootprintPlan footprintPlan, int orientation, int requestedCoord, int width) {
        List<BoundaryPlacement> placements = collectBoundaryPlacements(footprintPlan, footprintPlan);
        return findBestDoorPlacement(placements, orientation, requestedCoord, width);
    }

    private static DoorPlacement findBestDoorPlacement(List<BoundaryPlacement> boundaryPlacements, int orientation, int requestedCoord, int width) {
        List<BoundaryPlacement> placements = boundaryPlacements.stream()
                .filter(placement -> placement.cornerKind == CornerKind.NONE && placement.orientation == orientation)
                .sorted((left, right) -> Integer.compare(left.boundaryCoord(), right.boundaryCoord()))
                .toList();
        DoorPlacement bestPlacement = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int index = 0; index < placements.size(); index++) {
            if (!isValidDoorRun(placements, index, width)) {
                continue;
            }
            int startCoord = placements.get(index).boundaryCoord();
            int distance = Math.abs(startCoord - requestedCoord);
            if (bestPlacement == null || distance < bestDistance) {
                bestPlacement = new DoorPlacement(orientation, startCoord, width);
                bestDistance = distance;
            }
        }
        return bestPlacement;
    }

    private static boolean shouldUseDoubleDoor(FootprintPlan footprintPlan, int orientation, HouseStyleDefinition.Shell shell) {
        if (shell == null || shell.doubleDoors == null || shell.doubleDoors.isEmpty()) {
            return false;
        }
        int frontageSpan = wallRunLength(collectBoundaryPlacements(footprintPlan, footprintPlan), orientation);
        int minimumRequiredSpan = shell.doubleDoors.stream()
                .filter(doubleDoor -> doubleDoor != null && doubleDoor.leftDoorId > 0 && doubleDoor.rightDoorId > 0)
                .mapToInt(doubleDoor -> doubleDoor.minFrontageSpan > 0 ? doubleDoor.minFrontageSpan : 8)
                .min()
                .orElse(Integer.MAX_VALUE);
        return frontageSpan >= minimumRequiredSpan;
    }

    private static int wallRunLength(FootprintPlan footprintPlan, int orientation) {
        return wallRunLength(collectBoundaryPlacements(footprintPlan, footprintPlan), orientation);
    }

    private static int wallRunLength(List<BoundaryPlacement> placements, int orientation) {
        return (int) placements.stream()
                .filter(placement -> placement.cornerKind == CornerKind.NONE && placement.orientation == orientation)
                .count();
    }

    private static boolean shouldUseDoubleDoorOnPlacements(List<BoundaryPlacement> placements, int orientation, HouseStyleDefinition.Shell shell) {
        if (shell == null || shell.doubleDoors == null || shell.doubleDoors.isEmpty()) {
            return false;
        }
        int frontageSpan = wallRunLength(placements, orientation);
        int minimumRequiredSpan = shell.doubleDoors.stream()
                .filter(doubleDoor -> doubleDoor != null && doubleDoor.leftDoorId > 0 && doubleDoor.rightDoorId > 0)
                .mapToInt(doubleDoor -> doubleDoor.minFrontageSpan > 0 ? doubleDoor.minFrontageSpan : 8)
                .min()
                .orElse(Integer.MAX_VALUE);
        return frontageSpan >= minimumRequiredSpan;
    }

    private static boolean isValidDoorRun(List<BoundaryPlacement> placements, int index, int width) {
        if (index < 0 || index + width > placements.size()) {
            return false;
        }
        for (int offset = 1; offset < width; offset++) {
            if (placements.get(index + offset).boundaryCoord() != placements.get(index).boundaryCoord() + offset) {
                return false;
            }
        }
        if (index == 0 || index + width >= placements.size()) {
            return false;
        }
        int beforeCoord = placements.get(index - 1).boundaryCoord();
        int startCoord = placements.get(index).boundaryCoord();
        int endCoord = placements.get(index + width - 1).boundaryCoord();
        int afterCoord = placements.get(index + width).boundaryCoord();
        return beforeCoord == startCoord - 1 && afterCoord == endCoord + 1;
    }

    private static int resolveDoorObjectId(HouseStyleDefinition.Shell shell, DoorPlacement doorPlacement, BoundaryPlacement placement) {
        if (shell == null || doorPlacement == null || placement == null) {
            return -1;
        }
        if (!doorPlacement.matches(placement)) {
            return -1;
        }
        if (doorPlacement.width == 2 && shell.doubleDoors != null) {
            for (HouseStyleDefinition.DoubleDoor doubleDoor : shell.doubleDoors) {
                if (doubleDoor == null || doubleDoor.leftDoorId <= 0 || doubleDoor.rightDoorId <= 0) {
                    continue;
                }
                boolean firstPlacement = placement.boundaryCoord() == doorPlacement.coord;
                boolean leftDoorFirst = isLeftDoorFirst(placement.orientation);
                if (leftDoorFirst) {
                    return firstPlacement ? doubleDoor.leftDoorId : doubleDoor.rightDoorId;
                }
                return firstPlacement ? doubleDoor.rightDoorId : doubleDoor.leftDoorId;
            }
        }
        return shell.door == null ? -1 : shell.door.id;
    }

    private static boolean isLeftDoorFirst(int orientation) {
        int normalized = orientation & 3;
        return normalized == 0 || normalized == 1;
    }

    private static List<DoorPlacement> resolveFloorDoorPlacements(List<FootprintPlan> floorPlans,
                                                                  DoorPlacement groundDoorPlacement,
                                                                  HouseStyleDefinition.Shell shell,
                                                                  boolean tieredFlooring) {
        List<DoorPlacement> floorDoorPlacements = new ArrayList<>(floorPlans.size());
        if (floorPlans.isEmpty()) {
            return floorDoorPlacements;
        }
        floorDoorPlacements.add(groundDoorPlacement);
        for (int floor = 1; floor < floorPlans.size(); floor++) {
            if (!tieredFlooring) {
                floorDoorPlacements.add(null);
                continue;
            }
            DoorPlacement preferredDoor = floorDoorPlacements.get(floor - 1);
            FootprintPlan floorPlan = floorPlans.get(floor);
            FootprintPlan terracePlan = deriveVisibleRoofDeckPlan(floorPlans.get(floor - 1), floorPlan);
            DoorPlacement upperDoorPlacement = tieredFlooring
                    ? resolveTerraceDoorPlacement(floorPlan, terracePlan, preferredDoor, shell)
                    : resolveDoorPlacementForFootprint(floorPlan, preferredDoor, shell);
            floorDoorPlacements.add(upperDoorPlacement);
        }
        return floorDoorPlacements;
    }

    private static DoorPlacement resolveTerraceDoorPlacement(FootprintPlan floorPlan, FootprintPlan terracePlan,
                                                             DoorPlacement preferredDoor, HouseStyleDefinition.Shell shell) {
        if (floorPlan == null || terracePlan == null || terracePlan.occupiedTiles.isEmpty() || preferredDoor == null) {
            return preferredDoor;
        }

        List<BoundaryPlacement> terracePlacements = collectBoundaryPlacements(floorPlan, floorPlan).stream()
                .filter(placement -> placement.cornerKind == CornerKind.NONE)
                .filter(placement -> terracePlan.occupiedTiles.contains(tileKey(placement.x, placement.y)))
                .toList();
        if (terracePlacements.isEmpty()) {
            return preferredDoor;
        }

        DoorPlacement bestDoor = findBestDoorPlacement(
                terracePlacements,
                preferredDoor.orientation & 3,
                preferredDoor.coord,
                shouldUseDoubleDoorOnPlacements(terracePlacements, preferredDoor.orientation & 3, shell) ? 2 : 1
        );
        if (bestDoor != null) {
            return bestDoor;
        }

        for (int orientation = 0; orientation < 4; orientation++) {
            DoorPlacement candidate = findBestDoorPlacement(
                    terracePlacements,
                    orientation,
                    preferredDoor.coord,
                    shouldUseDoubleDoorOnPlacements(terracePlacements, orientation, shell) ? 2 : 1
            );
            if (candidate != null) {
                return candidate;
            }
        }
        return preferredDoor;
    }

    private static void spawnWindowsOnFloor(MapRegion mapRegion, SceneGraph sceneGraph, HouseStyleDefinition.Window window,
                                            FootprintPlan footprintPlan, int plane, DoorPlacement doorPlacement) {
        List<WindowRun> runs = collectWindowRuns(footprintPlan, doorPlacement);
        if (runs.isEmpty()) {
            return;
        }

        int interiorArea = footprintPlan.occupiedTiles.size();
        int minCount = window.minCount > 0 ? window.minCount : 1;
        int areaPerWindow = window.areaPerWindow > 0 ? window.areaPerWindow : 24;
        int areaDrivenCount = Math.max(minCount, (int) Math.ceil(interiorArea / (double) areaPerWindow));
        int runDrivenCount = Math.max(minCount, (int) Math.ceil(
                runs.stream().mapToInt(run -> run.eligibleSegments().size()).sum() / (double) WINDOW_RUN_TILES_PER_WINDOW
        ));
        int targetCount = Math.max(areaDrivenCount, runDrivenCount);
        int windowCount = Math.min(runs.stream().mapToInt(WindowRun::maxWindowCount).sum(), targetCount);
        if (windowCount <= 0) {
            return;
        }

        boolean spawnMirror = shouldSpawnWindowMirror(window);
        int mirrorType = window.mirrorType > 0 ? window.mirrorType : (window.type == 5 ? 4 : window.type);
        for (WindowRunAllocation allocation : allocateWindowRuns(runs, windowCount)) {
            for (WindowSegment segment : chooseRunWindowSegments(allocation.run(), allocation.count())) {
                mapRegion.spawnObjectToWorld(sceneGraph, window.id, segment.outerX, segment.outerY, plane, window.type, segment.outerOrientation, false);
                if (spawnMirror) {
                    mapRegion.spawnObjectToWorld(sceneGraph, resolveWindowMirrorId(window), segment.innerX, segment.innerY, plane, mirrorType, segment.innerOrientation, false);
                }
            }
        }
    }

    private static boolean shouldSpawnWindowMirror(HouseStyleDefinition.Window window) {
        if (window == null) {
            return false;
        }
        return window.mirrorId > 0 || window.mirrorType > 0 || window.type == 5;
    }

    private static int resolveWindowMirrorId(HouseStyleDefinition.Window window) {
        if (window == null) {
            return -1;
        }
        return window.mirrorId > 0 ? window.mirrorId : window.id;
    }

    private static List<WindowRun> collectWindowRuns(FootprintPlan footprintPlan, DoorPlacement doorPlacement) {
        List<BoundaryPlacement> allPlacements = collectBoundaryPlacements(footprintPlan, footprintPlan).stream()
                .filter(placement -> placement.cornerKind == CornerKind.NONE)
                .sorted((left, right) -> {
                    int compareOrientation = Integer.compare(left.orientation, right.orientation);
                    if (compareOrientation != 0) {
                        return compareOrientation;
                    }
                    int compareLine = Integer.compare(left.fixedCoord(), right.fixedCoord());
                    if (compareLine != 0) {
                        return compareLine;
                    }
                    return Integer.compare(left.boundaryCoord(), right.boundaryCoord());
                })
                .toList();
        Set<Long> allPlacementKeys = new LinkedHashSet<>(allPlacements.size());
        for (BoundaryPlacement placement : allPlacements) {
            allPlacementKeys.add(boundaryPlacementKey(placement.orientation, placement.fixedCoord(), placement.boundaryCoord()));
        }

        List<BoundaryPlacement> placements = allPlacements.stream()
                .filter(placement -> placement.cornerKind == CornerKind.NONE)
                .filter(placement -> doorPlacement == null || !placement.matchesDoor(doorPlacement))
                .toList();
        if (placements.isEmpty()) {
            return List.of();
        }

        List<WindowRun> runs = new ArrayList<>();
        List<BoundaryPlacement> currentRun = new ArrayList<>();
        BoundaryPlacement previous = null;
        for (BoundaryPlacement placement : placements) {
            if (previous == null
                    || placement.orientation != previous.orientation
                    || placement.fixedCoord() != previous.fixedCoord()
                    || placement.boundaryCoord() != previous.boundaryCoord() + 1) {
                addWindowRun(runs, currentRun, allPlacementKeys, doorPlacement);
                currentRun = new ArrayList<>();
            }
            currentRun.add(placement);
            previous = placement;
        }
        addWindowRun(runs, currentRun, allPlacementKeys, doorPlacement);
        runs.sort((left, right) -> {
            int compareLength = Integer.compare(right.eligibleSegments().size(), left.eligibleSegments().size());
            if (compareLength != 0) {
                return compareLength;
            }
            int compareOrientation = Integer.compare(left.orientation(), right.orientation());
            if (compareOrientation != 0) {
                return compareOrientation;
            }
            return Integer.compare(left.fixedCoord(), right.fixedCoord());
        });
        return runs;
    }

    private static void addWindowRun(List<WindowRun> runs, List<BoundaryPlacement> placements, Set<Long> allPlacementKeys,
                                     DoorPlacement doorPlacement) {
        if (placements == null || placements.isEmpty()) {
            return;
        }
        BoundaryPlacement firstPlacement = placements.get(0);
        BoundaryPlacement lastPlacement = placements.get(placements.size() - 1);
        boolean hasPreviousPlacement = allPlacementKeys.contains(boundaryPlacementKey(
                firstPlacement.orientation,
                firstPlacement.fixedCoord(),
                firstPlacement.boundaryCoord() - 1
        ));
        boolean hasNextPlacement = allPlacementKeys.contains(boundaryPlacementKey(
                lastPlacement.orientation,
                lastPlacement.fixedCoord(),
                lastPlacement.boundaryCoord() + 1
        ));

        boolean trimStartForDoor = doorPlacement != null
                && firstPlacement.orientation == (doorPlacement.orientation & 3)
                && firstPlacement.boundaryCoord() == doorPlacement.coord + doorPlacement.width;
        boolean trimEndForDoor = doorPlacement != null
                && lastPlacement.orientation == (doorPlacement.orientation & 3)
                && lastPlacement.boundaryCoord() == doorPlacement.coord - 1;
        int startIndex = (hasPreviousPlacement ? 0 : Math.min(WINDOW_END_MARGIN_TILES, placements.size()))
                + (trimStartForDoor ? 1 : 0);
        int endExclusive = Math.max(startIndex, placements.size() - (hasNextPlacement ? 0 : WINDOW_END_MARGIN_TILES) - (trimEndForDoor ? 1 : 0));
        if (startIndex >= endExclusive) {
            return;
        }
        List<WindowSegment> eligibleSegments = new ArrayList<>(endExclusive - startIndex);
        for (int index = startIndex; index < endExclusive; index++) {
            eligibleSegments.add(createWindowSegment(placements.get(index)));
        }
        if (eligibleSegments.isEmpty()) {
            return;
        }
        int maxWindowCount = maxWindowCountForRun(eligibleSegments.size());
        runs.add(new WindowRun(firstPlacement.orientation, firstPlacement.fixedCoord(), eligibleSegments, maxWindowCount));
    }

    private static int maxWindowCountForRun(int eligibleSegmentCount) {
        if (eligibleSegmentCount <= 0) {
            return 0;
        }
        int spacingCapacity = Math.max(1, (int) Math.ceil(eligibleSegmentCount / (double) WINDOW_SPACING_TILES));
        if (eligibleSegmentCount >= 10) {
            return Math.max(spacingCapacity, 3);
        }
        if (eligibleSegmentCount >= 6) {
            return Math.max(spacingCapacity, 2);
        }
        return spacingCapacity;
    }

    private static long boundaryPlacementKey(int orientation, int fixedCoord, int boundaryCoord) {
        long key = orientation & 3;
        key = (key << 20) ^ (fixedCoord & 0xfffffL);
        key = (key << 20) ^ (boundaryCoord & 0xfffffL);
        return key;
    }

    private static List<WindowRunAllocation> allocateWindowRuns(List<WindowRun> runs, int totalWindowCount) {
        if (runs == null || runs.isEmpty() || totalWindowCount <= 0) {
            return List.of();
        }

        List<WindowRunAllocation> allocations = new ArrayList<>(runs.size());
        int totalCapacity = runs.stream().mapToInt(WindowRun::maxWindowCount).sum();
        if (totalCapacity <= 0) {
            return List.of();
        }

        int remaining = totalWindowCount;
        for (WindowRun run : runs) {
            if (remaining <= 0) {
                break;
            }
            int minimum = minimumWindowsForRun(run);
            int assigned = Math.min(minimum, remaining);
            allocations.add(new WindowRunAllocation(run, assigned));
            remaining -= assigned;
        }
        for (int index = 0; index < allocations.size(); index++) {
            WindowRunAllocation allocation = allocations.get(index);
            WindowRun run = allocation.run();
            int proportional = (int) Math.floor((run.maxWindowCount() / (double) totalCapacity) * totalWindowCount);
            int target = Math.max(allocation.count(), proportional);
            if (remaining > 0 && target == allocation.count() && run.maxWindowCount() > allocation.count()) {
                target++;
            }
            int assigned = Math.min(run.maxWindowCount(), target);
            remaining += allocation.count();
            assigned = Math.min(assigned, remaining);
            allocations.set(index, new WindowRunAllocation(run, assigned));
            remaining -= assigned;
        }

        while (remaining > 0) {
            WindowRunAllocation best = null;
            int bestIndex = -1;
            double bestNeed = Double.NEGATIVE_INFINITY;
            for (int index = 0; index < allocations.size(); index++) {
                WindowRunAllocation allocation = allocations.get(index);
                if (allocation.count() >= allocation.run().maxWindowCount()) {
                    continue;
                }
                double ideal = (allocation.run().maxWindowCount() / (double) totalCapacity) * totalWindowCount;
                double need = ideal - allocation.count();
                if (best == null || need > bestNeed) {
                    best = allocation;
                    bestIndex = index;
                    bestNeed = need;
                }
            }
            if (best == null) {
                break;
            }
            allocations.set(bestIndex, new WindowRunAllocation(best.run(), best.count() + 1));
            remaining--;
        }

        return allocations.stream().filter(allocation -> allocation.count() > 0).toList();
    }

    private static int minimumWindowsForRun(WindowRun run) {
        if (run == null) {
            return 0;
        }
        int length = run.eligibleSegments().size();
        if (length >= 10) {
            return Math.min(3, run.maxWindowCount());
        }
        if (length >= 6) {
            return Math.min(2, run.maxWindowCount());
        }
        return Math.min(1, run.maxWindowCount());
    }

    private static List<WindowSegment> chooseRunWindowSegments(WindowRun run, int count) {
        if (run == null || count <= 0 || run.eligibleSegments().isEmpty()) {
            return List.of();
        }
        List<WindowSegment> eligibleSegments = run.eligibleSegments();
        int windowCount = Math.min(count, eligibleSegments.size());
        List<WindowSegment> chosen = new ArrayList<>(windowCount);
        int previousIndex = -1;
        for (int windowIndex = 0; windowIndex < windowCount; windowIndex++) {
            double slot = (windowIndex + 1) * (eligibleSegments.size() + 1.0) / (windowCount + 1.0);
            int candidateIndex = Math.max(0, Math.min(eligibleSegments.size() - 1, (int) Math.round(slot - 1)));
            if (candidateIndex <= previousIndex) {
                candidateIndex = Math.min(eligibleSegments.size() - 1, previousIndex + 1);
            }
            chosen.add(eligibleSegments.get(candidateIndex));
            previousIndex = candidateIndex;
        }
        return chosen;
    }

    private static WindowSegment createWindowSegment(BoundaryPlacement placement) {
        return switch (placement.orientation & 3) {
            case 0 -> new WindowSegment(placement.x, placement.y, 0, placement.x - 1, placement.y, 2);
            case 1 -> new WindowSegment(placement.x, placement.y, 1, placement.x, placement.y + 1, 3);
            case 2 -> new WindowSegment(placement.x, placement.y, 2, placement.x + 1, placement.y, 0);
            case 3 -> new WindowSegment(placement.x, placement.y, 3, placement.x, placement.y - 1, 1);
            default -> throw new IllegalStateException("Unsupported wall orientation: " + placement.orientation);
        };
    }

    private static List<BoundaryPlacement> collectBoundaryPlacements(FootprintPlan exposedPlan, FootprintPlan solidPlan) {
        LinkedHashMap<Long, LinkedHashSet<Integer>> boundaryMap = new LinkedHashMap<>();
        Set<Long> solidTiles = solidPlan == null ? Set.of() : solidPlan.occupiedTiles;
        for (long tile : exposedPlan.occupiedTiles) {
            int x = decodeX(tile);
            int y = decodeY(tile);
            if (!solidTiles.contains(tileKey(x - 1, y))) {
                addBoundary(boundaryMap, x - 1, y, 2);
            }
            if (!solidTiles.contains(tileKey(x + 1, y))) {
                addBoundary(boundaryMap, x + 1, y, 0);
            }
            if (!solidTiles.contains(tileKey(x, y - 1))) {
                addBoundary(boundaryMap, x, y - 1, 1);
            }
            if (!solidTiles.contains(tileKey(x, y + 1))) {
                addBoundary(boundaryMap, x, y + 1, 3);
            }
        }

        List<BoundaryPlacement> placements = new ArrayList<>();
        for (Map.Entry<Long, LinkedHashSet<Integer>> entry : boundaryMap.entrySet()) {
            int x = decodeX(entry.getKey());
            int y = decodeY(entry.getKey());
            LinkedHashSet<Integer> orientations = entry.getValue();
            if (orientations.size() == 1) {
                placements.add(new BoundaryPlacement(x, y, orientations.iterator().next(), CornerKind.NONE));
                continue;
            }
            CornerPlacement cornerPlacement = resolveCornerPlacement(x, y, orientations, solidTiles);
            if (cornerPlacement != null) {
                placements.add(new BoundaryPlacement(
                        x,
                        y,
                        cornerPlacement.orientation,
                        cornerPlacement.secondaryOrientation,
                        cornerPlacement.kind
                ));
            } else {
                for (int orientation : orientations) {
                    placements.add(new BoundaryPlacement(x, y, orientation, CornerKind.NONE));
                }
            }
        }
        return placements;
    }

    private static Set<Long> collectBoundaryTileKeys(FootprintPlan footprintPlan) {
        if (footprintPlan == null || footprintPlan.occupiedTiles == null || footprintPlan.occupiedTiles.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<Long> keys = new LinkedHashSet<>();
        for (BoundaryPlacement placement : collectBoundaryPlacements(footprintPlan, footprintPlan)) {
            keys.add(tileKey(placement.x, placement.y));
        }
        return keys;
    }

    private static void addBoundary(LinkedHashMap<Long, LinkedHashSet<Integer>> boundaryMap, int x, int y, int orientation) {
        boundaryMap.computeIfAbsent(tileKey(x, y), unused -> new LinkedHashSet<>()).add(orientation & 3);
    }

    private static CornerPlacement resolveCornerPlacement(int x, int y, Set<Integer> orientations, Set<Long> solidTiles) {
        if (orientations.size() != 2) {
            return null;
        }
        if (orientations.contains(0) && orientations.contains(1)) {
            return new CornerPlacement(0, 1, solidTiles.contains(tileKey(x - 1, y + 1)) ? CornerKind.INNER : CornerKind.OUTER);
        }
        if (orientations.contains(1) && orientations.contains(2)) {
            return new CornerPlacement(1, 2, solidTiles.contains(tileKey(x + 1, y + 1)) ? CornerKind.INNER : CornerKind.OUTER);
        }
        if (orientations.contains(2) && orientations.contains(3)) {
            return new CornerPlacement(2, 3, solidTiles.contains(tileKey(x + 1, y - 1)) ? CornerKind.INNER : CornerKind.OUTER);
        }
        if (orientations.contains(0) && orientations.contains(3)) {
            return new CornerPlacement(3, 0, solidTiles.contains(tileKey(x - 1, y - 1)) ? CornerKind.INNER : CornerKind.OUTER);
        }
        return null;
    }

    private static void applyUpperPlaneSupport(MapRegion mapRegion, SceneGraph sceneGraph, BuildArea buildArea, int roofPlane) {
        int[][][] heights = mapRegion.tileHeights;
        byte[][][] tileFlags = mapRegion.tileFlags;
        byte[][][] manualHeights = mapRegion.manualTileHeight;
        int planeCount = sceneGraph.tiles.length;

        for (int plane = 1; plane <= Math.min(roofPlane + 1, planeCount - 1); plane++) {
            for (int x = buildArea.minimumX + 1; x <= buildArea.maximumX; x++) {
                for (int y = buildArea.minimumY + 1; y <= buildArea.maximumY; y++) {
                    manualHeights[plane][x][y] = 1;
                    heights[plane][x][y] = heights[plane - 1][x][y] - UPPER_PLANE_HEIGHT_OFFSET;
                    tileFlags[plane][x][y] = (byte) (tileFlags[plane][x][y] | RenderFlags.BLOCKED_TILE.getBit());
                    sceneGraph.tiles[plane][x][y].hasUpdated = true;
                }
            }
        }
    }

    private static void flattenTileRectangle(MapRegion mapRegion, SceneGraph sceneGraph, int plane, int minTileX, int minTileY, int maxTileX, int maxTileY, int tileHeight) {
        int[][][] heights = mapRegion.tileHeights;
        byte[][][] manualHeights = mapRegion.manualTileHeight;
        int planeCount = sceneGraph.tiles.length;
        int width = heights[plane].length;
        int length = heights[plane][0].length;
        int minVertexX = Math.max(0, minTileX);
        int minVertexY = Math.max(0, minTileY);
        int maxVertexX = Math.min(width - 1, maxTileX + 1);
        int maxVertexY = Math.min(length - 1, maxTileY + 1);
        int absoluteHeight = -tileHeight;

        for (int x = minVertexX; x <= maxVertexX; x++) {
            for (int y = minVertexY; y <= maxVertexY; y++) {
                manualHeights[plane][x][y] = 1;
                heights[plane][x][y] = absoluteHeight;
                for (int z = plane + 1; z < planeCount; z++) {
                    if (heights[z][x][y] > heights[z - 1][x][y]) {
                        heights[z][x][y] = heights[z - 1][x][y];
                    }
                }

                if (x < sceneGraph.width && y < sceneGraph.length) {
                    sceneGraph.tiles[plane][x][y].hasUpdated = true;
                }
            }
        }
    }

    private static void setAbsoluteTileHeights(MapRegion mapRegion, SceneGraph sceneGraph, List<SceneTile> tiles, int tileHeight) {
        for (SceneTile tile : tiles) {
            if (tile == null) {
                continue;
            }
            int plane = tile.plane;
            int x = tile.positionX;
            int y = tile.positionY;
            mapRegion.manualTileHeight[plane][x][y] = 1;
            mapRegion.tileHeights[plane][x][y] = -tileHeight;
            for (int z = 1; z < 4; z++) {
                if (mapRegion.tileHeights[z][x][y] > mapRegion.tileHeights[z - 1][x][y]) {
                    mapRegion.tileHeights[z][x][y] = mapRegion.tileHeights[z - 1][x][y];
                }
            }
            sceneGraph.tiles[plane][x][y].hasUpdated = true;
        }
    }

    private static void applyTileFlag(MapRegion mapRegion, SceneGraph sceneGraph, List<SceneTile> baseTiles, int startPlane, int planeCount, int tileFlag) {
        int maxPlane = sceneGraph.tiles.length;
        for (SceneTile tile : baseTiles) {
            for (int plane = startPlane; plane < Math.min(maxPlane, startPlane + planeCount); plane++) {
                mapRegion.tileFlags[plane][tile.positionX][tile.positionY] = (byte) tileFlag;
                sceneGraph.tiles[plane][tile.positionX][tile.positionY].hasUpdated = true;
            }
        }
    }

    private static void paintOverlay(MapRegion mapRegion, SceneGraph sceneGraph, List<SceneTile> baseTiles, int startPlane, int planeCount,
                                     int overlayId, int overlayShape, int orientation) {
        int encodedShape = Math.max(0, overlayShape - 1);
        int maxPlane = sceneGraph.tiles.length;
        for (SceneTile tile : baseTiles) {
            for (int plane = startPlane; plane < Math.min(maxPlane, startPlane + planeCount); plane++) {
                mapRegion.overlays[plane][tile.positionX][tile.positionY] = (short) overlayId;
                mapRegion.overlayShapes[plane][tile.positionX][tile.positionY] = (byte) encodedShape;
                mapRegion.overlayOrientations[plane][tile.positionX][tile.positionY] = (byte) orientation;
                sceneGraph.tiles[plane][tile.positionX][tile.positionY].hasUpdated = true;
            }
        }
    }

    private static List<SceneTile> collectSceneTiles(SceneGraph sceneGraph, Set<Long> tileKeys) {
        List<SceneTile> tiles = new ArrayList<>();
        for (long tileKey : tileKeys) {
            int x = decodeX(tileKey);
            int y = decodeY(tileKey);
            if (x < 0 || y < 0 || x >= sceneGraph.width || y >= sceneGraph.length) {
                continue;
            }
            tiles.add(sceneGraph.getTile(0, x, y));
        }
        return tiles;
    }

    private static void flattenFootprintGround(MapRegion mapRegion, SceneGraph sceneGraph, FootprintPlan footprintPlan, int tileHeight) {
        flattenTileSet(mapRegion, sceneGraph, 0, expandTileKeys(footprintPlan.occupiedTiles, 2), tileHeight);
    }

    private static Set<Long> expandTileKeys(Set<Long> tileKeys, int radius) {
        Set<Long> expanded = new LinkedHashSet<>();
        for (long tileKey : tileKeys) {
            int x = decodeX(tileKey);
            int y = decodeY(tileKey);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    expanded.add(tileKey(x + dx, y + dy));
                }
            }
        }
        return expanded;
    }

    private static void flattenTileSet(MapRegion mapRegion, SceneGraph sceneGraph, int plane, Set<Long> tileKeys, int tileHeight) {
        int[][][] heights = mapRegion.tileHeights;
        byte[][][] manualHeights = mapRegion.manualTileHeight;
        int planeCount = sceneGraph.tiles.length;
        int width = heights[plane].length;
        int length = heights[plane][0].length;
        int absoluteHeight = -tileHeight;
        Set<Long> vertexKeys = new LinkedHashSet<>();

        for (long tileKey : tileKeys) {
            int x = decodeX(tileKey);
            int y = decodeY(tileKey);
            if (x < 0 || y < 0 || x >= width - 1 || y >= length - 1) {
                continue;
            }
            vertexKeys.add(tileKey(x, y));
            vertexKeys.add(tileKey(x + 1, y));
            vertexKeys.add(tileKey(x, y + 1));
            vertexKeys.add(tileKey(x + 1, y + 1));
        }

        for (long vertexKey : vertexKeys) {
            int x = decodeX(vertexKey);
            int y = decodeY(vertexKey);
            if (x < 0 || y < 0 || x >= width || y >= length) {
                continue;
            }
            manualHeights[plane][x][y] = 1;
            heights[plane][x][y] = absoluteHeight;
            for (int z = plane + 1; z < planeCount; z++) {
                if (heights[z][x][y] > heights[z - 1][x][y]) {
                    heights[z][x][y] = heights[z - 1][x][y];
                }
            }
            if (x < sceneGraph.width && y < sceneGraph.length) {
                sceneGraph.tiles[plane][x][y].hasUpdated = true;
            }
        }
    }

    private static void clearOverlay(MapRegion mapRegion, SceneGraph sceneGraph, int plane, int x, int y) {
        mapRegion.overlays[plane][x][y] = 0;
        mapRegion.overlayShapes[plane][x][y] = 0;
        mapRegion.overlayOrientations[plane][x][y] = 0;
        sceneGraph.tiles[plane][x][y].hasUpdated = true;
    }

    private static long tileKey(int x, int y) {
        return (((long) x) << 32) ^ (y & 0xffffffffL);
    }

    private static int decodeX(long tileKey) {
        return (int) (tileKey >> 32);
    }

    private static int decodeY(long tileKey) {
        return (int) tileKey;
    }

    private static RectangleBounds computeOccupiedBounds(FootprintPlan footprintPlan) {
        if (footprintPlan == null || footprintPlan.occupiedTiles == null || footprintPlan.occupiedTiles.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (long tileKey : footprintPlan.occupiedTiles) {
            int x = decodeX(tileKey);
            int y = decodeY(tileKey);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        return new RectangleBounds(minX, minY, maxX, maxY);
    }

    private static final class BuildArea {
        private final List<SceneTile> tilesAround;
        private final int averageTileHeight;
        private final int minimumX;
        private final int minimumY;
        private final int maximumX;
        private final int maximumY;

        private BuildArea(List<SceneTile> tilesAround, int averageTileHeight, int minimumX, int minimumY, int maximumX, int maximumY) {
            this.tilesAround = tilesAround;
            this.averageTileHeight = averageTileHeight;
            this.minimumX = minimumX;
            this.minimumY = minimumY;
            this.maximumX = maximumX;
            this.maximumY = maximumY;
        }
    }

    private static final class RectangleBounds {
        private final int minX;
        private final int minY;
        private final int maxX;
        private final int maxY;

        private RectangleBounds(int minX, int minY, int maxX, int maxY) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }

        private int area() {
            return (maxX - minX + 1) * (maxY - minY + 1);
        }
    }

    private static final class DoorPlacement {
        private final int orientation;
        private final int coord;
        private final int width;

        private DoorPlacement(int orientation, int coord) {
            this(orientation, coord, 1);
        }

        private DoorPlacement(int orientation, int coord, int width) {
            this.orientation = orientation;
            this.coord = coord;
            this.width = Math.max(1, width);
        }

        private boolean matches(BoundaryPlacement placement) {
            return placement != null
                    && placement.orientation == orientation
                    && placement.boundaryCoord() >= coord
                    && placement.boundaryCoord() < coord + width;
        }
    }

    private static final class FootprintPlan {
        private final BuildArea bounds;
        private final LinkedHashSet<Long> occupiedTiles;

        private FootprintPlan(BuildArea bounds, Set<Long> occupiedTiles) {
            this.bounds = bounds;
            this.occupiedTiles = new LinkedHashSet<>(occupiedTiles);
        }
    }

    private static final class FootprintFrame {
        private final BuildArea buildArea;
        private final int frontOrientation;
        private final int minInteriorX;
        private final int minInteriorY;
        private final int maxInteriorX;
        private final int maxInteriorY;
        private final int acrossSize;
        private final int depthSize;

        private FootprintFrame(BuildArea buildArea, int frontOrientation) {
            this.buildArea = buildArea;
            this.frontOrientation = frontOrientation & 3;
            this.minInteriorX = buildArea.minimumX + 2;
            this.minInteriorY = buildArea.minimumY + 2;
            this.maxInteriorX = buildArea.maximumX - 2;
            this.maxInteriorY = buildArea.maximumY - 2;
            if (this.frontOrientation == 0 || this.frontOrientation == 2) {
                this.acrossSize = Math.max(0, maxInteriorY - minInteriorY + 1);
                this.depthSize = Math.max(0, maxInteriorX - minInteriorX + 1);
            } else {
                this.acrossSize = Math.max(0, maxInteriorX - minInteriorX + 1);
                this.depthSize = Math.max(0, maxInteriorY - minInteriorY + 1);
            }
        }

        private long tileKey(int across, int depth) {
            int x;
            int y;
            switch (frontOrientation) {
                case 0 -> {
                    x = maxInteriorX - depth;
                    y = minInteriorY + across;
                }
                case 1 -> {
                    x = maxInteriorX - across;
                    y = minInteriorY + depth;
                }
                case 2 -> {
                    x = minInteriorX + depth;
                    y = maxInteriorY - across;
                }
                case 3 -> {
                    x = minInteriorX + across;
                    y = maxInteriorY - depth;
                }
                default -> {
                    x = minInteriorX + across;
                    y = minInteriorY + depth;
                }
            }
            return BuildingGenerator.tileKey(x, y);
        }
    }

    private static final class WeightedShape {
        private final HouseStyleDefinition.FootprintShape shape;
        private final int weight;

        private WeightedShape(HouseStyleDefinition.FootprintShape shape, int weight) {
            this.shape = shape;
            this.weight = weight;
        }
    }

    private static final class LadderPlacement {
        private final int x;
        private final int y;

        private LadderPlacement(int x, int y) {
            this.x = x;
            this.y = y;
        }

        private boolean matches(LadderPlacement other) {
            return other != null && x == other.x && y == other.y;
        }
    }

    private record RotatedPieceMarker(int x, int y) {
    }

    private static final class LadderSpec {
        private final int groundFloorId;
        private final int midFloorId;
        private final int topFloorId;

        private LadderSpec(int groundFloorId, int midFloorId, int topFloorId) {
            this.groundFloorId = groundFloorId;
            this.midFloorId = midFloorId;
            this.topFloorId = topFloorId;
        }
    }

    private static final class BoundaryPlacement {
        private final int x;
        private final int y;
        private final int orientation;
        private final int secondaryOrientation;
        private final CornerKind cornerKind;

        private BoundaryPlacement(int x, int y, int orientation, CornerKind cornerKind) {
            this(x, y, orientation, -1, cornerKind);
        }

        private BoundaryPlacement(int x, int y, int orientation, int secondaryOrientation, CornerKind cornerKind) {
            this.x = x;
            this.y = y;
            this.orientation = orientation;
            this.secondaryOrientation = secondaryOrientation;
            this.cornerKind = cornerKind == null ? CornerKind.NONE : cornerKind;
        }

        private int boundaryCoord() {
            return orientation == 0 || orientation == 2 ? y : x;
        }

        private int fixedCoord() {
            return orientation == 0 || orientation == 2 ? x : y;
        }

        private boolean hasSecondaryOrientation() {
            return secondaryOrientation >= 0;
        }

        private boolean matchesDoor(DoorPlacement doorPlacement) {
            return cornerKind == CornerKind.NONE
                    && doorPlacement != null
                    && doorPlacement.matches(this);
        }
    }

    private enum CornerKind {
        NONE,
        OUTER,
        INNER
    }

    private static final class CornerPlacement {
        private final int orientation;
        private final int secondaryOrientation;
        private final CornerKind kind;

        private CornerPlacement(int orientation, int secondaryOrientation, CornerKind kind) {
            this.orientation = orientation;
            this.secondaryOrientation = secondaryOrientation;
            this.kind = kind;
        }
    }

    private static final class WindowSegment {
        private final int outerX;
        private final int outerY;
        private final int outerOrientation;
        private final int innerX;
        private final int innerY;
        private final int innerOrientation;

        private WindowSegment(int outerX, int outerY, int outerOrientation, int innerX, int innerY, int innerOrientation) {
            this.outerX = outerX;
            this.outerY = outerY;
            this.outerOrientation = outerOrientation;
            this.innerX = innerX;
            this.innerY = innerY;
            this.innerOrientation = innerOrientation;
        }
    }

    private record WindowRun(
            int orientation,
            int fixedCoord,
            List<WindowSegment> eligibleSegments,
            int maxWindowCount
    ) {}

    private record WindowRunAllocation(
            WindowRun run,
            int count
    ) {}

    private static final class WallOpening {
        private final int orientation;
        private final int startCoord;
        private final int endCoord;

        private WallOpening(int orientation, int startCoord, int endCoord) {
            this.orientation = orientation;
            this.startCoord = startCoord;
            this.endCoord = endCoord;
        }
    }
}
