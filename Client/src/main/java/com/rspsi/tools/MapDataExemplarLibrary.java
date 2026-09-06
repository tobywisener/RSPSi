package com.rspsi.tools;

import com.jagex.Cache;
import com.jagex.Client;
import com.jagex.cache.def.Floor;
import com.jagex.cache.def.ObjectDefinition;
import com.jagex.cache.loader.floor.FloorDefinitionLoader;
import com.jagex.cache.loader.map.MapIndexLoader;
import com.jagex.cache.loader.object.ObjectDefinitionLoader;
import com.jagex.io.Buffer;
import com.jagex.map.MapRegion;
import com.jagex.map.SceneGraph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MapDataExemplarLibrary {

    private static final int MAX_SAMPLED_REGIONS = 320;
    public static final int REAL_WATER_OVERLAY_ID = 1;
    private static final int[] GRASSLAND_TERRAIN_REGION_HASHES = {12594};
    private static final int[] GRASSLAND_BUILDING_REGION_HASHES = {12338, 12339};
    private static final int GRASSLAND_TERRAIN_WEIGHT = 14;
    private static final int GRASSLAND_BUILDING_WEIGHT = 18;

    private static volatile MapDataExemplarLibrary cached;

    private final ThemeData grassland;
    private final ThemeData woodland;
    private final ThemeData marsh;
    private final ThemeData lakeland;
    private final ThemeData arid;
    private final int roadOverlayId;
    private final ShorelineRule[] shorelineRules;

    private MapDataExemplarLibrary(
            ThemeData grassland,
            ThemeData woodland,
            ThemeData marsh,
            ThemeData lakeland,
            ThemeData arid,
            int roadOverlayId,
            ShorelineRule[] shorelineRules
    ) {
        this.grassland = grassland;
        this.woodland = woodland;
        this.marsh = marsh;
        this.lakeland = lakeland;
        this.arid = arid;
        this.roadOverlayId = roadOverlayId;
        this.shorelineRules = shorelineRules;
    }

    public static MapDataExemplarLibrary resolve() {
        if (cached != null) {
            return cached;
        }
        synchronized (MapDataExemplarLibrary.class) {
            if (cached == null) {
                cached = build();
            }
        }
        return cached;
    }

    public ThemeData theme(ThemePreset preset) {
        return switch (preset) {
            case WOODLAND -> woodland;
            case MARSH -> marsh;
            case LAKELAND -> lakeland;
            case ARID -> arid;
            default -> grassland;
        };
    }

    public int roadOverlayId() {
        return roadOverlayId;
    }

    public ShorelineRule shorelineRule(int mask) {
        if (mask < 0 || mask >= shorelineRules.length) {
            return null;
        }
        return shorelineRules[mask];
    }

    private static MapDataExemplarLibrary build() {
        Client client = Client.getSingleton();
        Cache cache = client == null ? null : client.getCache();
        if (cache == null || MapIndexLoader.instance == null) {
            return fallback();
        }

        byte[] encodedMapIndex = MapIndexLoader.instance.encode();
        if (encodedMapIndex == null || encodedMapIndex.length < 2) {
            return fallback();
        }

        Buffer mapIndexBuffer = new Buffer(encodedMapIndex);
        int totalRegions = mapIndexBuffer.readUShort();
        List<MapIndexEntry> mapEntries = new ArrayList<>(totalRegions);
        Map<Integer, MapIndexEntry> mapEntryByHash = new HashMap<>();
        for (int index = 0; index < totalRegions; index++) {
            int hash = mapIndexBuffer.readUShort();
            int landscapeId = decodeMapId(mapIndexBuffer.readUShort());
            int objectId = decodeMapId(mapIndexBuffer.readUShort());
            MapIndexEntry entry = new MapIndexEntry(hash, landscapeId, objectId);
            mapEntries.add(entry);
            mapEntryByHash.put(hash, entry);
        }
        int stride = Math.max(1, totalRegions / MAX_SAMPLED_REGIONS);

        ThemeAccumulator grassland = new ThemeAccumulator();
        ThemeAccumulator woodland = new ThemeAccumulator();
        ThemeAccumulator marsh = new ThemeAccumulator();
        ThemeAccumulator lakeland = new ThemeAccumulator();
        ThemeAccumulator arid = new ThemeAccumulator();
        Map<Integer, Integer> roadOverlays = new HashMap<>();
        Map<Integer, Map<Integer, Integer>> shorelineCounts = new HashMap<>();

        int sampled = 0;
        for (int index = 0; index < totalRegions; index++) {
            MapIndexEntry entry = mapEntries.get(index);
            if (entry.landscapeId() == -1) {
                continue;
            }
            if (sampled >= MAX_SAMPLED_REGIONS) {
                continue;
            }
            if (index % stride != 0) {
                continue;
            }

            RegionSample sample = loadRegionSample(cache, entry);
            if (sample == null) {
                continue;
            }

            RegionStats stats = analyse(sample.mapRegion(), sample.objects(), roadOverlays, shorelineCounts);
            ThemeAccumulator accumulator = switch (classify(stats)) {
                case WOODLAND -> woodland;
                case MARSH -> marsh;
                case LAKELAND -> lakeland;
                case ARID -> arid;
                default -> grassland;
            };
            accumulator.absorb(sample.mapRegion(), sample.objects(), 1, 1, 1, 1, 1, 1);
            sampled++;
        }

        applyPreferredGrasslandTheme(cache, mapEntryByHash, grassland, roadOverlays, shorelineCounts);

        int fallbackRoadOverlay = selectOverlayByRgb(118, 102, 77, false);
        ThemeData fallbackGrassland = fallbackTheme(ThemePreset.GRASSLAND);
        ThemeData fallbackWoodland = fallbackTheme(ThemePreset.WOODLAND);
        ThemeData fallbackMarsh = fallbackTheme(ThemePreset.MARSH);
        ThemeData fallbackLakeland = fallbackTheme(ThemePreset.LAKELAND);
        ThemeData fallbackArid = fallbackTheme(ThemePreset.ARID);

        return new MapDataExemplarLibrary(
                grassland.build(fallbackGrassland),
                woodland.build(fallbackWoodland),
                marsh.build(fallbackMarsh),
                lakeland.build(fallbackLakeland),
                arid.build(fallbackArid),
                topId(roadOverlays, fallbackRoadOverlay),
                buildShorelineRules(shorelineCounts)
        );
    }

    private static MapDataExemplarLibrary fallback() {
        ThemeData grassland = fallbackTheme(ThemePreset.GRASSLAND);
        ThemeData woodland = fallbackTheme(ThemePreset.WOODLAND);
        ThemeData marsh = fallbackTheme(ThemePreset.MARSH);
        ThemeData lakeland = fallbackTheme(ThemePreset.LAKELAND);
        ThemeData arid = fallbackTheme(ThemePreset.ARID);

        ShorelineRule[] shorelineRules = new ShorelineRule[16];
        return new MapDataExemplarLibrary(
                grassland,
                woodland,
                marsh,
                lakeland,
                arid,
                selectOverlayByRgb(118, 102, 77, false),
                shorelineRules
        );
    }

    private static ThemeData fallbackTheme(ThemePreset preset) {
        int[] underlays = switch (preset) {
            case WOODLAND -> selectUnderlaysByRgb(78, 96, 58, 8);
            case MARSH -> selectUnderlaysByRgb(86, 96, 74, 7);
            case LAKELAND -> selectUnderlaysByRgb(88, 118, 74, 8);
            case ARID -> selectUnderlaysByRgb(170, 151, 102, 8);
            default -> selectUnderlaysByRgb(78, 112, 60, 8);
        };
        int waterOverlayId = REAL_WATER_OVERLAY_ID;
        ObjectPlacement[] trees = selectObjectPlacementsByName(
                new String[]{"tree", "oak", "willow", "maple", "yew", "evergreen", "palm"},
                new String[]{"stump", "roots", "sapling"},
                10,
                8
        );
        ObjectPlacement[] decor = switch (preset) {
            case ARID -> selectObjectPlacementsByName(
                    new String[]{"cactus", "rock", "stones", "mushroom"},
                    new String[]{"wall", "door", "gate"},
                    22,
                    8
            );
            default -> selectObjectPlacementsByName(
                    new String[]{"flower", "fern", "plant", "bush", "shrub", "grass", "mushroom", "reed", "rock"},
                    new String[]{"wall", "door", "gate"},
                    22,
                    10
            );
        };

        BuildingKit buildingKit = new BuildingKit(
                switch (preset) {
                    case WOODLAND, LAKELAND, GRASSLAND -> 1902;
                    case MARSH -> 24371;
                    default -> 23735;
                },
                0,
                switch (preset) {
                    case WOODLAND, LAKELAND, GRASSLAND -> 1535;
                    case MARSH -> 24369;
                    default -> 11775;
                },
                0
        );

        return new ThemeData(underlays, waterOverlayId, trees, decor, buildingKit);
    }

    private static int decodeMapId(int rawValue) {
        return rawValue == 65535 ? -1 : rawValue;
    }

    private static RegionSample loadRegionSample(Cache cache, MapIndexEntry entry) {
        if (entry == null || entry.landscapeId() == -1) {
            return null;
        }
        byte[] landscapeData = cache.readMap(entry.landscapeId(), entry.hash());
        if (landscapeData == null || landscapeData.length == 0) {
            return null;
        }

        int regionX = (entry.hash() >> 8) & 0xff;
        int regionY = entry.hash() & 0xff;
        SceneGraph sceneGraph = new SceneGraph(64, 64, 4);
        MapRegion mapRegion = new MapRegion(sceneGraph, 64, 64);
        mapRegion.unpackTiles(landscapeData, 0, 0, regionX * 64, regionY * 64);

        List<RegionObject> objects = entry.objectId() == -1
                ? List.of()
                : decodeObjects(cache.readMap(entry.objectId(), entry.hash()));
        return new RegionSample(mapRegion, objects);
    }

    private static void applyPreferredGrasslandTheme(
            Cache cache,
            Map<Integer, MapIndexEntry> mapEntryByHash,
            ThemeAccumulator grassland,
            Map<Integer, Integer> roadOverlays,
            Map<Integer, Map<Integer, Integer>> shorelineCounts
    ) {
        for (int hash : GRASSLAND_TERRAIN_REGION_HASHES) {
            RegionSample sample = loadRegionSample(cache, mapEntryByHash.get(hash));
            if (sample == null) {
                continue;
            }
            for (int weight = 0; weight < GRASSLAND_TERRAIN_WEIGHT; weight++) {
                analyse(sample.mapRegion(), sample.objects(), roadOverlays, shorelineCounts);
            }
            grassland.absorb(sample.mapRegion(), sample.objects(),
                    GRASSLAND_TERRAIN_WEIGHT,
                    GRASSLAND_TERRAIN_WEIGHT,
                    GRASSLAND_TERRAIN_WEIGHT,
                    GRASSLAND_TERRAIN_WEIGHT,
                    0,
                    0);
        }

        for (int hash : GRASSLAND_BUILDING_REGION_HASHES) {
            RegionSample sample = loadRegionSample(cache, mapEntryByHash.get(hash));
            if (sample == null) {
                continue;
            }
            grassland.absorb(sample.mapRegion(), sample.objects(),
                    0,
                    0,
                    0,
                    0,
                    GRASSLAND_BUILDING_WEIGHT,
                    GRASSLAND_BUILDING_WEIGHT);
        }
    }

    private static RegionStats analyse(
            MapRegion mapRegion,
            List<RegionObject> objects,
            Map<Integer, Integer> roadOverlays,
            Map<Integer, Map<Integer, Integer>> shorelineCounts
    ) {
        int waterTiles = 0;
        int sandTiles = 0;
        int greenTiles = 0;
        int heightDeltaTotal = 0;
        int heightDeltaSamples = 0;

        boolean[][] waterMask = new boolean[64][64];
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 64; y++) {
                int underlayId = mapRegion.underlays[0][x][y] & 0xffff;
                int overlayId = mapRegion.overlays[0][x][y] & 0xffff;
                Floor underlay = underlayId > 0 ? FloorDefinitionLoader.getUnderlay(underlayId - 1) : null;
                Floor overlay = overlayId > 0 ? FloorDefinitionLoader.getOverlay(overlayId - 1) : null;

                if (underlay != null && isAridFloor(underlay)) {
                    sandTiles++;
                }
                if ((underlay != null && isGreenFloor(underlay)) || (overlay != null && isGreenFloor(overlay))) {
                    greenTiles++;
                }
                if (overlayId > 0 && overlay != null && isWaterFloor(overlay)) {
                    waterMask[x][y] = true;
                    waterTiles++;
                } else if (underlayId > 0 && underlay != null && isWaterFloor(underlay)) {
                    waterMask[x][y] = true;
                    waterTiles++;
                } else if (overlayId > 0 && overlay != null && isRoadFloor(overlay)) {
                    roadOverlays.merge(overlayId, 1, Integer::sum);
                }

                int centre = Math.abs(mapRegion.tileHeights[0][x][y]);
                int east = Math.abs(mapRegion.tileHeights[0][x + 1][y]);
                int north = Math.abs(mapRegion.tileHeights[0][x][y + 1]);
                heightDeltaTotal += Math.abs(centre - east) + Math.abs(centre - north);
                heightDeltaSamples += 2;
            }
        }

        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 64; y++) {
                if (!waterMask[x][y]) {
                    continue;
                }
                int mask = waterNeighbourMask(waterMask, x, y);
                if (mask == 15 && mapRegion.overlayShapes[0][x][y] == 0) {
                    continue;
                }
                int key = shorelineKey(mapRegion.overlayShapes[0][x][y], mapRegion.overlayOrientations[0][x][y]);
                shorelineCounts.computeIfAbsent(mask, ignored -> new HashMap<>()).merge(key, 1, Integer::sum);
            }
        }

        int treeCount = 0;
        int decorCount = 0;
        int wallCount = 0;
        int doorCount = 0;
        for (RegionObject object : objects) {
            ObjectDefinition definition = ObjectDefinitionLoader.lookup(object.id());
            if (definition == null) {
                continue;
            }
            String name = normalise(definition.getName());
            if (isTreeName(name)) {
                treeCount++;
            }
            if (isDecorName(name)) {
                decorCount++;
            }
            if (isDoorName(name) && object.type() == 0) {
                doorCount++;
            } else if (isWallName(name) && object.type() == 0) {
                wallCount++;
            }
        }

        return new RegionStats(
                waterTiles / 4096.0,
                sandTiles / 4096.0,
                greenTiles / 4096.0,
                heightDeltaSamples == 0 ? 0.0 : heightDeltaTotal / (double) heightDeltaSamples,
                treeCount,
                decorCount,
                wallCount,
                doorCount
        );
    }

    private static ThemePreset classify(RegionStats stats) {
        if (stats.sandRatio > 0.22) {
            return ThemePreset.ARID;
        }
        if (stats.waterRatio > 0.13 && stats.treeCount > 22) {
            return ThemePreset.LAKELAND;
        }
        if (stats.waterRatio > 0.10) {
            return ThemePreset.MARSH;
        }
        if (stats.treeCount > 26 || (stats.greenRatio > 0.55 && stats.treeCount > 14)) {
            return ThemePreset.WOODLAND;
        }
        return ThemePreset.GRASSLAND;
    }

    private static List<RegionObject> decodeObjects(byte[] objectData) {
        if (objectData == null || objectData.length == 0) {
            return List.of();
        }
        Buffer buffer = new Buffer(objectData);
        List<RegionObject> result = new ArrayList<>();
        int id = -1;
        while (true) {
            int idOffset = buffer.readUSmartInt();
            if (idOffset == 0) {
                break;
            }
            id += idOffset;
            int position = 0;
            while (true) {
                int offset = buffer.readUSmartInt();
                if (offset == 0) {
                    break;
                }
                position += offset - 1;
                int y = position & 0x3f;
                int x = position >> 6 & 0x3f;
                int plane = position >> 12;
                int config = buffer.readUByte();
                int type = config >> 2;
                int orientation = config & 3;
                result.add(new RegionObject(id, type, orientation, x, y, plane));
            }
        }
        return result;
    }

    private static ShorelineRule[] buildShorelineRules(Map<Integer, Map<Integer, Integer>> shorelineCounts) {
        ShorelineRule[] rules = new ShorelineRule[16];
        for (int mask = 0; mask < rules.length; mask++) {
            Map<Integer, Integer> counts = shorelineCounts.get(mask);
            if (counts == null || counts.isEmpty()) {
                continue;
            }
            int key = topId(counts, -1);
            if (key == -1) {
                continue;
            }
            rules[mask] = new ShorelineRule((byte) ((key >> 2) & 0x3f), (byte) (key & 3));
        }
        return rules;
    }

    private static int shorelineKey(int shape, int orientation) {
        return ((shape & 0x3f) << 2) | (orientation & 3);
    }

    private static int waterNeighbourMask(boolean[][] waterMask, int x, int y) {
        int mask = 0;
        if (isWater(waterMask, x, y + 1)) {
            mask |= 1;
        }
        if (isWater(waterMask, x + 1, y)) {
            mask |= 2;
        }
        if (isWater(waterMask, x, y - 1)) {
            mask |= 4;
        }
        if (isWater(waterMask, x - 1, y)) {
            mask |= 8;
        }
        return mask;
    }

    private static boolean isWater(boolean[][] waterMask, int x, int y) {
        return x >= 0 && y >= 0 && x < 64 && y < 64 && waterMask[x][y];
    }

    private static boolean isWaterFloor(Floor floor) {
        if (floor == null) {
            return false;
        }
        int rgb = floor.getRgb();
        return blue(rgb) >= green(rgb) + 8 && blue(rgb) >= red(rgb) + 12;
    }

    private static boolean isRoadFloor(Floor floor) {
        if (floor == null) {
            return false;
        }
        int rgb = floor.getRgb();
        int spread = Math.max(red(rgb), Math.max(green(rgb), blue(rgb))) - Math.min(red(rgb), Math.min(green(rgb), blue(rgb)));
        return spread <= 70 && floor.getLuminance() >= 25 && floor.getLuminance() <= 190;
    }

    private static boolean isGreenFloor(Floor floor) {
        if (floor == null) {
            return false;
        }
        int rgb = floor.getRgb();
        return green(rgb) >= red(rgb) + 6 && green(rgb) >= blue(rgb) + 8 && floor.getLuminance() >= 28 && floor.getLuminance() <= 185;
    }

    private static boolean isAridFloor(Floor floor) {
        if (floor == null) {
            return false;
        }
        int rgb = floor.getRgb();
        return red(rgb) >= blue(rgb) + 10 && green(rgb) >= blue(rgb) + 4 && Math.abs(red(rgb) - green(rgb)) <= 75;
    }

    private static boolean isLowChromaFloor(Floor floor) {
        if (floor == null) {
            return true;
        }
        int rgb = floor.getRgb();
        int spread = Math.max(red(rgb), Math.max(green(rgb), blue(rgb))) - Math.min(red(rgb), Math.min(green(rgb), blue(rgb)));
        return spread < 14;
    }

    private static boolean isUsableGroundFloor(Floor floor) {
        return floor != null
                && floor.getRgb() != 0
                && floor.getRgb() != -1
                && floor.getTexture() == -1
                && !isWaterFloor(floor)
                && !isRoadFloor(floor)
                && !isLowChromaFloor(floor);
    }

    private static boolean isUsableWaterOverlay(Floor floor) {
        return floor != null
                && floor.getRgb() != 0
                && floor.getRgb() != -1
                && isWaterFloor(floor)
                && blue(floor.getRgb()) >= green(floor.getRgb()) + 4
                && green(floor.getRgb()) >= red(floor.getRgb()) - 10;
    }

    private static String normalise(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static boolean isTreeName(String name) {
        return containsAny(name, "tree", "oak", "willow", "maple", "yew", "evergreen", "palm")
                && !containsAny(name, "stump", "roots", "sapling", "deadfall");
    }

    private static boolean isDoorName(String name) {
        return containsAny(name, "door", "gate", "trapdoor");
    }

    private static boolean isWallName(String name) {
        return containsAny(name, "wall")
                && !containsAny(name, "fence", "railing", "door", "gate", "banner", "painting", "decoration");
    }

    private static boolean isDecorName(String name) {
        return containsAny(name, "flower", "fern", "plant", "bush", "shrub", "mushroom", "reed", "grass", "rock", "stones", "cactus")
                && !containsAny(name, "wall", "door", "gate");
    }

    private static boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static int[] selectUnderlaysByRgb(int red, int green, int blue, int count) {
        List<IntScore> scores = new ArrayList<>();
        if (FloorDefinitionLoader.instance == null) {
            return new int[]{1};
        }
        for (int id = 0; id < FloorDefinitionLoader.getUnderlayCount(); id++) {
            Floor floor = FloorDefinitionLoader.getUnderlay(id);
            if (floor == null || floor.getRgb() == 0 || floor.getRgb() == -1 || floor.getTexture() != -1) {
                continue;
            }
            double score = rgbDistance(floor.getRgb(), red, green, blue);
            scores.add(new IntScore(id + 1, score));
        }
        scores.sort(Comparator.comparingDouble(IntScore::score));
        if (scores.isEmpty()) {
            return new int[]{1};
        }
        int size = Math.min(count, scores.size());
        int[] ids = new int[size];
        for (int index = 0; index < size; index++) {
            ids[index] = scores.get(index).id;
        }
        return ids;
    }

    private static int selectOverlayByRgb(int red, int green, int blue, boolean preferTexture) {
        if (FloorDefinitionLoader.instance == null) {
            return 1;
        }
        IntScore best = null;
        for (int id = 0; id < FloorDefinitionLoader.getOverlayCount(); id++) {
            Floor floor = FloorDefinitionLoader.getOverlay(id);
            if (floor == null || floor.getRgb() == 0 || floor.getRgb() == -1) {
                continue;
            }
            double score = rgbDistance(floor.getRgb(), red, green, blue);
            if (preferTexture) {
                score += floor.getTexture() >= 0 ? 0.0 : 18.0;
            }
            if (best == null || score < best.score) {
                best = new IntScore(id + 1, score);
            }
        }
        return best == null ? 1 : best.id;
    }

    private static ObjectPlacement[] selectObjectPlacementsByName(String[] include, String[] exclude, int type, int count) {
        if (ObjectDefinitionLoader.instance == null) {
            return new ObjectPlacement[0];
        }
        List<ObjectPlacement> placements = new ArrayList<>();
        for (int id = 0; id < ObjectDefinitionLoader.getCount() && placements.size() < count; id++) {
            ObjectDefinition definition = ObjectDefinitionLoader.lookup(id);
            if (definition == null) {
                continue;
            }
            String name = normalise(definition.getName());
            if (!containsAnyArray(name, include)) {
                continue;
            }
            if (containsAnyArray(name, exclude)) {
                continue;
            }
            placements.add(new ObjectPlacement(id, type));
        }
        return placements.toArray(new ObjectPlacement[0]);
    }

    private static boolean containsAnyArray(String value, String[] tokens) {
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static int findWallCandidate() {
        return findObjectId(name -> containsAny(name, "wall") && !containsAny(name, "fence", "railing", "door", "gate"), 1902);
    }

    private static int findDoorCandidate() {
        return findObjectId(name -> containsAny(name, "door") && !containsAny(name, "trapdoor"), 1535);
    }

    private static int findObjectId(NameFilter filter, int fallback) {
        if (ObjectDefinitionLoader.instance == null) {
            return fallback;
        }
        for (int id = 0; id < ObjectDefinitionLoader.getCount(); id++) {
            ObjectDefinition definition = ObjectDefinitionLoader.lookup(id);
            if (definition == null) {
                continue;
            }
            String name = normalise(definition.getName());
            if (filter.matches(name)) {
                return id;
            }
        }
        return fallback;
    }

    private static int topId(Map<Integer, Integer> counts, int fallback) {
        int bestId = fallback;
        int bestCount = Integer.MIN_VALUE;
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                bestId = entry.getKey();
            }
        }
        return bestId;
    }

    private static int[] selectCountedUnderlays(Map<Integer, Integer> counts, int[] fallback, int limit) {
        if (counts.isEmpty()) {
            return fallback;
        }
        int targetRgb = averageUnderlayRgb(fallback);
        List<IntScore> scores = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            Floor floor = FloorDefinitionLoader.getUnderlay(entry.getKey() - 1);
            if (!isUsableGroundFloor(floor)) {
                continue;
            }
            double score = rgbDistance(floor.getRgb(), red(targetRgb), green(targetRgb), blue(targetRgb))
                    - Math.min(20.0, Math.log(entry.getValue() + 1.0) * 7.5);
            scores.add(new IntScore(entry.getKey(), score));
        }
        if (scores.isEmpty()) {
            return fallback;
        }
        scores.sort(Comparator.comparingDouble(IntScore::score));
        int[] ids = new int[Math.min(limit, scores.size())];
        for (int index = 0; index < ids.length; index++) {
            ids[index] = scores.get(index).id;
        }
        return ids.length == 0 ? fallback : ids;
    }

    private static int selectCountedOverlay(Map<Integer, Integer> counts, int fallback) {
        if (counts.isEmpty()) {
            return fallback;
        }
        Floor fallbackFloor = FloorDefinitionLoader.getOverlay(Math.max(0, fallback - 1));
        int targetRgb = fallbackFloor == null ? 0x35527c : fallbackFloor.getRgb();
        Integer bestId = null;
        int bestCount = Integer.MIN_VALUE;
        double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            Floor floor = FloorDefinitionLoader.getOverlay(entry.getKey() - 1);
            if (!isUsableWaterOverlay(floor)) {
                continue;
            }
            int count = entry.getValue();
            double distance = rgbDistance(floor.getRgb(), red(targetRgb), green(targetRgb), blue(targetRgb));
            if (count > bestCount || (count == bestCount && distance < bestDistance)) {
                bestId = entry.getKey();
                bestCount = count;
                bestDistance = distance;
            }
        }
        return bestId == null ? fallback : bestId;
    }

    private static int averageUnderlayRgb(int[] underlays) {
        int red = 0;
        int green = 0;
        int blue = 0;
        int count = 0;
        for (int underlayId : underlays) {
            Floor floor = FloorDefinitionLoader.getUnderlay(Math.max(0, underlayId - 1));
            if (floor == null) {
                continue;
            }
            red += red(floor.getRgb());
            green += green(floor.getRgb());
            blue += blue(floor.getRgb());
            count++;
        }
        if (count == 0) {
            return 0x5c7844;
        }
        return ((red / count) << 16) | ((green / count) << 8) | (blue / count);
    }

    private static int[] topIds(Map<Integer, Integer> counts, int limit, int[] fallback) {
        if (counts.isEmpty()) {
            return fallback;
        }
        List<Map.Entry<Integer, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((left, right) -> Integer.compare(right.getValue(), left.getValue()));
        int size = Math.min(limit, entries.size());
        int[] ids = new int[size];
        for (int index = 0; index < size; index++) {
            ids[index] = entries.get(index).getKey();
        }
        return ids.length == 0 ? fallback : ids;
    }

    private static ObjectPlacement[] topPlacements(Map<Integer, Integer> counts, int limit, ObjectPlacement[] fallback) {
        if (counts.isEmpty()) {
            return fallback;
        }
        List<Map.Entry<Integer, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((left, right) -> Integer.compare(right.getValue(), left.getValue()));
        int size = Math.min(limit, entries.size());
        ObjectPlacement[] placements = new ObjectPlacement[size];
        for (int index = 0; index < size; index++) {
            int packed = entries.get(index).getKey();
            placements[index] = new ObjectPlacement(packed >>> 5, packed & 0x1f);
        }
        return placements.length == 0 ? fallback : placements;
    }

    private static double rgbDistance(int rgb, int targetRed, int targetGreen, int targetBlue) {
        double redDistance = red(rgb) - targetRed;
        double greenDistance = green(rgb) - targetGreen;
        double blueDistance = blue(rgb) - targetBlue;
        return Math.sqrt(redDistance * redDistance + greenDistance * greenDistance + blueDistance * blueDistance);
    }

    private static int red(int rgb) {
        return (rgb >> 16) & 0xff;
    }

    private static int green(int rgb) {
        return (rgb >> 8) & 0xff;
    }

    private static int blue(int rgb) {
        return rgb & 0xff;
    }

    private interface NameFilter {
        boolean matches(String name);
    }

    public record ThemeData(
            int[] underlays,
            int waterOverlayId,
            ObjectPlacement[] treePlacements,
            ObjectPlacement[] decorPlacements,
            BuildingKit buildingKit
    ) {
    }

    public record ObjectPlacement(int id, int type) {
    }

    public record BuildingKit(int wallId, int wallType, int doorId, int doorType) {
    }

    public record ShorelineRule(byte shape, byte orientation) {
    }

    private record RegionObject(int id, int type, int orientation, int x, int y, int plane) {
    }

    private record RegionStats(
            double waterRatio,
            double sandRatio,
            double greenRatio,
            double heightDelta,
            int treeCount,
            int decorCount,
            int wallCount,
            int doorCount
    ) {
    }

    private record IntScore(int id, double score) {
    }

    private static final class ThemeAccumulator {
        private final Map<Integer, Integer> underlays = new HashMap<>();
        private final Map<Integer, Integer> waterOverlays = new HashMap<>();
        private final Map<Integer, Integer> treePlacements = new HashMap<>();
        private final Map<Integer, Integer> decorPlacements = new HashMap<>();
        private final Map<Integer, Integer> wallPlacements = new HashMap<>();
        private final Map<Integer, Integer> doorPlacements = new HashMap<>();

        private void absorb(
                MapRegion mapRegion,
                List<RegionObject> objects,
                int underlayWeight,
                int waterWeight,
                int treeWeight,
                int decorWeight,
                int wallWeight,
                int doorWeight
        ) {
            for (int x = 0; x < 64; x++) {
                for (int y = 0; y < 64; y++) {
                    int underlayId = mapRegion.underlays[0][x][y] & 0xffff;
                    int overlayId = mapRegion.overlays[0][x][y] & 0xffff;
                    if (underlayId > 0) {
                        Floor underlay = FloorDefinitionLoader.getUnderlay(underlayId - 1);
                        if (underlayWeight > 0 && isUsableGroundFloor(underlay)) {
                            underlays.merge(underlayId, underlayWeight, Integer::sum);
                        }
                    }
                    if (overlayId > 0) {
                        Floor overlay = FloorDefinitionLoader.getOverlay(overlayId - 1);
                        if (waterWeight > 0 && isUsableWaterOverlay(overlay)) {
                            waterOverlays.merge(overlayId, waterWeight, Integer::sum);
                        }
                    }
                }
            }

            for (RegionObject object : objects) {
                ObjectDefinition definition = ObjectDefinitionLoader.lookup(object.id());
                if (definition == null) {
                    continue;
                }
                String name = normalise(definition.getName());
                if (isTreeName(name) && (object.type() == 10 || object.type() == 22)) {
                    if (treeWeight > 0) {
                        treePlacements.merge(packPlacement(object.id(), object.type()), treeWeight, Integer::sum);
                    }
                }
                if (isDecorName(name) && (object.type() == 10 || object.type() == 22)) {
                    if (decorWeight > 0) {
                        decorPlacements.merge(packPlacement(object.id(), object.type()), decorWeight, Integer::sum);
                    }
                }
                if (object.type() == 0 && isWallName(name)) {
                    if (wallWeight > 0) {
                        wallPlacements.merge(packPlacement(object.id(), object.type()), wallWeight, Integer::sum);
                    }
                }
                if (object.type() == 0 && isDoorName(name)) {
                    if (doorWeight > 0) {
                        doorPlacements.merge(packPlacement(object.id(), object.type()), doorWeight, Integer::sum);
                    }
                }
            }
        }

        private ThemeData build(ThemeData fallback) {
            int[] sampledUnderlays = selectCountedUnderlays(underlays, fallback.underlays(), Math.max(6, fallback.underlays().length));
            int waterOverlayId = REAL_WATER_OVERLAY_ID;
            ObjectPlacement[] sampledTrees = topPlacements(treePlacements, Math.max(4, fallback.treePlacements().length), fallback.treePlacements());
            ObjectPlacement[] sampledDecor = topPlacements(decorPlacements, Math.max(6, fallback.decorPlacements().length), fallback.decorPlacements());

            BuildingKit fallbackKit = fallback.buildingKit();
            int packedWall = wallPlacements.isEmpty() ? packPlacement(fallbackKit.wallId(), fallbackKit.wallType()) : topId(wallPlacements, packPlacement(fallbackKit.wallId(), fallbackKit.wallType()));
            int packedDoor = doorPlacements.isEmpty() ? packPlacement(fallbackKit.doorId(), fallbackKit.doorType()) : topId(doorPlacements, packPlacement(fallbackKit.doorId(), fallbackKit.doorType()));
            BuildingKit buildingKit = isRenderablePlacement(packedWall, 0) && isRenderablePlacement(packedDoor, 0)
                    ? new BuildingKit(
                    unpackPlacementId(packedWall),
                    unpackPlacementType(packedWall),
                    unpackPlacementId(packedDoor),
                    unpackPlacementType(packedDoor)
            )
                    : fallbackKit;

            return new ThemeData(sampledUnderlays, waterOverlayId, sampledTrees, sampledDecor, buildingKit);
        }

        private static int packPlacement(int id, int type) {
            return (id << 5) | (type & 0x1f);
        }

        private static int unpackPlacementId(int packed) {
            return packed >>> 5;
        }

        private static int unpackPlacementType(int packed) {
            return packed & 0x1f;
        }

        private static boolean isRenderablePlacement(int packed, int expectedType) {
            int id = unpackPlacementId(packed);
            int type = unpackPlacementType(packed);
            if (type != expectedType) {
                return false;
            }
            ObjectDefinition definition = ObjectDefinitionLoader.lookup(id);
            return definition != null && definition.getModelIds() != null && definition.getModelIds().length > 0;
        }
    }

    private record MapIndexEntry(int hash, int landscapeId, int objectId) {
    }

    private record RegionSample(MapRegion mapRegion, List<RegionObject> objects) {
    }
}
