package com.rspsi.tools;

import com.google.gson.GsonBuilder;
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

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class CacheBiomeProfileMiner {

    private static final int REGION_SIZE = 64;
    private static final int DEFAULT_MAX_REGIONS = 3000;
    private static final int DEFAULT_CLUSTER_COUNT = 16;
    private static final int DEFAULT_TOKEN_COUNT = 20;
    private static final int PROFILE09_DARKER_GROUND_UNDERLAY_ID = 63;
    private static final int PROFILE13_DARKER_GROUND_UNDERLAY_ID = 126;
    private static final int MIN_TOKEN_DOCUMENT_FREQUENCY = 3;
    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "with", "from", "into", "over", "under", "null", "object",
            "open", "closed", "large", "small", "broken", "dead", "old", "new",
            "north", "south", "east", "west", "upper", "lower", "left", "right",
            "part", "pair", "entrance", "exit", "very", "plain", "set"
    );

    private CacheBiomeProfileMiner() {
    }

    public static MiningConfig defaultConfig(Path outputPath) {
        return new MiningConfig(DEFAULT_CLUSTER_COUNT, DEFAULT_MAX_REGIONS, DEFAULT_TOKEN_COUNT, 0x51eedL, outputPath);
    }

    public static void applyBuiltInSettlementProfiles(MiningReport report) {
        if (report == null || report.profiles == null) {
            return;
        }
        for (BiomeProfile profile : report.profiles) {
            if (profile == null) {
                continue;
            }
            if (profile.darkerGroundUnderlayId <= 0) {
                profile.darkerGroundUnderlayId = resolveDarkerGroundUnderlay(profile);
            }
            if (profile.settlementProfile != null) {
                continue;
            }
            profile.settlementProfile = builtInSettlementProfile(profile);
        }
    }

    public static SettlementProfile resolveSettlementProfile(BiomeProfile profile) {
        if (profile == null) {
            return null;
        }
        if (profile.settlementProfile != null) {
            return profile.settlementProfile;
        }
        return builtInSettlementProfile(profile);
    }

    private static SettlementProfile builtInSettlementProfile(BiomeProfile profile) {
        if (profile == null || profile.label == null) {
            return null;
        }
        String normalizedLabel = profile.label.trim();
        if (normalizedLabel.regionMatches(true, 0, "profile_09", 0, "profile_09".length())) {
            SettlementProfile settlement = new SettlementProfile();
            settlement.id = "grassland_village";
            settlement.sourceRegionHash = 0;
            settlement.layoutStyle = "clustered_compound";
            settlement.primaryUnderlayId = 47;
            settlement.secondaryUnderlayId = PROFILE09_DARKER_GROUND_UNDERLAY_ID;
            settlement.accentOverlayId = 0;
            settlement.waterOverlayId = MapDataExemplarLibrary.REAL_WATER_OVERLAY_ID;
            settlement.wallId = 1904;
            settlement.wallType = 0;
            settlement.upperWallId = 1904;
            settlement.upperWallType = 0;
            settlement.cornerId = 1902;
            settlement.cornerType = 3;
            settlement.doorId = 1540;
            settlement.doorType = 0;
            settlement.wallDecorId = 1828;
            settlement.wallDecorType = 5;
            settlement.roofSlopeId = 1926;
            settlement.roofEdgeId = 1792;
            settlement.roofEdgeType = 18;
            settlement.roofCornerId = 1792;
            settlement.roofCornerType = 19;
            settlement.roofInsetCornerId = 1926;
            settlement.roofInsetCornerType = 16;
            settlement.roofFlatId = 1926;
            settlement.roofFlatType = 17;
            settlement.perimeterTiles = 1;
            settlement.minAnchors = 2;
            settlement.maxAnchors = 3;
            settlement.minSatellites = 4;
            settlement.maxSatellites = 8;
            settlement.sampledBuildingCount = 8;
            settlement.anchorFootprints = List.of(
                    new SettlementFootprint(15, 11, 0.28, 2, HouseStyleDefinition.FootprintShape.RECT),
                    new SettlementFootprint(13, 10, 0.26, 2, HouseStyleDefinition.FootprintShape.RECT),
                    new SettlementFootprint(12, 9, 0.22, 1, HouseStyleDefinition.FootprintShape.RECT),
                    new SettlementFootprint(11, 8, 0.24, 2, HouseStyleDefinition.FootprintShape.RECT)
            );
            settlement.satelliteFootprints = List.of(
                    new SettlementFootprint(11, 8, 0.18, 1, HouseStyleDefinition.FootprintShape.RECT),
                    new SettlementFootprint(10, 7, 0.22, 1, HouseStyleDefinition.FootprintShape.RECT),
                    new SettlementFootprint(9, 7, 0.22, 2, HouseStyleDefinition.FootprintShape.RECT),
                    new SettlementFootprint(8, 6, 0.20, 1, HouseStyleDefinition.FootprintShape.RECT),
                    new SettlementFootprint(7, 6, 0.18, 1, HouseStyleDefinition.FootprintShape.RECT)
            );
            settlement.buildingTemplates = List.of();
            settlement.roofTemplates = List.of();
            settlement.props = List.of();
            return settlement;
        }
        if ("profile_13_mixed_dry_rugged".equalsIgnoreCase(normalizedLabel)
                || "profile_13_mixed_dry_dugged".equalsIgnoreCase(normalizedLabel)) {
            SettlementProfile settlement = new SettlementProfile();
            settlement.id = "desert_river_town_13358";
            settlement.sourceRegionHash = 13358;
            settlement.layoutStyle = "riverside_compound";
            settlement.primaryUnderlayId = 61;
            settlement.secondaryUnderlayId = resolveDarkerGroundUnderlay(profile);
            settlement.accentOverlayId = 20;
            settlement.waterOverlayId = MapDataExemplarLibrary.REAL_WATER_OVERLAY_ID;
            settlement.wallId = 1415;
            settlement.wallType = 0;
            settlement.upperWallId = 1416;
            settlement.upperWallType = 0;
            settlement.cornerId = 6248;
            settlement.cornerType = 3;
            settlement.doorId = 1534;
            settlement.doorType = 0;
            settlement.wallDecorId = 6247;
            settlement.wallDecorType = 4;
            settlement.roofSlopeId = 1416;
            settlement.roofEdgeId = 1416;
            settlement.roofEdgeType = 12;
            settlement.roofCornerId = 1416;
            settlement.roofCornerType = 14;
            settlement.roofInsetCornerId = 1416;
            settlement.roofInsetCornerType = 13;
            settlement.roofFlatId = 1416;
            settlement.roofFlatType = 17;
            settlement.perimeterTiles = 1;
            settlement.minAnchors = 1;
            settlement.maxAnchors = 2;
            settlement.minSatellites = 3;
            settlement.maxSatellites = 6;
            settlement.sampledBuildingCount = 7;
            settlement.anchorFootprints = List.of(
                    new SettlementFootprint(22, 19, 0.36, 1),
                    new SettlementFootprint(15, 17, 0.30, 1),
                    new SettlementFootprint(17, 13, 0.22, 1),
                    new SettlementFootprint(22, 34, 0.12, 1)
            );
            settlement.satelliteFootprints = List.of(
                    new SettlementFootprint(9, 9, 0.32, 1),
                    new SettlementFootprint(9, 8, 0.28, 1),
                    new SettlementFootprint(7, 6, 0.24, 1),
                    new SettlementFootprint(17, 13, 0.16, 1)
            );
            settlement.props = List.of(
                    new SettlementProp(1396, 10, 0.22),
                    new SettlementProp(2670, 10, 0.18),
                    new SettlementProp(5541, 10, 0.12),
                    new SettlementProp(1158, 10, 0.14),
                    new SettlementProp(1102, 10, 0.12),
                    new SettlementProp(1092, 10, 0.10),
                    new SettlementProp(602, 10, 0.06),
                    new SettlementProp(6237, 10, 0.06)
            );
            return settlement;
        }
        return null;
    }

    public static int resolveDarkerGroundUnderlay(BiomeProfile profile) {
        if (profile == null) {
            return 0;
        }
        if (profile.darkerGroundUnderlayId > 0) {
            return profile.darkerGroundUnderlayId;
        }
        String normalizedLabel = profile.label == null ? "" : profile.label.trim().toLowerCase(Locale.ROOT);
        if (normalizedLabel.regionMatches(true, 0, "profile_09", 0, "profile_09".length())) {
            return PROFILE09_DARKER_GROUND_UNDERLAY_ID;
        }
        if ("profile_13_mixed_dry_rugged".equals(normalizedLabel)
                || "profile_13_mixed_dry_dugged".equals(normalizedLabel)) {
            return PROFILE13_DARKER_GROUND_UNDERLAY_ID;
        }
        int darkestSampled = darkestUnderlay(profile.topUnderlays);
        return darkestSampled > 0 ? darkestSampled : 0;
    }

    private static int darkestUnderlay(List<RankedMaterial> materials) {
        if (materials == null || materials.isEmpty()) {
            return 0;
        }
        int darkestId = 0;
        double darkestScore = Double.MAX_VALUE;
        for (RankedMaterial material : materials) {
            if (material == null || material.id <= 0) {
                continue;
            }
            Floor floor = FloorDefinitionLoader.getUnderlay(material.id - 1);
            if (floor == null) {
                continue;
            }
            int rgb = floor.getRgb();
            double brightness = ((rgb >> 16) & 0xff) * 0.299
                    + ((rgb >> 8) & 0xff) * 0.587
                    + (rgb & 0xff) * 0.114;
            if (brightness < darkestScore) {
                darkestScore = brightness;
                darkestId = material.id;
            }
        }
        return darkestId;
    }

    public static MiningResult mineProfiles(MiningConfig config) throws IOException {
        Client client = Client.getSingleton();
        Cache cache = client == null ? null : client.getCache();
        if (cache == null || MapIndexLoader.instance == null) {
            throw new IOException("Cache or map index loader is not available.");
        }

        List<MapIndexEntry> allEntries = readMapIndexEntries();
        if (allEntries.isEmpty()) {
            throw new IOException("No map regions were found in the cache map index.");
        }

        int requestedRegions = Math.max(50, config.maxRegions());
        List<MapIndexEntry> sampledEntries = sampleEntries(allEntries, requestedRegions);
        List<RegionObservation> observations = new ArrayList<>();
        Map<String, Integer> tokenDocumentFrequency = new HashMap<>();

        for (MapIndexEntry entry : sampledEntries) {
            RegionObservation observation = observeRegion(cache, entry);
            if (observation == null) {
                continue;
            }
            observations.add(observation);
            for (String token : observation.tokenCounts.keySet()) {
                tokenDocumentFrequency.merge(token, 1, Integer::sum);
            }
        }

        if (observations.size() < 2) {
            throw new IOException("Not enough regions could be decoded to mine biome profiles.");
        }

        List<String> featureTokens = selectFeatureTokens(tokenDocumentFrequency, observations.size(), Math.max(4, config.tokenCount()));
        Dataset dataset = buildDataset(observations, featureTokens);
        int clusterCount = Math.max(2, Math.min(config.clusterCount(), observations.size()));
        KMeansResult clustering = cluster(dataset.normalizedRows, clusterCount, config.seed());
        MiningReport report = buildReport(config, observations, dataset, clustering, featureTokens);

        Files.createDirectories(config.outputPath().getParent());
        try (Writer writer = Files.newBufferedWriter(config.outputPath())) {
            new GsonBuilder().setPrettyPrinting().create().toJson(report, writer);
        }

        return new MiningResult(config.outputPath(), report);
    }

    private static List<MapIndexEntry> readMapIndexEntries() {
        byte[] encoded = MapIndexLoader.instance.encode();
        if (encoded == null || encoded.length < 2) {
            return List.of();
        }

        Buffer buffer = new Buffer(encoded);
        int count = buffer.readUShort();
        List<MapIndexEntry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int hash = buffer.readUShort();
            int landscapeId = decodeMapId(buffer.readUShort());
            int objectId = decodeMapId(buffer.readUShort());
            if (landscapeId != -1) {
                entries.add(new MapIndexEntry(hash, landscapeId, objectId));
            }
        }
        return entries;
    }

    private static List<MapIndexEntry> sampleEntries(List<MapIndexEntry> entries, int maxRegions) {
        if (entries.size() <= maxRegions) {
            return entries;
        }
        List<MapIndexEntry> sampled = new ArrayList<>(maxRegions);
        double stride = entries.size() / (double) maxRegions;
        for (int index = 0; index < maxRegions; index++) {
            sampled.add(entries.get(Math.min(entries.size() - 1, (int) Math.floor(index * stride))));
        }
        return sampled;
    }

    private static RegionObservation observeRegion(Cache cache, MapIndexEntry entry) {
        byte[] landscapeData = cache.readMap(entry.landscapeId, entry.hash);
        if (landscapeData == null || landscapeData.length == 0) {
            return null;
        }

        int regionX = (entry.hash >> 8) & 0xff;
        int regionY = entry.hash & 0xff;
        SceneGraph sceneGraph = new SceneGraph(REGION_SIZE, REGION_SIZE, 4);
        MapRegion mapRegion = new MapRegion(sceneGraph, REGION_SIZE, REGION_SIZE);
        mapRegion.unpackTiles(landscapeData, 0, 0, regionX * REGION_SIZE, regionY * REGION_SIZE);

        List<RegionObject> objects = entry.objectId == -1
                ? List.of()
                : decodeObjects(cache.readMap(entry.objectId, entry.hash));
        return RegionObservation.from(entry.hash, mapRegion, objects);
    }

    private static List<RegionObject> decodeObjects(byte[] objectData) {
        if (objectData == null || objectData.length == 0) {
            return List.of();
        }

        Buffer buffer = new Buffer(objectData);
        List<RegionObject> objects = new ArrayList<>();
        int objectId = -1;
        while (true) {
            int idOffset = buffer.readUSmartInt();
            if (idOffset == 0) {
                break;
            }
            objectId += idOffset;
            int position = 0;
            while (true) {
                int offset = buffer.readUSmartInt();
                if (offset == 0) {
                    break;
                }
                position += offset - 1;
                int localY = position & 0x3f;
                int localX = position >> 6 & 0x3f;
                int plane = position >> 12;
                int config = buffer.readUByte();
                int type = config >> 2;
                int orientation = config & 3;
                objects.add(new RegionObject(objectId, type, orientation, localX, localY, plane));
            }
        }
        return objects;
    }

    private static List<String> selectFeatureTokens(Map<String, Integer> tokenDocumentFrequency, int regionCount, int tokenCount) {
        int maxFrequency = Math.max(MIN_TOKEN_DOCUMENT_FREQUENCY + 1, (int) Math.floor(regionCount * 0.55));
        return tokenDocumentFrequency.entrySet().stream()
                .filter(entry -> entry.getValue() >= MIN_TOKEN_DOCUMENT_FREQUENCY)
                .filter(entry -> entry.getValue() <= maxFrequency)
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(tokenCount)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static Dataset buildDataset(List<RegionObservation> observations, List<String> featureTokens) {
        int featureCount = 12 + featureTokens.size();
        double[][] rawRows = new double[observations.size()][featureCount];
        for (int index = 0; index < observations.size(); index++) {
            RegionObservation observation = observations.get(index);
            double[] row = rawRows[index];
            row[0] = observation.waterRatio;
            row[1] = observation.roadRatio;
            row[2] = observation.textureRatio;
            row[3] = observation.underlayRed / 255.0;
            row[4] = observation.underlayGreen / 255.0;
            row[5] = observation.underlayBlue / 255.0;
            row[6] = observation.overlayRed / 255.0;
            row[7] = observation.overlayGreen / 255.0;
            row[8] = observation.overlayBlue / 255.0;
            row[9] = Math.min(1.0, observation.heightStdDev / 96.0);
            row[10] = Math.min(1.0, observation.meanSlope / 48.0);
            row[11] = Math.min(1.0, observation.namedObjectCount / 256.0);
            for (int tokenIndex = 0; tokenIndex < featureTokens.size(); tokenIndex++) {
                String token = featureTokens.get(tokenIndex);
                int count = observation.tokenCounts.getOrDefault(token, 0);
                row[12 + tokenIndex] = observation.namedObjectCount == 0 ? 0.0 : count / (double) observation.namedObjectCount;
            }
        }

        double[] means = new double[featureCount];
        double[] standardDeviations = new double[featureCount];
        for (int column = 0; column < featureCount; column++) {
            double sum = 0.0;
            for (double[] row : rawRows) {
                sum += row[column];
            }
            means[column] = sum / rawRows.length;
            double variance = 0.0;
            for (double[] row : rawRows) {
                double delta = row[column] - means[column];
                variance += delta * delta;
            }
            standardDeviations[column] = Math.sqrt(variance / rawRows.length);
            if (standardDeviations[column] < 1.0e-6) {
                standardDeviations[column] = 1.0;
            }
        }

        double[][] normalizedRows = new double[rawRows.length][featureCount];
        for (int rowIndex = 0; rowIndex < rawRows.length; rowIndex++) {
            for (int column = 0; column < featureCount; column++) {
                normalizedRows[rowIndex][column] = (rawRows[rowIndex][column] - means[column]) / standardDeviations[column];
            }
        }

        return new Dataset(rawRows, normalizedRows, means, standardDeviations);
    }

    private static KMeansResult cluster(double[][] rows, int clusterCount, long seed) {
        Random random = new Random(seed);
        int dimension = rows[0].length;
        double[][] centroids = new double[clusterCount][dimension];
        List<Integer> chosen = new ArrayList<>();
        chosen.add(random.nextInt(rows.length));
        centroids[0] = Arrays.copyOf(rows[chosen.get(0)], dimension);

        for (int centroidIndex = 1; centroidIndex < clusterCount; centroidIndex++) {
            double[] distances = new double[rows.length];
            double distanceSum = 0.0;
            for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
                double bestDistance = Double.MAX_VALUE;
                for (int chosenIndex : chosen) {
                    bestDistance = Math.min(bestDistance, distanceSquared(rows[rowIndex], rows[chosenIndex]));
                }
                distances[rowIndex] = bestDistance;
                distanceSum += bestDistance;
            }

            int selectedIndex = 0;
            if (distanceSum > 0.0) {
                double pick = random.nextDouble() * distanceSum;
                for (int rowIndex = 0; rowIndex < distances.length; rowIndex++) {
                    pick -= distances[rowIndex];
                    if (pick <= 0.0) {
                        selectedIndex = rowIndex;
                        break;
                    }
                }
            } else {
                selectedIndex = random.nextInt(rows.length);
            }

            chosen.add(selectedIndex);
            centroids[centroidIndex] = Arrays.copyOf(rows[selectedIndex], dimension);
        }

        int[] assignments = new int[rows.length];
        Arrays.fill(assignments, -1);

        for (int iteration = 0; iteration < 40; iteration++) {
            boolean changed = false;
            for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
                int bestCluster = 0;
                double bestDistance = Double.MAX_VALUE;
                for (int clusterIndex = 0; clusterIndex < clusterCount; clusterIndex++) {
                    double distance = distanceSquared(rows[rowIndex], centroids[clusterIndex]);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestCluster = clusterIndex;
                    }
                }
                if (assignments[rowIndex] != bestCluster) {
                    assignments[rowIndex] = bestCluster;
                    changed = true;
                }
            }

            double[][] nextCentroids = new double[clusterCount][dimension];
            int[] clusterSizes = new int[clusterCount];
            for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
                int clusterIndex = assignments[rowIndex];
                clusterSizes[clusterIndex]++;
                for (int dimensionIndex = 0; dimensionIndex < dimension; dimensionIndex++) {
                    nextCentroids[clusterIndex][dimensionIndex] += rows[rowIndex][dimensionIndex];
                }
            }

            for (int clusterIndex = 0; clusterIndex < clusterCount; clusterIndex++) {
                if (clusterSizes[clusterIndex] == 0) {
                    int replacement = random.nextInt(rows.length);
                    nextCentroids[clusterIndex] = Arrays.copyOf(rows[replacement], dimension);
                    continue;
                }
                for (int dimensionIndex = 0; dimensionIndex < dimension; dimensionIndex++) {
                    nextCentroids[clusterIndex][dimensionIndex] /= clusterSizes[clusterIndex];
                }
            }

            centroids = nextCentroids;
            if (!changed) {
                break;
            }
        }

        return new KMeansResult(assignments, centroids);
    }

    private static MiningReport buildReport(
            MiningConfig config,
            List<RegionObservation> observations,
            Dataset dataset,
            KMeansResult clustering,
            List<String> featureTokens
    ) {
        List<BiomeProfile> profiles = new ArrayList<>();
        int clusterCount = clustering.centroids.length;
        for (int clusterIndex = 0; clusterIndex < clusterCount; clusterIndex++) {
            List<Integer> memberIndexes = new ArrayList<>();
            for (int rowIndex = 0; rowIndex < clustering.assignments.length; rowIndex++) {
                if (clustering.assignments[rowIndex] == clusterIndex) {
                    memberIndexes.add(rowIndex);
                }
            }
            if (memberIndexes.isEmpty()) {
                continue;
            }

            Aggregates aggregates = aggregateCluster(observations, memberIndexes, clustering.centroids[clusterIndex], dataset.normalizedRows);
            BiomeProfile profile = new BiomeProfile();
            profile.profileId = clusterIndex + 1;
            profile.label = buildProfileLabel(clusterIndex + 1, aggregates.metrics, aggregates.topTokens);
            profile.regionCount = memberIndexes.size();
            profile.exampleRegions = aggregates.exampleRegions;
            profile.metrics = aggregates.metrics;
            profile.topUnderlays = aggregates.topUnderlays;
            profile.topOverlays = aggregates.topOverlays;
            profile.topObjects = aggregates.topObjects;
            profile.topTokens = aggregates.topTokens;
            profile.darkerGroundUnderlayId = resolveDarkerGroundUnderlay(profile);
            profiles.add(profile);
        }

        profiles.sort(Comparator.comparingInt(profile -> -profile.regionCount));

        MiningReport report = new MiningReport();
        report.generatedAtUtc = OffsetDateTime.now(ZoneOffset.UTC).toString();
        report.requestedClusters = config.clusterCount();
        report.discoveredProfiles = profiles.size();
        report.sampledRegions = observations.size();
        report.featureTokens = featureTokens;
        report.profiles = profiles;
        return report;
    }

    private static Aggregates aggregateCluster(
            List<RegionObservation> observations,
            List<Integer> memberIndexes,
            double[] centroid,
            double[][] normalizedRows
    ) {
        Map<Integer, Integer> underlays = new HashMap<>();
        Map<Integer, Integer> overlays = new HashMap<>();
        Map<Integer, Integer> objects = new HashMap<>();
        Map<String, Integer> tokens = new HashMap<>();
        ClusterMetrics metrics = new ClusterMetrics();

        List<RegionDistance> exampleDistances = new ArrayList<>();
        for (int rowIndex : memberIndexes) {
            RegionObservation observation = observations.get(rowIndex);
            mergeIntegerCounts(underlays, observation.underlayCounts);
            mergeIntegerCounts(overlays, observation.overlayCounts);
            mergeIntegerCounts(objects, observation.objectCounts);
            mergeTokenCounts(tokens, observation.tokenCounts);

            metrics.waterRatio += observation.waterRatio;
            metrics.roadRatio += observation.roadRatio;
            metrics.textureRatio += observation.textureRatio;
            metrics.heightStdDev += observation.heightStdDev;
            metrics.meanSlope += observation.meanSlope;
            metrics.underlayRed += observation.underlayRed;
            metrics.underlayGreen += observation.underlayGreen;
            metrics.underlayBlue += observation.underlayBlue;
            metrics.overlayRed += observation.overlayRed;
            metrics.overlayGreen += observation.overlayGreen;
            metrics.overlayBlue += observation.overlayBlue;
            metrics.namedObjectDensity += observation.namedObjectCount / 4096.0;

            exampleDistances.add(new RegionDistance(observation.hash, distanceSquared(normalizedRows[rowIndex], centroid)));
        }

        int count = memberIndexes.size();
        metrics.waterRatio /= count;
        metrics.roadRatio /= count;
        metrics.textureRatio /= count;
        metrics.heightStdDev /= count;
        metrics.meanSlope /= count;
        metrics.underlayRed /= count;
        metrics.underlayGreen /= count;
        metrics.underlayBlue /= count;
        metrics.overlayRed /= count;
        metrics.overlayGreen /= count;
        metrics.overlayBlue /= count;
        metrics.namedObjectDensity /= count;

        exampleDistances.sort(Comparator.comparingDouble(distance -> distance.distance));
        List<RegionRef> exampleRegions = exampleDistances.stream()
                .limit(6)
                .map(distance -> RegionRef.fromHash(distance.hash))
                .toList();

        return new Aggregates(
                metrics,
                exampleRegions,
                topMaterials(underlays, 8),
                topMaterials(overlays, 8),
                topObjects(objects, 10),
                topTokens(tokens, 10)
        );
    }

    private static String buildProfileLabel(int profileId, ClusterMetrics metrics, List<RankedToken> topTokens) {
        String tone = classifyTone(metrics, topTokens);
        String moisture = classifyMoisture(metrics);
        String relief = metrics.heightStdDev > 42.0 ? "rugged" : metrics.heightStdDev > 18.0 ? "rolling" : "flat";
        return String.format(Locale.ROOT, "profile_%02d_%s_%s_%s", profileId, tone, moisture, relief);
    }

    private static String classifyTone(ClusterMetrics metrics, List<RankedToken> topTokens) {
        double red = metrics.underlayRed;
        double green = metrics.underlayGreen;
        double blue = metrics.underlayBlue;
        double brightness = (red + green + blue) / 3.0;
        double chroma = Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue));

        if (hasToken(topTokens, "snow", "ice", "frozen")) {
            return "snowy";
        }
        if (brightness >= 164.0 && chroma <= 24.0) {
            return "snowy";
        }
        if (red >= 132.0 && green >= 118.0 && blue <= 112.0 && red >= blue + 24.0) {
            return "sandy";
        }
        if (green > red + 18.0 && green > blue + 14.0) {
            return "verdant";
        }
        if (metrics.waterRatio > 0.16 && green >= blue) {
            return "marsh";
        }
        if (red > green + 12.0 && green > blue + 6.0) {
            return "ochre";
        }
        if (blue > green + 10.0 && brightness < 150.0) {
            return "cool";
        }
        if (brightness < 88.0 && chroma < 20.0) {
            return "barren";
        }
        if (chroma < 10.0) {
            return "ashen";
        }
        return "mixed";
    }

    private static String classifyMoisture(ClusterMetrics metrics) {
        if (metrics.waterRatio > 0.20) {
            return "wet";
        }
        if (metrics.waterRatio > 0.10) {
            return "damp";
        }
        if (metrics.underlayRed >= 132.0 && metrics.underlayGreen >= 118.0 && metrics.underlayBlue <= 112.0) {
            return "arid";
        }
        return "dry";
    }

    private static boolean hasToken(List<RankedToken> topTokens, String... candidates) {
        if (topTokens == null || topTokens.isEmpty()) {
            return false;
        }
        for (RankedToken token : topTokens) {
            for (String candidate : candidates) {
                if (candidate.equalsIgnoreCase(token.token)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<RankedMaterial> topMaterials(Map<Integer, Integer> counts, int limit) {
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            return List.of();
        }
        return counts.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(limit)
                .map(entry -> new RankedMaterial(entry.getKey(), entry.getValue() / (double) total))
                .toList();
    }

    private static List<RankedObject> topObjects(Map<Integer, Integer> counts, int limit) {
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            return List.of();
        }
        return counts.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(limit)
                .map(entry -> {
                    ObjectDefinition definition = ObjectDefinitionLoader.lookup(entry.getKey());
                    String name = definition == null ? "" : safeName(definition.getName());
                    return new RankedObject(entry.getKey(), name, entry.getValue() / (double) total);
                })
                .toList();
    }

    private static List<RankedToken> topTokens(Map<String, Integer> counts, int limit) {
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            return List.of();
        }
        return counts.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(limit)
                .map(entry -> new RankedToken(entry.getKey(), entry.getValue() / (double) total))
                .toList();
    }

    private static void mergeIntegerCounts(Map<Integer, Integer> target, Map<Integer, Integer> source) {
        for (Map.Entry<Integer, Integer> entry : source.entrySet()) {
            target.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    private static void mergeTokenCounts(Map<String, Integer> target, Map<String, Integer> source) {
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            target.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    private static double distanceSquared(double[] left, double[] right) {
        double sum = 0.0;
        for (int index = 0; index < left.length; index++) {
            double delta = left[index] - right[index];
            sum += delta * delta;
        }
        return sum;
    }

    private static int decodeMapId(int rawValue) {
        return rawValue == 65535 ? -1 : rawValue;
    }

    private static boolean isLikelyWater(Floor floor) {
        if (floor == null || floor.getRgb() == 0 || floor.getRgb() == -1) {
            return false;
        }
        int rgb = floor.getRgb();
        return blue(rgb) >= green(rgb) + 8 && blue(rgb) >= red(rgb) + 10;
    }

    private static boolean isLikelyRoad(Floor floor) {
        if (floor == null || floor.getRgb() == 0 || floor.getRgb() == -1) {
            return false;
        }
        int rgb = floor.getRgb();
        int spread = Math.max(red(rgb), Math.max(green(rgb), blue(rgb))) - Math.min(red(rgb), Math.min(green(rgb), blue(rgb)));
        return spread <= 70 && floor.getLuminance() >= 20 && floor.getLuminance() <= 190;
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

    private static String safeName(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private static Set<String> tokenize(String value) {
        if (value == null || value.isBlank()) {
            return Collections.emptySet();
        }
        String normalised = safeName(value).replaceAll("[^a-z0-9]+", " ").trim();
        if (normalised.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalised.split("\\s+")) {
            if (token.length() < 3 || STOPWORDS.contains(token) || token.chars().allMatch(Character::isDigit)) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    public static final class MiningConfig {
        private final int clusterCount;
        private final int maxRegions;
        private final int tokenCount;
        private final long seed;
        private final Path outputPath;

        public MiningConfig(int clusterCount, int maxRegions, int tokenCount, long seed, Path outputPath) {
            this.clusterCount = clusterCount;
            this.maxRegions = maxRegions;
            this.tokenCount = tokenCount;
            this.seed = seed;
            this.outputPath = outputPath;
        }

        public int clusterCount() {
            return clusterCount;
        }

        public int maxRegions() {
            return maxRegions;
        }

        public int tokenCount() {
            return tokenCount;
        }

        public long seed() {
            return seed;
        }

        public Path outputPath() {
            return outputPath;
        }
    }

    public static final class MiningResult {
        public final Path outputPath;
        public final MiningReport report;

        public MiningResult(Path outputPath, MiningReport report) {
            this.outputPath = outputPath;
            this.report = report;
        }
    }

    public static final class MiningReport {
        public String generatedAtUtc;
        public int requestedClusters;
        public int discoveredProfiles;
        public int sampledRegions;
        public List<String> featureTokens;
        public List<BiomeProfile> profiles;
    }

    public static final class BiomeProfile {
        public int profileId;
        public String label;
        public int regionCount;
        public List<RegionRef> exampleRegions;
        public ClusterMetrics metrics;
        public List<RankedMaterial> topUnderlays;
        public List<RankedMaterial> topOverlays;
        public List<RankedObject> topObjects;
        public List<RankedToken> topTokens;
        public ObjectPlacementProfile objectPlacement;
        public int darkerGroundUnderlayId;
        public SettlementProfile settlementProfile;
    }

    public static final class RegionRef {
        public int hash;
        public int regionX;
        public int regionY;

        public static RegionRef fromHash(int hash) {
            RegionRef ref = new RegionRef();
            ref.hash = hash;
            ref.regionX = (hash >> 8) & 0xff;
            ref.regionY = hash & 0xff;
            return ref;
        }
    }

    public static final class ClusterMetrics {
        public double waterRatio;
        public double roadRatio;
        public double textureRatio;
        public double heightStdDev;
        public double meanSlope;
        public double namedObjectDensity;
        public double underlayRed;
        public double underlayGreen;
        public double underlayBlue;
        public double overlayRed;
        public double overlayGreen;
        public double overlayBlue;
    }

    public static final class RankedMaterial {
        public final int id;
        public final double share;

        public RankedMaterial(int id, double share) {
            this.id = id;
            this.share = share;
        }
    }

    public static final class RankedObject {
        public final int id;
        public final String name;
        public final double share;

        public RankedObject(int id, String name, double share) {
            this.id = id;
            this.name = name;
            this.share = share;
        }
    }

    public static final class RankedToken {
        public final String token;
        public final double share;

        public RankedToken(String token, double share) {
            this.token = token;
            this.share = share;
        }
    }

    public static final class ObjectPlacementProfile {
        public int landTileCount;
        public int waterTileCount;
        public double totalNamedObjectDensity;
        public ObjectCategoryPlacement trees;
        public ObjectCategoryPlacement shrubs;
        public ObjectCategoryPlacement decor;
    }

    public static final class ObjectCategoryPlacement {
        public final int objectCount;
        public final int occupiedTileCount;
        public final double tileDensity;
        public final double objectDensity;
        public final double adjacencyRatio;
        public final double meanNearestNeighborDistance;
        public final String layoutType;
        public final List<ObjectPlacementEntry> objects;

        public ObjectCategoryPlacement(
                int objectCount,
                int occupiedTileCount,
                double tileDensity,
                double objectDensity,
                double adjacencyRatio,
                double meanNearestNeighborDistance,
                String layoutType,
                List<ObjectPlacementEntry> objects
        ) {
            this.objectCount = objectCount;
            this.occupiedTileCount = occupiedTileCount;
            this.tileDensity = tileDensity;
            this.objectDensity = objectDensity;
            this.adjacencyRatio = adjacencyRatio;
            this.meanNearestNeighborDistance = meanNearestNeighborDistance;
            this.layoutType = layoutType;
            this.objects = objects;
        }
    }

    public static final class ObjectPlacementEntry {
        public final int id;
        public final String name;
        public final int objectCount;
        public final int tileCount;
        public final double objectShare;
        public final double tileDensity;
        public final int dominantType;
        public final int dominantOrientation;

        public ObjectPlacementEntry(
                int id,
                String name,
                int objectCount,
                int tileCount,
                double objectShare,
                double tileDensity,
                int dominantType,
                int dominantOrientation
        ) {
            this.id = id;
            this.name = name;
            this.objectCount = objectCount;
            this.tileCount = tileCount;
            this.objectShare = objectShare;
            this.tileDensity = tileDensity;
            this.dominantType = dominantType;
            this.dominantOrientation = dominantOrientation;
        }
    }

    public static final class SettlementProfile {
        public String id;
        public int sourceRegionHash;
        public String layoutStyle;
        public int sampledBuildingCount;
        public int primaryUnderlayId;
        public int secondaryUnderlayId;
        public int accentOverlayId;
        public int waterOverlayId;
        public int wallId;
        public int wallType;
        public int upperWallId;
        public int upperWallType;
        public int cornerId;
        public int cornerType;
        public int doorId;
        public int doorType;
        public int wallDecorId;
        public int wallDecorType;
        public int roofSlopeId;
        public int roofEdgeId;
        public int roofEdgeType;
        public int roofCornerId;
        public int roofCornerType;
        public int roofInsetCornerId;
        public int roofInsetCornerType;
        public int roofFlatId;
        public int roofFlatType;
        public int perimeterTiles;
        public int minAnchors;
        public int maxAnchors;
        public int minSatellites;
        public int maxSatellites;
        public List<SettlementPieceDefinition> anchorPieces;
        public List<SettlementPieceDefinition> satellitePieces;
        public List<SettlementFootprint> anchorFootprints;
        public List<SettlementFootprint> satelliteFootprints;
        public List<SettlementBuildingTemplate> buildingTemplates;
        public List<SettlementRoofTemplate> roofTemplates;
        public List<SettlementProp> props;
    }

    public static final class SettlementFootprint {
        public final int width;
        public final int height;
        public final double weight;
        public final int floors;
        public final HouseStyleDefinition.FootprintShape shapeHint;

        public SettlementFootprint(int width, int height, double weight, int floors) {
            this(width, height, weight, floors, null);
        }

        public SettlementFootprint(int width, int height, double weight, int floors, HouseStyleDefinition.FootprintShape shapeHint) {
            this.width = width;
            this.height = height;
            this.weight = weight;
            this.floors = floors;
            this.shapeHint = shapeHint;
        }
    }

    public static final class SettlementProp {
        public final int id;
        public final int type;
        public final double weight;

        public SettlementProp(int id, int type, double weight) {
            this.id = id;
            this.type = type;
            this.weight = weight;
        }
    }

    public static final class SettlementPieceDefinition {
        public final String id;
        public final String role;
        public final int width;
        public final int height;
        public final int floors;
        public final double weight;
        public final HouseStyleDefinition.FootprintShape shapeHint;
        public final List<String> mask;
        public final int frontOrientation;
        public final List<SettlementPieceMarker> doors;
        public final List<SettlementPieceMarker> windows;
        public final List<SettlementPieceMarker> ladders;
        public final List<SettlementPieceObject> objects;

        public SettlementPieceDefinition(
                String id,
                String role,
                int width,
                int height,
                int floors,
                double weight,
                HouseStyleDefinition.FootprintShape shapeHint,
                List<String> mask,
                int frontOrientation,
                List<SettlementPieceMarker> doors,
                List<SettlementPieceMarker> windows,
                List<SettlementPieceMarker> ladders,
                List<SettlementPieceObject> objects
        ) {
            this.id = id;
            this.role = role;
            this.width = width;
            this.height = height;
            this.floors = floors;
            this.weight = weight;
            this.shapeHint = shapeHint;
            this.mask = mask;
            this.frontOrientation = frontOrientation;
            this.doors = doors;
            this.windows = windows;
            this.ladders = ladders;
            this.objects = objects;
        }
    }

    public static final class SettlementPieceMarker {
        public final int z;
        public final int x;
        public final int y;

        public SettlementPieceMarker(int z, int x, int y) {
            this.z = z;
            this.x = x;
            this.y = y;
        }
    }

    public static final class SettlementPieceObject {
        public final int z;
        public final int x;
        public final int y;
        public final String roleKey;
        public final int id;
        public final int type;
        public final int orientation;

        public SettlementPieceObject(int z, int x, int y, String roleKey, int id, int type, int orientation) {
            this.z = z;
            this.x = x;
            this.y = y;
            this.roleKey = roleKey;
            this.id = id;
            this.type = type;
            this.orientation = orientation;
        }
    }

    public static final class SettlementRoofTemplate {
        public final int width;
        public final int height;
        public final int floors;
        public final List<SettlementRoofObject> objects;
        public final List<SettlementRoofTile> tiles;

        public SettlementRoofTemplate(
                int width,
                int height,
                int floors,
                List<SettlementRoofObject> objects,
                List<SettlementRoofTile> tiles
        ) {
            this.width = width;
            this.height = height;
            this.floors = floors;
            this.objects = objects;
            this.tiles = tiles;
        }
    }

    public static final class SettlementBuildingTemplate {
        public final int width;
        public final int height;
        public final int floors;
        public final double weight;
        public final List<SettlementTemplateObject> objects;
        public final List<SettlementTemplateTile> tiles;

        public SettlementBuildingTemplate(
                int width,
                int height,
                int floors,
                double weight,
                List<SettlementTemplateObject> objects,
                List<SettlementTemplateTile> tiles
        ) {
            this.width = width;
            this.height = height;
            this.floors = floors;
            this.weight = weight;
            this.objects = objects;
            this.tiles = tiles;
        }
    }

    public static final class SettlementTemplateObject {
        public final int plane;
        public final int x;
        public final int y;
        public final int id;
        public final int type;
        public final int orientation;

        public SettlementTemplateObject(int plane, int x, int y, int id, int type, int orientation) {
            this.plane = plane;
            this.x = x;
            this.y = y;
            this.id = id;
            this.type = type;
            this.orientation = orientation;
        }
    }

    public static final class SettlementTemplateTile {
        public final int plane;
        public final int x;
        public final int y;
        public final int underlayId;
        public final int overlayId;
        public final int shape;
        public final int orientation;
        public final int tileFlags;

        public SettlementTemplateTile(
                int plane,
                int x,
                int y,
                int underlayId,
                int overlayId,
                int shape,
                int orientation,
                int tileFlags
        ) {
            this.plane = plane;
            this.x = x;
            this.y = y;
            this.underlayId = underlayId;
            this.overlayId = overlayId;
            this.shape = shape;
            this.orientation = orientation;
            this.tileFlags = tileFlags;
        }
    }

    public static final class SettlementRoofObject {
        public final int x;
        public final int y;
        public final int id;
        public final int type;
        public final int orientation;

        public SettlementRoofObject(int x, int y, int id, int type, int orientation) {
            this.x = x;
            this.y = y;
            this.id = id;
            this.type = type;
            this.orientation = orientation;
        }
    }

    public static final class SettlementRoofTile {
        public final int x;
        public final int y;
        public final int underlayId;
        public final int overlayId;
        public final int shape;
        public final int orientation;
        public final int tileFlags;

        public SettlementRoofTile(int x, int y, int underlayId, int overlayId, int shape, int orientation, int tileFlags) {
            this.x = x;
            this.y = y;
            this.underlayId = underlayId;
            this.overlayId = overlayId;
            this.shape = shape;
            this.orientation = orientation;
            this.tileFlags = tileFlags;
        }
    }

    private static final class Dataset {
        private final double[][] rawRows;
        private final double[][] normalizedRows;
        private final double[] means;
        private final double[] standardDeviations;

        private Dataset(double[][] rawRows, double[][] normalizedRows, double[] means, double[] standardDeviations) {
            this.rawRows = rawRows;
            this.normalizedRows = normalizedRows;
            this.means = means;
            this.standardDeviations = standardDeviations;
        }
    }

    private static final class KMeansResult {
        private final int[] assignments;
        private final double[][] centroids;

        private KMeansResult(int[] assignments, double[][] centroids) {
            this.assignments = assignments;
            this.centroids = centroids;
        }
    }

    private static final class Aggregates {
        private final ClusterMetrics metrics;
        private final List<RegionRef> exampleRegions;
        private final List<RankedMaterial> topUnderlays;
        private final List<RankedMaterial> topOverlays;
        private final List<RankedObject> topObjects;
        private final List<RankedToken> topTokens;

        private Aggregates(
                ClusterMetrics metrics,
                List<RegionRef> exampleRegions,
                List<RankedMaterial> topUnderlays,
                List<RankedMaterial> topOverlays,
                List<RankedObject> topObjects,
                List<RankedToken> topTokens
        ) {
            this.metrics = metrics;
            this.exampleRegions = exampleRegions;
            this.topUnderlays = topUnderlays;
            this.topOverlays = topOverlays;
            this.topObjects = topObjects;
            this.topTokens = topTokens;
        }
    }

    private static final class RegionDistance {
        private final int hash;
        private final double distance;

        private RegionDistance(int hash, double distance) {
            this.hash = hash;
            this.distance = distance;
        }
    }

    private static final class MapIndexEntry {
        private final int hash;
        private final int landscapeId;
        private final int objectId;

        private MapIndexEntry(int hash, int landscapeId, int objectId) {
            this.hash = hash;
            this.landscapeId = landscapeId;
            this.objectId = objectId;
        }
    }

    private static final class RegionObject {
        private final int id;
        private final int type;
        private final int orientation;
        private final int x;
        private final int y;
        private final int plane;

        private RegionObject(int id, int type, int orientation, int x, int y, int plane) {
            this.id = id;
            this.type = type;
            this.orientation = orientation;
            this.x = x;
            this.y = y;
            this.plane = plane;
        }
    }

    private static final class RegionObservation {
        private final int hash;
        private final Map<Integer, Integer> underlayCounts;
        private final Map<Integer, Integer> overlayCounts;
        private final Map<Integer, Integer> objectCounts;
        private final Map<String, Integer> tokenCounts;
        private final int namedObjectCount;
        private final double waterRatio;
        private final double roadRatio;
        private final double textureRatio;
        private final double heightStdDev;
        private final double meanSlope;
        private final double underlayRed;
        private final double underlayGreen;
        private final double underlayBlue;
        private final double overlayRed;
        private final double overlayGreen;
        private final double overlayBlue;

        private RegionObservation(
                int hash,
                Map<Integer, Integer> underlayCounts,
                Map<Integer, Integer> overlayCounts,
                Map<Integer, Integer> objectCounts,
                Map<String, Integer> tokenCounts,
                int namedObjectCount,
                double waterRatio,
                double roadRatio,
                double textureRatio,
                double heightStdDev,
                double meanSlope,
                double underlayRed,
                double underlayGreen,
                double underlayBlue,
                double overlayRed,
                double overlayGreen,
                double overlayBlue
        ) {
            this.hash = hash;
            this.underlayCounts = underlayCounts;
            this.overlayCounts = overlayCounts;
            this.objectCounts = objectCounts;
            this.tokenCounts = tokenCounts;
            this.namedObjectCount = namedObjectCount;
            this.waterRatio = waterRatio;
            this.roadRatio = roadRatio;
            this.textureRatio = textureRatio;
            this.heightStdDev = heightStdDev;
            this.meanSlope = meanSlope;
            this.underlayRed = underlayRed;
            this.underlayGreen = underlayGreen;
            this.underlayBlue = underlayBlue;
            this.overlayRed = overlayRed;
            this.overlayGreen = overlayGreen;
            this.overlayBlue = overlayBlue;
        }

        private static RegionObservation from(int hash, MapRegion mapRegion, List<RegionObject> objects) {
            Map<Integer, Integer> underlays = new HashMap<>();
            Map<Integer, Integer> overlays = new HashMap<>();
            Map<Integer, Integer> objectCounts = new HashMap<>();
            Map<String, Integer> tokenCounts = new LinkedHashMap<>();

            double waterTiles = 0.0;
            double roadTiles = 0.0;
            double texturedTiles = 0.0;
            double totalTiles = REGION_SIZE * REGION_SIZE;

            double underlayRedSum = 0.0;
            double underlayGreenSum = 0.0;
            double underlayBlueSum = 0.0;
            int underlaySamples = 0;

            double overlayRedSum = 0.0;
            double overlayGreenSum = 0.0;
            double overlayBlueSum = 0.0;
            int overlaySamples = 0;

            double heightSum = 0.0;
            double heightSquareSum = 0.0;
            double slopeSum = 0.0;
            int heightSamples = 0;

            for (int x = 0; x < REGION_SIZE; x++) {
                for (int y = 0; y < REGION_SIZE; y++) {
                    int underlayId = mapRegion.underlays[0][x][y] & 0xffff;
                    int overlayId = mapRegion.overlays[0][x][y] & 0xffff;
                    Floor underlay = underlayId > 0 ? FloorDefinitionLoader.getUnderlay(underlayId - 1) : null;
                    Floor overlay = overlayId > 0 ? FloorDefinitionLoader.getOverlay(overlayId - 1) : null;

                    if (underlayId > 0) {
                        underlays.merge(underlayId, 1, Integer::sum);
                    }
                    if (overlayId > 0) {
                        overlays.merge(overlayId, 1, Integer::sum);
                    }

                    if (underlay != null && underlay.getRgb() != 0 && underlay.getRgb() != -1) {
                        underlayRedSum += red(underlay.getRgb());
                        underlayGreenSum += green(underlay.getRgb());
                        underlayBlueSum += blue(underlay.getRgb());
                        underlaySamples++;
                    }
                    if (overlay != null && overlay.getRgb() != 0 && overlay.getRgb() != -1) {
                        overlayRedSum += red(overlay.getRgb());
                        overlayGreenSum += green(overlay.getRgb());
                        overlayBlueSum += blue(overlay.getRgb());
                        overlaySamples++;
                    }

                    if ((overlay != null && isLikelyWater(overlay)) || (underlay != null && isLikelyWater(underlay))) {
                        waterTiles++;
                    }
                    if (overlay != null && isLikelyRoad(overlay)) {
                        roadTiles++;
                    }
                    if ((underlay != null && underlay.getTexture() != -1) || (overlay != null && overlay.getTexture() != -1)) {
                        texturedTiles++;
                    }

                    double height = Math.abs(mapRegion.tileHeights[0][x][y]);
                    double east = Math.abs(mapRegion.tileHeights[0][x + 1][y]);
                    double north = Math.abs(mapRegion.tileHeights[0][x][y + 1]);
                    heightSum += height;
                    heightSquareSum += height * height;
                    slopeSum += Math.abs(height - east) + Math.abs(height - north);
                    heightSamples++;
                }
            }

            int namedObjectCount = 0;
            for (RegionObject object : objects) {
                if (object.plane != 0) {
                    continue;
                }
                objectCounts.merge(object.id, 1, Integer::sum);
                ObjectDefinition definition = ObjectDefinitionLoader.lookup(object.id);
                if (definition == null) {
                    continue;
                }
                String name = safeName(definition.getName());
                if (name.isEmpty() || "null".equals(name)) {
                    continue;
                }
                namedObjectCount++;
                for (String token : tokenize(name)) {
                    tokenCounts.merge(token, 1, Integer::sum);
                }
            }

            double meanHeight = heightSamples == 0 ? 0.0 : heightSum / heightSamples;
            double heightVariance = heightSamples == 0 ? 0.0 : (heightSquareSum / heightSamples) - (meanHeight * meanHeight);
            double heightStdDev = Math.sqrt(Math.max(0.0, heightVariance));
            double meanSlope = heightSamples == 0 ? 0.0 : slopeSum / (heightSamples * 2.0);

            return new RegionObservation(
                    hash,
                    underlays,
                    overlays,
                    objectCounts,
                    tokenCounts,
                    namedObjectCount,
                    waterTiles / totalTiles,
                    roadTiles / totalTiles,
                    texturedTiles / totalTiles,
                    heightStdDev,
                    meanSlope,
                    underlaySamples == 0 ? 0.0 : underlayRedSum / underlaySamples,
                    underlaySamples == 0 ? 0.0 : underlayGreenSum / underlaySamples,
                    underlaySamples == 0 ? 0.0 : underlayBlueSum / underlaySamples,
                    overlaySamples == 0 ? 0.0 : overlayRedSum / overlaySamples,
                    overlaySamples == 0 ? 0.0 : overlayGreenSum / overlaySamples,
                    overlaySamples == 0 ? 0.0 : overlayBlueSum / overlaySamples
            );
        }
    }
}
