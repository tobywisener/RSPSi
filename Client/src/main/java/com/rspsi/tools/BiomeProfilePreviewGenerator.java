package com.rspsi.tools;

import com.jagex.Client;
import com.jagex.cache.def.Floor;
import com.jagex.cache.def.ObjectDefinition;
import com.jagex.cache.loader.floor.FloorDefinitionLoader;
import com.jagex.cache.loader.object.ObjectDefinitionLoader;
import com.jagex.chunk.Chunk;
import com.jagex.map.MapRegion;
import com.jagex.map.SceneGraph;
import com.rspsi.misc.SimplexNoise;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BiomeProfilePreviewGenerator {

    private static final int REGION_SIZE = 64;
    private static final int TILE_HEIGHT_UNIT = 8;
    private static final int MAX_EXPORTABLE_TERRAIN_HEIGHT = 2040;
    private static final int WATER_FLAG = 1;
    private static final byte FULL_TILE_OVERLAY_SHAPE = 0;
    private static final int WATER_CELL = 1;
    private static final int GROUND_CELL = 0;
    private static final int PREVIEW_OVERSCAN_REGIONS = 2;
    private static final int SETTLEMENT_CELL_SPACING = REGION_SIZE * 2;
    private static final int MOUNTAIN_CELL_SPACING = REGION_SIZE * 2;
    private static final int SETTLEMENT_CELL_WINDOW_MARGIN_REGIONS = 2;
    private static final int SETTLEMENT_CELL_WINDOW_REGIONS = 6;
    private static final int PROFILE13_DESERT_PRIMARY_UNDERLAY = 61;
    private static final int PROFILE13_DESERT_SECONDARY_UNDERLAY = 62;
    private static final int DISPLAY_ROUNDED_PLAZA_CORNER_SHAPE_ID = 11;
    private static final byte STORED_ROUNDED_PLAZA_CORNER_SHAPE = (byte) (DISPLAY_ROUNDED_PLAZA_CORNER_SHAPE_ID - 1);
    private static final int ROOF_SLOPE_SIDE_TYPE = 12;
    private static final int ROOF_SLOPE_INSET_CORNER_TYPE = 13;
    private static final int ROOF_SLOPE_CORNER_TYPE = 14;
    private static final int ROOF_TOP_FLAT_TYPE = 17;
    private static volatile List<SearchableObjectDefinition> searchableObjectDefinitions;
    private static final Set<Integer> readyPreviewModelIds = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, List<CachedChunkData>> previewChunkCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, WritableImage> previewThumbnailCache = new ConcurrentHashMap<>();

    private BiomeProfilePreviewGenerator() {
    }

    public static void invalidatePreviewCache() {
        previewChunkCache.clear();
        previewThumbnailCache.clear();
    }

    private static int centeredOriginRegionX(int chunkWidth) {
        return -Math.floorDiv(Math.max(1, chunkWidth), 2);
    }

    private static int centeredOriginRegionY(int chunkHeight) {
        return -Math.floorDiv(Math.max(1, chunkHeight), 2);
    }

    public static List<Chunk> generatePreview(
            CacheBiomeProfileMiner.BiomeProfile profile,
            int chunkWidth,
            int chunkHeight,
            long seed
    ) {
        return generatePreview(profile, chunkWidth, chunkHeight, seed, centeredOriginRegionX(chunkWidth), centeredOriginRegionY(chunkHeight), hasSettlementProfile(profile), 0L);
    }

    public static List<Chunk> generatePreview(
            CacheBiomeProfileMiner.BiomeProfile profile,
            int chunkWidth,
            int chunkHeight,
            long seed,
            boolean includeSettlement
    ) {
        return generatePreview(profile, chunkWidth, chunkHeight, seed, centeredOriginRegionX(chunkWidth), centeredOriginRegionY(chunkHeight), includeSettlement, 0L);
    }

    public static List<Chunk> generatePreview(
            CacheBiomeProfileMiner.BiomeProfile profile,
            int chunkWidth,
            int chunkHeight,
            long seed,
            int originRegionX,
            int originRegionY
    ) {
        return generatePreview(profile, chunkWidth, chunkHeight, seed, originRegionX, originRegionY, hasSettlementProfile(profile), 0L);
    }

    public static List<Chunk> generatePreview(
            CacheBiomeProfileMiner.BiomeProfile profile,
            int chunkWidth,
            int chunkHeight,
            long seed,
            int originRegionX,
            int originRegionY,
            boolean includeSettlement
    ) {
        return generatePreview(profile, chunkWidth, chunkHeight, seed, originRegionX, originRegionY, includeSettlement, 0L);
    }

    public static List<Chunk> generatePreview(
            CacheBiomeProfileMiner.BiomeProfile profile,
            int chunkWidth,
            int chunkHeight,
            long seed,
            int originRegionX,
            int originRegionY,
            boolean includeSettlement,
            long settlementRoll
    ) {
        int safeChunkWidth = Math.max(1, chunkWidth);
        int safeChunkHeight = Math.max(1, chunkHeight);
        PreviewWindow previewWindow = PreviewWindow.at(originRegionX, originRegionY, safeChunkWidth, safeChunkHeight);
        PreviewWindow plannerWindow = expandPreviewWindow(previewWindow, PREVIEW_OVERSCAN_REGIONS);
        int worldWidth = plannerWindow.worldWidth();
        int worldHeight = plannerWindow.worldHeight();
        int visibleOffsetX = (previewWindow.originRegionX() - plannerWindow.originRegionX()) * REGION_SIZE;
        int visibleOffsetY = (previewWindow.originRegionY() - plannerWindow.originRegionY()) * REGION_SIZE;
        String cacheKey = previewCacheKey(profile, previewWindow, seed, includeSettlement, settlementRoll);
        List<CachedChunkData> cached = previewChunkCache.get(cacheKey);
        if (cached != null) {
            return cloneCachedChunks(cached);
        }

        PreviewPalette palette = PreviewPalette.fromProfile(profile);
        PreviewObjectSet objects = PreviewObjectSet.fromProfile(profile);
        CacheBiomeProfileMiner.SettlementProfile worldSettlementProfile = resolvePreviewSettlementProfile(profile, palette, seed);
        CacheBiomeProfileMiner.SettlementProfile settlementProfile = includeSettlement ? worldSettlementProfile : null;
        long worldSettlementSeed = settlementSeed(seed, settlementRoll);
        boolean[][] roadMask = new boolean[worldWidth][worldHeight];
        boolean[][] waterMask = new boolean[worldWidth][worldHeight];
        buildRoadMask(profile, palette, roadMask, plannerWindow, seed);
        buildWaterMask(profile, palette, waterMask, roadMask, plannerWindow, seed);
        List<AcceptedSettlementCell> acceptedSettlementCells = worldSettlementProfile == null
                ? List.of()
                : collectAcceptedSettlementCells(profile, worldSettlementProfile, plannerWindow, worldSettlementSeed);
        SettlementPlan settlementPlan = includeSettlement && settlementProfile != null
                ? planSettlement(profile, settlementProfile, previewWindow, plannerWindow, worldSettlementSeed, acceptedSettlementCells)
                : null;
        MountainFeaturePlan mountainPlan = planMountainFeatures(profile, acceptedSettlementCells, plannerWindow, seed);
        boolean[][] mountainMask = buildMountainTileMask(mountainPlan, worldWidth, worldHeight);
        boolean modelsReady = warmPreviewModels(objects, settlementPlan, settlementProfile);

        SceneGraph sceneGraph = new SceneGraph(worldWidth, worldHeight, 4);
        MapRegion mapRegion = new MapRegion(sceneGraph, worldWidth, worldHeight);
        boolean[][] reservedMask = new boolean[worldWidth][worldHeight];

        paintTerrain(mapRegion, profile, palette, waterMask, roadMask, plannerWindow, seed);
        if (mountainPlan != null) {
            applyMountainTerrain(mapRegion, mountainPlan, worldWidth, worldHeight);
        }
        if (settlementPlan != null) {
            applySettlementTerrain(mapRegion, settlementPlan, reservedMask, mountainMask, worldWidth, worldHeight);
        }
        seedUpperPlaneHeights(mapRegion, worldWidth, worldHeight);

        mapRegion.setHeights();
        mapRegion.method171(sceneGraph);

        if (settlementPlan != null) {
            placeSettlement(mapRegion, sceneGraph, settlementPlan, reservedMask, worldWidth, worldHeight, seed);
        }
        placeObjects(mapRegion, sceneGraph, profile, objects, waterMask, roadMask, reservedMask, mountainMask, plannerWindow, seed);

        List<Chunk> chunks = new ArrayList<>();
        int fileId = 0;
        for (int chunkX = 0; chunkX < safeChunkWidth; chunkX++) {
            for (int chunkY = 0; chunkY < safeChunkHeight; chunkY++) {
                int regionX = previewWindow.originRegionX() + chunkX;
                int regionY = previewWindow.originRegionY() + chunkY;
                int hash = ((regionX & 0xff) << 8) | (regionY & 0xff);
                Chunk chunk = new Chunk(hash);
                int worldOffsetX = regionX * REGION_SIZE;
                int worldOffsetY = regionY * REGION_SIZE;
                int localOffsetX = visibleOffsetX + chunkX * REGION_SIZE;
                int localOffsetY = visibleOffsetY + chunkY * REGION_SIZE;
                chunk.offsetX = localOffsetX;
                chunk.offsetY = localOffsetY;
                chunk.tileMapId = fileId++;
                chunk.objectMapId = fileId++;
                chunk.tileMapData = mapRegion.save_terrain_block(chunk);
                chunk.objectMapData = sceneGraph.saveObjects(chunk);
                chunk.offsetX = worldOffsetX;
                chunk.offsetY = worldOffsetY;
                chunks.add(chunk);
            }
        }
        if (modelsReady) {
            previewChunkCache.put(cacheKey, snapshotChunks(chunks));
        }
        return chunks;
    }

    public static WritableImage renderThumbnail(
            CacheBiomeProfileMiner.BiomeProfile profile,
            int chunkWidth,
            int chunkHeight,
            long seed,
            int imageWidth,
            int imageHeight
    ) {
        return renderThumbnail(profile, chunkWidth, chunkHeight, seed, imageWidth, imageHeight, centeredOriginRegionX(chunkWidth), centeredOriginRegionY(chunkHeight), hasSettlementProfile(profile), 0L);
    }

    public static WritableImage renderThumbnail(
            CacheBiomeProfileMiner.BiomeProfile profile,
            int chunkWidth,
            int chunkHeight,
            long seed,
            int imageWidth,
            int imageHeight,
            boolean includeSettlement
    ) {
        return renderThumbnail(profile, chunkWidth, chunkHeight, seed, imageWidth, imageHeight, centeredOriginRegionX(chunkWidth), centeredOriginRegionY(chunkHeight), includeSettlement, 0L);
    }

    public static WritableImage renderThumbnail(
            CacheBiomeProfileMiner.BiomeProfile profile,
            int chunkWidth,
            int chunkHeight,
            long seed,
            int imageWidth,
            int imageHeight,
            int originRegionX,
            int originRegionY
    ) {
        return renderThumbnail(profile, chunkWidth, chunkHeight, seed, imageWidth, imageHeight, originRegionX, originRegionY, hasSettlementProfile(profile), 0L);
    }

    public static WritableImage renderThumbnail(
            CacheBiomeProfileMiner.BiomeProfile profile,
            int chunkWidth,
            int chunkHeight,
            long seed,
            int imageWidth,
            int imageHeight,
            int originRegionX,
            int originRegionY,
            boolean includeSettlement
    ) {
        return renderThumbnail(profile, chunkWidth, chunkHeight, seed, imageWidth, imageHeight, originRegionX, originRegionY, includeSettlement, 0L);
    }

    public static WritableImage renderThumbnail(
            CacheBiomeProfileMiner.BiomeProfile profile,
            int chunkWidth,
            int chunkHeight,
            long seed,
            int imageWidth,
            int imageHeight,
            int originRegionX,
            int originRegionY,
            boolean includeSettlement,
            long settlementRoll
    ) {
        int safeChunkWidth = Math.max(1, chunkWidth);
        int safeChunkHeight = Math.max(1, chunkHeight);
        int safeImageWidth = Math.max(96, imageWidth);
        int safeImageHeight = Math.max(96, imageHeight);
        PreviewWindow previewWindow = PreviewWindow.at(originRegionX, originRegionY, safeChunkWidth, safeChunkHeight);
        PreviewWindow plannerWindow = expandPreviewWindow(previewWindow, PREVIEW_OVERSCAN_REGIONS);
        int worldWidth = plannerWindow.worldWidth();
        int worldHeight = plannerWindow.worldHeight();
        int visibleWorldWidth = previewWindow.worldWidth();
        int visibleWorldHeight = previewWindow.worldHeight();
        int visibleOffsetX = (previewWindow.originRegionX() - plannerWindow.originRegionX()) * REGION_SIZE;
        int visibleOffsetY = (previewWindow.originRegionY() - plannerWindow.originRegionY()) * REGION_SIZE;
        String cacheKey = previewCacheKey(profile, previewWindow, seed, includeSettlement, settlementRoll)
                + ":thumb:" + safeImageWidth + "x" + safeImageHeight;
        WritableImage cached = previewThumbnailCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        PreviewPalette palette = PreviewPalette.fromProfile(profile);
        PreviewObjectSet objects = PreviewObjectSet.fromProfile(profile);
        boolean[][] roadMask = new boolean[worldWidth][worldHeight];
        boolean[][] waterMask = new boolean[worldWidth][worldHeight];
        SettlementPlan settlementPlan;
        long worldSettlementSeed = settlementSeed(seed, settlementRoll);

        buildRoadMask(profile, palette, roadMask, plannerWindow, seed);
        buildWaterMask(profile, palette, waterMask, roadMask, plannerWindow, seed);
        CacheBiomeProfileMiner.SettlementProfile worldSettlementProfile = resolvePreviewSettlementProfile(profile, palette, seed);
        CacheBiomeProfileMiner.SettlementProfile settlementProfile = includeSettlement ? worldSettlementProfile : null;
        List<AcceptedSettlementCell> acceptedSettlementCells = worldSettlementProfile == null
                ? List.of()
                : collectAcceptedSettlementCells(profile, worldSettlementProfile, plannerWindow, worldSettlementSeed);
        settlementPlan = includeSettlement && settlementProfile != null
                ? planSettlement(profile, settlementProfile, previewWindow, plannerWindow, worldSettlementSeed, acceptedSettlementCells)
                : null;
        MountainFeaturePlan mountainPlan = planMountainFeatures(profile, acceptedSettlementCells, plannerWindow, seed);

        boolean aridProfile = isAridProfile(profile);
        boolean profile13Desert = isProfile13DesertProfile(profile);
        short darkerGroundUnderlay = resolveDarkerGroundUnderlay(profile, palette);
        double ruggedness = terrainRuggedness(profile);
        int baseHeight = terrainBaseHeight();
        int relief = terrainRelief(profile, ruggedness);
        int elevationBias = terrainElevationBias(profile);
        VegetationProfile vegetation = vegetationProfile(profile);

        Color waterColor = materialColor(palette.waterOverlayId, true, Color.rgb(59, 97, 145));
        Color treeColor = Color.rgb(40, 74, 35);
        Color shrubColor = Color.rgb(64, 96, 42);
        Color decorColor = Color.rgb(78, 104, 52);
        WritableImage image = new WritableImage(safeImageWidth, safeImageHeight);
        PixelWriter writer = image.getPixelWriter();

        for (int pixelY = 0; pixelY < safeImageHeight; pixelY++) {
            int tileY = Math.min(visibleWorldHeight - 1, (pixelY * visibleWorldHeight) / safeImageHeight);
            int plannerTileY = visibleOffsetY + tileY;
            for (int pixelX = 0; pixelX < safeImageWidth; pixelX++) {
                int tileX = Math.min(visibleWorldWidth - 1, (pixelX * visibleWorldWidth) / safeImageWidth);
                int plannerTileX = visibleOffsetX + tileX;
                int absoluteX = previewWindow.absoluteTileX(tileX);
                int absoluteY = previewWindow.absoluteTileY(tileY);
                boolean waterTile = waterMask[plannerTileX][plannerTileY];
                boolean nearWater = !waterTile && touchesWater(waterMask, plannerTileX, plannerTileY, worldWidth, worldHeight);
                short underlayId = profile13Desert
                        ? chooseProfile13DesertUnderlay(seed, absoluteX, absoluteY)
                        : chooseUnderlay(palette, seed, absoluteX, absoluteY);
                if (darkerGroundUnderlay > 0 && (waterTile || nearWater)) {
                    underlayId = darkerGroundUnderlay;
                }
                Color baseColor = materialColor(underlayId, false, Color.rgb(96, 124, 72));
                double heightShade = sampleHeight(seed, absoluteX, absoluteY, baseHeight, relief, ruggedness, aridProfile, profile13Desert, elevationBias)
                        / (double) Math.max(TILE_HEIGHT_UNIT, baseHeight + relief + elevationBias);
                Color shaded = mix(baseColor, Color.BLACK, clamp(0.10 - heightShade * 0.12, -0.04, 0.16));

                if (waterTile) {
                    OverlayTileShape shoreline = resolveWaterOverlayShape(waterMask, plannerTileX, plannerTileY, worldWidth, worldHeight);
                    shaded = shoreline == null ? waterColor : mix(waterColor, Color.WHITE, 0.10);
                } else {
                    if (!objects.treeObjects.isEmpty()) {
                        if (hashedUnit(seed ^ 0x91abL, absoluteX, absoluteY) < treePlacementChance(vegetation.trees(), seed, absoluteX, absoluteY)) {
                            shaded = mix(shaded, treeColor, 0.45);
                        }
                    }

                    if (!objects.shrubObjects.isEmpty()) {
                        if (hashedUnit(seed ^ 0x57cdL, absoluteX, absoluteY) < shrubPlacementChance(vegetation.shrubs(), seed, absoluteX, absoluteY, nearWater)) {
                            shaded = mix(shaded, shrubColor, 0.28);
                        }
                    }

                    if (!objects.decorObjects.isEmpty()) {
                        if (hashedUnit(seed ^ 0x2bf1L, absoluteX, absoluteY) < decorPlacementChance(vegetation.decor(), seed, absoluteX, absoluteY, nearWater)) {
                            shaded = mix(shaded, decorColor, 0.22);
                        }
                    }

                    if (nearWater) {
                        shaded = mix(shaded, waterColor, 0.14);
                    }
                }

                writer.setColor(pixelX, pixelY, shaded);
            }
        }

        if (mountainPlan != null) {
            renderMountainThumbnail(
                    image,
                    mountainPlan,
                    safeImageWidth,
                    safeImageHeight,
                    visibleWorldWidth,
                    visibleWorldHeight,
                    visibleOffsetX,
                    visibleOffsetY
            );
        }

        if (settlementPlan != null) {
            renderSettlementThumbnail(
                    image,
                    settlementPlan,
                    safeImageWidth,
                    safeImageHeight,
                    visibleWorldWidth,
                    visibleWorldHeight,
                    visibleOffsetX,
                    visibleOffsetY
            );
        }

        previewThumbnailCache.put(cacheKey, image);
        return image;
    }

    private static String previewCacheKey(
            CacheBiomeProfileMiner.BiomeProfile profile,
            PreviewWindow previewWindow,
            long seed,
            boolean includeSettlement,
            long settlementRoll
    ) {
        return System.identityHashCode(profile)
                + ":" + previewWindow.originRegionX()
                + "," + previewWindow.originRegionY()
                + ":" + previewWindow.chunkWidth()
                + "x" + previewWindow.chunkHeight()
                + ":" + seed
                + ":" + includeSettlement
                + ":" + settlementRoll;
    }

    private static PreviewWindow expandPreviewWindow(PreviewWindow previewWindow, int regionMargin) {
        if (regionMargin <= 0) {
            return previewWindow;
        }
        return PreviewWindow.at(
                previewWindow.originRegionX() - regionMargin,
                previewWindow.originRegionY() - regionMargin,
                previewWindow.chunkWidth() + regionMargin * 2,
                previewWindow.chunkHeight() + regionMargin * 2
        );
    }

    private static List<CachedChunkData> snapshotChunks(List<Chunk> chunks) {
        List<CachedChunkData> snapshots = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            snapshots.add(new CachedChunkData(
                    chunk.regionHash,
                    chunk.offsetX,
                    chunk.offsetY,
                    chunk.tileMapId,
                    chunk.objectMapId,
                    chunk.tileMapData == null ? new byte[0] : Arrays.copyOf(chunk.tileMapData, chunk.tileMapData.length),
                    chunk.objectMapData == null ? new byte[0] : Arrays.copyOf(chunk.objectMapData, chunk.objectMapData.length)
            ));
        }
        return snapshots;
    }

    private static List<Chunk> cloneCachedChunks(List<CachedChunkData> cachedChunks) {
        List<Chunk> clones = new ArrayList<>(cachedChunks.size());
        for (CachedChunkData cached : cachedChunks) {
            Chunk chunk = new Chunk(cached.regionHash());
            chunk.offsetX = cached.offsetX();
            chunk.offsetY = cached.offsetY();
            chunk.tileMapId = cached.tileMapId();
            chunk.objectMapId = cached.objectMapId();
            chunk.tileMapData = Arrays.copyOf(cached.tileMapData(), cached.tileMapData().length);
            chunk.objectMapData = Arrays.copyOf(cached.objectMapData(), cached.objectMapData().length);
            clones.add(chunk);
        }
        return clones;
    }

    private static long settlementSeed(long seed, long settlementRoll) {
        if (settlementRoll == 0L) {
            return seed;
        }
        long mixed = settlementRoll * 0x9E3779B97F4A7C15L;
        return seed ^ Long.rotateLeft(mixed, 17);
    }

    private static boolean hasSettlementProfile(CacheBiomeProfileMiner.BiomeProfile profile) {
        if (profile == null) {
            return false;
        }
        return CacheBiomeProfileMiner.resolveSettlementProfile(profile) != null;
    }

    private static boolean supportsMountainFeatures(CacheBiomeProfileMiner.BiomeProfile profile) {
        if (profile == null || profile.label == null) {
            return false;
        }
        String label = profile.label.toLowerCase(Locale.ROOT);
        return label.startsWith("profile_01") || label.startsWith("profile_09");
    }

    private static MountainFeaturePlan planMountainFeatures(
            CacheBiomeProfileMiner.BiomeProfile profile,
            List<AcceptedSettlementCell> acceptedSettlementCells,
            PreviewWindow plannerWindow,
            long seed
    ) {
        if (!supportsMountainFeatures(profile)) {
            return null;
        }
        int minCellX = Math.floorDiv(plannerWindow.originTileX(), MOUNTAIN_CELL_SPACING) - 1;
        int maxCellX = Math.floorDiv(plannerWindow.originTileX() + plannerWindow.worldWidth() - 1, MOUNTAIN_CELL_SPACING) + 1;
        int minCellY = Math.floorDiv(plannerWindow.originTileY(), MOUNTAIN_CELL_SPACING) - 1;
        int maxCellY = Math.floorDiv(plannerWindow.originTileY() + plannerWindow.worldHeight() - 1, MOUNTAIN_CELL_SPACING) + 1;
        List<PlacedMountainFeature> features = new ArrayList<>();
        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellY = minCellY; cellY <= maxCellY; cellY++) {
                if (hashedUnit(seed ^ 0x72f4c1L, cellX, cellY) > 0.82) {
                    continue;
                }
                int width = 32 + (int) Math.floor(hashedUnit(seed ^ 0x18a7L, cellX, cellY) * 22.0);
                int height = 40 + (int) Math.floor(hashedUnit(seed ^ 0x29b8L, cellX, cellY) * 26.0);
                int absoluteMinX = cellX * MOUNTAIN_CELL_SPACING + (int) Math.floor(hashedUnit(seed ^ 0x34c9L, cellX, cellY) * Math.max(1, MOUNTAIN_CELL_SPACING - width));
                int absoluteMinY = cellY * MOUNTAIN_CELL_SPACING + (int) Math.floor(hashedUnit(seed ^ 0x45daL, cellX, cellY) * Math.max(1, MOUNTAIN_CELL_SPACING - height));
                int absoluteMaxX = absoluteMinX + width - 1;
                int absoluteMaxY = absoluteMinY + height - 1;
                if (overlapsAcceptedSettlementCells(acceptedSettlementCells, absoluteMinX - 2, absoluteMinY - 2, absoluteMaxX + 2, absoluteMaxY + 2)) {
                    continue;
                }
                if (absoluteMaxX < plannerWindow.originTileX()
                        || absoluteMaxY < plannerWindow.originTileY()
                        || absoluteMinX >= plannerWindow.originTileX() + plannerWindow.worldWidth()
                        || absoluteMinY >= plannerWindow.originTileY() + plannerWindow.worldHeight()) {
                    continue;
                }
                int localMinX = absoluteMinX - plannerWindow.originTileX();
                int localMinY = absoluteMinY - plannerWindow.originTileY();
                int localMaxX = absoluteMaxX - plannerWindow.originTileX();
                int localMaxY = absoluteMaxY - plannerWindow.originTileY();
                double plateauRadius = 0.30 + hashedUnit(seed ^ 0x781dL, cellX, cellY) * 0.12;
                int peakHeight = 1504 + (int) Math.floor(hashedUnit(seed ^ 0x892eL, cellX, cellY) * 240.0);
                double rotation = hashedUnit(seed ^ 0x9a3fL, cellX, cellY) * Math.PI;
                long featureSeed = seed ^ ((long) cellX << 32) ^ (cellY & 0xffffffffL) ^ 0x5f3c2a19L;
                List<MountainLobe> lobes = buildMountainLobes(width, height, featureSeed);
                features.add(new PlacedMountainFeature(localMinX, localMinY, localMaxX, localMaxY, 0, peakHeight, plateauRadius, rotation, featureSeed, lobes));
            }
        }
        return features.isEmpty() ? null : new MountainFeaturePlan(features);
    }

    private static List<MountainLobe> buildMountainLobes(int width, int height, long seed) {
        List<MountainLobe> lobes = new ArrayList<>();
        int peakCount = 3 + (int) Math.floor(hashedUnit(seed ^ 0x11a3L, width, height) * 3.0);
        double spineSpan = height * (0.22 + hashedUnit(seed ^ 0x26b4L, width, height) * 0.08);
        double spineCurve = width * (0.08 + hashedUnit(seed ^ 0x37c5L, width, height) * 0.06);
        double ridgeRadiusX = width * (0.18 + hashedUnit(seed ^ 0x48d6L, width, height) * 0.05);
        double ridgeRadiusY = height * (0.13 + hashedUnit(seed ^ 0x59e7L, width, height) * 0.04);
        List<MountainLobe> ridgePeaks = new ArrayList<>(peakCount);
        for (int index = 0; index < peakCount; index++) {
            double t = peakCount <= 1 ? 0.0 : index / (double) (peakCount - 1);
            double centredT = t * 2.0 - 1.0;
            double curveBias = Math.sin(t * Math.PI) * spineCurve;
            double lateralJitter = (hashedUnit(seed ^ (0x6af8L + index * 17L), width, height) - 0.5) * width * 0.10;
            double axialJitter = (hashedUnit(seed ^ (0x7bf9L + index * 29L), width, height) - 0.5) * height * 0.04;
            double offsetX = curveBias + lateralJitter;
            double offsetY = centredT * spineSpan + axialJitter;
            double radiusX = ridgeRadiusX * (0.90 + hashedUnit(seed ^ (0x8c1aL + index * 31L), width, height) * 0.34);
            double radiusY = ridgeRadiusY * (0.92 + hashedUnit(seed ^ (0x9d2bL + index * 37L), width, height) * 0.28);
            double weight = 0.92
                    + Math.sin(t * Math.PI) * 0.12
                    + hashedUnit(seed ^ (0xae3cL + index * 41L), width, height) * 0.08;
            ridgePeaks.add(new MountainLobe(offsetX, offsetY, radiusX, radiusY, weight));
        }
        lobes.addAll(ridgePeaks);
        for (int index = 0; index < ridgePeaks.size() - 1; index++) {
            MountainLobe first = ridgePeaks.get(index);
            MountainLobe second = ridgePeaks.get(index + 1);
            double bridgeX = (first.offsetX() + second.offsetX()) * 0.5;
            double bridgeY = (first.offsetY() + second.offsetY()) * 0.5;
            double bridgeRadiusX = Math.max(first.radiusX(), second.radiusX()) * 0.96;
            double bridgeRadiusY = Math.max(first.radiusY(), second.radiusY()) * 0.88;
            lobes.add(new MountainLobe(bridgeX, bridgeY, bridgeRadiusX, bridgeRadiusY, 0.74));
        }
        return lobes;
    }

    private static boolean overlapsAcceptedSettlementCells(
            List<AcceptedSettlementCell> acceptedSettlementCells,
            int absoluteMinX,
            int absoluteMinY,
            int absoluteMaxX,
            int absoluteMaxY
    ) {
        if (acceptedSettlementCells == null || acceptedSettlementCells.isEmpty()) {
            return false;
        }
        for (AcceptedSettlementCell settlementCell : acceptedSettlementCells) {
            SettlementSite site = settlementCell.absoluteSite();
            if (site == null) {
                continue;
            }
            if (absoluteMaxX < site.settlementMinX() - 8
                    || absoluteMinX > site.settlementMaxX() + 8
                    || absoluteMaxY < site.settlementMinY() - 8
                    || absoluteMinY > site.settlementMaxY() + 8) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static void applyMountainTerrain(MapRegion mapRegion, MountainFeaturePlan mountainPlan, int worldWidth, int worldHeight) {
        for (PlacedMountainFeature feature : mountainPlan.features()) {
            applyMountainFeature(mapRegion, feature, worldWidth, worldHeight);
        }
    }

    private static boolean[][] buildMountainTileMask(MountainFeaturePlan mountainPlan, int worldWidth, int worldHeight) {
        boolean[][] mountainMask = new boolean[worldWidth][worldHeight];
        if (mountainPlan == null) {
            return mountainMask;
        }
        for (PlacedMountainFeature feature : mountainPlan.features()) {
            for (int x = Math.max(0, feature.minX()); x <= Math.min(worldWidth - 1, feature.maxX()); x++) {
                for (int y = Math.max(0, feature.minY()); y <= Math.min(worldHeight - 1, feature.maxY()); y++) {
                    if (evaluateMountainTileBase(feature, x, y).active()) {
                        mountainMask[x][y] = true;
                    }
                }
            }
        }
        return mountainMask;
    }

    private static void applyMountainFeature(MapRegion mapRegion, PlacedMountainFeature feature, int worldWidth, int worldHeight) {
        if (feature == null) {
            return;
        }
        int featureBaseHeight = sampleMountainBaseHeight(mapRegion, feature, worldWidth, worldHeight);
        int availableUplift = Math.max(TILE_HEIGHT_UNIT * 8, quantizeHeight(MAX_EXPORTABLE_TERRAIN_HEIGHT - featureBaseHeight));
        int effectivePeakHeight = Math.min(feature.peakHeight(), availableUplift);
        int[][] desiredVertexHeights = new int[worldWidth + 1][worldHeight + 1];
        boolean[][] hasDesiredVertex = new boolean[worldWidth + 1][worldHeight + 1];
        for (int worldX = feature.minX(); worldX <= feature.maxX(); worldX++) {
            for (int worldY = feature.minY(); worldY <= feature.maxY(); worldY++) {
                if (worldX < 0 || worldY < 0 || worldX >= worldWidth || worldY >= worldHeight) {
                    continue;
                }
                MountainTileState state = evaluateMountainTile(feature, worldX, worldY, effectivePeakHeight);
                if (!state.active()) {
                    continue;
                }
                int targetHeight = quantizeHeight(Math.min(MAX_EXPORTABLE_TERRAIN_HEIGHT, featureBaseHeight + state.height()));
                for (int vertexX = worldX; vertexX <= Math.min(worldWidth, worldX + 1); vertexX++) {
                    for (int vertexY = worldY; vertexY <= Math.min(worldHeight, worldY + 1); vertexY++) {
                        if (!hasDesiredVertex[vertexX][vertexY] || targetHeight > desiredVertexHeights[vertexX][vertexY]) {
                            desiredVertexHeights[vertexX][vertexY] = targetHeight;
                            hasDesiredVertex[vertexX][vertexY] = true;
                        }
                    }
                }
                mapRegion.underlays[0][worldX][worldY] = (short) state.underlayId();
                mapRegion.overlays[0][worldX][worldY] = (short) state.overlayId();
                mapRegion.overlayShapes[0][worldX][worldY] = (byte) state.overlayType();
                mapRegion.overlayOrientations[0][worldX][worldY] = (byte) state.overlayOrientation();
                mapRegion.tileFlags[0][worldX][worldY] = (byte) state.tileFlag();
                mapRegion.manualTileHeight[0][worldX][worldY] = 1;
            }
        }
        for (int vertexX = Math.max(0, feature.minX()); vertexX <= Math.min(worldWidth, feature.maxX() + 1); vertexX++) {
            for (int vertexY = Math.max(0, feature.minY()); vertexY <= Math.min(worldHeight, feature.maxY() + 1); vertexY++) {
                if (!hasDesiredVertex[vertexX][vertexY]) {
                    continue;
                }
                int currentHeight = Math.abs(mapRegion.tileHeights[0][vertexX][vertexY]);
                int targetHeight = Math.max(currentHeight, desiredVertexHeights[vertexX][vertexY]);
                mapRegion.tileHeights[0][vertexX][vertexY] = -quantizeHeight(targetHeight);
            }
        }
    }

    private static int sampleMountainBaseHeight(MapRegion mapRegion, PlacedMountainFeature feature, int worldWidth, int worldHeight) {
        List<Integer> perimeterHeights = new ArrayList<>();
        List<Integer> centreHeights = new ArrayList<>(9);
        int centreX = Math.max(0, Math.min(worldWidth - 1, feature.centreX()));
        int centreY = Math.max(0, Math.min(worldHeight - 1, feature.centreY()));
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                int sampleX = centreX + dx;
                int sampleY = centreY + dy;
                if (sampleX < 0 || sampleY < 0 || sampleX >= worldWidth || sampleY >= worldHeight) {
                    continue;
                }
                centreHeights.add(averageTileHeight(mapRegion, sampleX, sampleY));
            }
        }

        int sampleMinX = Math.max(0, feature.minX() - 2);
        int sampleMinY = Math.max(0, feature.minY() - 2);
        int sampleMaxX = Math.min(worldWidth - 1, feature.maxX() + 2);
        int sampleMaxY = Math.min(worldHeight - 1, feature.maxY() + 2);
        for (int x = sampleMinX; x <= sampleMaxX; x++) {
            for (int y = sampleMinY; y <= sampleMaxY; y++) {
                boolean insideFeature = x >= feature.minX() && x <= feature.maxX() && y >= feature.minY() && y <= feature.maxY();
                if (insideFeature) {
                    continue;
                }
                int dx = 0;
                if (x < feature.minX()) {
                    dx = feature.minX() - x;
                } else if (x > feature.maxX()) {
                    dx = x - feature.maxX();
                }
                int dy = 0;
                if (y < feature.minY()) {
                    dy = feature.minY() - y;
                } else if (y > feature.maxY()) {
                    dy = y - feature.maxY();
                }
                if (Math.max(dx, dy) > 2) {
                    continue;
                }
                perimeterHeights.add(averageTileHeight(mapRegion, x, y));
            }
        }

        if (perimeterHeights.isEmpty() && centreHeights.isEmpty()) {
            return TILE_HEIGHT_UNIT * 5;
        }
        int centreMedian = centreHeights.isEmpty() ? TILE_HEIGHT_UNIT * 5 : percentile(centreHeights, 0.5);
        int perimeterMedian = perimeterHeights.isEmpty() ? centreMedian : percentile(perimeterHeights, 0.5);
        int perimeterHigh = perimeterHeights.isEmpty() ? centreMedian : percentile(perimeterHeights, 0.7);
        int base = Math.max(centreMedian, quantizeHeight((int) Math.round(perimeterMedian * 0.65 + perimeterHigh * 0.35)));
        return quantizeHeight(base);
    }

    private static void renderMountainThumbnail(
            WritableImage image,
            MountainFeaturePlan mountainPlan,
            int imageWidth,
            int imageHeight,
            int worldWidth,
            int worldHeight,
            int visibleMinX,
            int visibleMinY
    ) {
        PixelWriter writer = image.getPixelWriter();
        for (PlacedMountainFeature feature : mountainPlan.features()) {
            for (int worldX = feature.minX(); worldX <= feature.maxX(); worldX++) {
                for (int worldY = feature.minY(); worldY <= feature.maxY(); worldY++) {
                MountainTileState tile = evaluateMountainTile(feature, worldX, worldY);
                if (!tile.active()) {
                    continue;
                }
                int tileX = worldX - visibleMinX;
                int tileY = worldY - visibleMinY;
                if (tileX < 0 || tileY < 0 || tileX >= worldWidth || tileY >= worldHeight) {
                    continue;
                }
                int minPixelX = tileMinPixel(tileX, imageWidth, worldWidth);
                int maxPixelX = tileMaxPixel(tileX, imageWidth, worldWidth);
                int minPixelY = tileMinPixel(tileY, imageHeight, worldHeight);
                int maxPixelY = tileMaxPixel(tileY, imageHeight, worldHeight);
                Color base = materialColor(tile.underlayId(), false, Color.rgb(170, 176, 184));
                if (tile.overlayId() > 0) {
                    base = materialColor(tile.overlayId(), true, base);
                }
                if (tile.tileFlag() != 0) {
                    base = mix(base, Color.BLACK, 0.16);
                }
                double shade = clamp(tile.height() / (double) Math.max(1, feature.peakHeight()) * 0.28, 0.0, 0.28);
                Color shaded = shade >= 0 ? mix(base, Color.WHITE, shade) : mix(base, Color.BLACK, -shade);
                for (int pixelX = minPixelX; pixelX <= maxPixelX; pixelX++) {
                    for (int pixelY = minPixelY; pixelY <= maxPixelY; pixelY++) {
                        writer.setColor(pixelX, pixelY, shaded);
                    }
                }
            }
            }
        }
    }

    private static MountainTileState evaluateMountainTile(PlacedMountainFeature feature, int worldX, int worldY) {
        return evaluateMountainTile(feature, worldX, worldY, feature.peakHeight());
    }

    private static MountainTileState evaluateMountainTile(PlacedMountainFeature feature, int worldX, int worldY, int peakHeight) {
        MountainTileBase base = evaluateMountainTileBase(feature, worldX, worldY, peakHeight);
        if (!base.active()) {
            return MountainTileState.inactive();
        }
        int overlayType = 0;
        int overlayOrientation = 0;
        return new MountainTileState(true, base.height(), base.underlayId(), base.overlayId(), overlayType, overlayOrientation, base.tileFlag());
    }

    private static MountainTileBase evaluateMountainTileBase(PlacedMountainFeature feature, int worldX, int worldY) {
        return evaluateMountainTileBase(feature, worldX, worldY, feature.peakHeight());
    }

    private static MountainTileBase evaluateMountainTileBase(PlacedMountainFeature feature, int worldX, int worldY, int peakHeight) {
        double centerX = (feature.minX() + feature.maxX()) / 2.0;
        double centerY = (feature.minY() + feature.maxY()) / 2.0;
        double localX = worldX - centerX;
        double localY = worldY - centerY;
        double cos = Math.cos(feature.rotation());
        double sin = Math.sin(feature.rotation());
        double rotatedX = localX * cos - localY * sin;
        double rotatedY = localX * sin + localY * cos;
        double ridgeNoise = normalize(octaveNoise(feature.seed() ^ 0x71c2L, worldX * 0.065, worldY * 0.065, 3, 0.56));
        double contourNoise = normalize(octaveNoise(feature.seed() ^ 0x52a1L, worldX * 0.11, worldY * 0.11, 2, 0.5));
        double plateauNoise = normalize(octaveNoise(feature.seed() ^ 0x33b4L, worldX * 0.032, worldY * 0.032, 2, 0.52));
        double summitMacroNoise = normalize(octaveNoise(feature.seed() ^ 0x02b6L, worldX * 0.048, worldY * 0.048, 2, 0.54));
        double summitNoise = normalize(octaveNoise(feature.seed() ^ 0x24c8L, worldX * 0.082, worldY * 0.082, 3, 0.58));
        double summitDetailNoise = normalize(octaveNoise(feature.seed() ^ 0x13d7L, worldX * 0.18, worldY * 0.18, 2, 0.52));
        double summitSpikeNoise = normalize(octaveNoise(feature.seed() ^ 0x6cb9L, worldX * 0.28, worldY * 0.28, 2, 0.48));
        double summitCutNoise = normalize(octaveNoise(feature.seed() ^ 0x7dcaL, worldX * 0.34, worldY * 0.34, 2, 0.46));
        double summitNeedleNoise = normalize(octaveNoise(feature.seed() ^ 0x8edbL, worldX * 0.46, worldY * 0.46, 2, 0.42));
        double dominantSupport = 0.0;
        double combinedSupport = 0.0;
        for (MountainLobe lobe : feature.lobes()) {
            double lobeX = rotatedX - lobe.offsetX();
            double lobeY = rotatedY - lobe.offsetY();
            double nx = lobeX / Math.max(1.0, lobe.radiusX());
            double ny = lobeY / Math.max(1.0, lobe.radiusY());
            double distance = Math.sqrt(nx * nx + ny * ny);
            double support = clamp(Math.max(0.0, 1.0 - distance) * lobe.weight(), 0.0, 1.0);
            if (support > dominantSupport) {
                dominantSupport = support;
            }
            combinedSupport = 1.0 - (1.0 - combinedSupport) * (1.0 - support);
        }
        double rawMass = clamp(combinedSupport * 0.86 + dominantSupport * 0.20, 0.0, 1.0);
        double boundaryNoise = normalize(octaveNoise(feature.seed() ^ 0x4ab2L, worldX * 0.048, worldY * 0.048, 3, 0.56));
        double boundaryPerturb = (boundaryNoise - 0.5) * 0.05 * (1.0 - Math.min(1.0, rawMass * 1.45));
        double mass = clamp(rawMass + boundaryPerturb, 0.0, 1.0);
        if (mass <= 0.0) {
            return MountainTileBase.inactive();
        }

        double ridgeProfile = clamp(dominantSupport * 0.74 + combinedSupport * 0.34, 0.0, 1.0);
        double plateauThreshold = 0.53 + feature.plateauRadius() * 0.14 + (plateauNoise - 0.5) * 0.02;
        double plateauBlend = smoothstep(plateauThreshold, Math.min(0.95, plateauThreshold + 0.16), ridgeProfile);
        double peakProfile = Math.pow(ridgeProfile, 0.70);
        double summitVolatility = smoothstep(0.42, 0.72, plateauBlend) * smoothstep(0.56, 0.90, ridgeProfile);
        double terrace = 0.94 + contourNoise * 0.06;
        int slopeHeight = (int) Math.round(peakHeight * peakProfile * terrace);
        int summitRelief = quantizeHeight((int) Math.round(
                (summitMacroNoise - 0.5) * TILE_HEIGHT_UNIT * 36
                        + (summitNoise - 0.5) * TILE_HEIGHT_UNIT * 48
                        + (summitDetailNoise - 0.5) * TILE_HEIGHT_UNIT * 40
                        + (contourNoise - 0.5) * TILE_HEIGHT_UNIT * 18
        ));
        int plateauHeight = quantizeHeight((int) Math.round(
                peakHeight * (0.78 + plateauNoise * 0.14 + summitMacroNoise * 0.04 + ridgeNoise * 0.03)
        ) + summitRelief);
        int blendedHeight = quantizeHeight((int) Math.round(slopeHeight * (1.0 - plateauBlend) + plateauHeight * plateauBlend));
        int summitSpikeRelief = quantizeHeight((int) Math.round(summitVolatility * (
                Math.pow(Math.max(0.0, summitSpikeNoise - 0.40), 1.45) * TILE_HEIGHT_UNIT * 220
                        + Math.pow(Math.max(0.0, summitNeedleNoise - 0.55), 1.85) * TILE_HEIGHT_UNIT * 180
                        - Math.pow(Math.max(0.0, summitCutNoise - 0.46), 1.40) * TILE_HEIGHT_UNIT * 120
        )));
        int height = plateauBlend > 0.78
                ? quantizeHeight((int) Math.round(plateauHeight * 0.35 + blendedHeight * 0.65))
                : blendedHeight;
        height = quantizeHeight(height + summitSpikeRelief);

        int underlayId = 58;
        int overlayId = 0;
        int tileFlag = 0;
        double mudBandStrength = smoothstep(0.06, 0.15, rawMass)
                * (1.0 - smoothstep(0.54, 0.82, ridgeProfile))
                * (1.0 - smoothstep(0.48, 0.76, plateauBlend));
        boolean walkway = isMountainWalkway(feature, rotatedX, rotatedY, rawMass, ridgeProfile, plateauBlend);
        if (mudBandStrength > 0.18 && !walkway) {
            overlayId = 22;
            tileFlag = 1;
        }

        return new MountainTileBase(true, height, underlayId, overlayId, tileFlag);
    }

    private static boolean isMountainWalkway(
            PlacedMountainFeature feature,
            double rotatedX,
            double rotatedY,
            double rawMass,
            double ridgeProfile,
            double plateauBlend
    ) {
        if (rawMass < 0.08 || plateauBlend > 0.92) {
            return isMountainRidgeConnector(feature, rotatedX, rotatedY, ridgeProfile, plateauBlend);
        }
        double halfWidth = (feature.maxX() - feature.minX() + 1) * 0.5;
        double halfHeight = (feature.maxY() - feature.minY() + 1) * 0.5;
        double sideSign = hashedUnit(feature.seed() ^ 0xb17cL, feature.minX(), feature.minY()) < 0.5 ? -1.0 : 1.0;
        double alongShortSide = rotatedX * sideSign;
        double centreY = (hashedUnit(feature.seed() ^ 0xc28dL, feature.maxX(), feature.maxY()) - 0.5) * halfHeight * 0.85;
        double start = halfWidth * (0.10 + hashedUnit(feature.seed() ^ 0xd39eL, feature.minX(), feature.maxY()) * 0.08);
        double end = halfWidth * (0.96 + hashedUnit(feature.seed() ^ 0xe4afL, feature.maxX(), feature.minY()) * 0.04);
        boolean slopeApproach = false;
        if (alongShortSide >= start && alongShortSide <= end) {
            double t = clamp((alongShortSide - start) / Math.max(0.001, end - start), 0.0, 1.0);
            double bendAmplitude = halfHeight * (0.05 + hashedUnit(feature.seed() ^ 0xf5b0L, feature.minX(), feature.minY()) * 0.05);
            double centreOffsetY = centreY + Math.sin(t * Math.PI) * bendAmplitude * 0.5;
            double corridorHalfWidthY = halfHeight * (0.15 - t * 0.03);
            slopeApproach = Math.abs(rotatedY - centreOffsetY) <= corridorHalfWidthY;
        }
        return slopeApproach || isMountainRidgeConnector(feature, rotatedX, rotatedY, ridgeProfile, plateauBlend);
    }

    private static boolean isMountainRidgeConnector(
            PlacedMountainFeature feature,
            double rotatedX,
            double rotatedY,
            double ridgeProfile,
            double plateauBlend
    ) {
        double weightedOffsetX = 0.0;
        double totalWeight = 0.0;
        double maxReach = 0.0;
        for (MountainLobe lobe : feature.lobes()) {
            double yDistance = Math.abs(rotatedY - lobe.offsetY());
            double reach = Math.max(1.0, lobe.radiusY() * 1.35);
            double weight = Math.max(0.0, 1.0 - yDistance / reach) * lobe.weight();
            if (weight <= 0.0) {
                continue;
            }
            weightedOffsetX += lobe.offsetX() * weight;
            totalWeight += weight;
            maxReach = Math.max(maxReach, reach);
        }
        if (totalWeight <= 0.0) {
            return false;
        }
        double centreX = weightedOffsetX / totalWeight;
        double halfHeight = (feature.maxY() - feature.minY() + 1) * 0.5;
        double connectorHalfWidth = Math.max(2.5, halfHeight * (plateauBlend > 0.55 ? 0.12 : 0.09));
        boolean nearConnector = Math.abs(rotatedX - centreX) <= connectorHalfWidth;
        boolean alongMountain = Math.abs(rotatedY) <= maxReach + halfHeight * 0.22;
        return nearConnector && alongMountain && (ridgeProfile > 0.38 || plateauBlend > 0.28);
    }

    private static OverlayTileShape shapeForOverlayPatch(PlacedMountainFeature feature, int worldX, int worldY) {
        boolean north = evaluateMountainTileBase(feature, worldX, worldY + 1).overlayId() > 0;
        boolean south = evaluateMountainTileBase(feature, worldX, worldY - 1).overlayId() > 0;
        boolean east = evaluateMountainTileBase(feature, worldX + 1, worldY).overlayId() > 0;
        boolean west = evaluateMountainTileBase(feature, worldX - 1, worldY).overlayId() > 0;
        int mask = 0;
        if (north) mask |= 1;
        if (east) mask |= 2;
        if (south) mask |= 4;
        if (west) mask |= 8;
        MapDataExemplarLibrary.ShorelineRule rule = MapDataExemplarLibrary.resolve().shorelineRule(mask);
        if (rule != null && rule.shape() >= 0) {
            return new OverlayTileShape(rule.shape(), rule.orientation());
        }
        return switch (mask) {
            case 1 -> new OverlayTileShape((byte) 10, (byte) 0);
            case 2 -> new OverlayTileShape((byte) 10, (byte) 1);
            case 4 -> new OverlayTileShape((byte) 10, (byte) 2);
            case 8 -> new OverlayTileShape((byte) 10, (byte) 3);
            case 3 -> new OverlayTileShape((byte) 9, (byte) 0);
            case 6 -> new OverlayTileShape((byte) 9, (byte) 1);
            case 12 -> new OverlayTileShape((byte) 9, (byte) 2);
            case 9 -> new OverlayTileShape((byte) 9, (byte) 3);
            case 5 -> new OverlayTileShape((byte) 1, (byte) 0);
            case 10 -> new OverlayTileShape((byte) 1, (byte) 1);
            default -> null;
        };
    }

    private static CacheBiomeProfileMiner.SettlementProfile resolvePreviewSettlementProfile(
            CacheBiomeProfileMiner.BiomeProfile profile,
            PreviewPalette palette,
            long seed
    ) {
        CacheBiomeProfileMiner.SettlementProfile explicitSettlementProfile = CacheBiomeProfileMiner.resolveSettlementProfile(profile);
        BuildingStyle style = resolveSettlementBuildingStyle(profile, explicitSettlementProfile, seed);
        if (!shouldUseGeneratedSettlementShells(profile, style) && explicitSettlementProfile != null) {
            return explicitSettlementProfile;
        }
        if (style == null || palette == null || palette.underlays.length == 0) {
            return null;
        }

        CacheBiomeProfileMiner.SettlementProfile generated = new CacheBiomeProfileMiner.SettlementProfile();
        generated.id = "generated_" + normalizeSettlementId(profile);
        generated.layoutStyle = style == BuildingStyle.POLLNIVNEACH ? "riverside_compound" : "clustered_compound";
        generated.primaryUnderlayId = isProfile13DesertProfile(profile)
                ? PROFILE13_DESERT_PRIMARY_UNDERLAY
                : palette.underlays[0];
        generated.secondaryUnderlayId = resolveDarkerGroundUnderlay(profile, palette);
        generated.accentOverlayId = 0;
        generated.waterOverlayId = palette.waterOverlayId;
        generated.perimeterTiles = 1;
        generated.minAnchors = style == BuildingStyle.POLLNIVNEACH ? 3 : (style == BuildingStyle.YANILLE ? 2 : 1);
        generated.maxAnchors = style == BuildingStyle.BARBARIAN ? 1 : (style == BuildingStyle.POLLNIVNEACH ? 5 : (style == BuildingStyle.YANILLE ? 3 : 2));
        generated.minSatellites = style == BuildingStyle.BARBARIAN ? 2 : (style == BuildingStyle.POLLNIVNEACH ? 6 : (style == BuildingStyle.YANILLE ? 4 : 3));
        generated.maxSatellites = style == BuildingStyle.BARBARIAN ? 4 : (style == BuildingStyle.POLLNIVNEACH ? 12 : (style == BuildingStyle.YANILLE ? 8 : 6));
        generated.anchorFootprints = defaultAnchorFootprints(style);
        generated.satelliteFootprints = defaultSatelliteFootprints(style);
        generated.buildingTemplates = List.of();
        generated.roofTemplates = List.of();
        generated.props = List.of();
        return generated;
    }

    private static boolean shouldUseGeneratedSettlementShells(
            CacheBiomeProfileMiner.BiomeProfile profile,
            BuildingStyle style
    ) {
        if (profile == null || style == null) {
            return false;
        }
        if (style != BuildingStyle.POLLNIVNEACH) {
            return false;
        }
        String label = profile.label == null ? "" : profile.label.toLowerCase(Locale.ROOT);
        return "profile_13_mixed_dry_rugged".equals(label)
                || "profile_13_mixed_dry_dugged".equals(label)
                || label.contains("_sandy_")
                || label.contains("_arid_")
                || hasTopToken(profile, "desert", "sand", "sandy", "cactus", "palm");
    }

    private static boolean isProfile13DesertProfile(CacheBiomeProfileMiner.BiomeProfile profile) {
        String label = profile == null || profile.label == null ? "" : profile.label.toLowerCase(Locale.ROOT);
        return "profile_13_mixed_dry_rugged".equals(label)
                || "profile_13_mixed_dry_dugged".equals(label);
    }

    private static String normalizeSettlementId(CacheBiomeProfileMiner.BiomeProfile profile) {
        String label = profile == null || profile.label == null ? "preview_settlement" : profile.label;
        return label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    private static BuildingStyle resolveSettlementBuildingStyle(
            CacheBiomeProfileMiner.BiomeProfile profile,
            CacheBiomeProfileMiner.SettlementProfile settlementProfile,
            long seed
    ) {
        if (profile == null) {
            return null;
        }
        String label = profile.label == null ? "" : profile.label.toLowerCase(Locale.ROOT);
        if (label.startsWith("profile_09")) {
            return selectProfile09SettlementStyle(seed);
        }
        if ("profile_13_mixed_dry_rugged".equals(label) || "profile_13_mixed_dry_dugged".equals(label)) {
            return BuildingStyle.POLLNIVNEACH;
        }
        if (settlementProfile != null && settlementProfile.wallId == 1415 && settlementProfile.roofEdgeId == 1416) {
            return BuildingStyle.POLLNIVNEACH;
        }
        if (settlementProfile != null && settlementProfile.wallId == 1904 && settlementProfile.roofEdgeId == 1792) {
            return BuildingStyle.YANILLE;
        }
        if (label.contains("_sandy_") || label.contains("_arid_")
                || hasTopToken(profile, "desert", "sand", "sandy", "cactus", "palm")) {
            return BuildingStyle.POLLNIVNEACH;
        }
        if (label.contains("_marsh_") || label.contains("_wet_")
                || hasTopToken(profile, "swamp", "marsh", "bog", "mire", "grave", "fungus", "moss")) {
            return BuildingStyle.CANAFIS;
        }
        if (label.contains("_snowy_") || label.contains("_barren_") || label.contains("_cool_")
                || hasTopToken(profile, "snow", "ice", "frozen", "mountain", "pine", "rock")) {
            return BuildingStyle.BARBARIAN;
        }
        if (label.contains("_verdant_") || hasTopToken(profile, "oak", "willow", "maple", "tree", "garden")) {
            return BuildingStyle.VARROCK;
        }
        return BuildingStyle.VARROCK;
    }

    private static BuildingStyle selectProfile09SettlementStyle(long seed) {
        return hashedUnit(seed ^ 0x4fa1d9L, 9, 3) < 0.5 ? BuildingStyle.YANILLE : BuildingStyle.FALADOR;
    }

    private static boolean hasTopToken(CacheBiomeProfileMiner.BiomeProfile profile, String... candidates) {
        if (profile == null || profile.topTokens == null || profile.topTokens.isEmpty()) {
            return false;
        }
        for (CacheBiomeProfileMiner.RankedToken token : profile.topTokens) {
            if (token == null || token.token == null) {
                continue;
            }
            for (String candidate : candidates) {
                if (candidate.equalsIgnoreCase(token.token)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<CacheBiomeProfileMiner.SettlementFootprint> defaultAnchorFootprints(BuildingStyle style) {
        return switch (style) {
            case POLLNIVNEACH -> List.of(
                    new CacheBiomeProfileMiner.SettlementFootprint(18, 14, 0.11, 2, HouseStyleDefinition.FootprintShape.U),
                    new CacheBiomeProfileMiner.SettlementFootprint(17, 13, 0.12, 2, HouseStyleDefinition.FootprintShape.L),
                    new CacheBiomeProfileMiner.SettlementFootprint(16, 12, 0.15, 2, HouseStyleDefinition.FootprintShape.RECT),
                    new CacheBiomeProfileMiner.SettlementFootprint(15, 11, 0.15, 2, HouseStyleDefinition.FootprintShape.RECT),
                    new CacheBiomeProfileMiner.SettlementFootprint(14, 10, 0.17, 2, HouseStyleDefinition.FootprintShape.RECT),
                    new CacheBiomeProfileMiner.SettlementFootprint(13, 10, 0.13, 2, HouseStyleDefinition.FootprintShape.RECT),
                    new CacheBiomeProfileMiner.SettlementFootprint(15, 12, 0.10, 3, HouseStyleDefinition.FootprintShape.U),
                    new CacheBiomeProfileMiner.SettlementFootprint(14, 11, 0.07, 2, HouseStyleDefinition.FootprintShape.L)
            );
            case FALADOR, YANILLE -> List.of(
                    new CacheBiomeProfileMiner.SettlementFootprint(15, 11, 0.28, 2, HouseStyleDefinition.FootprintShape.RECT),
                    new CacheBiomeProfileMiner.SettlementFootprint(13, 10, 0.26, 2, HouseStyleDefinition.FootprintShape.RECT),
                    new CacheBiomeProfileMiner.SettlementFootprint(12, 9, 0.22, 1, HouseStyleDefinition.FootprintShape.RECT),
                    new CacheBiomeProfileMiner.SettlementFootprint(11, 8, 0.24, 2, HouseStyleDefinition.FootprintShape.RECT)
            );
            case CANAFIS -> List.of(
                    new CacheBiomeProfileMiner.SettlementFootprint(11, 9, 0.40, 2),
                    new CacheBiomeProfileMiner.SettlementFootprint(9, 8, 0.34, 2),
                    new CacheBiomeProfileMiner.SettlementFootprint(13, 9, 0.26, 1)
            );
            case BARBARIAN -> List.of(
                    new CacheBiomeProfileMiner.SettlementFootprint(15, 9, 0.46, 1),
                    new CacheBiomeProfileMiner.SettlementFootprint(13, 8, 0.34, 1),
                    new CacheBiomeProfileMiner.SettlementFootprint(11, 7, 0.20, 1)
            );
            case NORMAL, VARROCK -> List.of(
                    new CacheBiomeProfileMiner.SettlementFootprint(13, 11, 0.40, 2),
                    new CacheBiomeProfileMiner.SettlementFootprint(11, 9, 0.34, 1),
                    new CacheBiomeProfileMiner.SettlementFootprint(15, 11, 0.26, 2)
            );
        };
    }

    private static List<CacheBiomeProfileMiner.SettlementFootprint> defaultSatelliteFootprints(BuildingStyle style) {
        return switch (style) {
            case POLLNIVNEACH -> List.of(
                    new CacheBiomeProfileMiner.SettlementFootprint(14, 11, 0.10, 2, HouseStyleDefinition.FootprintShape.L),
                    new CacheBiomeProfileMiner.SettlementFootprint(13, 10, 0.10, 2, HouseStyleDefinition.FootprintShape.U),
                    new CacheBiomeProfileMiner.SettlementFootprint(12, 10, 0.16, 2, HouseStyleDefinition.FootprintShape.RECT),
                    new CacheBiomeProfileMiner.SettlementFootprint(11, 9, 0.18, 1, HouseStyleDefinition.FootprintShape.RECT),
                    new CacheBiomeProfileMiner.SettlementFootprint(10, 8, 0.16, 1, HouseStyleDefinition.FootprintShape.RECT),
                    new CacheBiomeProfileMiner.SettlementFootprint(9, 7, 0.14, 1, HouseStyleDefinition.FootprintShape.RECT),
                    new CacheBiomeProfileMiner.SettlementFootprint(10, 9, 0.08, 1, HouseStyleDefinition.FootprintShape.L),
                    new CacheBiomeProfileMiner.SettlementFootprint(11, 8, 0.08, 2, HouseStyleDefinition.FootprintShape.U)
            );
            case FALADOR, YANILLE -> List.of(
                    new CacheBiomeProfileMiner.SettlementFootprint(11, 8, 0.18, 1, HouseStyleDefinition.FootprintShape.RECT),
                    new CacheBiomeProfileMiner.SettlementFootprint(10, 7, 0.22, 1, HouseStyleDefinition.FootprintShape.RECT),
                    new CacheBiomeProfileMiner.SettlementFootprint(9, 7, 0.22, 2, HouseStyleDefinition.FootprintShape.RECT),
                    new CacheBiomeProfileMiner.SettlementFootprint(8, 6, 0.20, 1, HouseStyleDefinition.FootprintShape.RECT),
                    new CacheBiomeProfileMiner.SettlementFootprint(7, 6, 0.18, 1, HouseStyleDefinition.FootprintShape.RECT)
            );
            case CANAFIS -> List.of(
                    new CacheBiomeProfileMiner.SettlementFootprint(8, 7, 0.38, 2),
                    new CacheBiomeProfileMiner.SettlementFootprint(7, 6, 0.34, 1),
                    new CacheBiomeProfileMiner.SettlementFootprint(9, 8, 0.28, 2)
            );
            case BARBARIAN -> List.of(
                    new CacheBiomeProfileMiner.SettlementFootprint(11, 7, 0.42, 1),
                    new CacheBiomeProfileMiner.SettlementFootprint(9, 6, 0.34, 1),
                    new CacheBiomeProfileMiner.SettlementFootprint(8, 6, 0.24, 1)
            );
            case NORMAL, VARROCK -> List.of(
                    new CacheBiomeProfileMiner.SettlementFootprint(9, 7, 0.40, 1),
                    new CacheBiomeProfileMiner.SettlementFootprint(8, 6, 0.34, 1),
                    new CacheBiomeProfileMiner.SettlementFootprint(11, 8, 0.26, 2)
            );
        };
    }

    private static void paintTerrain(
            MapRegion mapRegion,
            CacheBiomeProfileMiner.BiomeProfile profile,
            PreviewPalette palette,
            boolean[][] waterMask,
            boolean[][] roadMask,
            PreviewWindow previewWindow,
            long seed
    ) {
        int worldWidth = previewWindow.worldWidth();
        int worldHeight = previewWindow.worldHeight();
        boolean aridProfile = isAridProfile(profile);
        boolean profile13Desert = isProfile13DesertProfile(profile);
        short darkerGroundUnderlay = resolveDarkerGroundUnderlay(profile, palette);
        double ruggedness = terrainRuggedness(profile);
        int baseHeight = terrainBaseHeight();
        int relief = terrainRelief(profile, ruggedness);
        int elevationBias = terrainElevationBias(profile);

        for (int x = 0; x <= worldWidth; x++) {
            for (int y = 0; y <= worldHeight; y++) {
                mapRegion.tileHeights[0][x][y] = -sampleHeight(
                        seed,
                        previewWindow.absoluteVertexX(x),
                        previewWindow.absoluteVertexY(y),
                        baseHeight,
                        relief,
                        ruggedness,
                        aridProfile,
                        profile13Desert,
                        elevationBias
                );
            }
        }
        flattenWaterBodies(mapRegion, waterMask, worldWidth, worldHeight);
        sculptWaterBanks(mapRegion, waterMask, worldWidth, worldHeight);

        for (int x = 0; x < worldWidth; x++) {
            for (int y = 0; y < worldHeight; y++) {
                int absoluteX = previewWindow.absoluteTileX(x);
                int absoluteY = previewWindow.absoluteTileY(y);
                mapRegion.manualTileHeight[0][x][y] = 1;
                mapRegion.underlays[0][x][y] = profile13Desert
                        ? chooseProfile13DesertUnderlay(seed, absoluteX, absoluteY)
                        : chooseUnderlay(palette, seed, absoluteX, absoluteY);
                if (darkerGroundUnderlay > 0 && (waterMask[x][y] || touchesWater(waterMask, x, y, worldWidth, worldHeight))) {
                    mapRegion.underlays[0][x][y] = darkerGroundUnderlay;
                }
                mapRegion.overlays[0][x][y] = 0;
                mapRegion.overlayShapes[0][x][y] = 0;
                mapRegion.overlayOrientations[0][x][y] = 0;
                mapRegion.tileFlags[0][x][y] = 0;

                if (roadMask[x][y] && palette.roadOverlayId > 0) {
                    mapRegion.overlays[0][x][y] = (short) palette.roadOverlayId;
                    mapRegion.overlayShapes[0][x][y] = FULL_TILE_OVERLAY_SHAPE;
                    continue;
                }

                if (waterMask[x][y] && palette.waterOverlayId > 0) {
                    OverlayTileShape shoreline = resolveWaterOverlayShape(waterMask, x, y, worldWidth, worldHeight);
                    mapRegion.overlays[0][x][y] = (short) palette.waterOverlayId;
                    mapRegion.overlayShapes[0][x][y] = shoreline == null ? FULL_TILE_OVERLAY_SHAPE : shoreline.shape();
                    mapRegion.overlayOrientations[0][x][y] = shoreline == null ? 0 : shoreline.orientation();
                    mapRegion.tileFlags[0][x][y] = WATER_FLAG;
                }
            }
        }
    }

    private static void buildRoadMask(
            CacheBiomeProfileMiner.BiomeProfile profile,
            PreviewPalette palette,
            boolean[][] roadMask,
            PreviewWindow previewWindow,
            long seed
    ) {
        // Intentionally disabled for now. The synthetic cross-map road pass
        // does not reflect mined cache data and was dominating the preview.
    }

    private static void buildWaterMask(
            CacheBiomeProfileMiner.BiomeProfile profile,
            PreviewPalette palette,
            boolean[][] waterMask,
            boolean[][] roadMask,
            PreviewWindow previewWindow,
            long seed
    ) {
        int worldWidth = previewWindow.worldWidth();
        int worldHeight = previewWindow.worldHeight();
        if (palette.waterOverlayId <= 0 || profile.metrics == null) {
            return;
        }

        double waterRatio = effectiveWaterRatio(profile, seed);
        if (waterRatio <= 0.0) {
            return;
        }

        for (int x = 0; x < worldWidth; x++) {
            int absoluteX = previewWindow.absoluteTileX(x);
            for (int y = 0; y < worldHeight; y++) {
                int absoluteY = previewWindow.absoluteTileY(y);
                if (sampleWorldWater(profile, seed, waterRatio, absoluteX, absoluteY)) {
                    waterMask[x][y] = true;
                }
            }
        }

        smoothWaterMask(waterMask, roadMask, worldWidth, worldHeight);
        roundWaterCorners(waterMask, roadMask, worldWidth, worldHeight);
        clearWaterAtRoads(waterMask, roadMask, worldWidth, worldHeight, 1);
        ensureLandConnectivity(waterMask, roadMask, worldWidth, worldHeight);
        roundWaterCorners(waterMask, roadMask, worldWidth, worldHeight);
        smoothWaterMask(waterMask, roadMask, worldWidth, worldHeight);
        clearWaterAtRoads(waterMask, roadMask, worldWidth, worldHeight, 1);
    }

    private static double effectiveWaterRatio(
            CacheBiomeProfileMiner.BiomeProfile profile,
            long seed
    ) {
        double base = clamp(profile.metrics == null ? 0.0 : profile.metrics.waterRatio, 0.0, 0.24);
        if (!shouldGenerateWater(profile, base, seed)) {
            return 0.0;
        }
        double minimum = inferredMinimumWaterRatio(profile);
        double varied = Math.max(base, minimum);
        double jitter = 0.35 + hashedUnit(seed ^ 0x52a1L, 17, 23) * 2.35;
        double volumeRoll = hashedUnit(seed ^ 0x63b2L, 29, 31);
        double volumeBand = volumeRoll < 0.16 ? 0.42
                : volumeRoll < 0.40 ? 0.78
                : volumeRoll < 0.72 ? 1.18
                : volumeRoll < 0.90 ? 1.85
                : 2.45;
        double result = varied * jitter * volumeBand;
        if (isAridProfile(profile)) {
            result *= 0.68;
        } else if (isWetProfile(profile)) {
            result *= 1.22;
        }
        return clamp(result, minimum * 0.60, 0.44);
    }

    private static boolean shouldGenerateWater(
            CacheBiomeProfileMiner.BiomeProfile profile,
            double sampledWaterRatio,
            long seed
    ) {
        double sampledBias = clamp(sampledWaterRatio / 0.18, 0.0, 1.0);
        double baseChance;
        if (isAridProfile(profile)) {
            baseChance = isProfile13DesertProfile(profile) ? 0.18 : 0.24;
        } else if (isWetProfile(profile)) {
            baseChance = 0.68;
        } else if (isSnowyProfile(profile)) {
            baseChance = 0.56;
        } else if (isVerdantProfile(profile)) {
            baseChance = 0.48;
        } else {
            baseChance = 0.40;
        }

        double chance = clamp(baseChance + sampledBias * 0.26, 0.16, 0.88);
        double roll = hashedUnit(seed ^ 0x41f3L, 41, 59);
        return roll <= chance;
    }

    private static boolean sampleWorldWater(
            CacheBiomeProfileMiner.BiomeProfile profile,
            long seed,
            double waterRatio,
            int absoluteX,
            int absoluteY
    ) {
        double warpX = octaveNoise(seed ^ 0x4f11L, absoluteX * 0.0012, absoluteY * 0.0012, 2, 0.52) * 52.0;
        double warpY = octaveNoise(seed ^ 0x5a21L, absoluteX * 0.0012, absoluteY * 0.0012, 2, 0.52) * 52.0;
        double basin = normalize(octaveNoise(seed ^ 0x6b31L, (absoluteX + warpX) * 0.0017, (absoluteY - warpY) * 0.0017, 4, 0.56));
        double basinDetail = normalize(octaveNoise(seed ^ 0x7c41L, (absoluteX - warpY * 0.3) * 0.0046, (absoluteY + warpX * 0.3) * 0.0046, 3, 0.55));
        double pondNoise = normalize(octaveNoise(seed ^ 0x8d51L, absoluteX * 0.0090, absoluteY * 0.0090, 2, 0.58));
        double riverPrimary = Math.abs(octaveNoise(seed ^ 0x9e61L, (absoluteX + warpX * 0.25) * 0.0023, (absoluteY + warpY * 0.25) * 0.0023, 3, 0.58));
        double riverSecondary = Math.abs(octaveNoise(seed ^ 0xaf71L, (absoluteX - warpY * 0.2) * 0.0045, (absoluteY + warpX * 0.2) * 0.0045, 2, 0.56));
        double moistureBand = normalize(octaveNoise(seed ^ 0xb081L, absoluteX * 0.00085, absoluteY * 0.00085, 3, 0.54));

        double lakeField = basin * 0.52 + basinDetail * 0.33 + pondNoise * 0.15;
        double lakeThreshold = clamp(0.92 - waterRatio * 1.12, 0.72, 0.95);
        double riverThreshold = clamp(0.010 + waterRatio * 0.11, 0.010, 0.050);

        if (isAridProfile(profile)) {
            lakeThreshold += isProfile13DesertProfile(profile) ? 0.05 : 0.03;
            riverThreshold *= isProfile13DesertProfile(profile) ? 0.68 : 0.80;
        } else if (isWetProfile(profile)) {
            lakeThreshold -= 0.04;
            riverThreshold *= 1.25;
        }

        boolean lake = lakeField > lakeThreshold && moistureBand > 0.44;
        boolean river = (riverPrimary < riverThreshold && moistureBand > 0.34)
                || (riverSecondary < riverThreshold * 0.62 && basin > 0.58);
        return lake || river;
    }

    private static double inferredMinimumWaterRatio(CacheBiomeProfileMiner.BiomeProfile profile) {
        if (profile == null) {
            return 0.0;
        }
        if (isWetProfile(profile)) {
            return 0.030;
        }
        if (isSnowyProfile(profile)) {
            return 0.022;
        }
        if (isVerdantProfile(profile)) {
            return 0.018;
        }
        if (isAridProfile(profile)) {
            return 0.008;
        }
        return 0.014;
    }

    private static WaterPlan planWaterFeatures(
            CacheBiomeProfileMiner.BiomeProfile profile,
            double waterRatio,
            int worldWidth,
            int worldHeight,
            long seed
    ) {
        double styleRoll = hashedUnit(seed ^ 0x7ad1L, worldWidth, worldHeight);
        double abundanceRoll = hashedUnit(seed ^ 0x8be2L, worldWidth + 17, worldHeight + 19);
        if (isAridProfile(profile) && waterRatio <= 0.014) {
            return planOasisWater(waterRatio, worldWidth, worldHeight, seed);
        }
        if (isWetProfile(profile)) {
            if (abundanceRoll > 0.78) {
                return planBroadLakeWater(waterRatio * 1.20, worldWidth, worldHeight, seed);
            }
            if (styleRoll < 0.26) {
                return planBroadLakeWater(waterRatio, worldWidth, worldHeight, seed);
            }
            if (styleRoll < 0.52) {
                return planChainLakeWater(waterRatio, worldWidth, worldHeight, seed);
            }
            if (styleRoll < 0.78) {
                return planRiverlandWater(waterRatio, worldWidth, worldHeight, seed);
            }
            if (styleRoll < 0.92) {
                return planHeadwatersWater(waterRatio, worldWidth, worldHeight, seed);
            }
            return planPondScatterWater(waterRatio, worldWidth, worldHeight, seed);
        }
        if (isSnowyProfile(profile)) {
            if (abundanceRoll > 0.80) {
                return planBroadLakeWater(waterRatio * 1.10, worldWidth, worldHeight, seed);
            }
            return styleRoll < 0.42
                    ? planHeadwatersWater(waterRatio, worldWidth, worldHeight, seed)
                    : planRiverlandWater(waterRatio, worldWidth, worldHeight, seed);
        }
        if (waterRatio < 0.014) {
            return styleRoll < 0.5
                    ? planOasisWater(waterRatio, worldWidth, worldHeight, seed)
                    : planPondScatterWater(waterRatio, worldWidth, worldHeight, seed);
        }
        if (abundanceRoll > 0.84) {
            return planBroadLakeWater(waterRatio * 1.22, worldWidth, worldHeight, seed);
        }
        if (styleRoll < 0.22) {
            return planBroadLakeWater(waterRatio, worldWidth, worldHeight, seed);
        }
        if (styleRoll < 0.42) {
            return planChainLakeWater(waterRatio, worldWidth, worldHeight, seed);
        }
        if (styleRoll < 0.66) {
            return planRiverlandWater(waterRatio, worldWidth, worldHeight, seed);
        }
        if (styleRoll < 0.86) {
            return planPondScatterWater(waterRatio, worldWidth, worldHeight, seed);
        }
        return planHeadwatersWater(waterRatio, worldWidth, worldHeight, seed);
    }

    private static WaterPlan planOasisWater(double waterRatio, int worldWidth, int worldHeight, long seed) {
        double centreX = pick(seed ^ 0x1381L, 0, 0, worldWidth * 0.28, worldWidth * 0.72);
        double centreY = pick(seed ^ 0x2491L, 0, 1, worldHeight * 0.28, worldHeight * 0.72);
        LakeFeature lake = new LakeFeature(
                centreX,
                centreY,
                5.5 + waterRatio * 34.0 + pick(seed ^ 0x35a1L, 0, 2, 0.0, 5.5),
                4.5 + waterRatio * 28.0 + pick(seed ^ 0x46b1L, 0, 3, 0.0, 4.5),
                pick(seed ^ 0x57c1L, 0, 4, 0.0, Math.PI)
        );
        List<RiverFeature> rivers = new ArrayList<>();
        if (hashedUnit(seed ^ 0x68d1L, 0, 5) > 0.42) {
            double startX = hashedUnit(seed ^ 0x79e1L, 0, 6) > 0.5 ? 1.0 : worldWidth - 2.0;
            double startY = clamp(centreY + pick(seed ^ 0x8af1L, 0, 7, -18.0, 18.0), 6.0, worldHeight - 7.0);
            rivers.add(new RiverFeature(startX, startY, centreX, centreY, 1.8 + waterRatio * 34.0, 9.0 + waterRatio * 56.0, 3.8, seed ^ 0x9b01L));
        }
        return new WaterPlan(List.of(lake), rivers);
    }

    private static WaterPlan planBroadLakeWater(double waterRatio, int worldWidth, int worldHeight, long seed) {
        double centreX = pick(seed ^ 0x1141L, 1, 0, worldWidth * 0.26, worldWidth * 0.74);
        double centreY = pick(seed ^ 0x2251L, 1, 1, worldHeight * 0.26, worldHeight * 0.74);
        List<LakeFeature> lakes = new ArrayList<>();
        lakes.add(new LakeFeature(
                centreX,
                centreY,
                17.0 + waterRatio * 110.0 + pick(seed ^ 0x3361L, 1, 2, 0.0, 18.0),
                14.0 + waterRatio * 96.0 + pick(seed ^ 0x4471L, 1, 3, 0.0, 15.0),
                pick(seed ^ 0x5581L, 1, 4, 0.0, Math.PI)
        ));
        if (hashedUnit(seed ^ 0x6691L, 1, 5) > 0.58) {
            lakes.add(new LakeFeature(
                    centreX + pick(seed ^ 0x77a1L, 1, 6, -20.0, 20.0),
                    centreY + pick(seed ^ 0x88b1L, 1, 7, -16.0, 16.0),
                    7.0 + waterRatio * 28.0,
                    5.8 + waterRatio * 22.0,
                    pick(seed ^ 0x99c1L, 1, 8, 0.0, Math.PI)
            ));
        }
        List<RiverFeature> rivers = new ArrayList<>();
        if (hashedUnit(seed ^ 0xaad1L, 1, 9) > 0.30) {
            rivers.add(edgeRiverToPoint(seed ^ 0xbbe1L, centreX, centreY, 2.8 + waterRatio * 40.0, 12.0 + waterRatio * 78.0, worldWidth, worldHeight));
        }
        if (hashedUnit(seed ^ 0xccf1L, 1, 10) > 0.68) {
            rivers.add(pointToEdgeRiver(seed ^ 0xdd01L, centreX, centreY, 2.2 + waterRatio * 30.0, 10.0 + waterRatio * 62.0, worldWidth, worldHeight));
        }
        return new WaterPlan(lakes, rivers);
    }

    private static WaterPlan planChainLakeWater(double waterRatio, int worldWidth, int worldHeight, long seed) {
        boolean horizontal = hashedUnit(seed ^ 0x1234L, 2, 0) > 0.5;
        int lakeCount = 2 + (int) Math.floor(hashedUnit(seed ^ 0x2345L, 2, 1) * 3.0);
        double span = horizontal ? worldWidth * 0.58 : worldHeight * 0.58;
        double start = horizontal ? worldWidth * 0.22 : worldHeight * 0.22;
        double gap = lakeCount <= 1 ? 0.0 : span / (lakeCount - 1);
        double axis = horizontal
                ? pick(seed ^ 0x3456L, 2, 2, worldHeight * 0.28, worldHeight * 0.72)
                : pick(seed ^ 0x4567L, 2, 3, worldWidth * 0.28, worldWidth * 0.72);

        List<LakeFeature> lakes = new ArrayList<>();
        for (int index = 0; index < lakeCount; index++) {
            double along = start + gap * index;
            double centreX = horizontal ? along : axis + pick(seed ^ 0x5678L, 2, 10 + index, -14.0, 14.0);
            double centreY = horizontal ? axis + pick(seed ^ 0x6789L, 2, 20 + index, -14.0, 14.0) : along;
            lakes.add(new LakeFeature(
                    centreX,
                    centreY,
                    6.0 + waterRatio * 30.0 + pick(seed ^ 0x789aL, 2, 30 + index, 0.0, 4.5),
                    5.2 + waterRatio * 26.0 + pick(seed ^ 0x89abL, 2, 40 + index, 0.0, 4.0),
                    pick(seed ^ 0x9abcL, 2, 50 + index, 0.0, Math.PI)
            ));
        }

        List<RiverFeature> rivers = new ArrayList<>();
        for (int index = 0; index < lakes.size() - 1; index++) {
            LakeFeature from = lakes.get(index);
            LakeFeature to = lakes.get(index + 1);
            rivers.add(new RiverFeature(
                    from.centreX(),
                    from.centreY(),
                    to.centreX(),
                    to.centreY(),
                    2.1 + waterRatio * 32.0,
                    10.0 + waterRatio * 52.0,
                    3.5 + index * 0.55,
                    seed ^ (0xabc0L + index * 17L)
            ));
        }
        if (!lakes.isEmpty()) {
            LakeFeature anchor = lakes.get((int) Math.floor(hashedUnit(seed ^ 0xbcdeL, 2, 60) * lakes.size()));
            rivers.add(edgeRiverToPoint(seed ^ 0xcdefL, anchor.centreX(), anchor.centreY(), 1.8 + waterRatio * 24.0, 11.0 + waterRatio * 58.0, worldWidth, worldHeight));
        }
        return new WaterPlan(lakes, rivers);
    }

    private static WaterPlan planRiverlandWater(double waterRatio, int worldWidth, int worldHeight, long seed) {
        List<LakeFeature> lakes = new ArrayList<>();
        List<RiverFeature> rivers = new ArrayList<>();
        double targetX = pick(seed ^ 0x1414L, 3, 0, worldWidth * 0.24, worldWidth * 0.76);
        double targetY = pick(seed ^ 0x2525L, 3, 1, worldHeight * 0.24, worldHeight * 0.76);
        if (hashedUnit(seed ^ 0x3636L, 3, 2) > 0.28) {
            LakeFeature terminalLake = new LakeFeature(
                    targetX,
                    targetY,
                    8.5 + waterRatio * 34.0 + pick(seed ^ 0x4747L, 3, 3, 0.0, 8.0),
                    7.5 + waterRatio * 28.0 + pick(seed ^ 0x5858L, 3, 4, 0.0, 7.0),
                    pick(seed ^ 0x6969L, 3, 5, 0.0, Math.PI)
            );
            lakes.add(terminalLake);
            targetX = terminalLake.centreX();
            targetY = terminalLake.centreY();
        }
        RiverFeature trunk = edgeRiverToPoint(seed ^ 0x7a7aL, targetX, targetY, 3.0 + waterRatio * 44.0, 14.0 + waterRatio * 86.0, worldWidth, worldHeight);
        rivers.add(trunk);
        if (hashedUnit(seed ^ 0x8b8bL, 3, 6) > 0.48) {
            double joinX = (trunk.startX() * 0.35) + (trunk.endX() * 0.65);
            double joinY = (trunk.startY() * 0.35) + (trunk.endY() * 0.65);
            rivers.add(edgeRiverToPoint(seed ^ 0x9c9cL, joinX, joinY, 1.8 + waterRatio * 22.0, 9.0 + waterRatio * 44.0, worldWidth, worldHeight));
        }
        return new WaterPlan(lakes, rivers);
    }

    private static WaterPlan planPondScatterWater(double waterRatio, int worldWidth, int worldHeight, long seed) {
        int pondCount = 1 + (int) Math.floor(hashedUnit(seed ^ 0x1112L, 4, 0) * 7.0);
        List<LakeFeature> lakes = new ArrayList<>();
        for (int index = 0; index < pondCount; index++) {
            lakes.add(new LakeFeature(
                    pick(seed ^ 0x2223L, 4, 10 + index, worldWidth * 0.16, worldWidth * 0.84),
                    pick(seed ^ 0x3334L, 4, 20 + index, worldHeight * 0.16, worldHeight * 0.84),
                    3.5 + waterRatio * 26.0 + pick(seed ^ 0x4445L, 4, 30 + index, 0.0, 7.5),
                    3.0 + waterRatio * 22.0 + pick(seed ^ 0x5556L, 4, 40 + index, 0.0, 6.0),
                    pick(seed ^ 0x6667L, 4, 50 + index, 0.0, Math.PI)
            ));
        }
        List<RiverFeature> rivers = new ArrayList<>();
        if (hashedUnit(seed ^ 0x7778L, 4, 1) > 0.68 && lakes.size() >= 2) {
            LakeFeature from = lakes.get(0);
            LakeFeature to = lakes.get(lakes.size() - 1);
            rivers.add(new RiverFeature(from.centreX(), from.centreY(), to.centreX(), to.centreY(), 1.5 + waterRatio * 18.0, 8.0 + waterRatio * 30.0, 3.6, seed ^ 0x8889L));
        }
        return new WaterPlan(lakes, rivers);
    }

    private static WaterPlan planHeadwatersWater(double waterRatio, int worldWidth, int worldHeight, long seed) {
        boolean northernSource = hashedUnit(seed ^ 0x9091L, 5, 0) > 0.5;
        double sourceBandY = northernSource ? worldHeight * 0.72 : worldHeight * 0.28;
        double sourceBandX = pick(seed ^ 0xa0a1L, 5, 1, worldWidth * 0.28, worldWidth * 0.72);
        int lakeCount = 2 + (int) Math.floor(hashedUnit(seed ^ 0xb0b1L, 5, 2) * 2.0);
        List<LakeFeature> lakes = new ArrayList<>();
        for (int index = 0; index < lakeCount; index++) {
            lakes.add(new LakeFeature(
                    sourceBandX + pick(seed ^ 0xc0c1L, 5, 10 + index, -18.0, 18.0),
                    sourceBandY + pick(seed ^ 0xd0d1L, 5, 20 + index, northernSource ? -10.0 : -14.0, northernSource ? 14.0 : 10.0),
                    5.5 + waterRatio * 22.0 + pick(seed ^ 0xe0e1L, 5, 30 + index, 0.0, 4.0),
                    4.5 + waterRatio * 18.0 + pick(seed ^ 0xf0f1L, 5, 40 + index, 0.0, 3.5),
                    pick(seed ^ 0x10102L, 5, 50 + index, 0.0, Math.PI)
            ));
        }

        double trunkEndX = pick(seed ^ 0x11112L, 5, 3, worldWidth * 0.25, worldWidth * 0.75);
        double trunkEndY = northernSource ? 1.0 : worldHeight - 2.0;
        List<RiverFeature> rivers = new ArrayList<>();
        for (int index = 0; index < lakes.size(); index++) {
            LakeFeature lake = lakes.get(index);
            rivers.add(new RiverFeature(
                    lake.centreX(),
                    lake.centreY(),
                    trunkEndX + pick(seed ^ 0x12122L, 5, 60 + index, -10.0, 10.0),
                    trunkEndY,
                    2.0 + waterRatio * 26.0,
                    10.0 + waterRatio * 46.0,
                    3.6 + index * 0.55,
                    seed ^ (0x13132L + index * 23L)
            ));
        }
        return new WaterPlan(lakes, rivers);
    }

    private static RiverFeature edgeRiverToPoint(
            long seed,
            double endX,
            double endY,
            double width,
            double meanderAmplitude,
            int worldWidth,
            int worldHeight
    ) {
        int side = (int) Math.floor(hashedUnit(seed ^ 0x14142L, 0, 0) * 4.0) & 3;
        double startX;
        double startY;
        if (side == 0) {
            startX = pick(seed ^ 0x15152L, 0, 1, worldWidth * 0.12, worldWidth * 0.88);
            startY = 1.0;
        } else if (side == 1) {
            startX = worldWidth - 2.0;
            startY = pick(seed ^ 0x16162L, 0, 2, worldHeight * 0.12, worldHeight * 0.88);
        } else if (side == 2) {
            startX = pick(seed ^ 0x17172L, 0, 3, worldWidth * 0.12, worldWidth * 0.88);
            startY = worldHeight - 2.0;
        } else {
            startX = 1.0;
            startY = pick(seed ^ 0x18182L, 0, 4, worldHeight * 0.12, worldHeight * 0.88);
        }
        return new RiverFeature(startX, startY, endX, endY, width, meanderAmplitude, 4.3 + hashedUnit(seed ^ 0x19192L, 0, 5) * 2.6, seed ^ 0x1a1a2L);
    }

    private static RiverFeature pointToEdgeRiver(
            long seed,
            double startX,
            double startY,
            double width,
            double meanderAmplitude,
            int worldWidth,
            int worldHeight
    ) {
        RiverFeature river = edgeRiverToPoint(seed, startX, startY, width, meanderAmplitude, worldWidth, worldHeight);
        return new RiverFeature(startX, startY, river.startX(), river.startY(), width, meanderAmplitude, river.meanderFrequency(), seed ^ 0x1b1b2L);
    }

    private static double pick(long seed, int x, int y, double min, double max) {
        return min + hashedUnit(seed, x, y) * Math.max(0.0, max - min);
    }

    private static boolean hasAnyWater(boolean[][] waterMask, int worldWidth, int worldHeight) {
        for (int x = 0; x < worldWidth; x++) {
            for (int y = 0; y < worldHeight; y++) {
                if (waterMask[x][y]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isAridProfile(CacheBiomeProfileMiner.BiomeProfile profile) {
        String label = profile == null || profile.label == null ? "" : profile.label.toLowerCase(Locale.ROOT);
        return "profile_13_mixed_dry_rugged".equals(label)
                || "profile_13_mixed_dry_dugged".equals(label)
                || label.contains("sandy")
                || label.contains("ochre")
                || label.contains("arid")
                || label.contains("_dry_")
                || label.contains("_desert_")
                || hasTopToken(profile, "desert", "sand", "sandy", "cactus", "palm", "dune", "arid");
    }

    private static boolean isSnowyProfile(CacheBiomeProfileMiner.BiomeProfile profile) {
        String label = profile == null || profile.label == null ? "" : profile.label.toLowerCase(Locale.ROOT);
        return label.contains("snowy") || label.contains("cool");
    }

    private static boolean isWetProfile(CacheBiomeProfileMiner.BiomeProfile profile) {
        String label = profile == null || profile.label == null ? "" : profile.label.toLowerCase(Locale.ROOT);
        return label.contains("wet") || label.contains("damp") || label.contains("marsh")
                || (profile != null && profile.metrics != null && profile.metrics.waterRatio > 0.09);
    }

    private static boolean isVerdantProfile(CacheBiomeProfileMiner.BiomeProfile profile) {
        String label = profile == null || profile.label == null ? "" : profile.label.toLowerCase(Locale.ROOT);
        return label.contains("verdant");
    }

    private static boolean isWoodedProfile(CacheBiomeProfileMiner.BiomeProfile profile) {
        String label = profile == null || profile.label == null ? "" : profile.label.toLowerCase(Locale.ROOT);
        return label.contains("verdant")
                || label.contains("wood")
                || label.contains("forest")
                || label.contains("jungle")
                || tokenShare(profile, "tree") >= 0.05;
    }

    private static double tokenShareSum(CacheBiomeProfileMiner.BiomeProfile profile, String... tokens) {
        double sum = 0.0;
        for (String token : tokens) {
            sum += tokenShare(profile, token);
        }
        return sum;
    }

    private static VegetationProfile vegetationProfile(CacheBiomeProfileMiner.BiomeProfile profile) {
        double namedObjectDensity = profile == null || profile.metrics == null ? 0.022 : profile.metrics.namedObjectDensity;
        double treeTokens = tokenShareSum(profile, "tree", "oak", "willow", "maple", "evergreen", "yew", "palm", "cactus", "jungle");
        double shrubTokens = tokenShareSum(profile, "bush", "shrub", "fern", "plant", "branch", "log", "stump", "root", "roots", "vine", "ivy");
        double groundTokens = tokenShareSum(profile, "flower", "flowers", "grass", "reed", "mushroom", "weed", "rock", "rocks", "stones", "boulder");
        double woodlandBias = isWoodedProfile(profile) ? 0.08 : 0.0;
        double wetBias = isWetProfile(profile) ? 0.015 : 0.0;

        double fallbackTreeDensity = clamp(namedObjectDensity * 3.45 + treeTokens * 0.44 + shrubTokens * 0.08 + woodlandBias + wetBias, 0.03, 0.38);
        double fallbackShrubDensity = clamp(namedObjectDensity * 3.05 + shrubTokens * 0.34 + groundTokens * 0.12 + woodlandBias * 0.85 + wetBias, 0.03, 0.34);
        double fallbackDecorDensity = clamp(namedObjectDensity * 2.35 + shrubTokens * 0.10 + groundTokens * 0.22 + woodlandBias * 0.40 + wetBias, 0.02, 0.22);
        CacheBiomeProfileMiner.ObjectPlacementProfile placement = profile == null ? null : profile.objectPlacement;
        return new VegetationProfile(
                placementTuning(placement == null ? null : placement.trees, fallbackTreeDensity, 0.46),
                placementTuning(placement == null ? null : placement.shrubs, fallbackShrubDensity, 0.42),
                placementTuning(placement == null ? null : placement.decor, fallbackDecorDensity, 0.28)
        );
    }

    private static PlacementTuning placementTuning(
            CacheBiomeProfileMiner.ObjectCategoryPlacement placement,
            double fallbackDensity,
            double maxChance
    ) {
        if (placement == null || placement.objectCount <= 0) {
            return new PlacementTuning(fallbackDensity, 0.55, maxChance);
        }
        double density = clamp(placement.tileDensity, 0.0, maxChance);
        double adjacencyBias = clamp(placement.adjacencyRatio, 0.0, 1.0);
        double spacingBias = clamp((5.5 - placement.meanNearestNeighborDistance) / 5.0, 0.0, 1.0);
        double clusterBias = clamp(adjacencyBias * 0.7 + spacingBias * 0.3, 0.0, 1.0);
        String layoutType = placement.layoutType == null ? "" : placement.layoutType.toLowerCase(Locale.ROOT);
        if (layoutType.contains("cluster")) {
            clusterBias = Math.max(clusterBias, 0.78);
        } else if (layoutType.contains("scatter")) {
            clusterBias = Math.min(clusterBias, 0.22);
        } else if (layoutType.contains("sparse")) {
            clusterBias = Math.min(clusterBias, 0.30);
        }
        return new PlacementTuning(density, clusterBias, maxChance);
    }

    private static double treePlacementChance(PlacementTuning tuning, long seed, int x, int y) {
        double cluster = normalize(octaveNoise(seed ^ 0x4591L, x * interpolate(0.038, 0.020, tuning.clusterBias()), y * interpolate(0.038, 0.020, tuning.clusterBias()), 3, 0.58));
        double detail = normalize(octaveNoise(seed ^ 0x5ab1L, x * interpolate(0.118, 0.082, tuning.clusterBias()), y * interpolate(0.118, 0.082, tuning.clusterBias()), 2, 0.54));
        double chance = tuning.density() * (
                interpolate(0.64, 0.16, tuning.clusterBias())
                        + cluster * interpolate(0.36, 1.34, tuning.clusterBias())
                        + detail * interpolate(0.42, 0.18, tuning.clusterBias())
        );
        return clamp(chance, 0.0, tuning.maxChance());
    }

    private static double shrubPlacementChance(PlacementTuning tuning, long seed, int x, int y, boolean nearWater) {
        double patch = normalize(octaveNoise(seed ^ 0x6bc2L, x * interpolate(0.040, 0.023, tuning.clusterBias()), y * interpolate(0.040, 0.023, tuning.clusterBias()), 3, 0.60));
        double detail = normalize(octaveNoise(seed ^ 0x7cd3L, x * interpolate(0.136, 0.090, tuning.clusterBias()), y * interpolate(0.136, 0.090, tuning.clusterBias()), 2, 0.55));
        double moisture = nearWater ? 1.12 : 1.0;
        double chance = tuning.density() * (
                interpolate(0.62, 0.18, tuning.clusterBias())
                        + patch * interpolate(0.38, 1.12, tuning.clusterBias())
                        + detail * interpolate(0.44, 0.22, tuning.clusterBias())
        ) * moisture;
        return clamp(chance, 0.0, tuning.maxChance());
    }

    private static double decorPlacementChance(PlacementTuning tuning, long seed, int x, int y, boolean nearWater) {
        double patch = normalize(octaveNoise(seed ^ 0x1a73L, x * interpolate(0.043, 0.026, tuning.clusterBias()), y * interpolate(0.043, 0.026, tuning.clusterBias()), 3, 0.58));
        double detail = normalize(octaveNoise(seed ^ 0x2bf1L, x * interpolate(0.150, 0.102, tuning.clusterBias()), y * interpolate(0.150, 0.102, tuning.clusterBias()), 2, 0.55));
        double moisture = nearWater ? 1.10 : 1.0;
        double chance = tuning.density() * (
                interpolate(0.68, 0.24, tuning.clusterBias())
                        + patch * interpolate(0.30, 0.96, tuning.clusterBias())
                        + detail * interpolate(0.42, 0.26, tuning.clusterBias())
        ) * moisture;
        return clamp(chance, 0.0, tuning.maxChance());
    }

    private static void placeObjects(
            MapRegion mapRegion,
            SceneGraph sceneGraph,
            CacheBiomeProfileMiner.BiomeProfile profile,
            PreviewObjectSet objects,
            boolean[][] waterMask,
            boolean[][] roadMask,
            boolean[][] reservedMask,
            boolean[][] mountainMask,
            PreviewWindow previewWindow,
            long seed
    ) {
        int worldWidth = previewWindow.worldWidth();
        int worldHeight = previewWindow.worldHeight();
        VegetationProfile vegetation = vegetationProfile(profile);

        for (int x = 1; x < worldWidth - 1; x++) {
            for (int y = 1; y < worldHeight - 1; y++) {
                if (waterMask[x][y] || roadMask[x][y] || reservedMask[x][y] || mountainMask[x][y]) {
                    continue;
                }

                int absoluteX = previewWindow.absoluteTileX(x);
                int absoluteY = previewWindow.absoluteTileY(y);
                boolean nearWater = touchesWater(waterMask, x, y, worldWidth, worldHeight);
                if (!objects.treeObjects.isEmpty()) {
                    if (hashedUnit(seed ^ 0x91abL, absoluteX, absoluteY) < treePlacementChance(vegetation.trees(), seed, absoluteX, absoluteY)) {
                        PreviewObject object = pickPreviewObject(objects.treeObjects, hashedUnit(seed ^ 0x3d91L, absoluteX, absoluteY));
                        mapRegion.spawnObjectToWorld(sceneGraph, object.id(), x, y, 0, object.type(), resolveOrientation(object, (absoluteX + absoluteY) & 3), false);
                        continue;
                    }
                }

                if (!objects.shrubObjects.isEmpty()) {
                    if (hashedUnit(seed ^ 0x57cdL, absoluteX, absoluteY) < shrubPlacementChance(vegetation.shrubs(), seed, absoluteX, absoluteY, nearWater)) {
                        PreviewObject object = pickPreviewObject(objects.shrubObjects, hashedUnit(seed ^ 0x6a41L, absoluteX, absoluteY));
                        mapRegion.spawnObjectToWorld(sceneGraph, object.id(), x, y, 0, object.type(), resolveOrientation(object, (absoluteX * 5 + absoluteY) & 3), false);
                        continue;
                    }
                }

                if (!objects.decorObjects.isEmpty()) {
                    if (hashedUnit(seed ^ 0x2bf1L, absoluteX, absoluteY) < decorPlacementChance(vegetation.decor(), seed, absoluteX, absoluteY, nearWater)) {
                        PreviewObject object = pickPreviewObject(objects.decorObjects, hashedUnit(seed ^ 0x1cf3L, absoluteX, absoluteY));
                        mapRegion.spawnObjectToWorld(sceneGraph, object.id(), x, y, 0, object.type(), resolveOrientation(object, (absoluteX * 3 + absoluteY) & 3), false);
                    }
                }
            }
        }
    }

    private static boolean warmPreviewModels(
            PreviewObjectSet objects,
            SettlementPlan settlementPlan,
            CacheBiomeProfileMiner.SettlementProfile settlementProfile
    ) {
        Client client = Client.getSingleton();
        if (client == null || client.getProvider() == null) {
            return false;
        }

        Set<Integer> ids = new LinkedHashSet<>();
        if (settlementPlan != null) {
            if (settlementPlan.buildingStyle() != null) {
                ids.addAll(BuildingGenerator.collectStyleObjectIds(settlementPlan.buildingStyle()));
            }
            for (SettlementBuilding building : settlementPlan.buildings()) {
                if (building.buildingStyle() != null) {
                    ids.addAll(BuildingGenerator.collectStyleObjectIds(building.buildingStyle()));
                }
            }
        }
        if (settlementProfile != null) {
            addIfPositive(ids, settlementProfile.wallId);
            addIfPositive(ids, settlementProfile.upperWallId);
            addIfPositive(ids, settlementProfile.cornerId);
            addIfPositive(ids, settlementProfile.doorId);
            addIfPositive(ids, settlementProfile.wallDecorId);
            addIfPositive(ids, settlementProfile.roofEdgeId);
            addIfPositive(ids, settlementProfile.roofCornerId);
            addIfPositive(ids, settlementProfile.roofInsetCornerId);
            addIfPositive(ids, settlementProfile.roofSlopeId);
            addIfPositive(ids, settlementProfile.roofFlatId);
            if (settlementPlan != null) {
                for (SettlementBuilding building : settlementPlan.buildings()) {
                    if (building == null || building.template() == null || building.template().objects == null) {
                        continue;
                    }
                    for (CacheBiomeProfileMiner.SettlementTemplateObject object : building.template().objects) {
                        if (object != null) {
                            addIfPositive(ids, object.id);
                        }
                    }
                }
            }
            if (settlementProfile.props != null) {
                for (CacheBiomeProfileMiner.SettlementProp prop : settlementProfile.props) {
                    if (prop != null) {
                        addIfPositive(ids, prop.id);
                    }
                }
            }
        }
        collectPreviewObjectIds(ids, objects.treeObjects, 6);
        collectPreviewObjectIds(ids, objects.shrubObjects, 5);
        collectPreviewObjectIds(ids, objects.decorObjects, 8);

        if (ids.isEmpty()) {
            return true;
        }

        List<ObjectDefinition> pending = new ArrayList<>();
        for (int id : ids) {
            if (readyPreviewModelIds.contains(id)) {
                continue;
            }
            ObjectDefinition definition = ObjectDefinitionLoader.lookup(id);
            if (definition == null) {
                continue;
            }
            definition.loadModels(client.getProvider());
            pending.add(definition);
        }

        if (pending.isEmpty()) {
            return true;
        }

        long budget = settlementPlan != null ? 220L : 140L;
        budget = Math.min(settlementPlan != null ? 420L : 240L, budget + pending.size() * 8L);
        long deadline = System.currentTimeMillis() + budget;
        while (System.currentTimeMillis() < deadline) {
            boolean ready = true;
            for (ObjectDefinition definition : pending) {
                if (definition != null && definition.ready()) {
                    readyPreviewModelIds.add(definition.getId());
                    continue;
                }
                if (definition != null) {
                    ready = false;
                }
            }
            if (ready) {
                return true;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        boolean ready = true;
        for (ObjectDefinition definition : pending) {
            if (definition != null && definition.ready()) {
                readyPreviewModelIds.add(definition.getId());
            } else if (definition != null) {
                ready = false;
            }
        }
        return ready;
    }

    private static void collectPreviewObjectIds(Set<Integer> ids, List<PreviewObject> objects) {
        collectPreviewObjectIds(ids, objects, Integer.MAX_VALUE);
    }

    private static void collectPreviewObjectIds(Set<Integer> ids, List<PreviewObject> objects, int limit) {
        if (objects == null) {
            return;
        }
        int added = 0;
        for (PreviewObject object : objects) {
            if (added >= limit) {
                return;
            }
            if (object != null) {
                addIfPositive(ids, object.id());
                added++;
            }
        }
    }

    private static void addIfPositive(Set<Integer> ids, int id) {
        if (id > 0) {
            ids.add(id);
        }
    }

    private static SettlementPlan planSettlement(
            CacheBiomeProfileMiner.BiomeProfile profile,
            CacheBiomeProfileMiner.SettlementProfile settlementProfile,
            PreviewWindow queryWindow,
            PreviewWindow targetWindow,
            long seed,
            List<AcceptedSettlementCell> acceptedSettlementCells
    ) {
        if (settlementProfile == null || acceptedSettlementCells == null || acceptedSettlementCells.isEmpty()) {
            return null;
        }

        List<SettlementBuilding> buildings = new ArrayList<>();
        Set<Long> pathTiles = new LinkedHashSet<>();
        Set<Long> centrepieceTiles = new LinkedHashSet<>();
        SettlementSite primarySite = null;

        List<PlannedSettlementCell> candidates = collectPlannedSettlementCells(
                profile,
                settlementProfile,
                acceptedSettlementCells,
                queryWindow,
                targetWindow,
                seed
        );
        for (PlannedSettlementCell candidate : candidates) {
            if (primarySite == null) {
                primarySite = candidate.site();
            }
            buildings.addAll(candidate.buildings());
            pathTiles.addAll(candidate.pathTiles());
            centrepieceTiles.addAll(candidate.centrepieceTiles());
        }
        if (buildings.isEmpty() || primarySite == null) {
            return null;
        }
        return new SettlementPlan(
                settlementProfile,
                primarySite,
                buildings,
                buildings.stream().map(SettlementBuilding::buildingStyle).filter(Objects::nonNull).findFirst().orElse(null),
                pathTiles,
                centrepieceTiles
        );
    }

    private static List<AcceptedSettlementCell> collectAcceptedSettlementCells(
            CacheBiomeProfileMiner.BiomeProfile profile,
            CacheBiomeProfileMiner.SettlementProfile settlementProfile,
            PreviewWindow queryWindow,
            long seed
    ) {
        int minCellX = Math.floorDiv(queryWindow.originTileX(), SETTLEMENT_CELL_SPACING) - 1;
        int maxCellX = Math.floorDiv(queryWindow.originTileX() + queryWindow.worldWidth() - 1, SETTLEMENT_CELL_SPACING) + 1;
        int minCellY = Math.floorDiv(queryWindow.originTileY(), SETTLEMENT_CELL_SPACING) - 1;
        int maxCellY = Math.floorDiv(queryWindow.originTileY() + queryWindow.worldHeight() - 1, SETTLEMENT_CELL_SPACING) + 1;

        List<AcceptedSettlementCell> candidates = new ArrayList<>();
        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellY = minCellY; cellY <= maxCellY; cellY++) {
                AcceptedSettlementCell candidate = evaluateSettlementCell(profile, settlementProfile, seed, cellX, cellY);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
        }
        candidates.sort((left, right) -> {
            int compareY = Integer.compare(left.absoluteSite().centreY(), right.absoluteSite().centreY());
            if (compareY != 0) {
                return compareY;
            }
            int compareX = Integer.compare(left.absoluteSite().centreX(), right.absoluteSite().centreX());
            if (compareX != 0) {
                return compareX;
            }
            return Double.compare(right.score(), left.score());
        });

        List<AcceptedSettlementCell> accepted = new ArrayList<>();
        List<SettlementSite> acceptedSites = new ArrayList<>();
        for (AcceptedSettlementCell candidate : candidates) {
            if (overlapsExistingSettlement(candidate.absoluteSite(), acceptedSites)) {
                continue;
            }
            accepted.add(candidate);
            acceptedSites.add(candidate.absoluteSite());
        }
        return accepted;
    }

    private static List<PlannedSettlementCell> collectPlannedSettlementCells(
            CacheBiomeProfileMiner.BiomeProfile profile,
            CacheBiomeProfileMiner.SettlementProfile settlementProfile,
            List<AcceptedSettlementCell> acceptedSettlementCells,
            PreviewWindow queryWindow,
            PreviewWindow targetWindow,
            long seed
    ) {
        List<PlannedSettlementCell> planned = new ArrayList<>(acceptedSettlementCells.size());
        for (AcceptedSettlementCell acceptedSettlementCell : acceptedSettlementCells) {
            PlannedSettlementCell candidate = planSettlementCell(
                    profile,
                    settlementProfile,
                    acceptedSettlementCell,
                    queryWindow,
                    targetWindow,
                    seed
            );
            if (candidate != null) {
                planned.add(candidate);
            }
        }
        planned.sort((left, right) -> {
            int compareY = Integer.compare(left.site().centreY(), right.site().centreY());
            if (compareY != 0) {
                return compareY;
            }
            int compareX = Integer.compare(left.site().centreX(), right.site().centreX());
            if (compareX != 0) {
                return compareX;
            }
            return Double.compare(right.score(), left.score());
        });
        return planned;
    }

    private static AcceptedSettlementCell evaluateSettlementCell(
            CacheBiomeProfileMiner.BiomeProfile profile,
            CacheBiomeProfileMiner.SettlementProfile settlementProfile,
            long seed,
            int cellX,
            int cellY
    ) {
        BuildingStyle cellBuildingStyle = resolveSettlementBuildingStyle(profile, settlementProfile, settlementCellStyleSeed(seed, cellX, cellY));
        if (cellBuildingStyle == null) {
            return null;
        }
        if (!shouldSpawnSettlementCell(profile, seed, cellX, cellY)) {
            return null;
        }

        PreviewWindow cellWindow = settlementCellWindow(cellX, cellY);
        int cellWorldWidth = cellWindow.worldWidth();
        int cellWorldHeight = cellWindow.worldHeight();
        PreviewPalette palette = PreviewPalette.fromProfile(profile);
        boolean[][] cellRoadMask = new boolean[cellWorldWidth][cellWorldHeight];
        boolean[][] cellWaterMask = new boolean[cellWorldWidth][cellWorldHeight];
        buildRoadMask(profile, palette, cellRoadMask, cellWindow, seed);
        buildWaterMask(profile, palette, cellWaterMask, cellRoadMask, cellWindow, seed);

        ScoredSettlementSite scored = pickSettlementSiteForCell(
                profile,
                settlementProfile,
                cellWindow,
                cellWaterMask,
                cellRoadMask,
                cellWorldWidth,
                cellWorldHeight,
                seed,
                cellX,
                cellY
        );
        if (scored == null) {
            return null;
        }

        SettlementSite absoluteSite = translateSettlementSite(scored.site(), cellWindow.originTileX(), cellWindow.originTileY());
        return new AcceptedSettlementCell(
                cellX,
                cellY,
                absoluteSite,
                scored.score(),
                cellBuildingStyle
        );
    }

    private static PlannedSettlementCell planSettlementCell(
            CacheBiomeProfileMiner.BiomeProfile profile,
            CacheBiomeProfileMiner.SettlementProfile settlementProfile,
            AcceptedSettlementCell acceptedSettlementCell,
            PreviewWindow queryWindow,
            PreviewWindow targetWindow,
            long seed
    ) {
        PreviewWindow cellWindow = settlementCellWindow(acceptedSettlementCell.cellX(), acceptedSettlementCell.cellY());
        int cellWorldWidth = cellWindow.worldWidth();
        int cellWorldHeight = cellWindow.worldHeight();
        PreviewPalette palette = PreviewPalette.fromProfile(profile);
        boolean[][] cellRoadMask = new boolean[cellWorldWidth][cellWorldHeight];
        boolean[][] cellWaterMask = new boolean[cellWorldWidth][cellWorldHeight];
        buildRoadMask(profile, palette, cellRoadMask, cellWindow, seed);
        buildWaterMask(profile, palette, cellWaterMask, cellRoadMask, cellWindow, seed);

        SettlementSite localSite = translateSettlementSite(
                acceptedSettlementCell.absoluteSite(),
                -cellWindow.originTileX(),
                -cellWindow.originTileY()
        );
        long layoutSeed = settlementLayoutSeed(seed, acceptedSettlementCell.absoluteSite(), settlementProfile);
        SettlementLayoutResult layout = layoutSettlement(
                settlementProfile,
                localSite,
                cellWaterMask,
                cellWorldWidth,
                cellWorldHeight,
                layoutSeed,
                localSite.settlementMinX(),
                localSite.settlementMinY(),
                localSite.settlementMaxX(),
                localSite.settlementMaxY()
        );
        if (layout.buildings().isEmpty()) {
            return null;
        }

        SettlementSite absoluteSite = acceptedSettlementCell.absoluteSite();
        List<SettlementBuilding> absoluteBuildings = withBuildingStyle(
                translateSettlementBuildings(layout.buildings(), absoluteSite, cellWindow.originTileX(), cellWindow.originTileY()),
                acceptedSettlementCell.buildingStyle()
        );
        Set<Long> absolutePathTiles = translateTileKeys(layout.pathTiles(), cellWindow.originTileX(), cellWindow.originTileY());
        Set<Long> absoluteCentrepieceTiles = translateTileKeys(layout.centrepieceTiles(), cellWindow.originTileX(), cellWindow.originTileY());

        if (!intersectsVisiblePreview(
                translateSettlementBuildings(absoluteBuildings, translateSettlementSite(absoluteSite, -queryWindow.originTileX(), -queryWindow.originTileY()), -queryWindow.originTileX(), -queryWindow.originTileY()),
                translateTileKeys(absolutePathTiles, -queryWindow.originTileX(), -queryWindow.originTileY()),
                translateTileKeys(absoluteCentrepieceTiles, -queryWindow.originTileX(), -queryWindow.originTileY()),
                queryWindow.worldWidth(),
                queryWindow.worldHeight()
        )) {
            return null;
        }

        SettlementSite translatedSite = translateSettlementSite(absoluteSite, -targetWindow.originTileX(), -targetWindow.originTileY());
        List<SettlementBuilding> translatedBuildings = translateSettlementBuildings(absoluteBuildings, translatedSite, -targetWindow.originTileX(), -targetWindow.originTileY());
        Set<Long> translatedPathTiles = translateTileKeys(absolutePathTiles, -targetWindow.originTileX(), -targetWindow.originTileY());
        Set<Long> translatedCentrepieceTiles = translateTileKeys(absoluteCentrepieceTiles, -targetWindow.originTileX(), -targetWindow.originTileY());

        return new PlannedSettlementCell(
                acceptedSettlementCell.cellX(),
                acceptedSettlementCell.cellY(),
                translatedSite,
                absoluteSite,
                acceptedSettlementCell.score(),
                translatedBuildings,
                translatedPathTiles,
                translatedCentrepieceTiles,
                acceptedSettlementCell.buildingStyle()
        );
    }

    private static long settlementCellStyleSeed(long seed, int cellX, int cellY) {
        return seed ^ 0x5e77b19dL ^ (long) cellX * 341873128712L ^ (long) cellY * 132897987541L;
    }

    private static PreviewWindow settlementCellWindow(int cellX, int cellY) {
        return PreviewWindow.at(
                cellX * 2 - SETTLEMENT_CELL_WINDOW_MARGIN_REGIONS,
                cellY * 2 - SETTLEMENT_CELL_WINDOW_MARGIN_REGIONS,
                SETTLEMENT_CELL_WINDOW_REGIONS,
                SETTLEMENT_CELL_WINDOW_REGIONS
        );
    }

    private static SettlementSite translateSettlementSite(SettlementSite site, PreviewWindow fromWindow, PreviewWindow toWindow) {
        return translateSettlementSite(site, fromWindow.originTileX() - toWindow.originTileX(), fromWindow.originTileY() - toWindow.originTileY());
    }

    private static SettlementSite translateSettlementSite(SettlementSite site, int deltaX, int deltaY) {
        return new SettlementSite(
                site.centreX() + deltaX,
                site.centreY() + deltaY,
                site.waterSideX(),
                site.waterSideY(),
                site.settlementMinX() + deltaX,
                site.settlementMinY() + deltaY,
                site.settlementMaxX() + deltaX,
                site.settlementMaxY() + deltaY
        );
    }

    private static List<SettlementBuilding> translateSettlementBuildings(
            List<SettlementBuilding> buildings,
            SettlementSite translatedSite,
            PreviewWindow fromWindow,
            PreviewWindow toWindow
    ) {
        return translateSettlementBuildings(buildings, translatedSite, fromWindow.originTileX() - toWindow.originTileX(), fromWindow.originTileY() - toWindow.originTileY());
    }

    private static List<SettlementBuilding> translateSettlementBuildings(
            List<SettlementBuilding> buildings,
            SettlementSite translatedSite,
            int deltaX,
            int deltaY
    ) {
        List<SettlementBuilding> translated = new ArrayList<>(buildings.size());
        for (SettlementBuilding building : buildings) {
            translated.add(new SettlementBuilding(
                    building.minX() + deltaX,
                    building.minY() + deltaY,
                    building.maxX() + deltaX,
                    building.maxY() + deltaY,
                    building.floors(),
                    building.piece(),
                    building.footprint(),
                    building.template(),
                    building.rotatedTemplate(),
                    building.forcedDoorOrientation(),
                    building.buildingStyle(),
                    translatedSite
            ));
        }
        return translated;
    }

    private static Set<Long> translateTileKeys(Set<Long> tiles, PreviewWindow fromWindow, PreviewWindow toWindow) {
        return translateTileKeys(tiles, fromWindow.originTileX() - toWindow.originTileX(), fromWindow.originTileY() - toWindow.originTileY());
    }

    private static Set<Long> translateTileKeys(Set<Long> tiles, int deltaX, int deltaY) {
        Set<Long> translated = new LinkedHashSet<>(tiles.size());
        for (long tile : tiles) {
            translated.add(tileKey(decodeTileX(tile) + deltaX, decodeTileY(tile) + deltaY));
        }
        return translated;
    }

    private static boolean intersectsVisiblePreview(
            List<SettlementBuilding> buildings,
            Set<Long> pathTiles,
            Set<Long> centrepieceTiles,
            int worldWidth,
            int worldHeight
    ) {
        for (SettlementBuilding building : buildings) {
            if (building.maxX() >= 0 && building.maxY() >= 0 && building.minX() < worldWidth && building.minY() < worldHeight) {
                return true;
            }
        }
        for (long tile : pathTiles) {
            int x = decodeTileX(tile);
            int y = decodeTileY(tile);
            if (x >= 0 && y >= 0 && x < worldWidth && y < worldHeight) {
                return true;
            }
        }
        for (long tile : centrepieceTiles) {
            int x = decodeTileX(tile);
            int y = decodeTileY(tile);
            if (x >= 0 && y >= 0 && x < worldWidth && y < worldHeight) {
                return true;
            }
        }
        return false;
    }

    private static long settlementLayoutSeed(
            long baseSeed,
            PreviewWindow previewWindow,
            SettlementSite site,
            CacheBiomeProfileMiner.SettlementProfile settlementProfile
    ) {
        return settlementLayoutSeed(
                baseSeed,
                new SettlementSite(
                        previewWindow.absoluteTileX(site.centreX()),
                        previewWindow.absoluteTileY(site.centreY()),
                        site.waterSideX(),
                        site.waterSideY(),
                        previewWindow.absoluteTileX(site.settlementMinX()),
                        previewWindow.absoluteTileY(site.settlementMinY()),
                        previewWindow.absoluteTileX(site.settlementMaxX()),
                        previewWindow.absoluteTileY(site.settlementMaxY())
                ),
                settlementProfile
        );
    }

    private static long settlementLayoutSeed(
            long baseSeed,
            SettlementSite absoluteSite,
            CacheBiomeProfileMiner.SettlementProfile settlementProfile
    ) {
        int absoluteX = absoluteSite.centreX();
        int absoluteY = absoluteSite.centreY();
        long profileHash = settlementProfile == null || settlementProfile.id == null ? 0L : settlementProfile.id.hashCode();
        return baseSeed
                ^ (((long) absoluteX) << 32)
                ^ (absoluteY & 0xffffffffL)
                ^ (profileHash * 0x9e3779b97f4a7c15L);
    }

    private static List<SettlementSite> collectSettlementSites(
            CacheBiomeProfileMiner.BiomeProfile profile,
            CacheBiomeProfileMiner.SettlementProfile settlementProfile,
            PreviewWindow previewWindow,
            boolean[][] waterMask,
            boolean[][] roadMask,
            int worldWidth,
            int worldHeight,
            long seed
    ) {
        int spacing = REGION_SIZE * 2;
        int originTileX = previewWindow.originTileX();
        int originTileY = previewWindow.originTileY();
        int minCellX = Math.floorDiv(originTileX, spacing) - 1;
        int maxCellX = Math.floorDiv(originTileX + worldWidth - 1, spacing) + 1;
        int minCellY = Math.floorDiv(originTileY, spacing) - 1;
        int maxCellY = Math.floorDiv(originTileY + worldHeight - 1, spacing) + 1;

        List<ScoredSettlementSite> candidates = new ArrayList<>();
        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellY = minCellY; cellY <= maxCellY; cellY++) {
                if (!shouldSpawnSettlementCell(profile, seed, cellX, cellY)) {
                    continue;
                }
                ScoredSettlementSite scored = pickSettlementSiteForCell(
                        profile,
                        settlementProfile,
                        previewWindow,
                        waterMask,
                        roadMask,
                        worldWidth,
                        worldHeight,
                        seed,
                        cellX,
                        cellY
                );
                if (scored != null) {
                    candidates.add(scored);
                }
            }
        }
        candidates.sort((left, right) -> {
            int compareY = Integer.compare(left.site().centreY(), right.site().centreY());
            if (compareY != 0) {
                return compareY;
            }
            int compareX = Integer.compare(left.site().centreX(), right.site().centreX());
            if (compareX != 0) {
                return compareX;
            }
            return Double.compare(right.score(), left.score());
        });

        List<SettlementSite> accepted = new ArrayList<>();
        for (ScoredSettlementSite candidate : candidates) {
            if (overlapsExistingSettlement(candidate.site(), accepted)) {
                continue;
            }
            accepted.add(candidate.site());
        }
        return accepted;
    }

    private static ScoredSettlementSite pickSettlementSiteForCell(
            CacheBiomeProfileMiner.BiomeProfile profile,
            CacheBiomeProfileMiner.SettlementProfile settlementProfile,
            PreviewWindow previewWindow,
            boolean[][] waterMask,
            boolean[][] roadMask,
            int worldWidth,
            int worldHeight,
            long seed,
            int cellX,
            int cellY
    ) {
        int spacing = REGION_SIZE * 2;
        int margin = 20;
        int absoluteX = cellX * spacing + margin + (int) Math.floor(hashedUnit(seed ^ 0x6f21L, cellX, cellY) * (spacing - margin * 2));
        int absoluteY = cellY * spacing + margin + (int) Math.floor(hashedUnit(seed ^ 0x7a31L, cellX, cellY) * (spacing - margin * 2));
        int localX = absoluteX - previewWindow.originTileX();
        int localY = absoluteY - previewWindow.originTileY();
        if (localX < 10 || localY < 10 || localX > worldWidth - 11 || localY > worldHeight - 11) {
            return null;
        }
        if (waterMask[localX][localY] || roadMask[localX][localY]) {
            return null;
        }

        boolean hasWater = hasAnyWater(waterMask, worldWidth, worldHeight);
        double waterPreference = settlementWaterPreference(profile, settlementProfile, hasWater, seed ^ (((long) cellX) << 32) ^ (cellY & 0xffffffffL));
        NearestWater nearestWater = nearestWater(waterMask, localX, localY, worldWidth, worldHeight, 16);
        if (nearestWater != null && nearestWater.distance() < 2.0) {
            return null;
        }

        boolean aridProfile = isAridProfile(profile);
        double ruggedness = terrainRuggedness(profile);
        int baseHeight = terrainBaseHeight();
        int relief = terrainRelief(profile, ruggedness);
        int localRange = approximateHeightRange(seed, absoluteX, absoluteY, profile, baseHeight, relief, ruggedness, 6);
        double rangePenalty = isProfile13DesertProfile(profile) ? 0.016 : 0.035;
        double score = -localRange * rangePenalty;
        if (nearestWater != null) {
            score += settlementWaterScore(nearestWater.distance(), waterPreference);
        } else if (waterPreference > 0.0) {
            score -= waterPreference * 1.4;
        }
        score += landClearanceScore(waterMask, localX, localY, worldWidth, worldHeight, 9);
        score += hashedUnit(seed ^ 0x4b1aL, absoluteX, absoluteY) * 0.85;

        int waterDx = nearestWater == null ? 0 : nearestWater.dx();
        int waterDy = nearestWater == null ? 0 : nearestWater.dy();
        if (Math.abs(waterDx) >= Math.abs(waterDy)) {
            waterDy = 0;
            waterDx = Integer.compare(waterDx, 0);
        } else {
            waterDx = 0;
            waterDy = Integer.compare(waterDy, 0);
        }

        int settlementHalfSpanX = 34 + (int) Math.floor(hashedUnit(seed ^ 0x18e1L, cellX, cellY) * 24.0);
        int settlementHalfSpanY = 34 + (int) Math.floor(hashedUnit(seed ^ 0x29f2L, cellX, cellY) * 24.0);
        if (aridProfile) {
            settlementHalfSpanX += 6;
            settlementHalfSpanY += 6;
        }
        int settlementMinX = Math.max(2, localX - settlementHalfSpanX);
        int settlementMinY = Math.max(2, localY - settlementHalfSpanY);
        int settlementMaxX = Math.min(worldWidth - 3, localX + settlementHalfSpanX);
        int settlementMaxY = Math.min(worldHeight - 3, localY + settlementHalfSpanY);
        SettlementSite site = new SettlementSite(localX, localY, waterDx, waterDy, settlementMinX, settlementMinY, settlementMaxX, settlementMaxY);
        return new ScoredSettlementSite(site, score);
    }

    private static SettlementSite pickSettlementSite(
            CacheBiomeProfileMiner.BiomeProfile profile,
            CacheBiomeProfileMiner.SettlementProfile settlementProfile,
            PreviewWindow previewWindow,
            boolean[][] waterMask,
            boolean[][] roadMask,
            int worldWidth,
            int worldHeight,
            long seed
    ) {
        ScoredSettlementSite scored = pickSettlementSiteInBounds(
                profile,
                settlementProfile,
                previewWindow,
                waterMask,
                roadMask,
                worldWidth,
                worldHeight,
                seed,
                Math.max(10, Math.min(worldWidth - 11, Math.max(10, Math.min(worldWidth, worldHeight) / 12))),
                Math.max(10, Math.min(worldHeight - 11, Math.max(10, Math.min(worldWidth, worldHeight) / 12))),
                Math.max(10, worldWidth - Math.max(10, Math.min(worldWidth, worldHeight) / 12) - 1),
                Math.max(10, worldHeight - Math.max(10, Math.min(worldWidth, worldHeight) / 12) - 1)
        );
        return scored == null ? null : scored.site();
    }

    private static ScoredSettlementSite pickSettlementSiteInBounds(
            CacheBiomeProfileMiner.BiomeProfile profile,
            CacheBiomeProfileMiner.SettlementProfile settlementProfile,
            PreviewWindow previewWindow,
            boolean[][] waterMask,
            boolean[][] roadMask,
            int worldWidth,
            int worldHeight,
            long seed,
            int minX,
            int minY,
            int maxX,
            int maxY
    ) {
        boolean hasWater = hasAnyWater(waterMask, worldWidth, worldHeight);
        double waterPreference = settlementWaterPreference(profile, settlementProfile, hasWater, seed);
        int boundedMinX = Math.max(10, minX);
        int boundedMinY = Math.max(10, minY);
        int boundedMaxX = Math.min(worldWidth - 11, maxX);
        int boundedMaxY = Math.min(worldHeight - 11, maxY);
        if (boundedMinX > boundedMaxX || boundedMinY > boundedMaxY) {
            return null;
        }
        int step = Math.max(3, Math.min(6, Math.max(1, Math.min(boundedMaxX - boundedMinX, boundedMaxY - boundedMinY)) / 24));
        boolean aridProfile = isAridProfile(profile);
        double ruggedness = terrainRuggedness(profile);
        int baseHeight = terrainBaseHeight();
        int relief = terrainRelief(profile, ruggedness);

        SettlementSite best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int x = boundedMinX; x <= boundedMaxX; x += step) {
            for (int y = boundedMinY; y <= boundedMaxY; y += step) {
                if (waterMask[x][y] || roadMask[x][y]) {
                    continue;
                }
                int absoluteX = previewWindow.absoluteTileX(x);
                int absoluteY = previewWindow.absoluteTileY(y);
                NearestWater nearestWater = nearestWater(waterMask, x, y, worldWidth, worldHeight, 16);
                if (nearestWater != null && nearestWater.distance() < 2.0) {
                    continue;
                }
                int localRange = approximateHeightRange(seed, absoluteX, absoluteY, profile, baseHeight, relief, ruggedness, 6);
                double rangePenalty = isProfile13DesertProfile(profile) ? 0.016 : 0.035;
                double score = -localRange * rangePenalty;
                if (nearestWater != null) {
                    score += settlementWaterScore(nearestWater.distance(), waterPreference);
                } else if (waterPreference > 0.0) {
                    score -= waterPreference * 1.4;
                }
                score += landClearanceScore(waterMask, x, y, worldWidth, worldHeight, 9);
                score += hashedUnit(seed ^ 0x4b1aL, absoluteX, absoluteY) * 0.85;
                if (score > bestScore) {
                    int waterDx = nearestWater == null ? 0 : nearestWater.dx();
                    int waterDy = nearestWater == null ? 0 : nearestWater.dy();
                    if (Math.abs(waterDx) >= Math.abs(waterDy)) {
                        waterDy = 0;
                        waterDx = Integer.compare(waterDx, 0);
                    } else {
                        waterDx = 0;
                        waterDy = Integer.compare(waterDy, 0);
                    }
                    bestScore = score;
                    int searchWidth = boundedMaxX - boundedMinX + 1;
                    int searchHeight = boundedMaxY - boundedMinY + 1;
                    int settlementHalfSpanX = Math.max(30, Math.min(REGION_SIZE + 40, searchWidth / 2));
                    int settlementHalfSpanY = Math.max(30, Math.min(REGION_SIZE + 40, searchHeight / 2));
                    int settlementMinX = Math.max(2, x - settlementHalfSpanX);
                    int settlementMinY = Math.max(2, y - settlementHalfSpanY);
                    int settlementMaxX = Math.min(worldWidth - 3, x + settlementHalfSpanX);
                    int settlementMaxY = Math.min(worldHeight - 3, y + settlementHalfSpanY);
                    best = new SettlementSite(x, y, waterDx, waterDy, settlementMinX, settlementMinY, settlementMaxX, settlementMaxY);
                }
            }
        }
        return best == null ? null : new ScoredSettlementSite(best, bestScore);
    }

    private static boolean shouldSpawnSettlementCell(
            CacheBiomeProfileMiner.BiomeProfile profile,
            long seed,
            int cellX,
            int cellY
    ) {
        double baseChance = isAridProfile(profile) ? 0.72 : isVerdantProfile(profile) ? 0.64 : 0.58;
        if (isWetProfile(profile)) {
            baseChance = 0.52;
        }
        double roll = hashedUnit(seed ^ 0x5219L, cellX, cellY);
        return roll <= baseChance;
    }

    private static boolean overlapsExistingSettlement(SettlementSite candidate, List<SettlementSite> accepted) {
        for (SettlementSite existing : accepted) {
            if (candidate.settlementMaxX() + 12 < existing.settlementMinX()
                    || candidate.settlementMinX() - 12 > existing.settlementMaxX()
                    || candidate.settlementMaxY() + 12 < existing.settlementMinY()
                    || candidate.settlementMinY() - 12 > existing.settlementMaxY()) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static double settlementWaterPreference(
            CacheBiomeProfileMiner.BiomeProfile profile,
            CacheBiomeProfileMiner.SettlementProfile settlementProfile,
            boolean hasWater,
            long seed
    ) {
        if (!hasWater || settlementProfile == null) {
            return 0.0;
        }
        if (!"riverside_compound".equalsIgnoreCase(settlementProfile.layoutStyle)) {
            return 0.0;
        }

        double basePreference = isAridProfile(profile) ? 0.24 : 0.38;
        double roll = hashedUnit(seed ^ 0x5c7dL, settlementProfile.id == null ? 0 : settlementProfile.id.hashCode(), 1);
        double band = roll < 0.30 ? 0.0
                : roll < 0.60 ? 0.35
                : roll < 0.85 ? 0.70
                : 1.0;
        return clamp(basePreference + band * 0.50, 0.0, 0.82);
    }

    private static double settlementWaterScore(double distance, double waterPreference) {
        if (waterPreference <= 0.0) {
            return 0.0;
        }
        return waterPreference * (10.0 - Math.abs(distance - 6.0) * 1.2);
    }

    private static SettlementLayoutResult layoutSettlement(
            CacheBiomeProfileMiner.SettlementProfile settlementProfile,
            SettlementSite site,
            boolean[][] waterMask,
            int worldWidth,
            int worldHeight,
            long seed,
            int boundMinX,
            int boundMinY,
            int boundMaxX,
            int boundMaxY
    ) {
        SettlementLayoutResult grownVillage = layoutVillageSettlement(
                settlementProfile,
                site,
                waterMask,
                worldWidth,
                worldHeight,
                seed,
                boundMinX,
                boundMinY,
                boundMaxX,
                boundMaxY
        );
        if (!grownVillage.buildings().isEmpty()) {
            return grownVillage;
        }

        if ("riverside_compound".equalsIgnoreCase(settlementProfile.layoutStyle)) {
            SettlementLayoutResult townSquare = layoutTownSquareSettlement(
                    settlementProfile,
                    site,
                    waterMask,
                    worldWidth,
                    worldHeight,
                    seed,
                    boundMinX,
                    boundMinY,
                    boundMaxX,
                    boundMaxY
            );
            if (!townSquare.buildings().isEmpty()) {
                return townSquare;
            }
        }

        return layoutClusteredSettlement(
                settlementProfile,
                site,
                waterMask,
                worldWidth,
                worldHeight,
                seed,
                boundMinX,
                boundMinY,
                boundMaxX,
                boundMaxY
        );
    }

    private static SettlementLayoutResult layoutVillageSettlement(
            CacheBiomeProfileMiner.SettlementProfile settlementProfile,
            SettlementSite site,
            boolean[][] waterMask,
            int worldWidth,
            int worldHeight,
            long seed,
            int boundMinX,
            int boundMinY,
            int boundMaxX,
            int boundMaxY
    ) {
        List<SettlementLayoutChoice> choices = collectSettlementLayoutChoices(settlementProfile, seed);
        if (choices.isEmpty()) {
            return new SettlementLayoutResult(List.of(), Set.of(), Set.of());
        }

        boolean[][] occupied = new boolean[worldWidth][worldHeight];
        LinkedHashSet<Long> pathTiles = new LinkedHashSet<>();
        LinkedHashSet<Long> centrepieceTiles = new LinkedHashSet<>();
        List<SettlementBuilding> buildings = new ArrayList<>();

        int averageFrontageSpan = Math.max(1, (int) Math.round(choices.stream()
                .mapToInt(choice -> spanAlongAxis(choice, true))
                .average()
                .orElse(8.0)));
        int plazaRadius = Math.max(3, Math.min(4, averageFrontageSpan / 5 + 1));
        int plazaSpan = Math.max(5, plazaRadius * 2);
        int plazaMinX = site.centreX() - (plazaSpan / 2);
        int plazaMaxX = plazaMinX + plazaSpan - 1;
        int plazaMinY = site.centreY() - (plazaSpan / 2);
        int plazaMaxY = plazaMinY + plazaSpan - 1;
        if (!canCarvePathRect(plazaMinX, plazaMinY, plazaMaxX, plazaMaxY, occupied, waterMask, worldWidth, worldHeight, boundMinX, boundMinY, boundMaxX, boundMaxY)) {
            return new SettlementLayoutResult(List.of(), Set.of(), Set.of());
        }
        carvePathRect(centrepieceTiles, plazaMinX, plazaMinY, plazaMaxX, plazaMaxY);

        ArrayDeque<VillageConnector> connectors = new ArrayDeque<>();
        connectors.add(new VillageConnector(site.centreX(), plazaMinY - 1, 0, -1, 0));
        connectors.add(new VillageConnector(plazaMaxX + 1, site.centreY(), 1, 0, 0));
        connectors.add(new VillageConnector(site.centreX(), plazaMaxY + 1, 0, 1, 0));
        connectors.add(new VillageConnector(plazaMinX - 1, site.centreY(), -1, 0, 0));
        Set<Long> visitedConnectors = new LinkedHashSet<>();

        int baseTargetBuildings = countFromSeed(seed ^ 0x77a3L, settlementProfile.minAnchors, settlementProfile.maxAnchors)
                + countFromSeed(seed ^ 0xbbd7L, settlementProfile.minSatellites, settlementProfile.maxSatellites);
        int targetBonus = countFromSeed(seed ^ 0x31afL, 2, 6);
        int targetBuildings = Math.max(6, Math.min(24, baseTargetBuildings + targetBonus));
        int maxDepth = Math.max(4, Math.min(8, targetBuildings / 2 + 1));
        int branchBudget = Math.max(32, targetBuildings * 12);
        int buildingIndex = 0;

        while (!connectors.isEmpty() && buildings.size() < targetBuildings && branchBudget-- > 0) {
            VillageConnector connector = connectors.removeFirst();
            if (connector.depth() > maxDepth) {
                continue;
            }
            long connectorKey = villageConnectorKey(connector);
            if (!visitedConnectors.add(connectorKey)) {
                continue;
            }

            VillagePathPieceType pieceType = chooseVillagePathPieceType(seed, site, connector, buildings.size(), targetBuildings, maxDepth);
            VillagePathPiece piece = buildVillagePathPiece(pieceType, connector, seed, site);
            if (piece == null) {
                continue;
            }
            if (!canCarvePathTiles(
                    piece.tiles(),
                    pathTiles,
                    centrepieceTiles,
                    occupied,
                    waterMask,
                    worldWidth,
                    worldHeight,
                    boundMinX,
                    boundMinY,
                    boundMaxX,
                    boundMaxY
            )) {
                if (pieceType != VillagePathPieceType.STREET_STRAIGHT) {
                    piece = buildVillagePathPiece(VillagePathPieceType.STREET_STRAIGHT, connector, seed, site);
                    if (piece == null || !canCarvePathTiles(
                            piece.tiles(),
                            pathTiles,
                            centrepieceTiles,
                            occupied,
                            waterMask,
                            worldWidth,
                            worldHeight,
                            boundMinX,
                            boundMinY,
                            boundMaxX,
                            boundMaxY
                    )) {
                        continue;
                    }
                } else {
                    continue;
                }
            }

            int placedOnSegment = 0;
            List<VillageStreetPlacement> segmentPlacements = new ArrayList<>();
            LinkedHashSet<Long> segmentAccessTiles = new LinkedHashSet<>();
            for (StreetFrontageBand frontage : piece.frontages()) {
                if (frontage.frontageTiles().isEmpty()) {
                    continue;
                }
                int segmentsToTry = Math.min(8, Math.max(2, frontage.frontageTiles().size() - 1));
                for (int placement = 0; placement < segmentsToTry && buildings.size() + segmentPlacements.size() < targetBuildings; placement++) {
                    int segmentIndex = Math.min(
                            frontage.frontageTiles().size() - 1,
                            ((placement + 1) * frontage.frontageTiles().size()) / (segmentsToTry + 1)
                    );
                    SettlementLayoutChoice choice = choices.get(Math.floorMod(buildingIndex, choices.size()));
                    VillageStreetPlacement frontagePlacement = tryCreateVillagePathBuilding(
                            choice,
                            frontage.frontageTiles().get(segmentIndex),
                            frontage.dirX(),
                            frontage.dirY(),
                            frontage.outwardDx(),
                            frontage.outwardDy(),
                            piece.tiles(),
                            occupied,
                            pathTiles,
                            centrepieceTiles,
                            waterMask,
                            worldWidth,
                            worldHeight,
                            boundMinX,
                            boundMinY,
                            boundMaxX,
                            boundMaxY
                    );
                    if (frontagePlacement != null) {
                        segmentPlacements.add(frontagePlacement);
                        segmentAccessTiles.add(frontagePlacement.accessTile());
                        SettlementBuilding building = frontagePlacement.building();
                        reserveRect(occupied, building.minX(), building.minY(), building.maxX(), building.maxY(), worldWidth, worldHeight);
                        placedOnSegment++;
                        buildingIndex++;
                    }
                }
            }

            boolean continuationOnlySegment = placedOnSegment == 0
                    && segmentContinuesBeyondWorld(piece.tiles(), worldWidth, worldHeight);
            if (placedOnSegment == 0 && !continuationOnlySegment) {
                continue;
            }

            pathTiles.addAll(filterTilesToWorld(piece.tiles(), worldWidth, worldHeight));
            pathTiles.addAll(filterTilesToWorld(segmentAccessTiles, worldWidth, worldHeight));
            for (VillageStreetPlacement placement : segmentPlacements) {
                buildings.add(placement.building());
            }

            if (connector.depth() < maxDepth && (buildings.size() < targetBuildings || continuationOnlySegment)) {
                for (VillageConnector output : piece.outputConnectors()) {
                    connectors.addLast(output);
                }
            }
        }
        ensurePrimaryBuildingConnections(pathTiles, centrepieceTiles, buildings, worldWidth, worldHeight, Math.max(3, Math.min(8, buildings.size() / 2 + 1)));
        return new SettlementLayoutResult(buildings, pathTiles, centrepieceTiles);
    }

    private static VillagePathPieceType chooseVillagePathPieceType(
            long seed,
            SettlementSite site,
            VillageConnector connector,
            int placedBuildings,
            int targetBuildings,
            int maxDepth
    ) {
        if (connector.depth() >= Math.max(1, maxDepth - 1)) {
            return VillagePathPieceType.STREET_STRAIGHT;
        }
        if (targetBuildings - placedBuildings < 3) {
            return VillagePathPieceType.STREET_STRAIGHT;
        }
        double roll = settlementLocalHashedUnit(seed ^ 0x6ab2L, site, connector.x() + connector.depth(), connector.y() - connector.depth());
        double tChance = connector.depth() == 0 ? 0.58 : connector.depth() == 1 ? 0.42 : 0.26;
        return roll < tChance ? VillagePathPieceType.STREET_T : VillagePathPieceType.STREET_STRAIGHT;
    }

    private static VillagePathPiece buildVillagePathPiece(
            VillagePathPieceType pieceType,
            VillageConnector connector,
            long seed,
            SettlementSite site
    ) {
        int pathLength = 6 + (int) Math.floor(settlementLocalHashedUnit(seed ^ 0x59a1L, site, connector.x(), connector.y() + connector.depth()) * 9.0);
        StreetSegment trunk = collectStreetSegment(connector.x(), connector.y(), connector.dirX(), connector.dirY(), pathLength);
        if (pieceType == VillagePathPieceType.STREET_T) {
            return buildTJunctionPiece(connector, trunk);
        }
        return buildStraightStreetPiece(connector, trunk);
    }

    private static VillagePathPiece buildStraightStreetPiece(VillageConnector connector, StreetSegment segment) {
        List<StreetFrontageBand> frontages = List.of(
                new StreetFrontageBand(segment.leftFrontageTiles(), connector.dirX(), connector.dirY(), connector.dirY(), -connector.dirX()),
                new StreetFrontageBand(segment.rightFrontageTiles(), connector.dirX(), connector.dirY(), -connector.dirY(), connector.dirX())
        );
        List<VillageConnector> outputs = List.of(new VillageConnector(
                segment.endX() + connector.dirX(),
                segment.endY() + connector.dirY(),
                connector.dirX(),
                connector.dirY(),
                connector.depth() + 1
        ));
        return new VillagePathPiece(VillagePathPieceType.STREET_STRAIGHT, new ArrayList<>(segment.tiles()), frontages, outputs);
    }

    private static VillagePathPiece buildTJunctionPiece(VillageConnector connector, StreetSegment segment) {
        int leftPerpX = connector.dirY();
        int leftPerpY = -connector.dirX();
        int endX = segment.endX();
        int endY = segment.endY();
        int secondEndX = endX + leftPerpX;
        int secondEndY = endY + leftPerpY;

        int junctionMinX;
        int junctionMaxX;
        int junctionMinY;
        int junctionMaxY;
        if (connector.dirY() != 0) {
            junctionMinX = Math.min(endX, secondEndX) - 1;
            junctionMaxX = Math.max(endX, secondEndX) + 1;
            junctionMinY = connector.dirY() < 0 ? endY : endY - 1;
            junctionMaxY = junctionMinY + 1;
        } else {
            junctionMinX = connector.dirX() > 0 ? endX - 1 : endX;
            junctionMaxX = junctionMinX + 1;
            junctionMinY = Math.min(endY, secondEndY) - 1;
            junctionMaxY = Math.max(endY, secondEndY) + 1;
        }

        LinkedHashSet<Long> tiles = new LinkedHashSet<>(segment.tiles());
        carvePathRect(tiles, junctionMinX, junctionMinY, junctionMaxX, junctionMaxY);

        List<Long> leftFrontage = trimTrailingFrontage(segment.leftFrontageTiles(), 2);
        List<Long> rightFrontage = trimTrailingFrontage(segment.rightFrontageTiles(), 2);
        List<StreetFrontageBand> frontages = List.of(
                new StreetFrontageBand(leftFrontage, connector.dirX(), connector.dirY(), connector.dirY(), -connector.dirX()),
                new StreetFrontageBand(rightFrontage, connector.dirX(), connector.dirY(), -connector.dirY(), connector.dirX())
        );

        List<VillageConnector> outputs;
        if (connector.dirY() != 0) {
            int branchY = junctionMinY;
            outputs = List.of(
                    new VillageConnector(junctionMinX - 1, branchY, -1, 0, connector.depth() + 1),
                    new VillageConnector(junctionMaxX + 1, branchY, 1, 0, connector.depth() + 1)
            );
        } else {
            int branchX = junctionMaxX;
            outputs = List.of(
                    new VillageConnector(branchX, junctionMinY - 1, 0, -1, connector.depth() + 1),
                    new VillageConnector(branchX, junctionMaxY + 1, 0, 1, connector.depth() + 1)
            );
        }
        return new VillagePathPiece(VillagePathPieceType.STREET_T, new ArrayList<>(tiles), frontages, outputs);
    }

    private static List<Long> trimTrailingFrontage(List<Long> frontageTiles, int trimCount) {
        if (frontageTiles == null || frontageTiles.isEmpty()) {
            return List.of();
        }
        int endExclusive = Math.max(1, frontageTiles.size() - Math.max(0, trimCount));
        return new ArrayList<>(frontageTiles.subList(0, endExclusive));
    }

    private static long villageConnectorKey(VillageConnector connector) {
        long x = connector.x() & 0xffffL;
        long y = connector.y() & 0xffffL;
        long dirX = (connector.dirX() + 1L) & 0x3L;
        long dirY = (connector.dirY() + 1L) & 0x3L;
        return x | (y << 16) | (dirX << 32) | (dirY << 34);
    }

    private static List<SettlementBuilding> withSettlementSite(List<SettlementBuilding> buildings, SettlementSite site) {
        List<SettlementBuilding> result = new ArrayList<>(buildings.size());
        for (SettlementBuilding building : buildings) {
            result.add(new SettlementBuilding(
                    building.minX(),
                    building.minY(),
                    building.maxX(),
                    building.maxY(),
                    building.floors(),
                    building.piece(),
                    building.footprint(),
                    building.template(),
                    building.rotatedTemplate(),
                    building.forcedDoorOrientation(),
                    building.buildingStyle(),
                    site
            ));
        }
        return result;
    }

    private static List<SettlementBuilding> withBuildingStyle(List<SettlementBuilding> buildings, BuildingStyle style) {
        if (style == null || buildings.isEmpty()) {
            return buildings;
        }
        List<SettlementBuilding> result = new ArrayList<>(buildings.size());
        for (SettlementBuilding building : buildings) {
            result.add(new SettlementBuilding(
                    building.minX(),
                    building.minY(),
                    building.maxX(),
                    building.maxY(),
                    building.floors(),
                    building.piece(),
                    building.footprint(),
                    building.template(),
                    building.rotatedTemplate(),
                    building.forcedDoorOrientation(),
                    style,
                    building.site()
            ));
        }
        return result;
    }

    private static SettlementLayoutResult layoutTownSquareSettlement(
            CacheBiomeProfileMiner.SettlementProfile settlementProfile,
            SettlementSite site,
            boolean[][] waterMask,
            int worldWidth,
            int worldHeight,
            long seed,
            int boundMinX,
            int boundMinY,
            int boundMaxX,
            int boundMaxY
    ) {
        List<SettlementLayoutChoice> choices = collectSettlementLayoutChoices(settlementProfile, seed);
        if (choices.isEmpty()) {
            return new SettlementLayoutResult(List.of(), Set.of(), Set.of());
        }

        boolean[][] occupied = new boolean[worldWidth][worldHeight];
        List<SettlementBuilding> buildings = new ArrayList<>();
        LinkedHashSet<Long> pathTiles = new LinkedHashSet<>();
        LinkedHashSet<Long> centrepieceTiles = new LinkedHashSet<>();
        boolean horizontalFrontage = hasHorizontalFrontage(site);
        int depthDirX = site.waterSideX() == 0 ? 0 : -site.waterSideX();
        int depthDirY = site.waterSideY() == 0 ? -1 : -site.waterSideY();
        if (site.waterSideX() == 0 && site.waterSideY() == 0) {
            depthDirY = -1;
        }

        int squareCentreX = site.centreX() + depthDirX * 4;
        int squareCentreY = site.centreY() + depthDirY * 4;
        int averageFrontageSpan = Math.max(1, (int) Math.round(choices.stream()
                .mapToInt(choice -> spanAlongAxis(choice, horizontalFrontage))
                .average()
                .orElse(8.0)));
        int averageDepthSpan = Math.max(1, (int) Math.round(choices.stream()
                .mapToInt(choice -> spanAcrossAxis(choice, horizontalFrontage))
                .average()
                .orElse(7.0)));
        int centralGap = Math.max(
                7,
                Math.min(10, averageDepthSpan + 2 + (int) Math.floor(settlementLocalHashedUnit(seed ^ 0x113dL, site, site.centreX(), site.centreY()) * 3.0))
        );
        int plazaDepthSpan = Math.max(6, centralGap - 1);
        int plazaMinDepth = (horizontalFrontage ? squareCentreY : squareCentreX) - (plazaDepthSpan / 2);
        int plazaMaxDepth = plazaMinDepth + plazaDepthSpan - 1;
        int plazaHalfSpan = Math.max(3, Math.min(4, averageFrontageSpan / 5 + 1));
        int plazaFrontageSpan = Math.max(5, plazaHalfSpan * 2);
        int plazaMinX = horizontalFrontage ? squareCentreX - (plazaFrontageSpan / 2) : plazaMinDepth;
        int plazaMaxX = horizontalFrontage ? plazaMinX + plazaFrontageSpan - 1 : plazaMaxDepth;
        int plazaMinY = horizontalFrontage ? plazaMinDepth : squareCentreY - (plazaFrontageSpan / 2);
        int plazaMaxY = horizontalFrontage ? plazaMinY + plazaFrontageSpan - 1 : plazaMaxDepth;
        if (!canCarvePathRect(plazaMinX, plazaMinY, plazaMaxX, plazaMaxY, occupied, waterMask, worldWidth, worldHeight, boundMinX, boundMinY, boundMaxX, boundMaxY)) {
            return new SettlementLayoutResult(List.of(), Set.of(), Set.of());
        }
        carvePathRect(centrepieceTiles, plazaMinX, plazaMinY, plazaMaxX, plazaMaxY);
        addTownSquarePrimaryStreets(pathTiles, plazaMinX, plazaMinY, plazaMaxX, plazaMaxY, 3);

        int slotCount = (choices.size() + 1) / 2;
        List<SettlementLayoutChoice> watersideRow = new ArrayList<>(slotCount);
        List<SettlementLayoutChoice> inlandRow = new ArrayList<>(slotCount);
        List<Integer> slotSpans = new ArrayList<>(slotCount);
        for (int slot = 0; slot < slotCount; slot++) {
            watersideRow.add(null);
            inlandRow.add(null);
            slotSpans.add(1);
        }

        for (int index = 0; index < choices.size(); index++) {
            int slot = index / 2;
            SettlementLayoutChoice choice = choices.get(index);
            if ((index & 1) == 0) {
                watersideRow.set(slot, choice);
            } else {
                inlandRow.set(slot, choice);
            }
            int currentSpan = slotSpans.get(slot);
            int nextSpan = Math.max(currentSpan, spanAlongAxis(choice, horizontalFrontage));
            slotSpans.set(slot, nextSpan);
        }

        List<Integer> slotCentres = layoutTownSquareSlotCentres(
                slotSpans,
                horizontalFrontage ? squareCentreX : squareCentreY,
                Math.max(5, averageFrontageSpan / 3)
        );
        int watersideSign = horizontalFrontage ? -Integer.signum(depthDirY) : -Integer.signum(depthDirX);
        int inlandSign = -watersideSign;
        if (watersideSign == 0) {
            watersideSign = -1;
            inlandSign = 1;
        }

        for (int slot = 0; slot < slotCount; slot++) {
            SettlementBuilding watersideBuilding = createTownSquareBuilding(
                    watersideRow.get(slot),
                    slotCentres.get(slot),
                    plazaMinDepth,
                    plazaMaxDepth,
                    watersideSign,
                    horizontalFrontage
            );
            if (tryAddBuilding(watersideBuilding, occupied, buildings, waterMask, worldWidth, worldHeight, 0, boundMinX, boundMinY, boundMaxX, boundMaxY)) {
                reserveRect(occupied, watersideBuilding.minX(), watersideBuilding.minY(), watersideBuilding.maxX(), watersideBuilding.maxY(), worldWidth, worldHeight);
                addTownSquareApproachPath(pathTiles, watersideBuilding, plazaMinX, plazaMinY, plazaMaxX, plazaMaxY, horizontalFrontage, watersideSign);
            }

            SettlementBuilding inlandBuilding = createTownSquareBuilding(
                    inlandRow.get(slot),
                    slotCentres.get(slot),
                    plazaMinDepth,
                    plazaMaxDepth,
                    inlandSign,
                    horizontalFrontage
            );
            if (tryAddBuilding(inlandBuilding, occupied, buildings, waterMask, worldWidth, worldHeight, 0, boundMinX, boundMinY, boundMaxX, boundMaxY)) {
                reserveRect(occupied, inlandBuilding.minX(), inlandBuilding.minY(), inlandBuilding.maxX(), inlandBuilding.maxY(), worldWidth, worldHeight);
                addTownSquareApproachPath(pathTiles, inlandBuilding, plazaMinX, plazaMinY, plazaMaxX, plazaMaxY, horizontalFrontage, inlandSign);
            }
        }

        ensurePrimaryBuildingConnections(pathTiles, centrepieceTiles, buildings, worldWidth, worldHeight, Math.max(3, Math.min(6, buildings.size() / 2 + 1)));
        return new SettlementLayoutResult(buildings, pathTiles, centrepieceTiles);
    }

    private static void addTownSquarePrimaryStreets(
            Set<Long> pathTiles,
            int plazaMinX,
            int plazaMinY,
            int plazaMaxX,
            int plazaMaxY,
            int extensionLength
    ) {
        int centerLeftX = Math.max(plazaMinX, (plazaMinX + plazaMaxX) / 2);
        int centerTopY = Math.max(plazaMinY, (plazaMinY + plazaMaxY) / 2);
        for (int step = 1; step <= extensionLength; step++) {
            pathTiles.add(tileKey(centerLeftX, plazaMinY - step));
            pathTiles.add(tileKey(Math.min(plazaMaxX, centerLeftX + 1), plazaMinY - step));
            pathTiles.add(tileKey(centerLeftX, plazaMaxY + step));
            pathTiles.add(tileKey(Math.min(plazaMaxX, centerLeftX + 1), plazaMaxY + step));
            pathTiles.add(tileKey(plazaMinX - step, centerTopY));
            pathTiles.add(tileKey(plazaMinX - step, Math.min(plazaMaxY, centerTopY + 1)));
            pathTiles.add(tileKey(plazaMaxX + step, centerTopY));
            pathTiles.add(tileKey(plazaMaxX + step, Math.min(plazaMaxY, centerTopY + 1)));
        }
    }

    private static void addTownSquareApproachPath(
            Set<Long> pathTiles,
            SettlementBuilding building,
            int plazaMinX,
            int plazaMinY,
            int plazaMaxX,
            int plazaMaxY,
            boolean horizontalFrontage,
            int rowSign
    ) {
        if (building == null) {
            return;
        }
        if (horizontalFrontage) {
            int startX = Math.max(plazaMinX, Math.min(plazaMaxX - 1, building.centreX()));
            int endY = rowSign < 0 ? plazaMinY : plazaMaxY;
            int fromY = rowSign < 0 ? building.maxY() + 1 : endY;
            int toY = rowSign < 0 ? endY : building.minY() - 1;
            for (int y = Math.min(fromY, toY); y <= Math.max(fromY, toY); y++) {
                pathTiles.add(tileKey(startX, y));
                pathTiles.add(tileKey(startX + 1, y));
            }
        } else {
            int startY = Math.max(plazaMinY, Math.min(plazaMaxY - 1, building.centreY()));
            int endX = rowSign < 0 ? plazaMinX : plazaMaxX;
            int fromX = rowSign < 0 ? building.maxX() + 1 : endX;
            int toX = rowSign < 0 ? endX : building.minX() - 1;
            for (int x = Math.min(fromX, toX); x <= Math.max(fromX, toX); x++) {
                pathTiles.add(tileKey(x, startY));
                pathTiles.add(tileKey(x, startY + 1));
            }
        }
    }

    private static SettlementLayoutResult layoutClusteredSettlement(
            CacheBiomeProfileMiner.SettlementProfile settlementProfile,
            SettlementSite site,
            boolean[][] waterMask,
            int worldWidth,
            int worldHeight,
            long seed,
            int boundMinX,
            int boundMinY,
            int boundMaxX,
            int boundMaxY
    ) {
        boolean[][] occupied = new boolean[worldWidth][worldHeight];
        List<SettlementBuilding> buildings = new ArrayList<>();
        LinkedHashSet<Long> pathTiles = new LinkedHashSet<>();
        boolean horizontalFrontage = site.waterSideY() != 0 || (site.waterSideX() == 0 && site.waterSideY() == 0);
        int depthDirX = site.waterSideX() == 0 ? 0 : -site.waterSideX();
        int depthDirY = site.waterSideY() == 0 ? -1 : -site.waterSideY();
        if (site.waterSideX() == 0 && site.waterSideY() == 0) {
            depthDirY = -1;
        }
        int axisDirX = horizontalFrontage ? 1 : 0;
        int axisDirY = horizontalFrontage ? 0 : 1;

        int anchorCount = countFromSeed(seed ^ 0x77a3L, settlementProfile.minAnchors, settlementProfile.maxAnchors);
        CacheBiomeProfileMiner.SettlementBuildingTemplate mainAnchorTemplate = chooseAnchorTemplate(settlementProfile, 0);
        CacheBiomeProfileMiner.SettlementFootprint mainAnchor = mainAnchorTemplate == null
                ? chooseFootprint(settlementProfile.anchorFootprints, hashedUnit(seed ^ 0x88b4L, 1, 1))
                : null;
        if (mainAnchor == null && mainAnchorTemplate == null) {
            return new SettlementLayoutResult(List.of(), Set.of(), Set.of());
        }
        SettlementBuilding primary = createBuildingAtSite(mainAnchor, mainAnchorTemplate, site, site.centreX(), site.centreY(), axisDirX, axisDirY, depthDirX, depthDirY, horizontalFrontage, 4);
        if (tryAddBuilding(primary, occupied, buildings, waterMask, worldWidth, worldHeight, 0, boundMinX, boundMinY, boundMaxX, boundMaxY)) {
            reserveRect(occupied, primary.minX(), primary.minY(), primary.maxX(), primary.maxY(), worldWidth, worldHeight);
        }

        if (anchorCount > 1) {
            CacheBiomeProfileMiner.SettlementBuildingTemplate secondAnchorTemplate = chooseAnchorTemplate(settlementProfile, 1);
            CacheBiomeProfileMiner.SettlementFootprint secondAnchor = secondAnchorTemplate == null
                    ? chooseFootprint(settlementProfile.anchorFootprints, hashedUnit(seed ^ 0x99c5L, 2, 2))
                    : null;
            if (secondAnchor != null || secondAnchorTemplate != null) {
                int side = hashedUnit(seed ^ 0xaad6L, 3, 3) > 0.5 ? 1 : -1;
                int offset = spanAlongAxis(primary, horizontalFrontage) / 2
                        + spanAlongAxis(secondAnchor, secondAnchorTemplate, horizontalFrontage) / 2 + 1;
                SettlementBuilding candidate = createBuildingAtSite(
                        secondAnchor,
                        secondAnchorTemplate,
                        site,
                        primary.centreX() + axisDirX * offset * side,
                        primary.centreY() + axisDirY * offset * side,
                        axisDirX,
                        axisDirY,
                        depthDirX,
                        depthDirY,
                        horizontalFrontage,
                        4
                );
                if (tryAddBuilding(candidate, occupied, buildings, waterMask, worldWidth, worldHeight, 0, boundMinX, boundMinY, boundMaxX, boundMaxY)) {
                    reserveRect(occupied, candidate.minX(), candidate.minY(), candidate.maxX(), candidate.maxY(), worldWidth, worldHeight);
                }
            }
        }

        if (buildings.isEmpty()) {
            return new SettlementLayoutResult(List.of(), Set.of(), Set.of());
        }

        int satelliteCount = countFromSeed(seed ^ 0xbbd7L, settlementProfile.minSatellites, settlementProfile.maxSatellites);
        int attempts = satelliteCount * 4;
        for (int index = 0; index < attempts && buildings.size() < anchorCount + satelliteCount; index++) {
            CacheBiomeProfileMiner.SettlementBuildingTemplate template = chooseSatelliteTemplate(settlementProfile, hashedUnit(seed ^ 0xcce8L, index, satelliteCount));
            CacheBiomeProfileMiner.SettlementFootprint footprint = template == null
                    ? chooseFootprint(settlementProfile.satelliteFootprints, hashedUnit(seed ^ 0xcce8L, index, satelliteCount))
                    : null;
            if (footprint == null && template == null) {
                continue;
            }
            if (buildings.isEmpty()) {
                break;
            }
            SettlementBuilding reference = buildings.get((int) Math.floor(hashedUnit(seed ^ 0xddf9L, index, buildings.size()) * buildings.size()));
            int side = hashedUnit(seed ^ 0xee1aL, index, 4) > 0.5 ? 1 : -1;
            int axisOffset = (spanAlongAxis(reference, horizontalFrontage) / 2)
                    + (spanAlongAxis(footprint, template, horizontalFrontage) / 2)
                    + 1
                    + (int) Math.floor(hashedUnit(seed ^ 0xff2bL, index, 5) * 2.0);
            int depthOffset = spanAcrossAxis(reference, horizontalFrontage) / 2
                    + spanAcrossAxis(footprint, template, horizontalFrontage) / 2
                    + 2
                    + (int) Math.floor(hashedUnit(seed ^ 0x102cL, index, 6) * 3.0);
            SettlementBuilding candidate = createBuildingAtSite(
                    footprint,
                    template,
                    site,
                    reference.centreX() + axisDirX * axisOffset * side + depthDirX * depthOffset,
                    reference.centreY() + axisDirY * axisOffset * side + depthDirY * depthOffset,
                    axisDirX,
                    axisDirY,
                    depthDirX,
                    depthDirY,
                    horizontalFrontage,
                    2
            );
            if (tryAddBuilding(candidate, occupied, buildings, waterMask, worldWidth, worldHeight, 0, boundMinX, boundMinY, boundMaxX, boundMaxY)) {
                reserveRect(occupied, candidate.minX(), candidate.minY(), candidate.maxX(), candidate.maxY(), worldWidth, worldHeight);
            }
        }
        ensurePrimaryBuildingConnections(pathTiles, Set.of(), buildings, worldWidth, worldHeight, Math.max(2, Math.min(5, buildings.size() / 2 + 1)));
        return new SettlementLayoutResult(buildings, pathTiles, Set.of());
    }

    private static boolean canCarvePathRect(
            int minX,
            int minY,
            int maxX,
            int maxY,
            boolean[][] occupied,
            boolean[][] waterMask,
            int worldWidth,
            int worldHeight,
            int boundMinX,
            int boundMinY,
            int boundMaxX,
            int boundMaxY
    ) {
        if (minX < Math.max(2, boundMinX)
                || minY < Math.max(2, boundMinY)
                || maxX > Math.min(worldWidth - 3, boundMaxX)
                || maxY > Math.min(worldHeight - 3, boundMaxY)) {
            return false;
        }
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                if (occupied[x][y] || waterMask[x][y]) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void carvePathRect(Set<Long> pathTiles, int minX, int minY, int maxX, int maxY) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                pathTiles.add(tileKey(x, y));
            }
        }
    }

    private static StreetSegment collectStreetSegment(int startX, int startY, int dirX, int dirY, int length) {
        List<Long> tiles = new ArrayList<>(Math.max(1, length) * 2);
        List<Long> leftFrontageTiles = new ArrayList<>(Math.max(1, length));
        List<Long> rightFrontageTiles = new ArrayList<>(Math.max(1, length));
        int leftPerpX = dirY;
        int leftPerpY = -dirX;
        for (int step = 0; step < length; step++) {
            int x = startX + dirX * step;
            int y = startY + dirY * step;
            tiles.add(tileKey(x, y));
            tiles.add(tileKey(x + leftPerpX, y + leftPerpY));
            leftFrontageTiles.add(tileKey(x + leftPerpX * 2, y + leftPerpY * 2));
            rightFrontageTiles.add(tileKey(x - leftPerpX, y - leftPerpY));
        }
        int endX = startX + dirX * (Math.max(1, length) - 1);
        int endY = startY + dirY * (Math.max(1, length) - 1);
        return new StreetSegment(tiles, leftFrontageTiles, rightFrontageTiles, endX, endY);
    }

    private static boolean canCarvePathTiles(
            List<Long> tiles,
            Set<Long> existingPathTiles,
            Set<Long> centrepieceTiles,
            boolean[][] occupied,
            boolean[][] waterMask,
            int worldWidth,
            int worldHeight,
            int boundMinX,
            int boundMinY,
            int boundMaxX,
            int boundMaxY
    ) {
        boolean anyVisibleTile = false;
        for (long tile : tiles) {
            int x = decodeTileX(tile);
            int y = decodeTileY(tile);
            if (x < 0 || y < 0 || x >= worldWidth || y >= worldHeight) {
                continue;
            }
            anyVisibleTile = true;
            if (existingPathTiles.contains(tile) || centrepieceTiles.contains(tile)) {
                return false;
            }
            if (occupied[x][y] || waterMask[x][y]) {
                return false;
            }
        }
        return anyVisibleTile;
    }

    private static List<Long> filterTilesToWorld(Collection<Long> tiles, int worldWidth, int worldHeight) {
        List<Long> clipped = new ArrayList<>(tiles.size());
        for (long tile : tiles) {
            int x = decodeTileX(tile);
            int y = decodeTileY(tile);
            if (x < 0 || y < 0 || x >= worldWidth || y >= worldHeight) {
                continue;
            }
            clipped.add(tile);
        }
        return clipped;
    }

    private static boolean segmentContinuesBeyondWorld(Collection<Long> tiles, int worldWidth, int worldHeight) {
        boolean hasVisibleTile = false;
        boolean hasOffscreenTile = false;
        for (long tile : tiles) {
            int x = decodeTileX(tile);
            int y = decodeTileY(tile);
            if (x >= 0 && y >= 0 && x < worldWidth && y < worldHeight) {
                hasVisibleTile = true;
            } else {
                hasOffscreenTile = true;
            }
            if (hasVisibleTile && hasOffscreenTile) {
                return true;
            }
        }
        return false;
    }

    private static VillageStreetPlacement tryCreateVillagePathBuilding(
            SettlementLayoutChoice choice,
            long frontageTile,
            int dirX,
            int dirY,
            int outwardDx,
            int outwardDy,
            Collection<Long> blockedStreetTiles,
            boolean[][] occupied,
            Set<Long> pathTiles,
            Set<Long> centrepieceTiles,
            boolean[][] waterMask,
            int worldWidth,
            int worldHeight,
            int boundMinX,
            int boundMinY,
            int boundMaxX,
            int boundMaxY
    ) {
        if (choice == null) {
            return null;
        }

        int frontageX = decodeTileX(frontageTile);
        int frontageY = decodeTileY(frontageTile);
        boolean horizontalFrontage = dirX != 0;
        CacheBiomeProfileMiner.SettlementFootprint footprint = choice.footprint();
        CacheBiomeProfileMiner.SettlementBuildingTemplate template = choice.template();
        boolean rotatedTemplate = template != null && shouldRotateTemplate(template, horizontalFrontage);
        int width = footprintWidth(footprint, template, rotatedTemplate, horizontalFrontage);
        int height = footprintHeight(footprint, template, rotatedTemplate, horizontalFrontage);
        if (!BuildingGenerator.isStampableFootprintSpan(width, height)) {
            return null;
        }

        int normalizedOutwardDx = Integer.signum(outwardDx);
        int normalizedOutwardDy = Integer.signum(outwardDy);
        if (normalizedOutwardDx == 0 && normalizedOutwardDy == 0) {
            normalizedOutwardDx = dirY == 0 ? 0 : Integer.signum(dirY);
            normalizedOutwardDy = dirX == 0 ? 0 : -Integer.signum(dirX);
        }
        if (normalizedOutwardDx == 0 && normalizedOutwardDy == 0) {
            normalizedOutwardDy = -1;
        }
        int centreX = frontageX + normalizedOutwardDx * ((width + 1) / 2);
        int centreY = frontageY + normalizedOutwardDy * ((height + 1) / 2);
        int minX = centreX - width / 2;
        int minY = centreY - height / 2;
        int maxX = minX + width - 1;
        int maxY = minY + height - 1;
        int floors = template != null
                ? Math.max(1, template.floors)
                : choice.piece() != null
                ? Math.max(1, choice.piece().floors)
                : Math.max(1, footprint == null ? 1 : footprint.floors);
        int forcedDoorOrientation = orientationFromDirection(-normalizedOutwardDx, -normalizedOutwardDy);
        SettlementBuilding building = new SettlementBuilding(
                minX,
                minY,
                maxX,
                maxY,
                floors,
                choice.piece(),
                footprint,
                template,
                rotatedTemplate,
                forcedDoorOrientation,
                null,
                null
        );
        if (!canPlaceSettlementBuilding(
                building,
                blockedStreetTiles,
                occupied,
                pathTiles,
                centrepieceTiles,
                waterMask,
                worldWidth,
                worldHeight,
                boundMinX,
                boundMinY,
                boundMaxX,
                boundMaxY
        )) {
            return null;
        }
        return new VillageStreetPlacement(building, frontageTile);
    }

    private static void ensurePrimaryBuildingConnections(
            Set<Long> pathTiles,
            Set<Long> centrepieceTiles,
            List<SettlementBuilding> buildings,
            int worldWidth,
            int worldHeight,
            int connectCount
    ) {
        if (buildings.isEmpty() || connectCount <= 0) {
            return;
        }
        List<SettlementBuilding> priority = buildings.stream()
                .sorted((left, right) -> Integer.compare((right.width() * right.height()), (left.width() * left.height())))
                .limit(connectCount)
                .toList();
        for (SettlementBuilding building : priority) {
            if (isBuildingConnectedToPaths(pathTiles, centrepieceTiles, building, worldWidth, worldHeight)) {
                continue;
            }
            connectBuildingToNearestPath(pathTiles, centrepieceTiles, building, worldWidth, worldHeight);
        }
    }

    private static double settlementLocalHashedUnit(long seed, SettlementSite site, int x, int y) {
        if (site == null) {
            return hashedUnit(seed, x, y);
        }
        return hashedUnit(seed, x - site.centreX(), y - site.centreY());
    }

    private static boolean isBuildingConnectedToPaths(
            Set<Long> pathTiles,
            Set<Long> centrepieceTiles,
            SettlementBuilding building,
            int worldWidth,
            int worldHeight
    ) {
        for (int x = Math.max(0, building.minX() - 2); x <= Math.min(worldWidth - 1, building.maxX() + 2); x++) {
            for (int y = Math.max(0, building.minY() - 2); y <= Math.min(worldHeight - 1, building.maxY() + 2); y++) {
                long key = tileKey(x, y);
                if (pathTiles.contains(key) || centrepieceTiles.contains(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void connectBuildingToNearestPath(
            Set<Long> pathTiles,
            Set<Long> centrepieceTiles,
            SettlementBuilding building,
            int worldWidth,
            int worldHeight
    ) {
        long bestTarget = Long.MIN_VALUE;
        int bestDistance = Integer.MAX_VALUE;
        for (long tile : pathTiles) {
            int distance = manhattanDistance(building, decodeTileX(tile), decodeTileY(tile));
            if (distance < bestDistance) {
                bestDistance = distance;
                bestTarget = tile;
            }
        }
        for (long tile : centrepieceTiles) {
            int distance = manhattanDistance(building, decodeTileX(tile), decodeTileY(tile));
            if (distance < bestDistance) {
                bestDistance = distance;
                bestTarget = tile;
            }
        }
        if (bestTarget == Long.MIN_VALUE) {
            return;
        }

        int startX = Math.max(0, Math.min(worldWidth - 1, building.centreX()));
        int startY = Math.max(0, Math.min(worldHeight - 1, building.centreY()));
        int targetX = decodeTileX(bestTarget);
        int targetY = decodeTileY(bestTarget);

        carvePathCorridor(pathTiles, startX, startY, targetX, startY, worldWidth, worldHeight);
        carvePathCorridor(pathTiles, targetX, startY, targetX, targetY, worldWidth, worldHeight);
    }

    private static int manhattanDistance(SettlementBuilding building, int tileX, int tileY) {
        int dx = 0;
        if (tileX < building.minX()) {
            dx = building.minX() - tileX;
        } else if (tileX > building.maxX()) {
            dx = tileX - building.maxX();
        }
        int dy = 0;
        if (tileY < building.minY()) {
            dy = building.minY() - tileY;
        } else if (tileY > building.maxY()) {
            dy = tileY - building.maxY();
        }
        return dx + dy;
    }

    private static void carvePathCorridor(
            Set<Long> pathTiles,
            int startX,
            int startY,
            int endX,
            int endY,
            int worldWidth,
            int worldHeight
    ) {
        int x = startX;
        int y = startY;
        int widthOffsetX = startX == endX ? 1 : 0;
        int widthOffsetY = startY == endY ? 1 : 0;
        while (true) {
            addPathTile(pathTiles, x, y, worldWidth, worldHeight);
            addPathTile(pathTiles, x + widthOffsetX, y + widthOffsetY, worldWidth, worldHeight);
            if (x == endX && y == endY) {
                return;
            }
            if (x != endX) {
                x += Integer.compare(endX, x);
            } else if (y != endY) {
                y += Integer.compare(endY, y);
            }
        }
    }

    private static void addPathTile(Set<Long> pathTiles, int x, int y, int worldWidth, int worldHeight) {
        if (x >= 0 && y >= 0 && x < worldWidth && y < worldHeight) {
            pathTiles.add(tileKey(x, y));
        }
    }

    private static boolean canPlaceSettlementBuilding(
            SettlementBuilding building,
            Collection<Long> blockedStreetTiles,
            boolean[][] occupied,
            Set<Long> pathTiles,
            Set<Long> centrepieceTiles,
            boolean[][] waterMask,
            int worldWidth,
            int worldHeight,
            int boundMinX,
            int boundMinY,
            int boundMaxX,
            int boundMaxY
    ) {
        if (building == null) {
            return false;
        }
        if (building.minX() < Math.max(2, boundMinX)
                || building.minY() < Math.max(2, boundMinY)
                || building.maxX() > Math.min(worldWidth - 3, boundMaxX)
                || building.maxY() > Math.min(worldHeight - 3, boundMaxY)) {
            return false;
        }
        for (int x = building.minX(); x <= building.maxX(); x++) {
            for (int y = building.minY(); y <= building.maxY(); y++) {
                if (x < 0 || y < 0 || x >= worldWidth || y >= worldHeight) {
                    return false;
                }
                if (occupied[x][y] || waterMask[x][y]) {
                    return false;
                }
                if (blockedStreetTiles.contains(tileKey(x, y))) {
                    return false;
                }
                if (pathTiles.contains(tileKey(x, y))) {
                    return false;
                }
                if (centrepieceTiles.contains(tileKey(x, y))) {
                    return false;
                }
            }
        }
        return true;
    }

    private record StreetSegment(
            List<Long> tiles,
            List<Long> leftFrontageTiles,
            List<Long> rightFrontageTiles,
            int endX,
            int endY
    ) {}

    private enum VillagePathPieceType {
        STREET_STRAIGHT,
        STREET_T
    }

    private record StreetFrontageBand(
            List<Long> frontageTiles,
            int dirX,
            int dirY,
            int outwardDx,
            int outwardDy
    ) {}

    private record VillagePathPiece(
            VillagePathPieceType type,
            List<Long> tiles,
            List<StreetFrontageBand> frontages,
            List<VillageConnector> outputConnectors
    ) {}

    private record VillageStreetPlacement(
            SettlementBuilding building,
            long accessTile
    ) {}

    private static List<SettlementLayoutChoice> collectSettlementLayoutChoices(
            CacheBiomeProfileMiner.SettlementProfile settlementProfile,
            long seed
    ) {
        int anchorCount = countFromSeed(seed ^ 0x77a3L, settlementProfile.minAnchors, settlementProfile.maxAnchors);
        int satelliteCount = countFromSeed(seed ^ 0xbbd7L, settlementProfile.minSatellites, settlementProfile.maxSatellites);
        List<SettlementLayoutChoice> choices = new ArrayList<>(anchorCount + satelliteCount);

        for (int anchorIndex = 0; anchorIndex < anchorCount; anchorIndex++) {
            CacheBiomeProfileMiner.SettlementPieceDefinition piece = chooseSettlementPiece(
                    settlementProfile.anchorPieces,
                    hashedUnit(seed ^ 0x71c3L, anchorIndex + 1, anchorCount + 11)
            );
            if (piece != null) {
                choices.add(new SettlementLayoutChoice(
                        piece,
                        pieceToFootprint(piece),
                        null
                ));
                continue;
            }
            CacheBiomeProfileMiner.SettlementBuildingTemplate template = chooseAnchorTemplate(settlementProfile, anchorIndex);
            CacheBiomeProfileMiner.SettlementFootprint footprint = template == null
                    ? chooseFootprint(settlementProfile.anchorFootprints, hashedUnit(seed ^ 0x88b4L, anchorIndex + 1, anchorCount + 1))
                    : null;
            if (template != null || footprint != null) {
                choices.add(new SettlementLayoutChoice(null, footprint, template));
            }
        }

        for (int satelliteIndex = 0; satelliteIndex < satelliteCount; satelliteIndex++) {
            CacheBiomeProfileMiner.SettlementPieceDefinition piece = chooseSettlementPiece(
                    settlementProfile.satellitePieces,
                    hashedUnit(seed ^ 0xb4e7L, satelliteIndex + 1, satelliteCount + 17)
            );
            if (piece != null) {
                choices.add(new SettlementLayoutChoice(
                        piece,
                        pieceToFootprint(piece),
                        null
                ));
                continue;
            }
            CacheBiomeProfileMiner.SettlementBuildingTemplate template = chooseSatelliteTemplate(
                    settlementProfile,
                    hashedUnit(seed ^ 0xcce8L, satelliteIndex, satelliteCount)
            );
            CacheBiomeProfileMiner.SettlementFootprint footprint = template == null
                    ? chooseFootprint(settlementProfile.satelliteFootprints, hashedUnit(seed ^ 0xdde9L, satelliteIndex, satelliteCount + 1))
                    : null;
            if (template != null || footprint != null) {
                choices.add(new SettlementLayoutChoice(null, footprint, template));
            }
        }

        choices.sort((left, right) -> Integer.compare(settlementChoiceArea(right), settlementChoiceArea(left)));
        return choices;
    }

    private static CacheBiomeProfileMiner.SettlementPieceDefinition chooseSettlementPiece(
            List<CacheBiomeProfileMiner.SettlementPieceDefinition> pieces,
            double normalizedWeight
    ) {
        if (pieces == null || pieces.isEmpty()) {
            return null;
        }
        double totalWeight = 0.0;
        for (CacheBiomeProfileMiner.SettlementPieceDefinition piece : pieces) {
            if (piece != null && piece.width > 0 && piece.height > 0 && piece.weight > 0.0) {
                totalWeight += piece.weight;
            }
        }
        if (totalWeight <= 0.0) {
            return null;
        }
        double cursor = Math.max(0.0, Math.min(0.999999, normalizedWeight)) * totalWeight;
        CacheBiomeProfileMiner.SettlementPieceDefinition last = null;
        for (CacheBiomeProfileMiner.SettlementPieceDefinition piece : pieces) {
            if (piece == null || piece.width <= 0 || piece.height <= 0 || piece.weight <= 0.0) {
                continue;
            }
            last = piece;
            cursor -= piece.weight;
            if (cursor <= 0.0) {
                return piece;
            }
        }
        return last;
    }

    private static CacheBiomeProfileMiner.SettlementFootprint pieceToFootprint(CacheBiomeProfileMiner.SettlementPieceDefinition piece) {
        if (piece == null) {
            return null;
        }
        return new CacheBiomeProfileMiner.SettlementFootprint(
                piece.width,
                piece.height,
                Math.max(1.0, piece.weight),
                Math.max(1, piece.floors),
                piece.shapeHint
        );
    }

    private static int settlementChoiceArea(SettlementLayoutChoice choice) {
        if (choice == null) {
            return 0;
        }
        if (choice.piece() != null) {
            return choice.piece().width * choice.piece().height;
        }
        if (choice.template() != null) {
            return choice.template().width * choice.template().height;
        }
        if (choice.footprint() != null) {
            return choice.footprint().width * choice.footprint().height;
        }
        return 0;
    }

    private static int spanAlongAxis(SettlementLayoutChoice choice, boolean horizontalFrontage) {
        if (choice == null) {
            return 1;
        }
        if (choice.piece() != null) {
            return horizontalFrontage ? choice.piece().width : choice.piece().height;
        }
        return spanAlongAxis(choice.footprint(), choice.template(), horizontalFrontage);
    }

    private static int spanAcrossAxis(SettlementLayoutChoice choice, boolean horizontalFrontage) {
        if (choice == null) {
            return 1;
        }
        if (choice.piece() != null) {
            return horizontalFrontage ? choice.piece().height : choice.piece().width;
        }
        return spanAcrossAxis(choice.footprint(), choice.template(), horizontalFrontage);
    }

    private static List<Integer> layoutTownSquareSlotCentres(List<Integer> slotSpans, int centre, int gap) {
        List<Integer> centres = new ArrayList<>(slotSpans.size());
        if (slotSpans.isEmpty()) {
            return centres;
        }

        for (int index = 0; index < slotSpans.size(); index++) {
            centres.add(centre);
        }

        int centralSpan = slotSpans.get(0);
        int centralMin = centre - centralSpan / 2;
        int centralMax = centralMin + centralSpan - 1;
        centres.set(0, (centralMin + centralMax) / 2);

        int leftEdge = centralMin;
        int rightEdge = centralMax;
        for (int slot = 1; slot < slotSpans.size(); slot++) {
            int span = slotSpans.get(slot);
            if ((slot & 1) == 1) {
                int max = leftEdge - gap - 1;
                int min = max - span + 1;
                centres.set(slot, (min + max) / 2);
                leftEdge = min;
            } else {
                int min = rightEdge + gap + 1;
                int max = min + span - 1;
                centres.set(slot, (min + max) / 2);
                rightEdge = max;
            }
        }
        return centres;
    }

    private static SettlementBuilding createTownSquareBuilding(
            SettlementLayoutChoice choice,
            int frontageCentre,
            int plazaMinDepth,
            int plazaMaxDepth,
            int rowSign,
            boolean horizontalFrontage
    ) {
        if (choice == null) {
            return null;
        }

        CacheBiomeProfileMiner.SettlementBuildingTemplate template = choice.template();
        CacheBiomeProfileMiner.SettlementFootprint footprint = choice.footprint();
        boolean rotatedTemplate = template != null && shouldRotateTemplate(template, horizontalFrontage);
        int frontageSpan = spanAlongAxis(footprint, template, horizontalFrontage);
        int depthSpan = spanAcrossAxis(footprint, template, horizontalFrontage);
        if (!BuildingGenerator.isStampableFootprintSpan(horizontalFrontage ? frontageSpan : depthSpan,
                horizontalFrontage ? depthSpan : frontageSpan)) {
            return null;
        }

        int frontageMin = frontageCentre - frontageSpan / 2;
        int frontageMax = frontageMin + frontageSpan - 1;
        int depthMin;
        int depthMax;
        if (rowSign < 0) {
            depthMax = plazaMinDepth - 1;
            depthMin = depthMax - depthSpan + 1;
        } else {
            depthMin = plazaMaxDepth + 1;
            depthMax = depthMin + depthSpan - 1;
        }

        int floors = template != null
                ? Math.max(1, template.floors)
                : choice.piece() != null
                ? Math.max(1, choice.piece().floors)
                : Math.max(1, footprint == null ? 1 : footprint.floors);
        return horizontalFrontage
                ? new SettlementBuilding(frontageMin, depthMin, frontageMax, depthMax, floors, choice.piece(), footprint, template, rotatedTemplate, -1, null, null)
                : new SettlementBuilding(depthMin, frontageMin, depthMax, frontageMax, floors, choice.piece(), footprint, template, rotatedTemplate, -1, null, null);
    }

    private static CacheBiomeProfileMiner.SettlementBuildingTemplate chooseAnchorTemplate(
            CacheBiomeProfileMiner.SettlementProfile profile,
            int anchorIndex
    ) {
        if (profile == null || profile.buildingTemplates == null || profile.buildingTemplates.isEmpty()) {
            return null;
        }
        List<CacheBiomeProfileMiner.SettlementBuildingTemplate> templates = profile.buildingTemplates.stream()
                .filter(Objects::nonNull)
                .filter(BiomeProfilePreviewGenerator::isUsableBuildingTemplate)
                .sorted((left, right) -> Integer.compare(right.width * right.height, left.width * left.height))
                .toList();
        if (templates.isEmpty()) {
            return null;
        }
        return templates.get(Math.min(anchorIndex, templates.size() - 1));
    }

    private static CacheBiomeProfileMiner.SettlementBuildingTemplate chooseSatelliteTemplate(
            CacheBiomeProfileMiner.SettlementProfile profile,
            double pick
    ) {
        if (profile == null || profile.buildingTemplates == null || profile.buildingTemplates.isEmpty()) {
            return null;
        }
        List<CacheBiomeProfileMiner.SettlementBuildingTemplate> templates = profile.buildingTemplates.stream()
                .filter(Objects::nonNull)
                .filter(BiomeProfilePreviewGenerator::isUsableBuildingTemplate)
                .sorted((left, right) -> Integer.compare(right.width * right.height, left.width * left.height))
                .toList();
        if (templates.isEmpty()) {
            return null;
        }
        List<CacheBiomeProfileMiner.SettlementBuildingTemplate> source = templates.size() > 1
                ? templates.subList(1, templates.size())
                : templates;
        return chooseBuildingTemplate(source, pick);
    }

    private static CacheBiomeProfileMiner.SettlementBuildingTemplate chooseBuildingTemplate(
            List<CacheBiomeProfileMiner.SettlementBuildingTemplate> templates,
            double pick
    ) {
        if (templates == null || templates.isEmpty()) {
            return null;
        }
        double total = 0.0;
        for (CacheBiomeProfileMiner.SettlementBuildingTemplate template : templates) {
            total += Math.max(0.0001, template.weight);
        }
        double threshold = pick * total;
        double cumulative = 0.0;
        CacheBiomeProfileMiner.SettlementBuildingTemplate last = templates.get(templates.size() - 1);
        for (CacheBiomeProfileMiner.SettlementBuildingTemplate template : templates) {
            cumulative += Math.max(0.0001, template.weight);
            if (threshold <= cumulative) {
                return template;
            }
        }
        return last;
    }

    private static boolean isUsableBuildingTemplate(CacheBiomeProfileMiner.SettlementBuildingTemplate template) {
        return buildingTemplateCompletenessScore(template) > 0;
    }

    private static int buildingTemplateCompletenessScore(CacheBiomeProfileMiner.SettlementBuildingTemplate template) {
        if (template == null || template.objects == null || template.objects.isEmpty()) {
            return 0;
        }
        int minX = 0;
        int minY = 0;
        int maxX = Math.max(0, template.width - 1);
        int maxY = Math.max(0, template.height - 1);
        Set<Integer> north = new LinkedHashSet<>();
        Set<Integer> south = new LinkedHashSet<>();
        Set<Integer> west = new LinkedHashSet<>();
        Set<Integer> east = new LinkedHashSet<>();
        int cornerCount = 0;
        for (CacheBiomeProfileMiner.SettlementTemplateObject object : template.objects) {
            if (object == null || object.plane != 0 || object.type > 4) {
                continue;
            }
            if (object.x == minX) {
                west.add(object.y);
            }
            if (object.x == maxX) {
                east.add(object.y);
            }
            if (object.y == minY) {
                north.add(object.x);
            }
            if (object.y == maxY) {
                south.add(object.x);
            }
            boolean corner = (object.x == minX || object.x == maxX) && (object.y == minY || object.y == maxY);
            if (corner) {
                cornerCount++;
            }
        }
        int horizontalThreshold = Math.max(2, Math.min(4, Math.max(1, template.width / 3)));
        int verticalThreshold = Math.max(2, Math.min(4, Math.max(1, template.height / 3)));
        int strongSides = 0;
        strongSides += north.size() >= horizontalThreshold ? 1 : 0;
        strongSides += south.size() >= horizontalThreshold ? 1 : 0;
        strongSides += west.size() >= verticalThreshold ? 1 : 0;
        strongSides += east.size() >= verticalThreshold ? 1 : 0;
        int uniqueBoundaryTiles = north.size() + south.size() + west.size() + east.size() - cornerCount;
        int perimeter = Math.max(1, 2 * (template.width + template.height) - 4);
        double boundaryRatio = uniqueBoundaryTiles / (double) perimeter;
        if (strongSides < 2) {
            return 0;
        }
        if (strongSides == 2 && cornerCount < 2) {
            return 0;
        }
        if (boundaryRatio < 0.24) {
            return 0;
        }
        return strongSides * 100 + cornerCount * 8 + (int) Math.round(boundaryRatio * 100.0);
    }

    private static SettlementBuilding createBuildingAtSite(
            CacheBiomeProfileMiner.SettlementFootprint footprint,
            CacheBiomeProfileMiner.SettlementBuildingTemplate template,
            SettlementSite site,
            int centreX,
            int centreY,
            int axisDirX,
            int axisDirY,
            int depthDirX,
            int depthDirY,
            boolean horizontalFrontage,
            int inwardBias
    ) {
        boolean rotatedTemplate = template != null && shouldRotateTemplate(template, horizontalFrontage);
        int width = footprintWidth(footprint, template, rotatedTemplate, horizontalFrontage);
        int height = footprintHeight(footprint, template, rotatedTemplate, horizontalFrontage);
        if (!BuildingGenerator.isStampableFootprintSpan(width, height)) {
            return null;
        }
        int floors = template != null
                ? Math.max(1, template.floors)
                : Math.max(1, footprint == null ? 1 : footprint.floors);
        int adjustedCentreX = centreX + depthDirX * inwardBias;
        int adjustedCentreY = centreY + depthDirY * inwardBias;
        int minX = adjustedCentreX - width / 2;
        int minY = adjustedCentreY - height / 2;
        int maxX = minX + width - 1;
        int maxY = minY + height - 1;
        return new SettlementBuilding(minX, minY, maxX, maxY, floors, null, footprint, template, rotatedTemplate, -1, null, site);
    }

    private static boolean tryAddBuilding(
            SettlementBuilding building,
            boolean[][] occupied,
            List<SettlementBuilding> buildings,
            boolean[][] waterMask,
            int worldWidth,
            int worldHeight,
            int perimeter,
            int boundMinX,
            int boundMinY,
            int boundMaxX,
            int boundMaxY
    ) {
        if (building == null) {
            return false;
        }
        if (!canPlaceRect(
                building.minX() - perimeter,
                building.minY() - perimeter,
                building.maxX() + perimeter,
                building.maxY() + perimeter,
                occupied,
                waterMask,
                worldWidth,
                worldHeight,
                boundMinX,
                boundMinY,
                boundMaxX,
                boundMaxY
        )) {
            return false;
        }
        buildings.add(building);
        return true;
    }

    private static boolean canPlaceRect(
            int minX,
            int minY,
            int maxX,
            int maxY,
            boolean[][] occupied,
            boolean[][] waterMask,
            int worldWidth,
            int worldHeight,
            int boundMinX,
            int boundMinY,
            int boundMaxX,
            int boundMaxY
    ) {
        if (minX < Math.max(2, boundMinX)
                || minY < Math.max(2, boundMinY)
                || maxX > Math.min(worldWidth - 3, boundMaxX)
                || maxY > Math.min(worldHeight - 3, boundMaxY)) {
            return false;
        }
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                if (occupied[x][y] || waterMask[x][y]) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void reserveRect(boolean[][] mask, int minX, int minY, int maxX, int maxY, int worldWidth, int worldHeight) {
        for (int x = Math.max(0, minX); x <= Math.min(worldWidth - 1, maxX); x++) {
            for (int y = Math.max(0, minY); y <= Math.min(worldHeight - 1, maxY); y++) {
                mask[x][y] = true;
            }
        }
    }

    private static int spanAlongAxis(SettlementBuilding building, boolean horizontalFrontage) {
        return horizontalFrontage ? building.width() : building.height();
    }

    private static int spanAlongAxis(CacheBiomeProfileMiner.SettlementFootprint building, boolean horizontalFrontage) {
        return horizontalFrontage ? building.width : building.height;
    }

    private static int spanAlongAxis(
            CacheBiomeProfileMiner.SettlementFootprint footprint,
            CacheBiomeProfileMiner.SettlementBuildingTemplate template,
            boolean horizontalFrontage
    ) {
        boolean rotatedTemplate = template != null && shouldRotateTemplate(template, horizontalFrontage);
        return horizontalFrontage
                ? footprintWidth(footprint, template, rotatedTemplate, horizontalFrontage)
                : footprintHeight(footprint, template, rotatedTemplate, horizontalFrontage);
    }

    private static int spanAcrossAxis(SettlementBuilding building, boolean horizontalFrontage) {
        return horizontalFrontage ? building.height() : building.width();
    }

    private static int spanAcrossAxis(CacheBiomeProfileMiner.SettlementFootprint building, boolean horizontalFrontage) {
        return horizontalFrontage ? building.height : building.width;
    }

    private static int spanAcrossAxis(
            CacheBiomeProfileMiner.SettlementFootprint footprint,
            CacheBiomeProfileMiner.SettlementBuildingTemplate template,
            boolean horizontalFrontage
    ) {
        boolean rotatedTemplate = template != null && shouldRotateTemplate(template, horizontalFrontage);
        return horizontalFrontage
                ? footprintHeight(footprint, template, rotatedTemplate, horizontalFrontage)
                : footprintWidth(footprint, template, rotatedTemplate, horizontalFrontage);
    }

    private static boolean shouldRotateTemplate(
            CacheBiomeProfileMiner.SettlementBuildingTemplate template,
            boolean horizontalFrontage
    ) {
        if (template == null || template.width == template.height) {
            return false;
        }
        return horizontalFrontage ? template.width < template.height : template.width > template.height;
    }

    private static int footprintWidth(
            CacheBiomeProfileMiner.SettlementFootprint footprint,
            CacheBiomeProfileMiner.SettlementBuildingTemplate template,
            boolean rotatedTemplate,
            boolean horizontalFrontage
    ) {
        if (template != null) {
            return rotatedTemplate ? template.height : template.width;
        }
        if (footprint == null) {
            return 1;
        }
        return horizontalFrontage ? footprint.width : footprint.height;
    }

    private static int footprintHeight(
            CacheBiomeProfileMiner.SettlementFootprint footprint,
            CacheBiomeProfileMiner.SettlementBuildingTemplate template,
            boolean rotatedTemplate,
            boolean horizontalFrontage
    ) {
        if (template != null) {
            return rotatedTemplate ? template.width : template.height;
        }
        if (footprint == null) {
            return 1;
        }
        return horizontalFrontage ? footprint.height : footprint.width;
    }

    private static CacheBiomeProfileMiner.SettlementFootprint chooseFootprint(
            List<CacheBiomeProfileMiner.SettlementFootprint> footprints,
            double pick
    ) {
        if (footprints == null || footprints.isEmpty()) {
            return null;
        }
        double total = 0.0;
        for (CacheBiomeProfileMiner.SettlementFootprint footprint : footprints) {
            total += Math.max(0.0001, footprint.weight);
        }
        double threshold = pick * total;
        double cumulative = 0.0;
        CacheBiomeProfileMiner.SettlementFootprint last = footprints.get(footprints.size() - 1);
        for (CacheBiomeProfileMiner.SettlementFootprint footprint : footprints) {
            cumulative += Math.max(0.0001, footprint.weight);
            if (threshold <= cumulative) {
                return footprint;
            }
        }
        return last;
    }

    private static int countFromSeed(long seed, int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + (int) Math.floor(hashedUnit(seed, min, max) * (max - min + 1));
    }

    private static void applySettlementTerrain(
            MapRegion mapRegion,
            SettlementPlan settlementPlan,
            boolean[][] reservedMask,
            boolean[][] mountainMask,
            int worldWidth,
            int worldHeight
    ) {
        CacheBiomeProfileMiner.SettlementProfile profile = settlementPlan.profile();
        HouseStyleDefinition.Village villageStyle = resolveSettlementVillageStyle(settlementPlan);
        int terrainPerimeter = 1;
        Set<Long> terrainCoreTiles = collectSettlementTerrainTiles(settlementPlan, terrainPerimeter, mountainMask, worldWidth, worldHeight);
        flattenSettlementTileMask(mapRegion, terrainCoreTiles, 1, worldWidth, worldHeight);
        for (SettlementBuilding building : settlementPlan.buildings()) {
            reserveRect(reservedMask, building.minX() - terrainPerimeter, building.minY() - terrainPerimeter, building.maxX() + terrainPerimeter, building.maxY() + terrainPerimeter, worldWidth, worldHeight);
        }
        for (long pathTile : settlementPlan.pathTiles()) {
            int x = decodeTileX(pathTile);
            int y = decodeTileY(pathTile);
            if (x < 0 || y < 0 || x >= worldWidth || y >= worldHeight || mountainMask[x][y]) {
                continue;
            }
            reservedMask[x][y] = true;
            mapRegion.underlays[0][x][y] = (short) profile.secondaryUnderlayId;
        }
        for (long centrepieceTile : settlementPlan.centrepieceTiles()) {
            int x = decodeTileX(centrepieceTile);
            int y = decodeTileY(centrepieceTile);
            if (x < 0 || y < 0 || x >= worldWidth || y >= worldHeight || mountainMask[x][y]) {
                continue;
            }
            reservedMask[x][y] = true;
            mapRegion.underlays[0][x][y] = (short) profile.secondaryUnderlayId;
        }

        int minX = settlementPlan.buildings().stream().mapToInt(SettlementBuilding::minX).min().orElse(0) - terrainPerimeter;
        int minY = settlementPlan.buildings().stream().mapToInt(SettlementBuilding::minY).min().orElse(0) - terrainPerimeter;
        int maxX = settlementPlan.buildings().stream().mapToInt(SettlementBuilding::maxX).max().orElse(0) + terrainPerimeter;
        int maxY = settlementPlan.buildings().stream().mapToInt(SettlementBuilding::maxY).max().orElse(0) + terrainPerimeter;

        for (int x = Math.max(0, minX); x <= Math.min(worldWidth - 1, maxX); x++) {
            for (int y = Math.max(0, minY); y <= Math.min(worldHeight - 1, maxY); y++) {
                if (reservedMask[x][y] && !mountainMask[x][y]) {
                    mapRegion.underlays[0][x][y] = (short) profile.secondaryUnderlayId;
                }
            }
        }

        paintSettlementOverlayTiles(mapRegion, settlementPlan.pathTiles(), villageStyle == null ? null : villageStyle.path, mountainMask, worldWidth, worldHeight, false);
        paintSettlementOverlayTiles(mapRegion, settlementPlan.centrepieceTiles(), villageStyle == null ? null : villageStyle.centrepiece, mountainMask, worldWidth, worldHeight, true);

        if (profile.accentOverlayId > 0) {
            for (SettlementBuilding building : settlementPlan.buildings()) {
                if (building.template() == null) {
                    paintBuildingFloor(mapRegion, profile.accentOverlayId, building, worldWidth, worldHeight);
                }
            }
        }

        for (SettlementBuilding building : settlementPlan.buildings()) {
            if (building.template() != null) {
                applyBuildingTemplateTiles(mapRegion, profile, building);
            }
        }
    }

    private static Set<Long> collectSettlementTerrainTiles(
            SettlementPlan settlementPlan,
            int perimeter,
            boolean[][] mountainMask,
            int worldWidth,
            int worldHeight
    ) {
        LinkedHashSet<Long> terrainTiles = new LinkedHashSet<>();
        for (long tile : expandTileSet(settlementPlan.pathTiles(), 1, worldWidth, worldHeight)) {
            int x = decodeTileX(tile);
            int y = decodeTileY(tile);
            if (x >= 0 && y >= 0 && x < worldWidth && y < worldHeight && !mountainMask[x][y]) {
                terrainTiles.add(tile);
            }
        }
        for (long tile : expandTileSet(settlementPlan.centrepieceTiles(), 1, worldWidth, worldHeight)) {
            int x = decodeTileX(tile);
            int y = decodeTileY(tile);
            if (x >= 0 && y >= 0 && x < worldWidth && y < worldHeight && !mountainMask[x][y]) {
                terrainTiles.add(tile);
            }
        }
        for (SettlementBuilding building : settlementPlan.buildings()) {
            for (int x = Math.max(0, building.minX() - perimeter); x <= Math.min(worldWidth - 1, building.maxX() + perimeter); x++) {
                for (int y = Math.max(0, building.minY() - perimeter); y <= Math.min(worldHeight - 1, building.maxY() + perimeter); y++) {
                    if (!mountainMask[x][y]) {
                        terrainTiles.add(tileKey(x, y));
                    }
                }
            }
        }
        return terrainTiles;
    }

    private static void flattenSettlementTileMask(
            MapRegion mapRegion,
            Set<Long> tileKeys,
            int blendPerimeter,
            int worldWidth,
            int worldHeight
    ) {
        if (tileKeys == null || tileKeys.isEmpty()) {
            return;
        }
        List<Integer> tileHeights = new ArrayList<>(tileKeys.size());
        for (long tileKey : tileKeys) {
            int x = decodeTileX(tileKey);
            int y = decodeTileY(tileKey);
            if (x < 0 || y < 0 || x >= worldWidth || y >= worldHeight) {
                continue;
            }
            tileHeights.add(averageTileHeight(mapRegion, x, y));
        }
        if (tileHeights.isEmpty()) {
            return;
        }

        List<Integer> borderHeights = new ArrayList<>();
        Set<Long> borderTiles = expandTileSet(tileKeys, Math.max(1, blendPerimeter + 1), worldWidth, worldHeight);
        borderTiles.removeAll(tileKeys);
        for (long borderTile : borderTiles) {
            int x = decodeTileX(borderTile);
            int y = decodeTileY(borderTile);
            if (x < 0 || y < 0 || x >= worldWidth || y >= worldHeight) {
                continue;
            }
            borderHeights.add(averageTileHeight(mapRegion, x, y));
        }

        int tileMedian = percentile(tileHeights, 0.50);
        int target = borderHeights.isEmpty()
                ? quantizeHeight(tileMedian)
                : quantizeHeight((int) Math.round(
                        tileMedian * 0.62
                                + percentile(borderHeights, 0.50) * 0.25
                                + percentile(borderHeights, 0.30) * 0.13
                ));

        for (long tileKey : tileKeys) {
            int x = decodeTileX(tileKey);
            int y = decodeTileY(tileKey);
            if (x < 0 || y < 0 || x >= worldWidth || y >= worldHeight) {
                continue;
            }
            setVertexHeight(mapRegion, x, y, target, worldWidth, worldHeight);
        }

        Set<Long> visited = new LinkedHashSet<>(tileKeys);
        Set<Long> frontier = new LinkedHashSet<>(tileKeys);
        for (int ring = 1; ring <= blendPerimeter; ring++) {
            Set<Long> nextRing = new LinkedHashSet<>();
            for (long tileKey : frontier) {
                int tileX = decodeTileX(tileKey);
                int tileY = decodeTileY(tileKey);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (dx == 0 && dy == 0) {
                            continue;
                        }
                        int neighbourX = tileX + dx;
                        int neighbourY = tileY + dy;
                        if (neighbourX < 0 || neighbourY < 0 || neighbourX >= worldWidth || neighbourY >= worldHeight) {
                            continue;
                        }
                        long neighbourKey = tileKey(neighbourX, neighbourY);
                        if (!visited.add(neighbourKey)) {
                            continue;
                        }
                        nextRing.add(neighbourKey);
                    }
                }
            }
            double blend = 1.0 - (ring / (double) (blendPerimeter + 1));
            for (long tileKey : nextRing) {
                int x = decodeTileX(tileKey);
                int y = decodeTileY(tileKey);
                int current = averageTileHeight(mapRegion, x, y);
                int blended = quantizeHeight((int) Math.round(current * (1.0 - blend) + target * blend));
                setVertexHeight(mapRegion, x, y, blended, worldWidth, worldHeight);
            }
            frontier = nextRing;
        }
    }

    private static Set<Long> expandTileSet(
            Collection<Long> tileKeys,
            int radius,
            int worldWidth,
            int worldHeight
    ) {
        LinkedHashSet<Long> expanded = new LinkedHashSet<>();
        if (tileKeys == null || tileKeys.isEmpty() || radius < 0) {
            return expanded;
        }
        Set<Long> frontier = new LinkedHashSet<>();
        for (long tileKey : tileKeys) {
            int x = decodeTileX(tileKey);
            int y = decodeTileY(tileKey);
            if (x < 0 || y < 0 || x >= worldWidth || y >= worldHeight) {
                continue;
            }
            expanded.add(tileKey);
            frontier.add(tileKey);
        }
        for (int ring = 0; ring < radius; ring++) {
            Set<Long> nextFrontier = new LinkedHashSet<>();
            for (long tileKey : frontier) {
                int tileX = decodeTileX(tileKey);
                int tileY = decodeTileY(tileKey);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (dx == 0 && dy == 0) {
                            continue;
                        }
                        int neighbourX = tileX + dx;
                        int neighbourY = tileY + dy;
                        if (neighbourX < 0 || neighbourY < 0 || neighbourX >= worldWidth || neighbourY >= worldHeight) {
                            continue;
                        }
                        long neighbourKey = tileKey(neighbourX, neighbourY);
                        if (expanded.add(neighbourKey)) {
                            nextFrontier.add(neighbourKey);
                        }
                    }
                }
            }
            frontier = nextFrontier;
            if (frontier.isEmpty()) {
                break;
            }
        }
        return expanded;
    }

    private static void flattenSettlementTileSet(
            MapRegion mapRegion,
            Set<Long> tileKeys,
            int perimeter,
            int worldWidth,
            int worldHeight
    ) {
        if (tileKeys == null || tileKeys.isEmpty()) {
            return;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        List<Integer> tileHeights = new ArrayList<>(tileKeys.size());
        List<Integer> borderHeights = new ArrayList<>();
        for (long tileKey : tileKeys) {
            int x = decodeTileX(tileKey);
            int y = decodeTileY(tileKey);
            if (x < 0 || y < 0 || x >= worldWidth || y >= worldHeight) {
                continue;
            }
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            tileHeights.add(averageTileHeight(mapRegion, x, y));
        }
        if (tileHeights.isEmpty()) {
            return;
        }
        int sampleMinX = Math.max(0, minX - perimeter);
        int sampleMinY = Math.max(0, minY - perimeter);
        int sampleMaxX = Math.min(worldWidth - 1, maxX + perimeter);
        int sampleMaxY = Math.min(worldHeight - 1, maxY + perimeter);
        for (int x = sampleMinX; x <= sampleMaxX; x++) {
            for (int y = sampleMinY; y <= sampleMaxY; y++) {
                if (x >= minX && x <= maxX && y >= minY && y <= maxY) {
                    continue;
                }
                borderHeights.add(averageTileHeight(mapRegion, x, y));
            }
        }
        int tileMedian = percentile(tileHeights, 0.50);
        int target = borderHeights.isEmpty()
                ? quantizeHeight(tileMedian)
                : quantizeHeight((int) Math.round(
                        tileMedian * 0.58
                                + percentile(borderHeights, 0.50) * 0.27
                                + percentile(borderHeights, 0.30) * 0.15
                ));
        for (int x = sampleMinX; x <= sampleMaxX; x++) {
            for (int y = sampleMinY; y <= sampleMaxY; y++) {
                setVertexHeight(mapRegion, x, y, target, worldWidth, worldHeight);
            }
        }
    }

    private static void flattenBuildingPad(
            MapRegion mapRegion,
            SettlementBuilding building,
            int perimeter,
            int worldWidth,
            int worldHeight
    ) {
        int padMinX = Math.max(0, building.minX() - perimeter);
        int padMinY = Math.max(0, building.minY() - perimeter);
        int padMaxX = Math.min(worldWidth - 1, building.maxX() + perimeter);
        int padMaxY = Math.min(worldHeight - 1, building.maxY() + perimeter);
        int target = chooseBuildingPadHeight(mapRegion, building, perimeter, worldWidth, worldHeight);
        for (int x = padMinX; x <= padMaxX; x++) {
            for (int y = padMinY; y <= padMaxY; y++) {
                int ringDistance = distanceOutsideBuilding(building, x, y);
                if (ringDistance <= 0) {
                    setVertexHeight(mapRegion, x, y, target, worldWidth, worldHeight);
                    continue;
                }
                double blend = 1.0 - (ringDistance / (double) (perimeter + 1));
                int current = averageTileHeight(mapRegion, x, y);
                int blended = quantizeHeight((int) Math.round(current * (1.0 - blend) + target * blend));
                setVertexHeight(mapRegion, x, y, blended, worldWidth, worldHeight);
            }
        }
    }

    private static HouseStyleDefinition.Village resolveVillageStyle(BuildingStyle buildingStyle) {
        if (buildingStyle == null || buildingStyle.usesHouseType()) {
            return null;
        }
        HouseStyleDefinition definition = HouseStyleRepository.load(buildingStyle);
        if (definition == null || definition.proceduralRecipe == null) {
            return null;
        }
        return definition.proceduralRecipe.village;
    }

    private static BuildingStyle settlementBuildingStyle(SettlementPlan settlementPlan, SettlementBuilding building) {
        if (building != null && building.buildingStyle() != null) {
            return building.buildingStyle();
        }
        return settlementPlan == null ? null : settlementPlan.buildingStyle();
    }

    private static HouseStyleDefinition.Village resolveSettlementVillageStyle(SettlementPlan settlementPlan) {
        if (settlementPlan == null) {
            return null;
        }
        HouseStyleDefinition.Village village = resolveVillageStyle(settlementPlan.buildingStyle());
        if (village != null) {
            return village;
        }
        for (SettlementBuilding building : settlementPlan.buildings()) {
            village = resolveVillageStyle(building.buildingStyle());
            if (village != null) {
                return village;
            }
        }
        return null;
    }

    private static void paintSettlementOverlayTiles(
            MapRegion mapRegion,
            Set<Long> tileKeys,
            HouseStyleDefinition.TileLayer tileLayer,
            boolean[][] mountainMask,
            int worldWidth,
            int worldHeight,
            boolean roundOuterCorners
    ) {
        if (tileLayer == null || tileLayer.overlayId <= 0 || tileKeys == null || tileKeys.isEmpty()) {
            return;
        }
        byte encodedShape = (byte) Math.max(0, tileLayer.overlayShape - 1);
        for (long tileKey : tileKeys) {
            int x = decodeTileX(tileKey);
            int y = decodeTileY(tileKey);
            if (x < 0 || y < 0 || x >= worldWidth || y >= worldHeight || mountainMask[x][y]) {
                continue;
            }
            byte shape = encodedShape;
            byte orientation = 0;
            if (roundOuterCorners) {
                OverlayTileShape roundedCorner = resolveRoundedOuterCornerShape(tileKeys, x, y);
                if (roundedCorner != null) {
                    shape = roundedCorner.shape();
                    orientation = roundedCorner.orientation();
                }
            }
            mapRegion.overlays[0][x][y] = (short) tileLayer.overlayId;
            mapRegion.overlayShapes[0][x][y] = shape;
            mapRegion.overlayOrientations[0][x][y] = orientation;
        }
    }

    private static OverlayTileShape resolveRoundedOuterCornerShape(Set<Long> tileKeys, int x, int y) {
        int mask = tileKeyNeighbourMask(tileKeys, x, y);
        if (mask != 3 && mask != 6 && mask != 9 && mask != 12) {
            return null;
        }
        return new OverlayTileShape(STORED_ROUNDED_PLAZA_CORNER_SHAPE, roundedCornerOrientation(mask));
    }

    private static int tileKeyNeighbourMask(Set<Long> tileKeys, int x, int y) {
        int mask = 0;
        if (tileKeys.contains(tileKey(x, y + 1))) {
            mask |= 1;
        }
        if (tileKeys.contains(tileKey(x + 1, y))) {
            mask |= 2;
        }
        if (tileKeys.contains(tileKey(x, y - 1))) {
            mask |= 4;
        }
        if (tileKeys.contains(tileKey(x - 1, y))) {
            mask |= 8;
        }
        return mask;
    }

    private static byte roundedCornerOrientation(int mask) {
        return switch (mask) {
            case 3 -> 3;
            case 6 -> 0;
            case 12 -> 1;
            case 9 -> 2;
            default -> 0;
        };
    }

    private static Color villageOverlayColor(HouseStyleDefinition.TileLayer tileLayer, Color fallback) {
        if (tileLayer == null || tileLayer.overlayId <= 0) {
            return fallback;
        }
        return materialColor(tileLayer.overlayId, true, fallback);
    }

    private static int chooseBuildingPadHeight(
            MapRegion mapRegion,
            SettlementBuilding building,
            int perimeter,
            int worldWidth,
            int worldHeight
    ) {
        List<Integer> footprintHeights = new ArrayList<>();
        List<Integer> borderHeights = new ArrayList<>();
        int sampleMinX = Math.max(0, building.minX() - perimeter - 1);
        int sampleMinY = Math.max(0, building.minY() - perimeter - 1);
        int sampleMaxX = Math.min(worldWidth - 1, building.maxX() + perimeter + 1);
        int sampleMaxY = Math.min(worldHeight - 1, building.maxY() + perimeter + 1);

        for (int x = sampleMinX; x <= sampleMaxX; x++) {
            for (int y = sampleMinY; y <= sampleMaxY; y++) {
                int height = averageTileHeight(mapRegion, x, y);
                if (x >= building.minX() && x <= building.maxX() && y >= building.minY() && y <= building.maxY()) {
                    footprintHeights.add(height);
                    continue;
                }
                if (x >= building.minX() - perimeter - 1 && x <= building.maxX() + perimeter + 1
                        && y >= building.minY() - perimeter - 1 && y <= building.maxY() + perimeter + 1) {
                    boolean borderTile = x == building.minX() - perimeter - 1
                            || x == building.maxX() + perimeter + 1
                            || y == building.minY() - perimeter - 1
                            || y == building.maxY() + perimeter + 1;
                    if (borderTile) {
                        borderHeights.add(height);
                    }
                }
            }
        }

        if (footprintHeights.isEmpty()) {
            return quantizeHeight(averageTileHeight(
                    mapRegion,
                    Math.max(0, Math.min(worldWidth - 1, building.centreX())),
                    Math.max(0, Math.min(worldHeight - 1, building.centreY()))
            ));
        }

        int footprintMedian = percentile(footprintHeights, 0.50);
        if (borderHeights.isEmpty()) {
            return quantizeHeight(footprintMedian);
        }
        int borderMedian = percentile(borderHeights, 0.50);
        int borderLow = percentile(borderHeights, 0.30);
        int target = (int) Math.round(footprintMedian * 0.52 + borderMedian * 0.28 + borderLow * 0.20);
        return quantizeHeight(target);
    }

    private static int distanceOutsideBuilding(SettlementBuilding building, int x, int y) {
        int dx = 0;
        if (x < building.minX()) {
            dx = building.minX() - x;
        } else if (x > building.maxX()) {
            dx = x - building.maxX();
        }
        int dy = 0;
        if (y < building.minY()) {
            dy = building.minY() - y;
        } else if (y > building.maxY()) {
            dy = y - building.maxY();
        }
        return Math.max(dx, dy);
    }

    private static void seedUpperPlaneHeights(MapRegion mapRegion, int worldWidth, int worldHeight) {
        for (int plane = 1; plane < 4; plane++) {
            for (int x = 0; x <= worldWidth; x++) {
                for (int y = 0; y <= worldHeight; y++) {
                    mapRegion.tileHeights[plane][x][y] = mapRegion.tileHeights[plane - 1][x][y] - 240;
                }
            }
            for (int x = 0; x < worldWidth; x++) {
                for (int y = 0; y < worldHeight; y++) {
                    mapRegion.manualTileHeight[plane][x][y] = 1;
                }
            }
        }
    }

    private static void placeSettlement(
            MapRegion mapRegion,
            SceneGraph sceneGraph,
            SettlementPlan settlementPlan,
            boolean[][] reservedMask,
            int worldWidth,
            int worldHeight,
            long seed
    ) {
        for (SettlementBuilding building : settlementPlan.buildings()) {
            stampBuildingShell(mapRegion, sceneGraph, settlementPlan, building);
            if (building.template() == null) {
                placeInteriorContents(mapRegion, sceneGraph, settlementPlan, building, seed);
            }
        }
        scatterSettlementProps(mapRegion, sceneGraph, settlementPlan, reservedMask, worldWidth, worldHeight, seed);
    }

    private static void stampBuildingShell(
            MapRegion mapRegion,
            SceneGraph sceneGraph,
            SettlementPlan settlementPlan,
            SettlementBuilding building
    ) {
        if (building.template() != null && applyBuildingTemplateObjects(mapRegion, sceneGraph, settlementPlan.profile(), building)) {
            return;
        }

        BuildingStyle buildingStyle = settlementBuildingStyle(settlementPlan, building);
        if (buildingStyle != null) {
            int doorOrientation = settlementDoorOrientation(settlementPlan, building);
            int doorCoord = doorOrientation == 0 || doorOrientation == 2
                    ? doorwayCoordinate(building.minY(), building.maxY())
                    : doorwayCoordinate(building.minX(), building.maxX());
            int tileHeight = quantizeHeight(averageTileHeight(
                    mapRegion,
                    Math.max(0, building.centreX()),
                    Math.max(0, building.centreY())
            ));
            BuildingGenerator.stampBuilding(
                    mapRegion,
                    sceneGraph,
                    buildingStyle,
                    building.minX(),
                    building.minY(),
                    building.maxX(),
                    building.maxY(),
                    tileHeight,
                    building.floors(),
                    doorOrientation,
                    doorCoord,
                    building.footprint() == null ? null : building.footprint().shapeHint,
                    building.piece()
            );
            return;
        }

        CacheBiomeProfileMiner.SettlementProfile profile = settlementPlan.profile();
        int doorOrientation = settlementDoorOrientation(settlementPlan, building);
        int doorCoord = doorOrientation == 0 || doorOrientation == 2
                ? doorwayCoordinate(building.minY(), building.maxY())
                : doorwayCoordinate(building.minX(), building.maxX());
        int wallType = resolveWallType(profile);
        int upperWallType = resolveUpperWallType(profile);
        int cornerType = resolveCornerType(profile);
        int doorType = resolveDoorType(profile);

        for (int floor = 0; floor < building.floors(); floor++) {
            int wallId = floor == 0 || profile.upperWallId <= 0 ? profile.wallId : profile.upperWallId;
            int activeWallType = floor == 0 || profile.upperWallId <= 0 ? wallType : upperWallType;
            boolean isTopFloor = floor == building.floors() - 1;
            for (int y = building.minY() + 2; y < building.maxY() - 1; y++) {
                if (floor == 0 && doorOrientation == 2 && y == doorCoord && profile.doorId > 0) {
                    mapRegion.spawnObjectToWorld(sceneGraph, profile.doorId, building.minX() + 1, y, floor, doorType, 2, false);
                } else {
                    mapRegion.spawnObjectToWorld(sceneGraph, wallId, building.minX() + 1, y, floor, activeWallType, 2, false);
                }
                if (floor == 0 && doorOrientation == 0 && y == doorCoord && profile.doorId > 0) {
                    mapRegion.spawnObjectToWorld(sceneGraph, profile.doorId, building.maxX() - 1, y, floor, doorType, 0, false);
                } else {
                    mapRegion.spawnObjectToWorld(sceneGraph, wallId, building.maxX() - 1, y, floor, activeWallType, 0, false);
                }
            }
            for (int x = building.minX() + 2; x < building.maxX() - 1; x++) {
                if (floor == 0 && doorOrientation == 1 && x == doorCoord && profile.doorId > 0) {
                    mapRegion.spawnObjectToWorld(sceneGraph, profile.doorId, x, building.minY() + 1, floor, doorType, 1, false);
                } else {
                    mapRegion.spawnObjectToWorld(sceneGraph, wallId, x, building.minY() + 1, floor, activeWallType, 1, false);
                }
                if (floor == 0 && doorOrientation == 3 && x == doorCoord && profile.doorId > 0) {
                    mapRegion.spawnObjectToWorld(sceneGraph, profile.doorId, x, building.maxY() - 1, floor, doorType, 3, false);
                } else {
                    mapRegion.spawnObjectToWorld(sceneGraph, wallId, x, building.maxY() - 1, floor, activeWallType, 3, false);
                }
            }

            if (profile.cornerId > 0) {
                mapRegion.spawnObjectToWorld(sceneGraph, profile.cornerId, building.minX() + 1, building.minY() + 1, floor, cornerType, 1, false);
                mapRegion.spawnObjectToWorld(sceneGraph, profile.cornerId, building.minX() + 1, building.maxY() - 1, floor, cornerType, 2, false);
                mapRegion.spawnObjectToWorld(sceneGraph, profile.cornerId, building.maxX() - 1, building.maxY() - 1, floor, cornerType, 3, false);
                mapRegion.spawnObjectToWorld(sceneGraph, profile.cornerId, building.maxX() - 1, building.minY() + 1, floor, cornerType, 0, false);
            }
            if (isTopFloor) {
                stampRoof(mapRegion, sceneGraph, settlementPlan.profile(), building, floor + 1);
            }
        }
        stampWallDecor(mapRegion, sceneGraph, settlementPlan.profile(), building);
    }

    private static void applyBuildingTemplateTiles(
            MapRegion mapRegion,
            CacheBiomeProfileMiner.SettlementProfile profile,
            SettlementBuilding building
    ) {
        CacheBiomeProfileMiner.SettlementBuildingTemplate template = building.template();
        if (template == null) {
            return;
        }

        if (template.tiles != null) {
            for (CacheBiomeProfileMiner.SettlementTemplateTile tile : template.tiles) {
                if (tile == null || tile.plane < 0 || tile.plane >= 4) {
                    continue;
                }
                RotatedCoordinate coordinate = rotateCoordinate(tile.x, tile.y, template.width, template.height, building.rotatedTemplate());
                int worldX = building.minX() + coordinate.x();
                int worldY = building.minY() + coordinate.y();
                if (worldX < 0 || worldY < 0
                        || worldX >= mapRegion.underlays[tile.plane].length
                        || worldY >= mapRegion.underlays[tile.plane][worldX].length) {
                    continue;
                }
                mapRegion.underlays[tile.plane][worldX][worldY] = (short) tile.underlayId;
                mapRegion.overlays[tile.plane][worldX][worldY] = (short) tile.overlayId;
                mapRegion.overlayShapes[tile.plane][worldX][worldY] = (byte) tile.shape;
                mapRegion.overlayOrientations[tile.plane][worldX][worldY] = (byte) rotateOrientation(tile.orientation, building.rotatedTemplate());
                mapRegion.tileFlags[tile.plane][worldX][worldY] = (byte) tile.tileFlags;
            }
        }
    }

    private static boolean applyBuildingTemplateObjects(
            MapRegion mapRegion,
            SceneGraph sceneGraph,
            CacheBiomeProfileMiner.SettlementProfile profile,
            SettlementBuilding building
    ) {
        CacheBiomeProfileMiner.SettlementBuildingTemplate template = building.template();
        if (template == null) {
            return false;
        }

        boolean placed = false;
        if (template.objects != null) {
            for (CacheBiomeProfileMiner.SettlementTemplateObject object : template.objects) {
                if (object == null || object.plane < 0 || object.plane >= 4) {
                    continue;
                }
                RotatedCoordinate coordinate = rotateMarginCoordinate(object.x, object.y, template.width, template.height, building.rotatedTemplate());
                int worldX = building.minX() + coordinate.x();
                int worldY = building.minY() + coordinate.y();
                if (worldX < 0 || worldY < 0
                        || worldX >= mapRegion.underlays[object.plane].length
                        || worldY >= mapRegion.underlays[object.plane][worldX].length) {
                    continue;
                }
                mapRegion.spawnObjectToWorld(
                        sceneGraph,
                        object.id,
                        worldX,
                        worldY,
                        object.plane,
                        object.type,
                        rotateOrientation(object.orientation, building.rotatedTemplate()),
                        false
                );
                placed = true;
            }
        }
        return placed;
    }

    private static boolean placeSettlementPieceObjects(
            MapRegion mapRegion,
            SceneGraph sceneGraph,
            SettlementPlan settlementPlan,
            SettlementBuilding building
    ) {
        CacheBiomeProfileMiner.SettlementPieceDefinition piece = building.piece();
        if (piece == null || piece.objects == null || piece.objects.isEmpty()) {
            return false;
        }

        int rotationSteps = Math.floorMod(settlementDoorOrientation(settlementPlan, building) - normalizePieceFrontOrientation(piece.frontOrientation), 4);
        boolean placed = false;
        for (CacheBiomeProfileMiner.SettlementPieceObject object : piece.objects) {
            if (object == null || object.z < 0 || object.z >= 4) {
                continue;
            }
            RotatedPieceCoordinate coordinate = rotatePieceCoordinate(object.x, object.y, piece.width, piece.height, rotationSteps);
            int worldX = building.minX() + coordinate.x();
            int worldY = building.minY() + coordinate.y();
            if (worldX < 0 || worldY < 0
                    || worldX >= mapRegion.underlays[object.z].length
                    || worldY >= mapRegion.underlays[object.z][worldX].length) {
                continue;
            }
            HouseStyleDefinition.Role resolvedRole = resolveSettlementPieceRole(settlementBuildingStyle(settlementPlan, building), object.roleKey);
            int objectId = object.id > 0 ? object.id : resolvedRole == null ? 0 : resolvedRole.id;
            int objectType = object.type >= 0 ? object.type : resolvedRole == null ? -1 : resolvedRole.type;
            if (objectId <= 0 || objectType < 0) {
                continue;
            }
            mapRegion.spawnObjectToWorld(
                sceneGraph,
                objectId,
                worldX,
                worldY,
                object.z,
                objectType,
                Math.floorMod(object.orientation + rotationSteps, 4),
                false
            );
            placed = true;
        }
        return placed;
    }

    private static int normalizePieceFrontOrientation(int frontOrientation) {
        return frontOrientation >= 0 ? (frontOrientation & 3) : 1;
    }

    private static HouseStyleDefinition.Role resolveSettlementPieceRole(
            BuildingStyle style,
            String roleKey
    ) {
        if (style == null || style.usesHouseType() || roleKey == null || roleKey.isBlank()) {
            return null;
        }
        HouseStyleDefinition definition = HouseStyleRepository.load(style);
        if (definition == null
                || definition.proceduralRecipe == null
                || definition.proceduralRecipe.village == null
                || definition.proceduralRecipe.village.objectRoles == null) {
            return null;
        }
        return definition.proceduralRecipe.village.objectRoles.get(roleKey);
    }

    private static RotatedPieceCoordinate rotatePieceCoordinate(int x, int y, int width, int height, int rotationSteps) {
        int rotatedX = x;
        int rotatedY = y;
        int rotatedWidth = width;
        int rotatedHeight = height;
        for (int step = 0; step < Math.floorMod(rotationSteps, 4); step++) {
            int nextX = rotatedHeight - 1 - rotatedY;
            int nextY = rotatedX;
            rotatedX = nextX;
            rotatedY = nextY;
            int oldWidth = rotatedWidth;
            rotatedWidth = rotatedHeight;
            rotatedHeight = oldWidth;
        }
        return new RotatedPieceCoordinate(rotatedX, rotatedY);
    }

    private static void stampRoof(
            MapRegion mapRegion,
            SceneGraph sceneGraph,
            CacheBiomeProfileMiner.SettlementProfile profile,
            SettlementBuilding building,
            int roofPlane
    ) {
        RoofTemplateMatch templateMatch = selectRoofTemplate(profile, building);
        if (templateMatch != null && applyRoofTemplate(mapRegion, sceneGraph, templateMatch, building, roofPlane)) {
            return;
        }

        int edgeId = resolveRoofEdgeId(profile);
        int edgeType = resolveRoofEdgeType(profile);
        int cornerId = resolveRoofCornerId(profile);
        int cornerType = resolveRoofCornerType(profile);
        int insetCornerId = resolveRoofInsetCornerId(profile);
        int insetCornerType = resolveRoofInsetCornerType(profile);
        int flatId = resolveRoofFlatId(profile);
        int flatType = resolveRoofFlatType(profile);

        if (edgeId <= 0 || building.width() < 4 || building.height() < 4) {
            return;
        }
        for (int y = building.minY() + 1; y <= building.maxY() - 1; y++) {
            mapRegion.spawnObjectToWorld(sceneGraph, edgeId, building.minX() + 1, y, roofPlane, edgeType, 2, false);
            mapRegion.spawnObjectToWorld(sceneGraph, edgeId, building.maxX() - 1, y, roofPlane, edgeType, 0, false);
        }
        for (int x = building.minX() + 2; x <= building.maxX() - 2; x++) {
            mapRegion.spawnObjectToWorld(sceneGraph, edgeId, x, building.minY() + 1, roofPlane, edgeType, 1, false);
            mapRegion.spawnObjectToWorld(sceneGraph, edgeId, x, building.maxY() - 1, roofPlane, edgeType, 3, false);
        }

        if (cornerId > 0) {
            mapRegion.spawnObjectToWorld(sceneGraph, cornerId, building.minX() + 1, building.minY() + 1, roofPlane, cornerType, 1, false);
            mapRegion.spawnObjectToWorld(sceneGraph, cornerId, building.minX() + 1, building.maxY() - 1, roofPlane, cornerType, 2, false);
            mapRegion.spawnObjectToWorld(sceneGraph, cornerId, building.maxX() - 1, building.maxY() - 1, roofPlane, cornerType, 3, false);
            mapRegion.spawnObjectToWorld(sceneGraph, cornerId, building.maxX() - 1, building.minY() + 1, roofPlane, cornerType, 0, false);
        }

        if (flatId > 0 && building.width() >= 4 && building.height() >= 4) {
            if (insetCornerId > 0) {
                mapRegion.spawnObjectToWorld(sceneGraph, insetCornerId, building.minX() + 2, building.minY() + 2, roofPlane, insetCornerType, 1, false);
                mapRegion.spawnObjectToWorld(sceneGraph, insetCornerId, building.minX() + 2, building.maxY() - 2, roofPlane, insetCornerType, 2, false);
                mapRegion.spawnObjectToWorld(sceneGraph, insetCornerId, building.maxX() - 2, building.maxY() - 2, roofPlane, insetCornerType, 3, false);
                mapRegion.spawnObjectToWorld(sceneGraph, insetCornerId, building.maxX() - 2, building.minY() + 2, roofPlane, insetCornerType, 0, false);
            }
            for (int x = building.minX() + 2; x <= building.maxX() - 2; x++) {
                for (int y = building.minY() + 2; y <= building.maxY() - 2; y++) {
                    boolean insetCorner = (x == building.minX() + 2 || x == building.maxX() - 2)
                            && (y == building.minY() + 2 || y == building.maxY() - 2);
                    if (!insetCorner) {
                        mapRegion.spawnObjectToWorld(sceneGraph, flatId, x, y, roofPlane, flatType, 0, false);
                    }
                }
            }
        }
    }

    private static RoofTemplateMatch selectRoofTemplate(
            CacheBiomeProfileMiner.SettlementProfile profile,
            SettlementBuilding building
    ) {
        if (profile == null || profile.roofTemplates == null || profile.roofTemplates.isEmpty()) {
            return null;
        }

        CacheBiomeProfileMiner.SettlementRoofTemplate exact = null;
        CacheBiomeProfileMiner.SettlementRoofTemplate swapped = null;
        int targetWidth = building.width();
        int targetHeight = building.height();
        for (CacheBiomeProfileMiner.SettlementRoofTemplate template : profile.roofTemplates) {
            if (template == null || template.floors != building.floors()) {
                continue;
            }
            if (template.width == targetWidth && template.height == targetHeight) {
                exact = template;
                break;
            }
            if (template.width == targetHeight && template.height == targetWidth) {
                swapped = template;
            }
        }
        if (exact != null) {
            return new RoofTemplateMatch(exact, false);
        }
        if (swapped != null) {
            return new RoofTemplateMatch(swapped, true);
        }
        return null;
    }

    private static boolean applyRoofTemplate(
            MapRegion mapRegion,
            SceneGraph sceneGraph,
            RoofTemplateMatch match,
            SettlementBuilding building,
            int roofPlane
    ) {
        CacheBiomeProfileMiner.SettlementRoofTemplate template = match.template();
        boolean placed = false;
        if (template.tiles != null) {
            for (CacheBiomeProfileMiner.SettlementRoofTile tile : template.tiles) {
                RotatedCoordinate coordinate = rotateCoordinate(tile.x, tile.y, template.width, template.height, match.rotated());
                int worldX = building.minX() + coordinate.x();
                int worldY = building.minY() + coordinate.y();
                if (worldX < 0 || worldY < 0
                        || worldX >= mapRegion.overlays[roofPlane].length
                        || worldY >= mapRegion.overlays[roofPlane][worldX].length) {
                    continue;
                }
                mapRegion.underlays[roofPlane][worldX][worldY] = (short) tile.underlayId;
                mapRegion.overlays[roofPlane][worldX][worldY] = (short) tile.overlayId;
                mapRegion.overlayShapes[roofPlane][worldX][worldY] = (byte) tile.shape;
                mapRegion.overlayOrientations[roofPlane][worldX][worldY] = (byte) rotateOrientation(tile.orientation, match.rotated());
                mapRegion.tileFlags[roofPlane][worldX][worldY] = (byte) tile.tileFlags;
                placed = true;
            }
        }

        if (template.objects != null) {
            for (CacheBiomeProfileMiner.SettlementRoofObject object : template.objects) {
                RotatedCoordinate coordinate = rotateMarginCoordinate(object.x, object.y, template.width, template.height, match.rotated());
                int worldX = building.minX() + coordinate.x();
                int worldY = building.minY() + coordinate.y();
                if (worldX < 0 || worldY < 0
                        || worldX >= mapRegion.overlays[roofPlane].length
                        || worldY >= mapRegion.overlays[roofPlane][worldX].length) {
                    continue;
                }
                mapRegion.spawnObjectToWorld(
                        sceneGraph,
                        object.id,
                        worldX,
                        worldY,
                        roofPlane,
                        object.type,
                        rotateOrientation(object.orientation, match.rotated()),
                        false
                );
                placed = true;
            }
        }
        return placed;
    }

    private static RotatedCoordinate rotateCoordinate(int x, int y, int width, int height, boolean rotated) {
        if (!rotated) {
            return new RotatedCoordinate(x, y);
        }
        return new RotatedCoordinate(height - 1 - y, x);
    }

    private static RotatedCoordinate rotateMarginCoordinate(int x, int y, int width, int height, boolean rotated) {
        if (!rotated) {
            return new RotatedCoordinate(x, y);
        }
        int marginX = x + 1;
        int marginY = y + 1;
        int rotatedMarginX = (height + 1) - marginY;
        int rotatedMarginY = marginX;
        return new RotatedCoordinate(rotatedMarginX - 1, rotatedMarginY - 1);
    }

    private static int rotateOrientation(int orientation, boolean rotated) {
        return rotated ? ((orientation + 1) & 3) : (orientation & 3);
    }

    private static void placeInteriorContents(
            MapRegion mapRegion,
            SceneGraph sceneGraph,
            SettlementPlan settlementPlan,
            SettlementBuilding building,
            long seed
    ) {
        if (placeSettlementPieceObjects(mapRegion, sceneGraph, settlementPlan, building)) {
            return;
        }
        CacheBiomeProfileMiner.SettlementProfile profile = settlementPlan.profile();
        if (profile.props == null || profile.props.isEmpty() || building.width() < 3 || building.height() < 3) {
            return;
        }
        List<CacheBiomeProfileMiner.SettlementProp> interiorProps = new ArrayList<>();
        for (CacheBiomeProfileMiner.SettlementProp prop : profile.props) {
            if (prop != null && !isExteriorSettlementProp(prop.id)) {
                interiorProps.add(prop);
            }
        }
        if (interiorProps.isEmpty()) {
            return;
        }

        int minX = Math.min(building.maxX() - 1, building.minX() + 2);
        int maxX = Math.max(minX, building.maxX() - 2);
        int minY = Math.min(building.maxY() - 1, building.minY() + 2);
        int maxY = Math.max(minY, building.maxY() - 2);
        int placements = Math.max(1, Math.min(3, Math.max(1, ((building.width() - 2) * (building.height() - 2)) / 28)));
        SettlementSite site = building.site();
        for (int index = 0; index < placements; index++) {
            int x = minX + (int) Math.floor(settlementLocalHashedUnit(seed ^ 0x4fa1L, site, building.centreX() + index, building.centreY()) * Math.max(1, maxX - minX + 1));
            int y = minY + (int) Math.floor(settlementLocalHashedUnit(seed ^ 0x5ab2L, site, building.centreX(), building.centreY() + index) * Math.max(1, maxY - minY + 1));
            CacheBiomeProfileMiner.SettlementProp prop = pickSettlementProp(interiorProps, settlementLocalHashedUnit(seed ^ 0x6bc3L, site, x + index, y));
            if (prop == null) {
                continue;
            }
            int orientation = (int) Math.floor(settlementLocalHashedUnit(seed ^ 0x7cd4L, site, x, y + index) * 4.0) & 3;
            mapRegion.spawnObjectToWorld(sceneGraph, prop.id, x, y, 0, prop.type, orientation, false);
        }
    }

    private static void paintBuildingFloor(
            MapRegion mapRegion,
            int overlayId,
            SettlementBuilding building,
            int worldWidth,
            int worldHeight
    ) {
        for (int x = Math.max(0, building.minX() + 2); x <= Math.min(worldWidth - 1, building.maxX() - 2); x++) {
            for (int y = Math.max(0, building.minY() + 2); y <= Math.min(worldHeight - 1, building.maxY() - 2); y++) {
                mapRegion.overlays[0][x][y] = (short) overlayId;
                mapRegion.overlayShapes[0][x][y] = FULL_TILE_OVERLAY_SHAPE;
                mapRegion.overlayOrientations[0][x][y] = 0;
            }
        }
    }

    private static int doorwayCoordinate(int min, int max) {
        int safeMin = min + 3;
        int safeMax = max - 3;
        if (safeMin > safeMax) {
            return (min + max) / 2;
        }
        return (safeMin + safeMax) / 2;
    }

    private static int settlementDoorOrientation(SettlementPlan settlementPlan, SettlementBuilding building) {
        if (building != null && building.forcedDoorOrientation() >= 0) {
            return building.forcedDoorOrientation();
        }
        SettlementSite site = building == null ? null : building.site();
        if (site == null) {
            site = settlementPlan.site();
        }
        Integer courtyardOrientation = courtyardDoorOrientation(settlementPlan, building);
        if (courtyardOrientation != null) {
            return courtyardOrientation;
        }

        SettlementBuilding facingTarget = findFacingTarget(settlementPlan, building);
        if (facingTarget != null) {
            int dx = facingTarget.centreX() - building.centreX();
            int dy = facingTarget.centreY() - building.centreY();
            if (dx != 0 || dy != 0) {
                return orientationFromDirection(dx, dy);
            }
        }

        int dx = site.centreX() - building.centreX();
        int dy = site.centreY() - building.centreY();
        if (dx != 0 || dy != 0) {
            return orientationFromDirection(dx, dy);
        }

        int fallbackDx = -site.waterSideX();
        int fallbackDy = -site.waterSideY();
        if (fallbackDx != 0 || fallbackDy != 0) {
            return orientationFromDirection(fallbackDx, fallbackDy);
        }

        return building.width() >= building.height() ? 1 : 0;
    }

    private static Integer courtyardDoorOrientation(SettlementPlan settlementPlan, SettlementBuilding building) {
        if (settlementPlan == null || building == null || settlementPlan.profile() == null
                || !"riverside_compound".equalsIgnoreCase(settlementPlan.profile().layoutStyle)) {
            return null;
        }
        SettlementSite site = building.site() == null ? settlementPlan.site() : building.site();
        boolean horizontalFrontage = hasHorizontalFrontage(site);
        int courtyardCentreX = site.centreX() + (-site.waterSideX()) * 4;
        int courtyardCentreY = site.centreY() + (-site.waterSideY()) * 4;
        if (site.waterSideX() == 0 && site.waterSideY() == 0) {
            courtyardCentreY -= 4;
        }

        int dx = courtyardCentreX - building.centreX();
        int dy = courtyardCentreY - building.centreY();
        if (horizontalFrontage) {
            if (dy != 0) {
                return dy > 0 ? 3 : 1;
            }
        } else if (dx != 0) {
            return dx > 0 ? 0 : 2;
        }

        if (dx != 0 || dy != 0) {
            return orientationFromDirection(dx, dy);
        }
        return null;
    }

    private static SettlementBuilding findFacingTarget(SettlementPlan settlementPlan, SettlementBuilding building) {
        SettlementSite site = building == null || building.site() == null ? settlementPlan.site() : building.site();
        boolean horizontalFrontage = hasHorizontalFrontage(site);
        SettlementBuilding best = null;
        int bestDepthGap = Integer.MAX_VALUE;
        int bestFrontageGap = Integer.MAX_VALUE;
        int bestOverlap = -1;
        long bestDistance = Long.MAX_VALUE;

        for (SettlementBuilding other : settlementPlan.buildings()) {
            if (other == null || other.equals(building) || other.site() != site) {
                continue;
            }

            int overlap = horizontalFrontage
                    ? inclusiveOverlap(building.minX(), building.maxX(), other.minX(), other.maxX())
                    : inclusiveOverlap(building.minY(), building.maxY(), other.minY(), other.maxY());
            if (overlap <= 0) {
                continue;
            }

            int depthGap = horizontalFrontage
                    ? rangeGap(building.minY(), building.maxY(), other.minY(), other.maxY())
                    : rangeGap(building.minX(), building.maxX(), other.minX(), other.maxX());
            if (depthGap <= 0) {
                continue;
            }

            int frontageGap = horizontalFrontage
                    ? rangeGap(building.minX(), building.maxX(), other.minX(), other.maxX())
                    : rangeGap(building.minY(), building.maxY(), other.minY(), other.maxY());
            long distance = squaredDistance(building.centreX(), building.centreY(), other.centreX(), other.centreY());

            if (depthGap < bestDepthGap
                    || (depthGap == bestDepthGap && overlap > bestOverlap)
                    || (depthGap == bestDepthGap && overlap == bestOverlap && frontageGap < bestFrontageGap)
                    || (depthGap == bestDepthGap && overlap == bestOverlap && frontageGap == bestFrontageGap && distance < bestDistance)) {
                best = other;
                bestDepthGap = depthGap;
                bestFrontageGap = frontageGap;
                bestOverlap = overlap;
                bestDistance = distance;
            }
        }

        return best;
    }

    private static boolean hasHorizontalFrontage(SettlementSite site) {
        return site.waterSideY() != 0 || (site.waterSideX() == 0 && site.waterSideY() == 0);
    }

    private static int inclusiveOverlap(int firstMin, int firstMax, int secondMin, int secondMax) {
        return Math.max(0, Math.min(firstMax, secondMax) - Math.max(firstMin, secondMin) + 1);
    }

    private static int rangeGap(int firstMin, int firstMax, int secondMin, int secondMax) {
        if (firstMax < secondMin) {
            return secondMin - firstMax;
        }
        if (secondMax < firstMin) {
            return firstMin - secondMax;
        }
        return 0;
    }

    private static long squaredDistance(int x1, int y1, int x2, int y2) {
        long dx = (long) x2 - x1;
        long dy = (long) y2 - y1;
        return dx * dx + dy * dy;
    }

    private static void stampWallDecor(
            MapRegion mapRegion,
            SceneGraph sceneGraph,
            CacheBiomeProfileMiner.SettlementProfile profile,
            SettlementBuilding building
    ) {
        if (profile.wallDecorId <= 0) {
            return;
        }
        for (int y = building.minY() + 3; y <= building.maxY() - 3; y += 4) {
            mapRegion.spawnObjectToWorld(sceneGraph, profile.wallDecorId, building.minX() + 1, y, 0, resolveWallDecorType(profile), 2, false);
            mapRegion.spawnObjectToWorld(sceneGraph, profile.wallDecorId, building.maxX() - 1, y, 0, resolveWallDecorType(profile), 0, false);
        }
    }

    private static int resolveWallType(CacheBiomeProfileMiner.SettlementProfile profile) {
        return profile == null ? 0 : profile.wallType;
    }

    private static int resolveUpperWallType(CacheBiomeProfileMiner.SettlementProfile profile) {
        if (profile == null) {
            return 0;
        }
        return profile.upperWallId > 0 ? profile.upperWallType : profile.wallType;
    }

    private static int resolveCornerType(CacheBiomeProfileMiner.SettlementProfile profile) {
        return profile != null && profile.cornerType > 0 ? profile.cornerType : 3;
    }

    private static int resolveDoorType(CacheBiomeProfileMiner.SettlementProfile profile) {
        return profile == null ? 0 : profile.doorType;
    }

    private static int resolveWallDecorType(CacheBiomeProfileMiner.SettlementProfile profile) {
        return profile != null && profile.wallDecorType > 0 ? profile.wallDecorType : 4;
    }

    private static int resolveRoofEdgeId(CacheBiomeProfileMiner.SettlementProfile profile) {
        if (profile == null) {
            return 0;
        }
        return profile.roofEdgeId > 0 ? profile.roofEdgeId : profile.roofSlopeId;
    }

    private static int resolveRoofEdgeType(CacheBiomeProfileMiner.SettlementProfile profile) {
        return profile != null && profile.roofEdgeType > 0 ? profile.roofEdgeType : ROOF_SLOPE_SIDE_TYPE;
    }

    private static int resolveRoofCornerId(CacheBiomeProfileMiner.SettlementProfile profile) {
        if (profile == null) {
            return 0;
        }
        if (profile.roofCornerId > 0) {
            return profile.roofCornerId;
        }
        return resolveRoofEdgeId(profile);
    }

    private static int resolveRoofCornerType(CacheBiomeProfileMiner.SettlementProfile profile) {
        return profile != null && profile.roofCornerType > 0 ? profile.roofCornerType : ROOF_SLOPE_CORNER_TYPE;
    }

    private static int resolveRoofInsetCornerId(CacheBiomeProfileMiner.SettlementProfile profile) {
        if (profile == null) {
            return 0;
        }
        if (profile.roofInsetCornerId > 0) {
            return profile.roofInsetCornerId;
        }
        return resolveRoofEdgeId(profile);
    }

    private static int resolveRoofInsetCornerType(CacheBiomeProfileMiner.SettlementProfile profile) {
        return profile != null && profile.roofInsetCornerType > 0 ? profile.roofInsetCornerType : ROOF_SLOPE_INSET_CORNER_TYPE;
    }

    private static int resolveRoofFlatId(CacheBiomeProfileMiner.SettlementProfile profile) {
        return profile == null ? 0 : profile.roofFlatId;
    }

    private static int resolveRoofFlatType(CacheBiomeProfileMiner.SettlementProfile profile) {
        return profile != null && profile.roofFlatType > 0 ? profile.roofFlatType : ROOF_TOP_FLAT_TYPE;
    }

    private static void scatterSettlementProps(
            MapRegion mapRegion,
            SceneGraph sceneGraph,
            SettlementPlan settlementPlan,
            boolean[][] reservedMask,
            int worldWidth,
            int worldHeight,
            long seed
    ) {
        CacheBiomeProfileMiner.SettlementProfile profile = settlementPlan.profile();
        if (profile.props == null || profile.props.isEmpty()) {
            return;
        }
        int minX = settlementPlan.buildings().stream().mapToInt(SettlementBuilding::minX).min().orElse(0) - 3;
        int minY = settlementPlan.buildings().stream().mapToInt(SettlementBuilding::minY).min().orElse(0) - 3;
        int maxX = settlementPlan.buildings().stream().mapToInt(SettlementBuilding::maxX).max().orElse(0) + 3;
        int maxY = settlementPlan.buildings().stream().mapToInt(SettlementBuilding::maxY).max().orElse(0) + 3;

        int propBudget = Math.max(4, Math.min(18, profile.props.size() + settlementPlan.buildings().size() * 2));
        int placed = 0;
        for (int x = Math.max(1, minX); x <= Math.min(worldWidth - 2, maxX) && placed < propBudget; x++) {
            for (int y = Math.max(1, minY); y <= Math.min(worldHeight - 2, maxY) && placed < propBudget; y++) {
                if (!reservedMask[x][y] || hashedUnit(seed ^ 0x8812L, x, y) < 0.76) {
                    continue;
                }
                if (insideAnyBuilding(settlementPlan, x, y)) {
                    continue;
                }
                CacheBiomeProfileMiner.SettlementProp prop = pickSettlementProp(profile.props, hashedUnit(seed ^ 0x9923L, x, y));
                if (prop == null) {
                    continue;
                }
                int orientation = (int) Math.floor(hashedUnit(seed ^ 0xaa34L, x, y) * 4.0) & 3;
                mapRegion.spawnObjectToWorld(sceneGraph, prop.id, x, y, 0, prop.type, orientation, false);
                placed++;
            }
        }
    }

    private static boolean insideAnyBuilding(SettlementPlan settlementPlan, int x, int y) {
        for (SettlementBuilding building : settlementPlan.buildings()) {
            if (x >= building.minX() + 2 && x <= building.maxX() - 2
                    && y >= building.minY() + 2 && y <= building.maxY() - 2) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExteriorSettlementProp(int id) {
        return id == 1396 || id == 2670 || id == 5541;
    }

    private static CacheBiomeProfileMiner.SettlementProp pickSettlementProp(List<CacheBiomeProfileMiner.SettlementProp> props, double pick) {
        double total = 0.0;
        for (CacheBiomeProfileMiner.SettlementProp prop : props) {
            total += Math.max(0.0001, prop.weight);
        }
        double threshold = pick * total;
        double cumulative = 0.0;
        CacheBiomeProfileMiner.SettlementProp last = props.get(props.size() - 1);
        for (CacheBiomeProfileMiner.SettlementProp prop : props) {
            cumulative += Math.max(0.0001, prop.weight);
            if (threshold <= cumulative) {
                return prop;
            }
        }
        return last;
    }

    private static int orientationFromDirection(int dx, int dy) {
        if (Math.abs(dx) >= Math.abs(dy)) {
            return dx >= 0 ? 0 : 2;
        }
        return dy >= 0 ? 3 : 1;
    }

    private static int approximateHeightRange(
            long seed,
            int centreX,
            int centreY,
            CacheBiomeProfileMiner.BiomeProfile profile,
            int baseHeight,
            int relief,
            double ruggedness,
            int radius
    ) {
        boolean aridProfile = isAridProfile(profile);
        boolean profile13Desert = isProfile13DesertProfile(profile);
        int elevationBias = terrainElevationBias(profile);
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int dx = -radius; dx <= radius; dx += 2) {
            for (int dy = -radius; dy <= radius; dy += 2) {
                int sample = sampleHeight(seed, centreX + dx, centreY + dy, baseHeight, relief, ruggedness, aridProfile, profile13Desert, elevationBias);
                min = Math.min(min, sample);
                max = Math.max(max, sample);
            }
        }
        return max - min;
    }

    private static double landClearanceScore(boolean[][] waterMask, int centreX, int centreY, int worldWidth, int worldHeight, int radius) {
        int land = 0;
        int total = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                int x = centreX + dx;
                int y = centreY + dy;
                if (x < 0 || y < 0 || x >= worldWidth || y >= worldHeight) {
                    continue;
                }
                total++;
                if (!waterMask[x][y]) {
                    land++;
                }
            }
        }
        if (total == 0) {
            return 0.0;
        }
        return (land / (double) total) * 6.0;
    }

    private static NearestWater nearestWater(boolean[][] waterMask, int centreX, int centreY, int worldWidth, int worldHeight, int radius) {
        double bestDistance = Double.MAX_VALUE;
        int bestDx = 0;
        int bestDy = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                int x = centreX + dx;
                int y = centreY + dy;
                if (x < 0 || y < 0 || x >= worldWidth || y >= worldHeight || !waterMask[x][y]) {
                    continue;
                }
                double distance = Math.hypot(dx, dy);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestDx = dx;
                    bestDy = dy;
                }
            }
        }
        return bestDistance == Double.MAX_VALUE ? null : new NearestWater(bestDx, bestDy, bestDistance);
    }

    private static int distanceToSettlement(SettlementPlan settlementPlan, int x, int y) {
        int best = Integer.MAX_VALUE;
        for (SettlementBuilding building : settlementPlan.buildings()) {
            int dx = x < building.minX() ? building.minX() - x : (x > building.maxX() ? x - building.maxX() : 0);
            int dy = y < building.minY() ? building.minY() - y : (y > building.maxY() ? y - building.maxY() : 0);
            best = Math.min(best, dx + dy);
        }
        return best;
    }

    private static void renderSettlementThumbnail(
            WritableImage image,
            SettlementPlan settlementPlan,
            int imageWidth,
            int imageHeight,
            int worldWidth,
            int worldHeight,
            int visibleMinX,
            int visibleMinY
    ) {
        PixelWriter writer = image.getPixelWriter();
        Color roofColor = Color.rgb(147, 110, 69);
        Color wallColor = Color.rgb(189, 161, 110);
        Color outline = Color.rgb(87, 63, 37);
        HouseStyleDefinition.Village villageStyle = resolveSettlementVillageStyle(settlementPlan);
        Color pathColor = villageOverlayColor(villageStyle == null ? null : villageStyle.path, Color.rgb(165, 136, 92));
        Color centrepieceColor = villageOverlayColor(villageStyle == null ? null : villageStyle.centrepiece, pathColor.interpolate(Color.WHITE, 0.08));
        for (long pathTile : settlementPlan.pathTiles()) {
            int tileX = decodeTileX(pathTile) - visibleMinX;
            int tileY = decodeTileY(pathTile) - visibleMinY;
            if (tileX < 0 || tileY < 0 || tileX >= worldWidth || tileY >= worldHeight) {
                continue;
            }
            int minPixelX = tileMinPixel(tileX, imageWidth, worldWidth);
            int maxPixelX = tileMaxPixel(tileX, imageWidth, worldWidth);
            int minPixelY = tileMinPixel(tileY, imageHeight, worldHeight);
            int maxPixelY = tileMaxPixel(tileY, imageHeight, worldHeight);
            for (int pixelX = minPixelX; pixelX <= maxPixelX; pixelX++) {
                for (int pixelY = minPixelY; pixelY <= maxPixelY; pixelY++) {
                    writer.setColor(pixelX, pixelY, pathColor);
                }
            }
        }
        for (long centrepieceTile : settlementPlan.centrepieceTiles()) {
            int tileX = decodeTileX(centrepieceTile) - visibleMinX;
            int tileY = decodeTileY(centrepieceTile) - visibleMinY;
            if (tileX < 0 || tileY < 0 || tileX >= worldWidth || tileY >= worldHeight) {
                continue;
            }
            int minPixelX = tileMinPixel(tileX, imageWidth, worldWidth);
            int maxPixelX = tileMaxPixel(tileX, imageWidth, worldWidth);
            int minPixelY = tileMinPixel(tileY, imageHeight, worldHeight);
            int maxPixelY = tileMaxPixel(tileY, imageHeight, worldHeight);
            for (int pixelX = minPixelX; pixelX <= maxPixelX; pixelX++) {
                for (int pixelY = minPixelY; pixelY <= maxPixelY; pixelY++) {
                    writer.setColor(pixelX, pixelY, centrepieceColor);
                }
            }
        }
        for (SettlementBuilding building : settlementPlan.buildings()) {
            renderSettlementBuildingThumbnail(
                    writer,
                    settlementPlan,
                    building,
                    imageWidth,
                    imageHeight,
                    worldWidth,
                    worldHeight,
                    visibleMinX,
                    visibleMinY,
                    roofColor,
                    wallColor,
                    outline
            );
        }
    }

    private static void renderSettlementBuildingThumbnail(
            PixelWriter writer,
            SettlementPlan settlementPlan,
            SettlementBuilding building,
            int imageWidth,
            int imageHeight,
            int worldWidth,
            int worldHeight,
            int visibleMinX,
            int visibleMinY,
            Color roofColor,
            Color wallColor,
            Color outline
    ) {
        Set<Long> footprintTiles = resolveThumbnailFootprintTiles(settlementPlan, building);
        if (footprintTiles.isEmpty()) {
            return;
        }

        Color fillColor = wallColor.interpolate(roofColor, 0.65);
        for (long tileKey : footprintTiles) {
            int tileX = decodeTileX(tileKey) - visibleMinX;
            int tileY = decodeTileY(tileKey) - visibleMinY;
            if (tileX < 0 || tileY < 0 || tileX >= worldWidth || tileY >= worldHeight) {
                continue;
            }
            int minPixelX = tileMinPixel(tileX, imageWidth, worldWidth);
            int maxPixelX = tileMaxPixel(tileX, imageWidth, worldWidth);
            int minPixelY = tileMinPixel(tileY, imageHeight, worldHeight);
            int maxPixelY = tileMaxPixel(tileY, imageHeight, worldHeight);
            if (minPixelX > maxPixelX || minPixelY > maxPixelY) {
                continue;
            }

            for (int pixelX = minPixelX; pixelX <= maxPixelX; pixelX++) {
                for (int pixelY = minPixelY; pixelY <= maxPixelY; pixelY++) {
                    writer.setColor(pixelX, pixelY, fillColor);
                }
            }

            if (!footprintTiles.contains(tileKey(tileX + visibleMinX, tileY + visibleMinY - 1))) {
                drawHorizontalLine(writer, minPixelX, maxPixelX, minPixelY, imageHeight, outline);
            }
            if (!footprintTiles.contains(tileKey(tileX + visibleMinX, tileY + visibleMinY + 1))) {
                drawHorizontalLine(writer, minPixelX, maxPixelX, maxPixelY, imageHeight, outline);
            }
            if (!footprintTiles.contains(tileKey(tileX + visibleMinX - 1, tileY + visibleMinY))) {
                drawVerticalLine(writer, minPixelY, maxPixelY, minPixelX, imageWidth, outline);
            }
            if (!footprintTiles.contains(tileKey(tileX + visibleMinX + 1, tileY + visibleMinY))) {
                drawVerticalLine(writer, minPixelY, maxPixelY, maxPixelX, imageWidth, outline);
            }
        }
    }

    private static Set<Long> resolveThumbnailFootprintTiles(SettlementPlan settlementPlan, SettlementBuilding building) {
        if (building == null) {
            return Set.of();
        }
        if (building.template() != null) {
            Set<Long> rectangle = new LinkedHashSet<>();
            for (int x = building.minX(); x <= building.maxX(); x++) {
                for (int y = building.minY(); y <= building.maxY(); y++) {
                    rectangle.add(tileKey(x, y));
                }
            }
            return rectangle;
        }
        int doorOrientation = settlementDoorOrientation(settlementPlan, building);
        return BuildingGenerator.previewFootprintTiles(
                settlementBuildingStyle(settlementPlan, building),
                building.minX(),
                building.minY(),
                building.maxX(),
                building.maxY(),
                doorOrientation,
                building.footprint() == null ? null : building.footprint().shapeHint,
                building.piece()
        );
    }

    private static int tileMinPixel(int tileCoordinate, int imageSpan, int worldSpan) {
        return Math.max(0, Math.min(imageSpan - 1, (int) Math.floor(tileCoordinate * imageSpan / (double) worldSpan)));
    }

    private static int tileMaxPixel(int tileCoordinate, int imageSpan, int worldSpan) {
        return Math.max(0, Math.min(imageSpan - 1, (int) Math.ceil((tileCoordinate + 1) * imageSpan / (double) worldSpan) - 1));
    }

    private static void drawHorizontalLine(PixelWriter writer, int minX, int maxX, int y, int imageHeight, Color color) {
        if (y < 0 || y >= imageHeight) {
            return;
        }
        for (int x = minX; x <= maxX; x++) {
            writer.setColor(x, y, color);
        }
    }

    private static void drawVerticalLine(PixelWriter writer, int minY, int maxY, int x, int imageWidth, Color color) {
        if (x < 0 || x >= imageWidth) {
            return;
        }
        for (int y = minY; y <= maxY; y++) {
            writer.setColor(x, y, color);
        }
    }

    private static long tileKey(int x, int y) {
        return (((long) x) << 32) ^ (y & 0xffffffffL);
    }

    private static int decodeTileX(long tileKey) {
        return (int) (tileKey >> 32);
    }

    private static int decodeTileY(long tileKey) {
        return (int) tileKey;
    }

    private static int sampleHeight(long seed, int x, int y, int baseHeight, int relief, double ruggedness, boolean aridProfile, boolean profile13Desert, int elevationBias) {
        double warpX = octaveNoise(seed ^ 0x12f1L, x * 0.0052, y * 0.0052, 2, 0.5) * (20.0 + ruggedness * 34.0);
        double warpY = octaveNoise(seed ^ 0x24e1L, x * 0.0052, y * 0.0052, 2, 0.5) * (20.0 + ruggedness * 34.0);
        double continental = normalize(octaveNoise(
                seed ^ 0x06b1L,
                (x - warpX * 0.3) * (0.0010 + ruggedness * 0.00055),
                (y + warpY * 0.3) * (0.0010 + ruggedness * 0.00055),
                3,
                0.55
        ));
        double macro = normalize(octaveNoise(
                seed ^ 0x31a1L,
                (x + warpX) * (0.0018 + ruggedness * 0.0014),
                (y + warpY) * (0.0018 + ruggedness * 0.0014),
                4,
                0.52
        ));
        double hills = normalize(octaveNoise(
                seed ^ 0x72c1L,
                (x - warpY * 0.55) * (0.0046 + ruggedness * 0.0028),
                (y + warpX * 0.55) * (0.0046 + ruggedness * 0.0028),
                3,
                0.55
        ));
        double ridges = 1.0 - Math.abs(octaveNoise(seed ^ 0x18d3L, x * (0.008 + ruggedness * 0.0035), y * (0.008 + ruggedness * 0.0035), 3, 0.58));
        double basins = normalize(octaveNoise(seed ^ 0x49b5L, x * 0.00125, y * 0.00125, 2, 0.55));
        double detail = normalize(octaveNoise(seed ^ 0x5ad7L, x * 0.020, y * 0.020, 2, 0.5));
        double peaks = normalize(octaveNoise(
                seed ^ 0x8ed1L,
                (x + warpX * 0.6) * (0.0092 + ruggedness * 0.0041),
                (y - warpY * 0.6) * (0.0092 + ruggedness * 0.0041),
                4,
                0.58
        ));
        double dunes = normalize(octaveNoise(
                seed ^ 0xb9f1L,
                (x + warpY * 0.24) * (0.014 + ruggedness * 0.0045),
                (y - warpX * 0.24) * (0.014 + ruggedness * 0.0045),
                3,
                0.56
        ));
        double scarps = 1.0 - Math.abs(octaveNoise(
                seed ^ 0xcae3L,
                x * (0.016 + ruggedness * 0.0045),
                y * (0.016 + ruggedness * 0.0045),
                2,
                0.60
        ));
        double hillocks = normalize(octaveNoise(
                seed ^ 0xdf17L,
                (x + warpX * 0.18) * (0.0105 + ruggedness * 0.0040),
                (y - warpY * 0.18) * (0.0105 + ruggedness * 0.0040),
                3,
                0.54
        ));
        double shaped = clamp(continental * 0.22 + macro * 0.25 + hills * 0.21 + ridges * 0.15 + basins * 0.05 + detail * 0.04 + hillocks * 0.08, 0.0, 1.0);
        double contrast = clamp((shaped - 0.5) * (1.26 + ruggedness * 0.32) + 0.5, 0.0, 1.0);
        double peakMask = clamp(macro * 0.50 + ridges * 0.50 - 0.22 + ruggedness * 0.08, 0.0, 1.0);
        double hillMask = clamp(hills * 0.62 + hillocks * 0.38 - 0.18, 0.0, 1.0);
        shaped = clamp(
                contrast
                        + Math.pow(peaks, 1.40) * peakMask * (0.16 + ruggedness * 0.16)
                        + Math.pow(hillocks, 1.18) * hillMask * (0.08 + ruggedness * 0.10),
                0.0,
                1.0
        );
        if (aridProfile) {
            double duneMask = clamp(hills * 0.52 + continental * 0.34 - 0.18, 0.0, 1.0);
            shaped = clamp(
                    contrast * (profile13Desert ? 0.88 : 0.92)
                            + Math.pow(peaks, profile13Desert ? 1.56 : 1.48) * peakMask * (profile13Desert ? 0.22 + ruggedness * 0.22 : 0.18 + ruggedness * 0.18)
                            + Math.pow(dunes, profile13Desert ? 1.30 : 1.22) * duneMask * (profile13Desert ? 0.16 + ruggedness * 0.14 : 0.12 + ruggedness * 0.10)
                            + Math.pow(scarps, profile13Desert ? 1.72 : 1.55) * peakMask * (profile13Desert ? 0.12 + ruggedness * 0.13 : 0.08 + ruggedness * 0.10)
                            + Math.pow(hillocks, profile13Desert ? 1.24 : 1.18) * hillMask * (profile13Desert ? 0.10 + ruggedness * 0.12 : 0.0),
                    0.0,
                    1.0
            );
            shaped = Math.pow(shaped, Math.max(profile13Desert ? 0.26 : 0.38, (profile13Desert ? 0.50 : 0.60) - ruggedness * 0.10));
        } else {
            shaped = Math.pow(shaped, Math.max(0.40, 0.66 - ruggedness * 0.10));
        }
        return quantizeHeight(baseHeight + elevationBias + (int) Math.round(relief * shaped));
    }

    private static double terrainRuggedness(CacheBiomeProfileMiner.BiomeProfile profile) {
        double source = profile == null || profile.metrics == null ? 0.28 : profile.metrics.heightStdDev / 34.0;
        if (isProfile13DesertProfile(profile)) {
            source = Math.max(source + 0.34, 0.86);
        } else
        if (isAridProfile(profile)) {
            source = Math.max(source + 0.16, 0.48);
        }
        return clamp(source, 0.22, isProfile13DesertProfile(profile) ? 1.85 : 1.55);
    }

    private static int terrainBaseHeight() {
        return TILE_HEIGHT_UNIT * 5;
    }

    private static int terrainElevationBias(CacheBiomeProfileMiner.BiomeProfile profile) {
        return supportsMountainFeatures(profile) ? -640 : 0;
    }

    private static int terrainRelief(CacheBiomeProfileMiner.BiomeProfile profile, double ruggedness) {
        double meanSlope = profile == null || profile.metrics == null ? 0.0 : profile.metrics.meanSlope;
        double baseRelief = 128 + ruggedness * 352.0 + meanSlope * 20.0;
        if (isProfile13DesertProfile(profile)) {
            baseRelief = baseRelief * 1.62 + 144.0;
        } else if (isAridProfile(profile)) {
            baseRelief = baseRelief * 1.30 + 56.0;
        }
        return quantizeHeight((int) Math.round(baseRelief));
    }

    private static short chooseUnderlay(PreviewPalette palette, long seed, int x, int y) {
        if (palette.underlays.length == 0) {
            return 1;
        }
        if (palette.underlays.length == 1) {
            return (short) palette.underlays[0];
        }

        double broad = normalize(octaveNoise(seed ^ 0x4c17L, x * 0.0105, y * 0.0105, 3, 0.55));
        double medium = normalize(octaveNoise(seed ^ 0x1d93L, x * 0.022, y * 0.022, 2, 0.5));
        double accent = normalize(octaveNoise(seed ^ 0x6ab1L, x * 0.048, y * 0.048, 1, 0.5));
        double selector;
        if (palette.aridProfile) {
            double dunes = normalize(octaveNoise(seed ^ 0xdcc1L, x * 0.017, y * 0.017, 2, 0.58));
            double scarps = 1.0 - Math.abs(octaveNoise(seed ^ 0xeed1L, x * 0.031, y * 0.031, 2, 0.60));
            selector = clamp(broad * 0.20 + medium * 0.18 + accent * 0.20 + dunes * 0.26 + scarps * 0.16, 0.0, 1.0);
        } else {
            double pockets = normalize(octaveNoise(seed ^ 0x91c3L, x * 0.019, y * 0.019, 2, 0.58));
            selector = clamp(broad * 0.40 + medium * 0.24 + accent * 0.14 + pockets * 0.22, 0.0, 1.0);
        }

        for (int index = 0; index < palette.underlayThresholds.length; index++) {
            if (selector <= palette.underlayThresholds[index]) {
                return (short) palette.underlays[index];
            }
        }
        return (short) palette.underlays[palette.underlays.length - 1];
    }

    private static short chooseProfile13DesertUnderlay(long seed, int x, int y) {
        double broad = normalize(octaveNoise(seed ^ 0x4c17L, x * 0.0085, y * 0.0085, 3, 0.55));
        double dunes = normalize(octaveNoise(seed ^ 0xdcc1L, x * 0.016, y * 0.016, 2, 0.58));
        double accent = normalize(octaveNoise(seed ^ 0x6ab1L, x * 0.040, y * 0.040, 1, 0.5));
        double selector = clamp(broad * 0.56 + dunes * 0.28 + accent * 0.16, 0.0, 1.0);
        return (short) (selector <= 0.76 ? PROFILE13_DESERT_PRIMARY_UNDERLAY : PROFILE13_DESERT_SECONDARY_UNDERLAY);
    }

    private static short resolveDarkerGroundUnderlay(CacheBiomeProfileMiner.BiomeProfile profile, PreviewPalette palette) {
        int explicit = CacheBiomeProfileMiner.resolveDarkerGroundUnderlay(profile);
        if (explicit > 0) {
            return (short) explicit;
        }
        if (palette == null || palette.underlays.length == 0) {
            return 0;
        }
        if (palette.underlays.length == 1) {
            return (short) palette.underlays[0];
        }
        int darkestId = palette.underlays[0];
        double darkestScore = Double.MAX_VALUE;
        for (int underlayId : palette.underlays) {
            Floor floor = FloorDefinitionLoader.getUnderlay(Math.max(0, underlayId - 1));
            if (floor == null) {
                continue;
            }
            int rgb = floor.getRgb();
            double brightness = ((rgb >> 16) & 0xff) * 0.299
                    + ((rgb >> 8) & 0xff) * 0.587
                    + (rgb & 0xff) * 0.114;
            if (brightness < darkestScore) {
                darkestScore = brightness;
                darkestId = underlayId;
            }
        }
        return (short) darkestId;
    }

    private static void flattenWaterBodies(MapRegion mapRegion, boolean[][] waterMask, int worldWidth, int worldHeight) {
        boolean[][] visited = new boolean[worldWidth][worldHeight];
        ArrayDeque<Integer> queue = new ArrayDeque<>();

        for (int startX = 0; startX < worldWidth; startX++) {
            for (int startY = 0; startY < worldHeight; startY++) {
                if (!waterMask[startX][startY] || visited[startX][startY]) {
                    continue;
                }

                List<Integer> componentTiles = new ArrayList<>();
                List<Integer> tileHeights = new ArrayList<>();
                List<Integer> borderLandHeights = new ArrayList<>();
                visited[startX][startY] = true;
                queue.addLast(encode(startX, startY));

                while (!queue.isEmpty()) {
                    int encoded = queue.removeFirst();
                    int tileX = decodeX(encoded);
                    int tileY = decodeY(encoded);
                    componentTiles.add(encoded);
                    tileHeights.add(averageTileHeight(mapRegion, tileX, tileY));

                    collectBorderLandHeights(mapRegion, waterMask, tileX, tileY, worldWidth, worldHeight, borderLandHeights);

                    if (tileX > 0 && waterMask[tileX - 1][tileY] && !visited[tileX - 1][tileY]) {
                        visited[tileX - 1][tileY] = true;
                        queue.addLast(encode(tileX - 1, tileY));
                    }
                    if (tileX + 1 < worldWidth && waterMask[tileX + 1][tileY] && !visited[tileX + 1][tileY]) {
                        visited[tileX + 1][tileY] = true;
                        queue.addLast(encode(tileX + 1, tileY));
                    }
                    if (tileY > 0 && waterMask[tileX][tileY - 1] && !visited[tileX][tileY - 1]) {
                        visited[tileX][tileY - 1] = true;
                        queue.addLast(encode(tileX, tileY - 1));
                    }
                    if (tileY + 1 < worldHeight && waterMask[tileX][tileY + 1] && !visited[tileX][tileY + 1]) {
                        visited[tileX][tileY + 1] = true;
                        queue.addLast(encode(tileX, tileY + 1));
                    }
                }

                if (componentTiles.isEmpty()) {
                    continue;
                }

                tileHeights.sort(Integer::compareTo);
                borderLandHeights.sort(Integer::compareTo);
                int percentileIndex = Math.max(0, Math.min(tileHeights.size() - 1, (int) Math.floor(tileHeights.size() * 0.12)));
                int baseFloor = tileHeights.get(percentileIndex);
                int borderReference = borderLandHeights.isEmpty()
                        ? baseFloor + TILE_HEIGHT_UNIT * 4
                        : borderLandHeights.get(Math.max(0, Math.min(borderLandHeights.size() - 1, borderLandHeights.size() / 3)));
                int sizeBonus = Math.min(TILE_HEIGHT_UNIT * 4, quantizeHeight((int) Math.round(Math.sqrt(componentTiles.size()) * 1.8)));
                int desiredFloor = Math.min(baseFloor - TILE_HEIGHT_UNIT * 2, borderReference - (TILE_HEIGHT_UNIT * 3 + sizeBonus));
                int targetFloor = quantizeHeight(Math.max(TILE_HEIGHT_UNIT, desiredFloor));
                for (int encoded : componentTiles) {
                    setVertexHeight(mapRegion, decodeX(encoded), decodeY(encoded), targetFloor, worldWidth, worldHeight);
                }
            }
        }
    }

    private static void sculptWaterBanks(MapRegion mapRegion, boolean[][] waterMask, int worldWidth, int worldHeight) {
        double[][] desiredHeightSum = new double[worldWidth + 1][worldHeight + 1];
        double[][] desiredWeightSum = new double[worldWidth + 1][worldHeight + 1];

        for (int x = 0; x < worldWidth; x++) {
            for (int y = 0; y < worldHeight; y++) {
                if (!waterMask[x][y]) {
                    continue;
                }

                int waterHeight = averageTileHeight(mapRegion, x, y);
                for (int vertexX = Math.max(0, x - 4); vertexX <= Math.min(worldWidth, x + 5); vertexX++) {
                    for (int vertexY = Math.max(0, y - 4); vertexY <= Math.min(worldHeight, y + 5); vertexY++) {
                        if (vertexTouchesWater(waterMask, vertexX, vertexY, worldWidth, worldHeight)) {
                            continue;
                        }
                        double dx = vertexX - (x + 0.5);
                        double dy = vertexY - (y + 0.5);
                        double distance = Math.hypot(dx, dy);
                        if (distance < 0.90 || distance > 4.25) {
                            continue;
                        }
                        double desiredHeight = waterHeight + 7.0 + distance * 13.5 + distance * distance * 1.05;
                        double weight = 1.0 / (1.15 + distance * distance);
                        desiredHeightSum[vertexX][vertexY] += desiredHeight * weight;
                        desiredWeightSum[vertexX][vertexY] += weight;
                    }
                }
            }
        }

        for (int vertexX = 0; vertexX <= worldWidth; vertexX++) {
            for (int vertexY = 0; vertexY <= worldHeight; vertexY++) {
                if (desiredWeightSum[vertexX][vertexY] <= 0.0 || vertexTouchesWater(waterMask, vertexX, vertexY, worldWidth, worldHeight)) {
                    continue;
                }
                int currentHeight = Math.abs(mapRegion.tileHeights[0][vertexX][vertexY]);
                int targetHeight = quantizeHeight((int) Math.round(desiredHeightSum[vertexX][vertexY] / desiredWeightSum[vertexX][vertexY]));
                int blended = quantizeHeight((int) Math.round(currentHeight * 0.66 + targetHeight * 0.34));
                mapRegion.tileHeights[0][vertexX][vertexY] = -blended;
            }
        }
    }

    private static void collectBorderLandHeights(
            MapRegion mapRegion,
            boolean[][] waterMask,
            int tileX,
            int tileY,
            int worldWidth,
            int worldHeight,
            List<Integer> borderLandHeights
    ) {
        if (tileX > 0 && !waterMask[tileX - 1][tileY]) {
            borderLandHeights.add(averageTileHeight(mapRegion, tileX - 1, tileY));
        }
        if (tileX + 1 < worldWidth && !waterMask[tileX + 1][tileY]) {
            borderLandHeights.add(averageTileHeight(mapRegion, tileX + 1, tileY));
        }
        if (tileY > 0 && !waterMask[tileX][tileY - 1]) {
            borderLandHeights.add(averageTileHeight(mapRegion, tileX, tileY - 1));
        }
        if (tileY + 1 < worldHeight && !waterMask[tileX][tileY + 1]) {
            borderLandHeights.add(averageTileHeight(mapRegion, tileX, tileY + 1));
        }
    }

    private static int averageTileHeight(MapRegion mapRegion, int tileX, int tileY) {
        int southWest = Math.abs(mapRegion.tileHeights[0][tileX][tileY]);
        int southEast = Math.abs(mapRegion.tileHeights[0][tileX + 1][tileY]);
        int northWest = Math.abs(mapRegion.tileHeights[0][tileX][tileY + 1]);
        int northEast = Math.abs(mapRegion.tileHeights[0][tileX + 1][tileY + 1]);
        return (southWest + southEast + northWest + northEast) / 4;
    }

    private static int percentile(List<Integer> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return TILE_HEIGHT_UNIT;
        }
        values.sort(Integer::compareTo);
        int index = Math.max(0, Math.min(values.size() - 1, (int) Math.floor((values.size() - 1) * percentile)));
        return values.get(index);
    }

    private static boolean vertexTouchesWater(boolean[][] waterMask, int vertexX, int vertexY, int worldWidth, int worldHeight) {
        for (int tileX = vertexX - 1; tileX <= vertexX; tileX++) {
            for (int tileY = vertexY - 1; tileY <= vertexY; tileY++) {
                if (tileX >= 0 && tileY >= 0 && tileX < worldWidth && tileY < worldHeight && waterMask[tileX][tileY]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void setVertexHeight(MapRegion mapRegion, int tileX, int tileY, int height, int worldWidth, int worldHeight) {
        int quantized = quantizeHeight(height);
        int maxX = Math.min(worldWidth, tileX + 1);
        int maxY = Math.min(worldHeight, tileY + 1);
        for (int x = tileX; x <= maxX; x++) {
            for (int y = tileY; y <= maxY; y++) {
                mapRegion.tileHeights[0][x][y] = -quantized;
            }
        }
    }

    private static List<LakeFeature> planLakes(double waterRatio, int worldWidth, int worldHeight, long seed) {
        List<LakeFeature> lakes = new ArrayList<>();
        int maxLakes = Math.max(1, Math.min(4, (worldWidth * worldHeight) / (REGION_SIZE * REGION_SIZE * 4)));
        int lakeCount = waterRatio < 0.04 ? 1 : waterRatio < 0.09 ? Math.min(2, maxLakes) : Math.min(3, maxLakes);
        int marginX = Math.max(18, worldWidth / 10);
        int marginY = Math.max(18, worldHeight / 10);

        for (int index = 0; index < lakeCount; index++) {
            double centreX = marginX + hashedUnit(seed ^ 0x61abL, index, 0) * Math.max(8.0, worldWidth - marginX * 2.0);
            double centreY = marginY + hashedUnit(seed ^ 0x71cdL, index, 1) * Math.max(8.0, worldHeight - marginY * 2.0);
            double radiusX = 8.0 + waterRatio * 48.0 + hashedUnit(seed ^ 0x18b7L, index, 2) * (8.0 + waterRatio * 24.0);
            double radiusY = 7.0 + waterRatio * 42.0 + hashedUnit(seed ^ 0x2fd1L, index, 3) * (8.0 + waterRatio * 20.0);
            double rotation = hashedUnit(seed ^ 0x84f1L, index, 4) * Math.PI;
            lakes.add(new LakeFeature(centreX, centreY, radiusX, radiusY, rotation));
        }
        return lakes;
    }

    private static int riverCountFor(double waterRatio, int worldWidth, int worldHeight) {
        if (waterRatio < 0.035) {
            return 0;
        }
        if (waterRatio < 0.11) {
            return 1;
        }
        return worldWidth >= REGION_SIZE * 4 || worldHeight >= REGION_SIZE * 4 ? 2 : 1;
    }

    private static void paintLake(
            boolean[][] waterMask,
            boolean[][] roadMask,
            LakeFeature lake,
            int worldWidth,
            int worldHeight,
            long seed
    ) {
        int minX = Math.max(1, (int) Math.floor(lake.centreX - lake.radiusX - 4.0));
        int maxX = Math.min(worldWidth - 2, (int) Math.ceil(lake.centreX + lake.radiusX + 4.0));
        int minY = Math.max(1, (int) Math.floor(lake.centreY - lake.radiusY - 4.0));
        int maxY = Math.min(worldHeight - 2, (int) Math.ceil(lake.centreY + lake.radiusY + 4.0));
        double cos = Math.cos(lake.rotation);
        double sin = Math.sin(lake.rotation);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                if (roadMask[x][y]) {
                    continue;
                }
                double localX = x - lake.centreX;
                double localY = y - lake.centreY;
                double rotatedX = localX * cos + localY * sin;
                double rotatedY = localY * cos - localX * sin;
                double ellipse = (rotatedX * rotatedX) / (lake.radiusX * lake.radiusX)
                        + (rotatedY * rotatedY) / (lake.radiusY * lake.radiusY);
                double shorelineWarp = octaveNoise(seed ^ 0x93b1L, x * 0.08, y * 0.08, 2, 0.55) * 0.14;
                if (ellipse <= 1.0 + shorelineWarp) {
                    waterMask[x][y] = true;
                }
            }
        }
    }

    private static void paintRiver(
            boolean[][] waterMask,
            boolean[][] roadMask,
            RiverFeature river,
            int worldWidth,
            int worldHeight
    ) {
        double deltaX = river.endX() - river.startX();
        double deltaY = river.endY() - river.startY();
        double length = Math.max(1.0, Math.hypot(deltaX, deltaY));
        double normalX = -deltaY / length;
        double normalY = deltaX / length;
        int steps = Math.max(24, (int) Math.ceil(length * 1.15));
        double control1T = 0.18 + hashedUnit(river.noiseSeed() ^ 0x71f1L, 0, 1) * 0.22;
        double control2T = 0.62 + hashedUnit(river.noiseSeed() ^ 0x82e1L, 0, 2) * 0.20;
        double control1Offset = signedPick(river.noiseSeed() ^ 0x93d1L, 0, 3, 0.45, 1.05) * river.meanderAmplitude();
        double control2Offset = signedPick(river.noiseSeed() ^ 0xa4c1L, 0, 4, 0.45, 1.05) * river.meanderAmplitude();
        double control1X = river.startX() + deltaX * control1T + normalX * control1Offset;
        double control1Y = river.startY() + deltaY * control1T + normalY * control1Offset;
        double control2X = river.startX() + deltaX * control2T + normalX * control2Offset;
        double control2Y = river.startY() + deltaY * control2T + normalY * control2Offset;

        for (int step = 0; step <= steps; step++) {
            double t = step / (double) steps;
            double alongX = cubicBezier(river.startX(), control1X, control2X, river.endX(), t);
            double alongY = cubicBezier(river.startY(), control1Y, control2Y, river.endY(), t);
            double envelope = 0.28 + Math.sin(Math.PI * t) * 0.72;
            double fineMeander = octaveNoise(river.noiseSeed(), t * river.meanderFrequency(), river.noiseSeed() * 0.00017, 3, 0.55)
                    * river.meanderAmplitude() * 0.34 * envelope;
            double coarseMeander = octaveNoise(river.noiseSeed() ^ 0xb5b1L, t * (river.meanderFrequency() * 0.42), 0.37, 2, 0.6)
                    * river.meanderAmplitude() * 0.18 * envelope;
            double meander = fineMeander + coarseMeander;
            int centreX = (int) Math.round(alongX + normalX * meander);
            int centreY = (int) Math.round(alongY + normalY * meander);
            double widthNoise = normalize(octaveNoise(river.noiseSeed() ^ 0xc6a1L, t * 3.8, 0.11, 2, 0.55));
            double widthEnvelope = 0.74 + Math.sin(Math.PI * t) * 0.26;
            int radius = Math.max(1, (int) Math.round(river.width() * widthEnvelope * (0.80 + widthNoise * 0.55)));
            paintWaterStroke(waterMask, roadMask, centreX, centreY, radius, worldWidth, worldHeight);
        }
    }

    private static double signedPick(long seed, int x, int y, double minMagnitude, double maxMagnitude) {
        double magnitude = pick(seed, x, y, minMagnitude, maxMagnitude);
        return hashedUnit(seed ^ 0xd791L, x + 17, y + 19) > 0.5 ? magnitude : -magnitude;
    }

    private static double cubicBezier(double p0, double p1, double p2, double p3, double t) {
        double oneMinusT = 1.0 - t;
        return oneMinusT * oneMinusT * oneMinusT * p0
                + 3.0 * oneMinusT * oneMinusT * t * p1
                + 3.0 * oneMinusT * t * t * p2
                + t * t * t * p3;
    }

    private static void paintWaterStroke(
            boolean[][] waterMask,
            boolean[][] roadMask,
            int centreX,
            int centreY,
            int radius,
            int worldWidth,
            int worldHeight
    ) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                if ((dx * dx) + (dy * dy) > radius * radius + radius) {
                    continue;
                }
                int x = centreX + dx;
                int y = centreY + dy;
                if (x <= 0 || y <= 0 || x >= worldWidth - 1 || y >= worldHeight - 1 || roadMask[x][y]) {
                    continue;
                }
                waterMask[x][y] = true;
            }
        }
    }

    private static void paintRoadStroke(boolean[][] roadMask, int centreX, int centreY, int width, int worldWidth, int worldHeight) {
        for (int dx = -width; dx <= width; dx++) {
            for (int dy = -width; dy <= width; dy++) {
                int x = centreX + dx;
                int y = centreY + dy;
                if (x > 0 && y > 0 && x < worldWidth && y < worldHeight) {
                    roadMask[x][y] = true;
                }
            }
        }
    }

    private static void smoothWaterMask(boolean[][] waterMask, boolean[][] roadMask, int worldWidth, int worldHeight) {
        boolean[][] snapshot = new boolean[worldWidth][worldHeight];
        for (int x = 0; x < worldWidth; x++) {
            System.arraycopy(waterMask[x], 0, snapshot[x], 0, worldHeight);
        }

        for (int x = 1; x < worldWidth - 1; x++) {
            for (int y = 1; y < worldHeight - 1; y++) {
                if (roadMask[x][y]) {
                    waterMask[x][y] = false;
                    continue;
                }
                int neighbours = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (dx == 0 && dy == 0) {
                            continue;
                        }
                        if (snapshot[x + dx][y + dy]) {
                            neighbours++;
                        }
                    }
                }
                if (snapshot[x][y] && neighbours <= 1) {
                    waterMask[x][y] = false;
                } else if (!snapshot[x][y] && neighbours >= 6) {
                    waterMask[x][y] = true;
                }
            }
        }
    }

    private static void roundWaterCorners(boolean[][] waterMask, boolean[][] roadMask, int worldWidth, int worldHeight) {
        boolean[][] snapshot = new boolean[worldWidth][worldHeight];
        for (int x = 0; x < worldWidth; x++) {
            System.arraycopy(waterMask[x], 0, snapshot[x], 0, worldHeight);
        }

        for (int x = 1; x < worldWidth - 2; x++) {
            for (int y = 1; y < worldHeight - 2; y++) {
                boolean northWest = snapshot[x][y];
                boolean northEast = snapshot[x + 1][y];
                boolean southWest = snapshot[x][y + 1];
                boolean southEast = snapshot[x + 1][y + 1];
                int waterCount = (northWest ? 1 : 0)
                        + (northEast ? 1 : 0)
                        + (southWest ? 1 : 0)
                        + (southEast ? 1 : 0);

                if (waterCount == 3) {
                    if (!northWest && !roadMask[x][y] && cornerFillScore(snapshot, x, y, worldWidth, worldHeight) >= 4) {
                        waterMask[x][y] = true;
                    } else if (!northEast && !roadMask[x + 1][y] && cornerFillScore(snapshot, x + 1, y, worldWidth, worldHeight) >= 4) {
                        waterMask[x + 1][y] = true;
                    } else if (!southWest && !roadMask[x][y + 1] && cornerFillScore(snapshot, x, y + 1, worldWidth, worldHeight) >= 4) {
                        waterMask[x][y + 1] = true;
                    } else if (!southEast && !roadMask[x + 1][y + 1] && cornerFillScore(snapshot, x + 1, y + 1, worldWidth, worldHeight) >= 4) {
                        waterMask[x + 1][y + 1] = true;
                    }
                    continue;
                }

                if (waterCount == 1) {
                    if (northWest && !roadMask[x][y] && cornerFillScore(snapshot, x, y, worldWidth, worldHeight) <= 1) {
                        waterMask[x][y] = false;
                    } else if (northEast && !roadMask[x + 1][y] && cornerFillScore(snapshot, x + 1, y, worldWidth, worldHeight) <= 1) {
                        waterMask[x + 1][y] = false;
                    } else if (southWest && !roadMask[x][y + 1] && cornerFillScore(snapshot, x, y + 1, worldWidth, worldHeight) <= 1) {
                        waterMask[x][y + 1] = false;
                    } else if (southEast && !roadMask[x + 1][y + 1] && cornerFillScore(snapshot, x + 1, y + 1, worldWidth, worldHeight) <= 1) {
                        waterMask[x + 1][y + 1] = false;
                    }
                    continue;
                }

                if (waterCount != 2) {
                    continue;
                }

                if (northWest && southEast && !northEast && !southWest) {
                    int northEastScore = cornerFillScore(snapshot, x + 1, y, worldWidth, worldHeight);
                    int southWestScore = cornerFillScore(snapshot, x, y + 1, worldWidth, worldHeight);
                    if (northEastScore >= southWestScore && !roadMask[x + 1][y]) {
                        waterMask[x + 1][y] = true;
                    } else if (!roadMask[x][y + 1]) {
                        waterMask[x][y + 1] = true;
                    }
                } else if (northEast && southWest && !northWest && !southEast) {
                    int northWestScore = cornerFillScore(snapshot, x, y, worldWidth, worldHeight);
                    int southEastScore = cornerFillScore(snapshot, x + 1, y + 1, worldWidth, worldHeight);
                    if (northWestScore >= southEastScore && !roadMask[x][y]) {
                        waterMask[x][y] = true;
                    } else if (!roadMask[x + 1][y + 1]) {
                        waterMask[x + 1][y + 1] = true;
                    }
                }
            }
        }
    }

    private static int cornerFillScore(boolean[][] snapshot, int tileX, int tileY, int worldWidth, int worldHeight) {
        int score = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                int sampleX = tileX + dx;
                int sampleY = tileY + dy;
                if (sampleX >= 0 && sampleY >= 0 && sampleX < worldWidth && sampleY < worldHeight && snapshot[sampleX][sampleY]) {
                    score++;
                }
            }
        }
        return score;
    }

    private static void clearWaterAtRoads(boolean[][] waterMask, boolean[][] roadMask, int worldWidth, int worldHeight, int padding) {
        for (int x = 0; x < worldWidth; x++) {
            for (int y = 0; y < worldHeight; y++) {
                if (!roadMask[x][y]) {
                    continue;
                }
                for (int dx = -padding; dx <= padding; dx++) {
                    for (int dy = -padding; dy <= padding; dy++) {
                        int sampleX = x + dx;
                        int sampleY = y + dy;
                        if (sampleX >= 0 && sampleY >= 0 && sampleX < worldWidth && sampleY < worldHeight) {
                            waterMask[sampleX][sampleY] = false;
                        }
                    }
                }
            }
        }
    }

    private static void ensureLandConnectivity(boolean[][] waterMask, boolean[][] roadMask, int worldWidth, int worldHeight) {
        for (int pass = 0; pass < 3; pass++) {
            List<LandComponent> components = collectLandComponents(waterMask, worldWidth, worldHeight);
            if (components.size() <= 1) {
                return;
            }

            LandComponent main = components.get(0);
            for (int index = 1; index < components.size(); index++) {
                LandComponent component = components.get(index);
                carveLandCorridor(
                        waterMask,
                        component.representativeX,
                        component.representativeY,
                        main.representativeX,
                        main.representativeY,
                        2,
                        worldWidth,
                        worldHeight
                );
            }
            clearWaterAtRoads(waterMask, roadMask, worldWidth, worldHeight, 1);
        }
    }

    private static List<LandComponent> collectLandComponents(boolean[][] waterMask, int worldWidth, int worldHeight) {
        boolean[][] visited = new boolean[worldWidth][worldHeight];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        List<LandComponent> components = new ArrayList<>();

        for (int x = 0; x < worldWidth; x++) {
            for (int y = 0; y < worldHeight; y++) {
                if (waterMask[x][y] || visited[x][y]) {
                    continue;
                }

                int size = 0;
                int sumX = 0;
                int sumY = 0;
                int representativeX = x;
                int representativeY = y;
                visited[x][y] = true;
                queue.addLast(encode(x, y));

                while (!queue.isEmpty()) {
                    int encoded = queue.removeFirst();
                    int tileX = decodeX(encoded);
                    int tileY = decodeY(encoded);
                    size++;
                    sumX += tileX;
                    sumY += tileY;

                    if (tileX > 0 && !waterMask[tileX - 1][tileY] && !visited[tileX - 1][tileY]) {
                        visited[tileX - 1][tileY] = true;
                        queue.addLast(encode(tileX - 1, tileY));
                    }
                    if (tileX + 1 < worldWidth && !waterMask[tileX + 1][tileY] && !visited[tileX + 1][tileY]) {
                        visited[tileX + 1][tileY] = true;
                        queue.addLast(encode(tileX + 1, tileY));
                    }
                    if (tileY > 0 && !waterMask[tileX][tileY - 1] && !visited[tileX][tileY - 1]) {
                        visited[tileX][tileY - 1] = true;
                        queue.addLast(encode(tileX, tileY - 1));
                    }
                    if (tileY + 1 < worldHeight && !waterMask[tileX][tileY + 1] && !visited[tileX][tileY + 1]) {
                        visited[tileX][tileY + 1] = true;
                        queue.addLast(encode(tileX, tileY + 1));
                    }
                }

                components.add(new LandComponent(
                        representativeX,
                        representativeY,
                        size,
                        size == 0 ? representativeX : sumX / size,
                        size == 0 ? representativeY : sumY / size
                ));
            }
        }

        components.sort((left, right) -> Integer.compare(right.size, left.size));
        return components;
    }

    private static void carveLandCorridor(
            boolean[][] waterMask,
            int startX,
            int startY,
            int endX,
            int endY,
            int radius,
            int worldWidth,
            int worldHeight
    ) {
        int steps = Math.max(Math.abs(endX - startX), Math.abs(endY - startY));
        if (steps == 0) {
            clearWaterDisc(waterMask, startX, startY, radius, worldWidth, worldHeight);
            return;
        }
        for (int step = 0; step <= steps; step++) {
            double t = step / (double) steps;
            int x = (int) Math.round(startX + (endX - startX) * t);
            int y = (int) Math.round(startY + (endY - startY) * t);
            clearWaterDisc(waterMask, x, y, radius, worldWidth, worldHeight);
        }
    }

    private static void clearWaterDisc(boolean[][] waterMask, int centreX, int centreY, int radius, int worldWidth, int worldHeight) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                if ((dx * dx) + (dy * dy) > radius * radius + radius) {
                    continue;
                }
                int x = centreX + dx;
                int y = centreY + dy;
                if (x >= 0 && y >= 0 && x < worldWidth && y < worldHeight) {
                    waterMask[x][y] = false;
                }
            }
        }
    }

    private static OverlayTileShape resolveWaterOverlayShape(boolean[][] waterMask, int x, int y, int worldWidth, int worldHeight) {
        int[][] localWater = new int[3][3];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                int sampleX = x + column - 1;
                int sampleY = y + 1 - row;
                localWater[row][column] = sampleX >= 0
                        && sampleY >= 0
                        && sampleX < worldWidth
                        && sampleY < worldHeight
                        && waterMask[sampleX][sampleY]
                        ? WATER_CELL
                        : GROUND_CELL;
            }
        }

        if (matches(localWater, new int[][]{{GROUND_CELL, WATER_CELL, WATER_CELL}, {GROUND_CELL, WATER_CELL, WATER_CELL}, {GROUND_CELL, GROUND_CELL, GROUND_CELL}})) {
            return new OverlayTileShape((byte) 1, (byte) 2);
        }
        if (matches(localWater, new int[][]{{WATER_CELL, GROUND_CELL, GROUND_CELL}, {WATER_CELL, WATER_CELL, GROUND_CELL}, {GROUND_CELL, WATER_CELL, WATER_CELL}})) {
            return new OverlayTileShape((byte) 10, (byte) 1);
        }
        if (matches(localWater, new int[][]{{WATER_CELL, WATER_CELL, GROUND_CELL}, {GROUND_CELL, WATER_CELL, WATER_CELL}, {GROUND_CELL, GROUND_CELL, GROUND_CELL}})) {
            return new OverlayTileShape((byte) 10, (byte) 3);
        }
        if (matches(localWater, new int[][]{{GROUND_CELL, GROUND_CELL, WATER_CELL}, {GROUND_CELL, WATER_CELL, WATER_CELL}, {GROUND_CELL, WATER_CELL, WATER_CELL}})) {
            return new OverlayTileShape((byte) 10, (byte) 0);
        }
        if (matches(localWater, new int[][]{{GROUND_CELL, WATER_CELL, WATER_CELL}, {GROUND_CELL, WATER_CELL, WATER_CELL}, {GROUND_CELL, GROUND_CELL, WATER_CELL}})) {
            return new OverlayTileShape((byte) 1, (byte) 2);
        }
        if (matches(localWater, new int[][]{{WATER_CELL, WATER_CELL, WATER_CELL}, {GROUND_CELL, WATER_CELL, WATER_CELL}, {GROUND_CELL, GROUND_CELL, WATER_CELL}})) {
            return new OverlayTileShape((byte) 1, (byte) 2);
        }
        if (matches(localWater, new int[][]{{WATER_CELL, WATER_CELL, WATER_CELL}, {WATER_CELL, WATER_CELL, GROUND_CELL}, {GROUND_CELL, GROUND_CELL, GROUND_CELL}})) {
            return new OverlayTileShape((byte) 1, (byte) 1);
        }
        if (matches(localWater, new int[][]{{WATER_CELL, WATER_CELL, WATER_CELL}, {WATER_CELL, WATER_CELL, GROUND_CELL}, {WATER_CELL, GROUND_CELL, GROUND_CELL}})) {
            return new OverlayTileShape((byte) 1, (byte) 1);
        }
        if (matches(localWater, new int[][]{{GROUND_CELL, GROUND_CELL, GROUND_CELL}, {GROUND_CELL, WATER_CELL, GROUND_CELL}, {GROUND_CELL, WATER_CELL, WATER_CELL}})) {
            return new OverlayTileShape((byte) 11, (byte) 0);
        }
        if (matches(localWater, new int[][]{{GROUND_CELL, WATER_CELL, GROUND_CELL}, {GROUND_CELL, WATER_CELL, GROUND_CELL}, {GROUND_CELL, GROUND_CELL, GROUND_CELL}})) {
            return new OverlayTileShape((byte) 11, (byte) 2);
        }
        if (matches(localWater, new int[][]{{GROUND_CELL, GROUND_CELL, GROUND_CELL}, {GROUND_CELL, WATER_CELL, GROUND_CELL}, {GROUND_CELL, WATER_CELL, GROUND_CELL}})) {
            return new OverlayTileShape((byte) 11, (byte) 0);
        }
        if (matches(localWater, new int[][]{{WATER_CELL, WATER_CELL, GROUND_CELL}, {WATER_CELL, WATER_CELL, GROUND_CELL}, {GROUND_CELL, GROUND_CELL, GROUND_CELL}})) {
            return new OverlayTileShape((byte) 8, (byte) 3);
        }
        if (matches(localWater, new int[][]{{WATER_CELL, GROUND_CELL, GROUND_CELL}, {WATER_CELL, WATER_CELL, GROUND_CELL}, {WATER_CELL, WATER_CELL, GROUND_CELL}})) {
            return new OverlayTileShape((byte) 1, (byte) 0);
        }
        if (matches(localWater, new int[][]{{WATER_CELL, GROUND_CELL, GROUND_CELL}, {WATER_CELL, WATER_CELL, GROUND_CELL}, {WATER_CELL, WATER_CELL, WATER_CELL}})) {
            return new OverlayTileShape((byte) 1, (byte) 0);
        }
        if (matches(localWater, new int[][]{{WATER_CELL, WATER_CELL, GROUND_CELL}, {WATER_CELL, WATER_CELL, GROUND_CELL}, {WATER_CELL, GROUND_CELL, GROUND_CELL}})) {
            return new OverlayTileShape((byte) 1, (byte) 1);
        }
        if (matches(localWater, new int[][]{{GROUND_CELL, GROUND_CELL, GROUND_CELL}, {WATER_CELL, WATER_CELL, GROUND_CELL}, {WATER_CELL, WATER_CELL, WATER_CELL}})) {
            return new OverlayTileShape((byte) 10, (byte) 1);
        }
        if (matches(localWater, new int[][]{{GROUND_CELL, GROUND_CELL, GROUND_CELL}, {WATER_CELL, WATER_CELL, GROUND_CELL}, {WATER_CELL, WATER_CELL, GROUND_CELL}})) {
            return new OverlayTileShape((byte) 10, (byte) 0);
        }
        if (matches(localWater, new int[][]{{WATER_CELL, WATER_CELL, WATER_CELL}, {GROUND_CELL, WATER_CELL, WATER_CELL}, {GROUND_CELL, GROUND_CELL, GROUND_CELL}})) {
            return new OverlayTileShape((byte) 10, (byte) 3);
        }
        if (matches(localWater, new int[][]{{GROUND_CELL, GROUND_CELL, GROUND_CELL}, {GROUND_CELL, WATER_CELL, WATER_CELL}, {GROUND_CELL, WATER_CELL, WATER_CELL}})) {
            return new OverlayTileShape((byte) 10, (byte) 0);
        }
        if (matches(localWater, new int[][]{{GROUND_CELL, GROUND_CELL, GROUND_CELL}, {GROUND_CELL, WATER_CELL, GROUND_CELL}, {WATER_CELL, WATER_CELL, GROUND_CELL}})) {
            return new OverlayTileShape((byte) 11, (byte) 0);
        }
        if (matches(localWater, new int[][]{{GROUND_CELL, WATER_CELL, WATER_CELL}, {GROUND_CELL, WATER_CELL, GROUND_CELL}, {GROUND_CELL, GROUND_CELL, GROUND_CELL}})) {
            return new OverlayTileShape((byte) 11, (byte) 2);
        }
        if (matches(localWater, new int[][]{{WATER_CELL, GROUND_CELL, GROUND_CELL}, {WATER_CELL, WATER_CELL, GROUND_CELL}, {GROUND_CELL, GROUND_CELL, GROUND_CELL}})) {
            return new OverlayTileShape((byte) 11, (byte) 1);
        }
        if (matches(localWater, new int[][]{{GROUND_CELL, GROUND_CELL, GROUND_CELL}, {WATER_CELL, WATER_CELL, GROUND_CELL}, {GROUND_CELL, GROUND_CELL, GROUND_CELL}})) {
            return new OverlayTileShape((byte) 11, (byte) 1);
        }
        if (matches(localWater, new int[][]{{GROUND_CELL, GROUND_CELL, WATER_CELL}, {GROUND_CELL, WATER_CELL, WATER_CELL}, {GROUND_CELL, GROUND_CELL, GROUND_CELL}})) {
            return new OverlayTileShape((byte) 11, (byte) 3);
        }
        if (matches(localWater, new int[][]{{GROUND_CELL, GROUND_CELL, WATER_CELL}, {GROUND_CELL, WATER_CELL, WATER_CELL}, {WATER_CELL, WATER_CELL, WATER_CELL}})) {
            return new OverlayTileShape((byte) 1, (byte) 3);
        }
        if (matches(localWater, new int[][]{{GROUND_CELL, GROUND_CELL, GROUND_CELL}, {GROUND_CELL, WATER_CELL, WATER_CELL}, {WATER_CELL, WATER_CELL, WATER_CELL}})) {
            return new OverlayTileShape((byte) 1, (byte) 3);
        }
        if (matches(localWater, new int[][]{{GROUND_CELL, GROUND_CELL, GROUND_CELL}, {GROUND_CELL, WATER_CELL, GROUND_CELL}, {WATER_CELL, WATER_CELL, WATER_CELL}})) {
            return new OverlayTileShape((byte) 11, (byte) 0);
        }
        if (matches(localWater, new int[][]{{WATER_CELL, GROUND_CELL, GROUND_CELL}, {WATER_CELL, WATER_CELL, GROUND_CELL}, {WATER_CELL, GROUND_CELL, GROUND_CELL}})) {
            return new OverlayTileShape((byte) 11, (byte) 1);
        }

        return cacheDerivedShorelineRule(waterMask, x, y, worldWidth, worldHeight);
    }

    private static boolean matches(int[][] candidate, int[][]... patterns) {
        for (int[][] pattern : patterns) {
            boolean match = true;
            for (int row = 0; row < 3 && match; row++) {
                for (int column = 0; column < 3; column++) {
                    if (candidate[row][column] != pattern[row][column]) {
                        match = false;
                        break;
                    }
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    private static OverlayTileShape cacheDerivedShorelineRule(boolean[][] waterMask, int x, int y, int worldWidth, int worldHeight) {
        int mask = waterNeighbourMask(waterMask, x, y, worldWidth, worldHeight);
        if (mask == 0 || mask == 15) {
            return null;
        }
        MapDataExemplarLibrary.ShorelineRule rule = MapDataExemplarLibrary.resolve().shorelineRule(mask);
        if (rule == null || rule.shape() < 0) {
            return null;
        }
        return new OverlayTileShape(rule.shape(), rule.orientation());
    }

    private static int waterNeighbourMask(boolean[][] waterMask, int x, int y, int worldWidth, int worldHeight) {
        int mask = 0;
        if (isWaterTile(waterMask, x, y + 1, worldWidth, worldHeight)) {
            mask |= 1;
        }
        if (isWaterTile(waterMask, x + 1, y, worldWidth, worldHeight)) {
            mask |= 2;
        }
        if (isWaterTile(waterMask, x, y - 1, worldWidth, worldHeight)) {
            mask |= 4;
        }
        if (isWaterTile(waterMask, x - 1, y, worldWidth, worldHeight)) {
            mask |= 8;
        }
        return mask;
    }

    private static boolean isWaterTile(boolean[][] waterMask, int x, int y, int worldWidth, int worldHeight) {
        return x >= 0 && y >= 0 && x < worldWidth && y < worldHeight && waterMask[x][y];
    }

    private static int encode(int x, int y) {
        return (x << 16) | (y & 0xffff);
    }

    private static int decodeX(int encoded) {
        return (encoded >>> 16) & 0xffff;
    }

    private static int decodeY(int encoded) {
        return encoded & 0xffff;
    }

    private static double hashedUnit(long seed, int x, int y) {
        long value = seed;
        value ^= ((long) x) * 0x9E3779B97F4A7C15L;
        value ^= ((long) y) * 0xC2B2AE3D27D4EB4FL;
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return (value & 0x1fffffffffffffL) / (double) 0x1fffffffffffffL;
    }

    private static boolean touchesWater(boolean[][] waterMask, int x, int y, int worldWidth, int worldHeight) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                int sampleX = x + dx;
                int sampleY = y + dy;
                if (sampleX >= 0 && sampleY >= 0 && sampleX < worldWidth && sampleY < worldHeight && waterMask[sampleX][sampleY]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double tokenShare(CacheBiomeProfileMiner.BiomeProfile profile, String token) {
        if (profile.topTokens == null) {
            return 0.0;
        }
        for (CacheBiomeProfileMiner.RankedToken rankedToken : profile.topTokens) {
            if (token.equalsIgnoreCase(rankedToken.token)) {
                return rankedToken.share;
            }
        }
        return 0.0;
    }

    private static int indexNoise(long seed, int x, int y, int bound) {
        if (bound <= 1) {
            return 0;
        }
        double noise = octaveNoise(seed, x * 0.071, y * 0.071, 2, 0.5);
        int index = (int) Math.abs(Math.round(noise * 10000.0)) % bound;
        return index;
    }

    private static int quantizeHeight(int rawHeight) {
        return Math.max(TILE_HEIGHT_UNIT, (rawHeight / TILE_HEIGHT_UNIT) * TILE_HEIGHT_UNIT);
    }

    private static double octaveNoise(long seed, double x, double y, int octaves, double persistence) {
        double amplitude = 1.0;
        double frequency = 1.0;
        double total = 0.0;
        double max = 0.0;
        double seedOffsetX = (seed & 0xffff) * 0.00137;
        double seedOffsetY = ((seed >>> 16) & 0xffff) * 0.00191;
        for (int index = 0; index < octaves; index++) {
            total += SimplexNoise.noise((x * frequency) + seedOffsetX, (y * frequency) - seedOffsetY) * amplitude;
            max += amplitude;
            amplitude *= persistence;
            frequency *= 2.0;
        }
        return max == 0.0 ? 0.0 : total / max;
    }

    private static double normalize(double value) {
        return clamp((value + 1.0) * 0.5, 0.0, 1.0);
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        if (edge0 == edge1) {
            return value < edge0 ? 0.0 : 1.0;
        }
        double t = clamp((value - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class PreviewPalette {
        private final int[] underlays;
        private final double[] underlayThresholds;
        private final int waterOverlayId;
        private final int roadOverlayId;
        private final boolean aridProfile;

        private PreviewPalette(int[] underlays, double[] underlayThresholds, int waterOverlayId, int roadOverlayId, boolean aridProfile) {
            this.underlays = underlays;
            this.underlayThresholds = underlayThresholds;
            this.waterOverlayId = waterOverlayId;
            this.roadOverlayId = roadOverlayId;
            this.aridProfile = aridProfile;
        }

        private static PreviewPalette fromProfile(CacheBiomeProfileMiner.BiomeProfile profile) {
            boolean aridProfile = isAridProfile(profile);
            int waterOverlayId = MapDataExemplarLibrary.REAL_WATER_OVERLAY_ID;
            int roadOverlayId = pickOverlay(profile, false);
            if (isProfile13DesertProfile(profile)) {
                return new PreviewPalette(
                        new int[]{PROFILE13_DESERT_PRIMARY_UNDERLAY, PROFILE13_DESERT_SECONDARY_UNDERLAY},
                        new double[]{0.76, 1.0},
                        waterOverlayId,
                        roadOverlayId,
                        true
                );
            }
            WeightedUnderlayPalette weightedUnderlays = profile.topUnderlays == null || profile.topUnderlays.isEmpty()
                    ? closestUnderlays(profile, aridProfile ? 7 : 6)
                    : weightedUnderlays(profile.topUnderlays, aridProfile ? 7 : 6, aridProfile);
            weightedUnderlays = ensureMinimumPaletteVariety(
                    weightedUnderlays,
                    closestUnderlays(profile, aridProfile ? 7 : 6),
                    aridProfile ? 5 : 3
            );
            weightedUnderlays = softenDominantWeight(weightedUnderlays, aridProfile ? 0.42 : 0.56);
            return new PreviewPalette(weightedUnderlays.ids, weightedUnderlays.thresholds, waterOverlayId, roadOverlayId, aridProfile);
        }

        private static WeightedUnderlayPalette ensureMinimumPaletteVariety(
                WeightedUnderlayPalette primary,
                WeightedUnderlayPalette supplemental,
                int minimumCount
        ) {
            if (primary == null || primary.ids.length >= minimumCount || supplemental == null) {
                return primary;
            }

            LinkedHashSet<Integer> orderedIds = new LinkedHashSet<>();
            for (int id : primary.ids) {
                orderedIds.add(id);
            }
            for (int id : supplemental.ids) {
                orderedIds.add(id);
                if (orderedIds.size() >= minimumCount) {
                    break;
                }
            }
            if (orderedIds.size() <= primary.ids.length) {
                return primary;
            }

            double[] primaryWeights = weightsFromThresholds(primary.thresholds);
            int[] ids = new int[orderedIds.size()];
            double[] weights = new double[ids.length];
            double retainedShare = 0.78;
            int index = 0;
            for (int id : orderedIds) {
                ids[index] = id;
                if (index < primary.ids.length) {
                    weights[index] = primaryWeights[index] * retainedShare;
                } else {
                    weights[index] = (1.0 - retainedShare) / Math.max(1, ids.length - primary.ids.length);
                }
                index++;
            }

            double sum = 0.0;
            for (double weight : weights) {
                sum += weight;
            }
            for (int weightIndex = 0; weightIndex < weights.length; weightIndex++) {
                weights[weightIndex] /= Math.max(0.0001, sum);
            }
            return new WeightedUnderlayPalette(ids, thresholds(weights));
        }

        private static double[] weightsFromThresholds(double[] thresholds) {
            double[] weights = new double[thresholds.length];
            double previous = 0.0;
            for (int index = 0; index < thresholds.length; index++) {
                weights[index] = Math.max(0.0, thresholds[index] - previous);
                previous = thresholds[index];
            }
            return weights;
        }

        private static WeightedUnderlayPalette softenDominantWeight(WeightedUnderlayPalette palette, double maxPrimaryWeight) {
            if (palette == null || palette.ids.length <= 1) {
                return palette;
            }
            double[] weights = weightsFromThresholds(palette.thresholds);
            if (weights.length <= 1 || weights[0] <= maxPrimaryWeight) {
                return palette;
            }
            double excess = weights[0] - maxPrimaryWeight;
            weights[0] = maxPrimaryWeight;
            double remainderBase = 0.0;
            for (int index = 1; index < weights.length; index++) {
                remainderBase += Math.max(0.0001, weights[index]);
            }
            for (int index = 1; index < weights.length; index++) {
                double share = Math.max(0.0001, weights[index]) / remainderBase;
                weights[index] += excess * share;
            }
            double sum = 0.0;
            for (double weight : weights) {
                sum += weight;
            }
            for (int index = 0; index < weights.length; index++) {
                weights[index] /= Math.max(0.0001, sum);
            }
            return new WeightedUnderlayPalette(palette.ids, thresholds(weights));
        }

        private static WeightedUnderlayPalette closestUnderlays(CacheBiomeProfileMiner.BiomeProfile profile, int count) {
            double targetRed = profile.metrics == null ? 92.0 : profile.metrics.underlayRed;
            double targetGreen = profile.metrics == null ? 120.0 : profile.metrics.underlayGreen;
            double targetBlue = profile.metrics == null ? 68.0 : profile.metrics.underlayBlue;
            List<MaterialScore> scores = new ArrayList<>();
            if (FloorDefinitionLoader.instance == null) {
                return new WeightedUnderlayPalette(new int[]{1}, new double[]{1.0});
            }
            for (int id = 0; id < FloorDefinitionLoader.getUnderlayCount(); id++) {
                Floor floor = FloorDefinitionLoader.getUnderlay(id);
                if (floor == null || floor.getRgb() == 0 || floor.getRgb() == -1 || floor.getTexture() != -1) {
                    continue;
                }
                scores.add(new MaterialScore(id + 1, rgbDistance(floor.getRgb(), targetRed, targetGreen, targetBlue)));
            }
            scores.sort((left, right) -> Double.compare(left.score, right.score));
            if (scores.isEmpty()) {
                return new WeightedUnderlayPalette(new int[]{1}, new double[]{1.0});
            }
            int size = Math.min(count, scores.size());
            int[] ids = new int[size];
            double[] weights = new double[size];
            boolean aridProfile = isAridProfile(profile);
            List<MaterialScore> selected = new ArrayList<>();
            selected.add(scores.get(0));
            int candidatePool = Math.min(scores.size(), Math.max(size * 6, aridProfile ? 28 : 18));
            while (selected.size() < size) {
                MaterialScore bestCandidate = null;
                double bestCandidateScore = Double.NEGATIVE_INFINITY;
                for (int index = 1; index < candidatePool; index++) {
                    MaterialScore candidate = scores.get(index);
                    boolean alreadySelected = false;
                    for (MaterialScore existing : selected) {
                        if (existing.id == candidate.id) {
                            alreadySelected = true;
                            break;
                        }
                    }
                    if (alreadySelected) {
                        continue;
                    }
                    int candidateRgb = materialRgb(candidate.id, false);
                    double minDistance = Double.MAX_VALUE;
                    for (MaterialScore existing : selected) {
                        int existingRgb = materialRgb(existing.id, false);
                        if (candidateRgb == -1 || existingRgb == -1) {
                            continue;
                        }
                        minDistance = Math.min(minDistance, rgbDistance(
                                candidateRgb,
                                red(existingRgb),
                                green(existingRgb),
                                blue(existingRgb)
                        ));
                    }
                    if (minDistance == Double.MAX_VALUE) {
                        minDistance = 0.0;
                    }
                    double candidateScore = minDistance * (aridProfile ? 1.30 : 0.95) - candidate.score * (aridProfile ? 0.70 : 0.88);
                    if (bestCandidate == null || candidateScore > bestCandidateScore) {
                        bestCandidate = candidate;
                        bestCandidateScore = candidateScore;
                    }
                }
                if (bestCandidate == null) {
                    for (MaterialScore candidate : scores) {
                        boolean alreadySelected = false;
                        for (MaterialScore existing : selected) {
                            if (existing.id == candidate.id) {
                                alreadySelected = true;
                                break;
                            }
                        }
                        if (!alreadySelected) {
                            bestCandidate = candidate;
                            break;
                        }
                    }
                }
                if (bestCandidate == null) {
                    break;
                }
                selected.add(bestCandidate);
            }
            ids = new int[selected.size()];
            weights = new double[selected.size()];
            double sum = 0.0;
            for (int index = 0; index < selected.size(); index++) {
                ids[index] = selected.get(index).id;
                weights[index] = 1.0 / Math.pow(index + 1.0, aridProfile ? 0.68 : 0.82);
                sum += weights[index];
            }
            for (int index = 0; index < selected.size(); index++) {
                weights[index] /= sum;
            }
            return new WeightedUnderlayPalette(ids, thresholds(weights));
        }

        private static WeightedUnderlayPalette weightedUnderlays(List<CacheBiomeProfileMiner.RankedMaterial> rankedMaterials, int limit, boolean aridProfile) {
            List<CacheBiomeProfileMiner.RankedMaterial> selected = rankedMaterials.stream()
                    .filter(material -> material.share >= (aridProfile ? 0.010 : 0.012))
                    .limit(limit)
                    .toList();
            if (selected.isEmpty()) {
                selected = rankedMaterials.stream().limit(Math.min(limit, rankedMaterials.size())).toList();
            }

            CacheBiomeProfileMiner.RankedMaterial dominant = selected.get(0);
            int dominantRgb = materialRgb(dominant.id, false);
            List<CacheBiomeProfileMiner.RankedMaterial> cohesive = new ArrayList<>();
            cohesive.add(dominant);

            for (int index = 1; index < selected.size(); index++) {
                CacheBiomeProfileMiner.RankedMaterial material = selected.get(index);
                int rgb = materialRgb(material.id, false);
                if (rgb == -1 || dominantRgb == -1) {
                    continue;
                }
                double distance = rgbDistance(rgb, red(dominantRgb), green(dominantRgb), blue(dominantRgb));
                int brightnessDelta = Math.abs(brightness(rgb) - brightness(dominantRgb));
                if (distance <= (aridProfile ? 42.0 : 26.0)
                        || (distance <= (aridProfile ? 58.0 : 40.0) && brightnessDelta <= (aridProfile ? 24 : 14))) {
                    cohesive.add(material);
                }
            }

            int[] ids = new int[cohesive.size()];
            double[] weights = new double[cohesive.size()];
            for (int index = 0; index < cohesive.size(); index++) {
                CacheBiomeProfileMiner.RankedMaterial material = cohesive.get(index);
                ids[index] = material.id;
                double softenedShare = Math.pow(Math.max(material.share, aridProfile ? 0.010 : 0.012), aridProfile ? 0.62 : 0.72);
                double rankBias = (aridProfile ? 0.10 : 0.07) / (index + 1.0);
                weights[index] = softenedShare + rankBias;
            }
            double weightSum = 0.0;
            for (double weight : weights) {
                weightSum += weight;
            }
            if (weightSum <= 0.0) {
                Arrays.fill(weights, 1.0 / Math.max(1, weights.length));
            } else {
                for (int index = 0; index < weights.length; index++) {
                    weights[index] /= weightSum;
                }
            }
            return new WeightedUnderlayPalette(ids, thresholds(weights));
        }

        private static double[] thresholds(double[] weights) {
            double[] thresholds = new double[weights.length];
            double cumulative = 0.0;
            for (int index = 0; index < weights.length; index++) {
                cumulative += weights[index];
                thresholds[index] = index == weights.length - 1 ? 1.0 : cumulative;
            }
            return thresholds;
        }

        private static int pickOverlay(CacheBiomeProfileMiner.BiomeProfile profile, boolean water) {
            if (water) {
                return MapDataExemplarLibrary.REAL_WATER_OVERLAY_ID;
            }
            if (profile.topOverlays != null) {
                for (CacheBiomeProfileMiner.RankedMaterial overlay : profile.topOverlays) {
                    Floor floor = FloorDefinitionLoader.getOverlay(Math.max(0, overlay.id - 1));
                    if (floor == null || floor.getRgb() == 0 || floor.getRgb() == -1) {
                        continue;
                    }
                    int rgb = floor.getRgb();
                    boolean blue = blue(rgb) >= green(rgb) + 8 && blue(rgb) >= red(rgb) + 10;
                    if (water == blue) {
                        return overlay.id;
                    }
                }
            }
            return chooseClosestOverlay(water ? 52.0 : 118.0, water ? 84.0 : 102.0, water ? 124.0 : 77.0, water);
        }

        private static int chooseClosestOverlay(double targetRed, double targetGreen, double targetBlue, boolean preferTexture) {
            if (FloorDefinitionLoader.instance == null) {
                return 0;
            }
            MaterialScore best = null;
            for (int id = 0; id < FloorDefinitionLoader.getOverlayCount(); id++) {
                Floor floor = FloorDefinitionLoader.getOverlay(id);
                if (floor == null || floor.getRgb() == 0 || floor.getRgb() == -1) {
                    continue;
                }
                double score = rgbDistance(floor.getRgb(), targetRed, targetGreen, targetBlue);
                if (preferTexture) {
                    score += floor.getTexture() >= 0 ? 0.0 : 16.0;
                }
                if (best == null || score < best.score) {
                    best = new MaterialScore(id + 1, score);
                }
            }
            return best == null ? 0 : best.id;
        }
    }

    private static final class PreviewObjectSet {
        private final List<PreviewObject> treeObjects;
        private final List<PreviewObject> shrubObjects;
        private final List<PreviewObject> decorObjects;

        private PreviewObjectSet(List<PreviewObject> treeObjects, List<PreviewObject> shrubObjects, List<PreviewObject> decorObjects) {
            this.treeObjects = treeObjects;
            this.shrubObjects = shrubObjects;
            this.decorObjects = decorObjects;
        }

        private static PreviewObjectSet fromProfile(CacheBiomeProfileMiner.BiomeProfile profile) {
            List<PreviewObject> trees = new ArrayList<>();
            List<PreviewObject> shrubs = new ArrayList<>();
            List<PreviewObject> decor = new ArrayList<>();
            boolean hasPlacementObjects = profile != null
                    && profile.objectPlacement != null
                    && ((profile.objectPlacement.trees != null && profile.objectPlacement.trees.objects != null && !profile.objectPlacement.trees.objects.isEmpty())
                    || (profile.objectPlacement.shrubs != null && profile.objectPlacement.shrubs.objects != null && !profile.objectPlacement.shrubs.objects.isEmpty())
                    || (profile.objectPlacement.decor != null && profile.objectPlacement.decor.objects != null && !profile.objectPlacement.decor.objects.isEmpty()));
            if (hasPlacementObjects) {
                addPlacementObjects(profile.objectPlacement.trees, trees, 10);
                addPlacementObjects(profile.objectPlacement.shrubs, shrubs, 10);
                addPlacementObjects(profile.objectPlacement.decor, decor, 22);
            }
            boolean hasExplicitProfileObjects = profile != null && profile.topObjects != null && !profile.topObjects.isEmpty();
            if (!hasPlacementObjects && hasExplicitProfileObjects) {
                for (CacheBiomeProfileMiner.RankedObject object : profile.topObjects) {
                    addCategorizedPreviewObject(object.id, resolveObjectName(object.id, object.name), object.share, trees, shrubs, decor);
                }
            }
            if (!hasPlacementObjects && !hasExplicitProfileObjects) {
                populateFallbackObjects(profile, trees, shrubs, decor);
            }
            return new PreviewObjectSet(trees, shrubs, decor);
        }
    }

    private static void addPlacementObjects(
            CacheBiomeProfileMiner.ObjectCategoryPlacement placement,
            List<PreviewObject> target,
            int fallbackType
    ) {
        if (placement == null || placement.objects == null) {
            return;
        }
        for (CacheBiomeProfileMiner.ObjectPlacementEntry object : placement.objects) {
            int type = object.dominantType <= 0 ? fallbackType : object.dominantType;
            double weight = Math.max(0.0001, object.objectShare > 0.0 ? object.objectShare : object.tileDensity);
            addPreviewObject(target, new PreviewObject(object.id, type, object.dominantOrientation, weight));
        }
    }

    private static boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static void populateFallbackObjects(
            CacheBiomeProfileMiner.BiomeProfile profile,
            List<PreviewObject> trees,
            List<PreviewObject> shrubs,
            List<PreviewObject> decor
    ) {
        populateCategoryFallback(profileTreeKeywords(profile), trees, 10, 18, 0);
        populateCategoryFallback(profileShrubKeywords(profile), shrubs, 10, 18, 1);
        populateCategoryFallback(profileDecorKeywords(profile), decor, 22, 18, 2);
    }

    private static void populateCategoryFallback(List<String> keywords, List<PreviewObject> target, int type, int limit, int category) {
        for (String keyword : keywords) {
            for (SearchableObjectDefinition object : searchableObjectDefinitions()) {
                if (target.size() >= limit) {
                    return;
                }
                String name = object.name();
                if (!name.contains(keyword) || isStructuralObjectName(name) || !matchesCategory(name, category)) {
                    continue;
                }
                addPreviewObject(target, new PreviewObject(object.id(), type, -1, 1.0));
            }
        }
    }

    private static boolean matchesCategory(String name, int category) {
        return switch (category) {
            case 0 -> isTreeObjectName(name);
            case 1 -> isShrubObjectName(name);
            case 2 -> isGroundDecorObjectName(name);
            default -> false;
        };
    }

    private static List<String> profileTreeKeywords(CacheBiomeProfileMiner.BiomeProfile profile) {
        List<String> keywords = new ArrayList<>();
        if (isAridProfile(profile)) {
            addKeywords(keywords, "cactus", "palm", "dead tree", "tree");
        } else if (isSnowyProfile(profile)) {
            addKeywords(keywords, "evergreen", "pine", "fir", "tree", "dead tree");
        } else if (isWetProfile(profile)) {
            addKeywords(keywords, "willow", "tree", "mangrove", "dead tree");
        } else if (isWoodedProfile(profile)) {
            addKeywords(keywords, "tree", "oak", "willow", "maple", "evergreen", "yew", "jungle");
        } else {
            addKeywords(keywords, "tree", "oak", "willow", "maple", "dead tree");
        }
        appendProfileTokens(profile, keywords, "tree", "oak", "willow", "maple", "evergreen", "yew", "palm", "cactus", "jungle", "dead");
        return keywords;
    }

    private static List<String> profileShrubKeywords(CacheBiomeProfileMiner.BiomeProfile profile) {
        List<String> keywords = new ArrayList<>();
        if (isAridProfile(profile)) {
            addKeywords(keywords, "cactus", "roots", "root", "stump", "log", "branch");
        } else if (isSnowyProfile(profile)) {
            addKeywords(keywords, "bush", "shrub", "stump", "log", "branch", "roots");
        } else if (isWetProfile(profile)) {
            addKeywords(keywords, "fern", "plant", "bush", "shrub", "roots", "vine", "ivy", "reed");
        } else if (isWoodedProfile(profile)) {
            addKeywords(keywords, "bush", "shrub", "fern", "plant", "branch", "log", "stump", "roots", "vine", "ivy");
        } else {
            addKeywords(keywords, "bush", "shrub", "fern", "plant", "log", "stump");
        }
        appendProfileTokens(profile, keywords, "bush", "shrub", "fern", "plant", "branch", "log", "stump", "root", "roots", "vine", "ivy", "reed");
        return keywords;
    }

    private static List<String> profileDecorKeywords(CacheBiomeProfileMiner.BiomeProfile profile) {
        List<String> keywords = new ArrayList<>();
        if (isAridProfile(profile)) {
            addKeywords(keywords, "rock", "rocks", "stones", "boulder", "grass", "weed");
        } else if (isSnowyProfile(profile)) {
            addKeywords(keywords, "rock", "rocks", "stones", "mushroom", "grass");
        } else if (isWetProfile(profile)) {
            addKeywords(keywords, "reed", "grass", "mushroom", "flower", "flowers", "weed", "rock");
        } else if (isWoodedProfile(profile)) {
            addKeywords(keywords, "flower", "flowers", "grass", "mushroom", "herb", "reed", "rock", "rocks");
        } else {
            addKeywords(keywords, "grass", "flower", "flowers", "mushroom", "reed", "rock");
        }
        appendProfileTokens(profile, keywords, "flower", "flowers", "grass", "mushroom", "reed", "weed", "rock", "rocks", "stones", "boulder", "herb");
        return keywords;
    }

    private static void appendProfileTokens(CacheBiomeProfileMiner.BiomeProfile profile, List<String> keywords, String... allowedTokens) {
        if (profile == null || profile.topTokens == null) {
            return;
        }
        for (CacheBiomeProfileMiner.RankedToken token : profile.topTokens) {
            if (containsAny(token.token, allowedTokens)) {
                addKeywords(keywords, token.token);
            }
        }
    }

    private static void addKeywords(List<String> keywords, String... values) {
        for (String value : values) {
            if (value == null || value.isBlank() || keywords.contains(value)) {
                continue;
            }
            keywords.add(value);
        }
    }

    private static void addCategorizedPreviewObject(
            int id,
            String name,
            double weight,
            List<PreviewObject> trees,
            List<PreviewObject> shrubs,
            List<PreviewObject> decor
    ) {
        if (name.isBlank() || isStructuralObjectName(name)) {
            return;
        }
        if (isTreeObjectName(name)) {
            addPreviewObject(trees, new PreviewObject(id, 10, -1, Math.max(0.0001, weight)));
        } else if (isShrubObjectName(name)) {
            addPreviewObject(shrubs, new PreviewObject(id, 10, -1, Math.max(0.0001, weight)));
        } else if (isGroundDecorObjectName(name)) {
            addPreviewObject(decor, new PreviewObject(id, 22, -1, Math.max(0.0001, weight)));
        }
    }

    private static void addPreviewObject(List<PreviewObject> target, PreviewObject object) {
        for (PreviewObject existing : target) {
            if (existing.id() == object.id() && existing.type() == object.type() && existing.orientation() == object.orientation()) {
                return;
            }
        }
        target.add(object);
    }

    private static PreviewObject pickPreviewObject(List<PreviewObject> objects, double pick) {
        if (objects.isEmpty()) {
            return null;
        }
        double totalWeight = 0.0;
        for (PreviewObject object : objects) {
            totalWeight += Math.max(0.0001, object.weight());
        }
        double threshold = pick * totalWeight;
        double cumulative = 0.0;
        PreviewObject last = objects.get(objects.size() - 1);
        for (PreviewObject object : objects) {
            cumulative += Math.max(0.0001, object.weight());
            if (threshold <= cumulative) {
                return object;
            }
        }
        return last;
    }

    private static int resolveOrientation(PreviewObject object, int fallback) {
        return object.orientation() >= 0 ? object.orientation() & 3 : fallback & 3;
    }

    private static String resolveObjectName(int id, String candidate) {
        String normalized = normalizeObjectName(candidate);
        if (!normalized.isBlank()) {
            return normalized;
        }
        ObjectDefinition definition = ObjectDefinitionLoader.lookup(id);
        return definition == null ? "" : normalizeObjectName(definition.getName());
    }

    private static String normalizeObjectName(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.toLowerCase(Locale.ROOT).trim();
        return normalized.equals("null") ? "" : normalized;
    }

    private static boolean isStructuralObjectName(String name) {
        return containsAny(name,
                "wall", "door", "gate", "roof", "fence", "window", "building", "house",
                "table", "chair", "stool", "bed", "crate", "barrel", "chest", "ladder",
                "bookcase", "bookshelf", "bench", "sign", "stall", "counter");
    }

    private static boolean isTreeObjectName(String name) {
        return containsAny(name, "tree", "oak", "willow", "maple", "evergreen", "yew", "dead tree", "cactus", "palm", "jungle");
    }

    private static boolean isShrubObjectName(String name) {
        return containsAny(name, "bush", "shrub", "fern", "plant", "branch", "log", "stump", "root", "roots", "vine", "ivy", "reed");
    }

    private static boolean isGroundDecorObjectName(String name) {
        return containsAny(name, "flower", "flowers", "grass", "reed", "mushroom", "weed", "herb", "rock", "rocks", "stones", "boulder");
    }

    private static List<SearchableObjectDefinition> searchableObjectDefinitions() {
        if (searchableObjectDefinitions != null) {
            return searchableObjectDefinitions;
        }
        synchronized (BiomeProfilePreviewGenerator.class) {
            if (searchableObjectDefinitions == null) {
                List<SearchableObjectDefinition> objects = new ArrayList<>();
                int count = ObjectDefinitionLoader.instance == null ? 0 : ObjectDefinitionLoader.getCount();
                for (int id = 0; id < count; id++) {
                    ObjectDefinition definition = ObjectDefinitionLoader.lookup(id);
                    String name = definition == null ? "" : normalizeObjectName(definition.getName());
                    if (!name.isBlank()) {
                        objects.add(new SearchableObjectDefinition(id, name));
                    }
                }
                searchableObjectDefinitions = objects;
            }
            return searchableObjectDefinitions;
        }
    }

    private static double rgbDistance(int rgb, double red, double green, double blue) {
        double redDelta = red(rgb) - red;
        double greenDelta = green(rgb) - green;
        double blueDelta = blue(rgb) - blue;
        return Math.sqrt(redDelta * redDelta + greenDelta * greenDelta + blueDelta * blueDelta);
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

    private static int brightness(int rgb) {
        return (red(rgb) + green(rgb) + blue(rgb)) / 3;
    }

    private static int materialRgb(int materialId, boolean overlay) {
        Floor floor = overlay
                ? FloorDefinitionLoader.getOverlay(Math.max(0, materialId - 1))
                : FloorDefinitionLoader.getUnderlay(Math.max(0, materialId - 1));
        if (floor == null || floor.getRgb() == 0 || floor.getRgb() == -1) {
            return -1;
        }
        return floor.getRgb();
    }

    private static Color materialColor(int materialId, boolean overlay, Color fallback) {
        int rgb = materialRgb(materialId, overlay);
        if (rgb == -1) {
            return fallback;
        }
        return Color.rgb(red(rgb), green(rgb), blue(rgb));
    }

    private static Color mix(Color start, Color end, double ratio) {
        if (ratio <= 0.0) {
            return start;
        }
        if (ratio >= 1.0) {
            return end;
        }
        return start.interpolate(end, ratio);
    }

    private static double interpolate(double start, double end, double ratio) {
        if (ratio <= 0.0) {
            return start;
        }
        if (ratio >= 1.0) {
            return end;
        }
        return start + (end - start) * ratio;
    }

    private record PreviewWindow(
            int originRegionX,
            int originRegionY,
            int chunkWidth,
            int chunkHeight
    ) {
        private static PreviewWindow at(int originRegionX, int originRegionY, int chunkWidth, int chunkHeight) {
            return new PreviewWindow(originRegionX, originRegionY, chunkWidth, chunkHeight);
        }

        private static PreviewWindow centered(int chunkWidth, int chunkHeight) {
            return at(centeredOriginRegionX(chunkWidth), centeredOriginRegionY(chunkHeight), chunkWidth, chunkHeight);
        }

        private int worldWidth() {
            return chunkWidth * REGION_SIZE;
        }

        private int worldHeight() {
            return chunkHeight * REGION_SIZE;
        }

        private int originTileX() {
            return originRegionX * REGION_SIZE;
        }

        private int originTileY() {
            return originRegionY * REGION_SIZE;
        }

        private int absoluteTileX(int localTileX) {
            return originTileX() + localTileX;
        }

        private int absoluteTileY(int localTileY) {
            return originTileY() + localTileY;
        }

        private int absoluteVertexX(int localVertexX) {
            return originTileX() + localVertexX;
        }

        private int absoluteVertexY(int localVertexY) {
            return originTileY() + localVertexY;
        }
    }

    private record VegetationProfile(PlacementTuning trees, PlacementTuning shrubs, PlacementTuning decor) {
    }

    private record PlacementTuning(double density, double clusterBias, double maxChance) {
    }

    private record SearchableObjectDefinition(int id, String name) {
    }

    private record MaterialScore(int id, double score) {
    }

    private record WeightedUnderlayPalette(int[] ids, double[] thresholds) {
    }

    private record PreviewObject(int id, int type, int orientation, double weight) {
    }

    private record LakeFeature(double centreX, double centreY, double radiusX, double radiusY, double rotation) {
    }

    private record RiverFeature(
            double startX,
            double startY,
            double endX,
            double endY,
            double width,
            double meanderAmplitude,
            double meanderFrequency,
            long noiseSeed
    ) {
    }

    private record WaterPlan(List<LakeFeature> lakes, List<RiverFeature> rivers) {
    }

    private record LandComponent(int representativeX, int representativeY, int size, int centreX, int centreY) {
    }

    private record NearestWater(int dx, int dy, double distance) {
    }

    private record SettlementSite(
            int centreX,
            int centreY,
            int waterSideX,
            int waterSideY,
            int settlementMinX,
            int settlementMinY,
            int settlementMaxX,
            int settlementMaxY
    ) {
    }

    private record SettlementLayoutChoice(
            CacheBiomeProfileMiner.SettlementPieceDefinition piece,
            CacheBiomeProfileMiner.SettlementFootprint footprint,
            CacheBiomeProfileMiner.SettlementBuildingTemplate template
    ) {
    }

    private record SettlementLayoutResult(
            List<SettlementBuilding> buildings,
            Set<Long> pathTiles,
            Set<Long> centrepieceTiles
    ) {
    }

    private record SettlementBuilding(
            int minX,
            int minY,
            int maxX,
            int maxY,
            int floors,
            CacheBiomeProfileMiner.SettlementPieceDefinition piece,
            CacheBiomeProfileMiner.SettlementFootprint footprint,
            CacheBiomeProfileMiner.SettlementBuildingTemplate template,
            boolean rotatedTemplate,
            int forcedDoorOrientation,
            BuildingStyle buildingStyle,
            SettlementSite site
    ) {
        private int width() {
            return maxX - minX + 1;
        }

        private int height() {
            return maxY - minY + 1;
        }

        private int centreX() {
            return (minX + maxX) / 2;
        }

        private int centreY() {
            return (minY + maxY) / 2;
        }
    }

    private record ScoredSettlementSite(SettlementSite site, double score) {
    }

    private record AcceptedSettlementCell(
            int cellX,
            int cellY,
            SettlementSite absoluteSite,
            double score,
            BuildingStyle buildingStyle
    ) {
    }

    private record PlannedSettlementCell(
            int cellX,
            int cellY,
            SettlementSite site,
            SettlementSite absoluteSite,
            double score,
            List<SettlementBuilding> buildings,
            Set<Long> pathTiles,
            Set<Long> centrepieceTiles,
            BuildingStyle buildingStyle
    ) {
    }

    private record SettlementPlan(
            CacheBiomeProfileMiner.SettlementProfile profile,
            SettlementSite site,
            List<SettlementBuilding> buildings,
            BuildingStyle buildingStyle,
            Set<Long> pathTiles,
            Set<Long> centrepieceTiles
    ) {
    }

    private record MountainFeaturePlan(
            List<PlacedMountainFeature> features
    ) {
    }

    private record PlacedMountainFeature(
            int minX,
            int minY,
            int maxX,
            int maxY,
            int anchorHeight,
            int peakHeight,
            double plateauRadius,
            double rotation,
            long seed,
            List<MountainLobe> lobes
    ) {
        private int centreX() {
            return (minX + maxX) / 2;
        }

        private int centreY() {
            return (minY + maxY) / 2;
        }
    }

    private record MountainLobe(
            double offsetX,
            double offsetY,
            double radiusX,
            double radiusY,
            double weight
    ) {
    }

    private record MountainTileBase(
            boolean active,
            int height,
            int underlayId,
            int overlayId,
            int tileFlag
    ) {
        private static MountainTileBase inactive() {
            return new MountainTileBase(false, 0, 0, 0, 0);
        }
    }

    private record MountainTileState(
            boolean active,
            int height,
            int underlayId,
            int overlayId,
            int overlayType,
            int overlayOrientation,
            int tileFlag
    ) {
        private static MountainTileState inactive() {
            return new MountainTileState(false, 0, 0, 0, 0, 0, 0);
        }
    }

    private record VillageConnector(
            int x,
            int y,
            int dirX,
            int dirY,
            int depth
    ) {
    }

    private record RoofTemplateMatch(
            CacheBiomeProfileMiner.SettlementRoofTemplate template,
            boolean rotated
    ) {
    }

    private record RotatedPieceCoordinate(
            int x,
            int y
    ) {
    }

    private record RotatedCoordinate(int x, int y) {
    }

    private record CachedChunkData(
            int regionHash,
            int offsetX,
            int offsetY,
            int tileMapId,
            int objectMapId,
            byte[] tileMapData,
            byte[] objectMapData
    ) {
    }

    private record OverlayTileShape(byte shape, byte orientation) {
    }
}
