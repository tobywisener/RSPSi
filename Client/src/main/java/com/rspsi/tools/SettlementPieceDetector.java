package com.rspsi.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jagex.Client;
import com.jagex.cache.def.ObjectDefinition;
import com.jagex.cache.loader.object.ObjectDefinitionLoader;
import com.jagex.map.object.DefaultWorldObject;
import com.jagex.map.object.GameObject;
import com.jagex.map.object.GroundDecoration;
import com.jagex.map.object.Wall;
import com.jagex.map.object.WallDecoration;
import com.jagex.map.tile.SceneTile;
import com.jagex.util.ObjectKey;
import com.rspsi.misc.JsonUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SettlementPieceDetector {

    private static final ObjectMapper JSON_MAPPER = JsonUtil.getDefaultMapper();
    private static final int MAX_INTERIOR_TILES = 512;
    private static final Set<Integer> STRUCTURAL_OBJECT_TYPES = Set.of(
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            12, 16, 17, 18, 19, 21
    );

    private SettlementPieceDetector() {
    }

    public static String detectSelectedSettlementPieceJson() throws Exception {
        Client client = Client.getSingleton();
        if (client == null || client.sceneGraph == null || client.loadState != Client.LoadState.ACTIVE) {
            throw new Exception("Map is not active");
        }

        List<SceneTile> selectedTiles = client.sceneGraph.getSelectedTiles();
        if (selectedTiles == null || selectedTiles.isEmpty()) {
            throw new Exception("No tile selected");
        }

        SceneTile seedTile = selectedTiles.get(0);
        DetectedSettlementPiece piece = selectedTiles.size() == 1
                ? detectPiece(client, seedTile)
                : detectSelectedAreaPiece(client, selectedTiles, seedTile);
        try {
            return JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(piece);
        } catch (JsonProcessingException ex) {
            throw new Exception("Could not serialize SettlementPiece", ex);
        }
    }

    private static DetectedSettlementPiece detectPiece(Client client, SceneTile seedTile) throws Exception {
        Set<Long> occupiedTiles = floodInterior(client, seedTile);
        if (occupiedTiles.isEmpty()) {
            throw new Exception("Could not detect enclosed building area");
        }

        int minX = occupiedTiles.stream().mapToInt(SettlementPieceDetector::decodeX).min().orElse(seedTile.positionX);
        int maxX = occupiedTiles.stream().mapToInt(SettlementPieceDetector::decodeX).max().orElse(seedTile.positionX);
        int minY = occupiedTiles.stream().mapToInt(SettlementPieceDetector::decodeY).min().orElse(seedTile.positionY);
        int maxY = occupiedTiles.stream().mapToInt(SettlementPieceDetector::decodeY).max().orElse(seedTile.positionY);

        List<DetectedPieceObject> objects = collectInteriorObjects(client, occupiedTiles, seedTile.plane, minX, minY);
        List<DetectedPieceMarker> doors = collectDoorMarkers(client, occupiedTiles, seedTile.plane, minX, minY);
        List<DetectedPieceMarker> windows = collectWindowMarkers(client, occupiedTiles, seedTile.plane, minX, minY);
        List<DetectedPieceMarker> ladders = extractLadderMarkers(objects);
        int frontOrientation = resolveFrontOrientation(client, occupiedTiles, seedTile, minX, maxX, minY, maxY, doors);
        int floors = detectFloors(client, occupiedTiles, seedTile.plane, minX, maxX, minY, maxY, objects, doors, windows, ladders);

        return new DetectedSettlementPiece(
                "detected_piece",
                "HOUSE",
                maxX - minX + 1,
                maxY - minY + 1,
                floors,
                1.0,
                inferShape(occupiedTiles, minX, maxX, minY, maxY),
                buildMask(inferShape(occupiedTiles, minX, maxX, minY, maxY), occupiedTiles, minX, maxX, minY, maxY),
                frontOrientation,
                doors,
                windows,
                ladders,
                objects
        );
    }

    private static DetectedSettlementPiece detectSelectedAreaPiece(Client client, List<SceneTile> selectedTiles, SceneTile seedTile) {
        Set<Long> occupiedTiles = new LinkedHashSet<>();
        int basePlane = seedTile.plane;
        for (SceneTile tile : selectedTiles) {
            if (tile != null && tile.plane == basePlane) {
                occupiedTiles.add(encode(tile.positionX, tile.positionY));
            }
        }

        int minX = occupiedTiles.stream().mapToInt(SettlementPieceDetector::decodeX).min().orElse(seedTile.positionX);
        int maxX = occupiedTiles.stream().mapToInt(SettlementPieceDetector::decodeX).max().orElse(seedTile.positionX);
        int minY = occupiedTiles.stream().mapToInt(SettlementPieceDetector::decodeY).min().orElse(seedTile.positionY);
        int maxY = occupiedTiles.stream().mapToInt(SettlementPieceDetector::decodeY).max().orElse(seedTile.positionY);

        List<DetectedPieceObject> objects = collectObjects(client, occupiedTiles, basePlane, minX, minY, true);
        int floors = detectFloors(client, occupiedTiles, basePlane, minX, maxX, minY, maxY, objects, List.of(), List.of(), List.of());

        return new DetectedSettlementPiece(
                "detected_piece",
                "FEATURE",
                maxX - minX + 1,
                maxY - minY + 1,
                floors,
                1.0,
                inferShape(occupiedTiles, minX, maxX, minY, maxY),
                buildMask(inferShape(occupiedTiles, minX, maxX, minY, maxY), occupiedTiles, minX, maxX, minY, maxY),
                findClosestBoundaryOrientation(client, occupiedTiles, seedTile),
                List.of(),
                List.of(),
                List.of(),
                objects
        );
    }

    private static Set<Long> floodInterior(Client client, SceneTile seedTile) throws Exception {
        Set<Long> visited = new LinkedHashSet<>();
        ArrayDeque<SceneTile> queue = new ArrayDeque<>();
        queue.add(seedTile);

        while (!queue.isEmpty()) {
            SceneTile tile = queue.removeFirst();
            if (tile == null || tile.plane != seedTile.plane) {
                continue;
            }
            long key = encode(tile.positionX, tile.positionY);
            if (!visited.add(key)) {
                continue;
            }
            if (visited.size() > MAX_INTERIOR_TILES) {
                throw new Exception("Selected area is not enclosed enough to detect a building");
            }

            tryAddNeighbour(client, seedTile.plane, tile.positionX, tile.positionY, 1, 0, seedTile, queue);
            tryAddNeighbour(client, seedTile.plane, tile.positionX, tile.positionY, -1, 0, seedTile, queue);
            tryAddNeighbour(client, seedTile.plane, tile.positionX, tile.positionY, 0, 1, seedTile, queue);
            tryAddNeighbour(client, seedTile.plane, tile.positionX, tile.positionY, 0, -1, seedTile, queue);
        }
        return visited;
    }

    private static void tryAddNeighbour(Client client, int plane, int x, int y, int dirX, int dirY, SceneTile seedTile, ArrayDeque<SceneTile> queue) {
        int nextX = x + dirX;
        int nextY = y + dirY;
        if (nextX < 0 || nextY < 0 || nextX >= client.sceneGraph.width || nextY >= client.sceneGraph.length) {
            return;
        }
        SceneTile current = client.sceneGraph.tiles[plane][x][y];
        SceneTile next = client.sceneGraph.tiles[plane][nextX][nextY];
        if (current == null || next == null) {
            return;
        }
        if (edgeBlocked(current, dirX, dirY) || edgeBlocked(next, -dirX, -dirY)) {
            return;
        }
        if (!matchesInteriorSurface(client, plane, nextX, nextY, seedTile)) {
            return;
        }
        queue.addLast(next);
    }

    private static boolean matchesInteriorSurface(Client client, int plane, int x, int y, SceneTile seedTile) {
        if (seedTile == null || client == null || client.mapRegion == null) {
            return true;
        }
        int seedOverlay = client.mapRegion.overlays[plane][seedTile.positionX][seedTile.positionY] & 0xffff;
        int seedUnderlay = client.mapRegion.underlays[plane][seedTile.positionX][seedTile.positionY] & 0xffff;
        int overlay = client.mapRegion.overlays[plane][x][y] & 0xffff;
        int underlay = client.mapRegion.underlays[plane][x][y] & 0xffff;

        if (seedOverlay > 0) {
            return overlay == seedOverlay;
        }
        if (seedUnderlay > 0) {
            return underlay == seedUnderlay;
        }
        return overlay == 0 && underlay == 0;
    }

    private static boolean edgeBlocked(SceneTile tile, int dirX, int dirY) {
        if (tile == null || tile.wall == null) {
            return false;
        }
        ObjectKey key = tile.wall.getKey();
        if (key == null) {
            return false;
        }
        int direction = directionFromDelta(dirX, dirY);
        if (direction == -1) {
            return false;
        }
        int type = key.getType();
        int orientation = key.getOrientation() & 3;

        if (type == 0) {
            return orientation == direction;
        }
        if (type == 2) {
            return orientation == direction || ((orientation + 1) & 3) == direction;
        }
        if (type == 1 || type == 3) {
            return cornerBlocksDirection(orientation, direction);
        }
        return false;
    }

    private static boolean cornerBlocksDirection(int orientation, int direction) {
        return switch (orientation & 3) {
            case 0 -> direction == 0 || direction == 1;
            case 1 -> direction == 1 || direction == 2;
            case 2 -> direction == 2 || direction == 3;
            case 3 -> direction == 0 || direction == 3;
            default -> false;
        };
    }

    private static int directionFromDelta(int dirX, int dirY) {
        if (dirX > 0) {
            return 0;
        }
        if (dirY < 0) {
            return 1;
        }
        if (dirX < 0) {
            return 2;
        }
        if (dirY > 0) {
            return 3;
        }
        return -1;
    }

    private static String inferShape(Set<Long> occupiedTiles, int minX, int maxX, int minY, int maxY) {
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        int area = width * height;
        if (occupiedTiles.size() == area) {
            return "RECT";
        }

        Set<Long> missing = new HashSet<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                long key = encode(x, y);
                if (!occupiedTiles.contains(key)) {
                    missing.add(key);
                }
            }
        }
        if (missing.isEmpty()) {
            return "RECT";
        }
        if (isCornerRectangle(missing, minX, maxX, minY, maxY)) {
            return "L";
        }
        if (isSideRectangle(missing, minX, maxX, minY, maxY)) {
            return "U";
        }
        return "MASK";
    }

    private static boolean isCornerRectangle(Set<Long> missing, int minX, int maxX, int minY, int maxY) {
        int missMinX = missing.stream().mapToInt(SettlementPieceDetector::decodeX).min().orElse(minX);
        int missMaxX = missing.stream().mapToInt(SettlementPieceDetector::decodeX).max().orElse(maxX);
        int missMinY = missing.stream().mapToInt(SettlementPieceDetector::decodeY).min().orElse(minY);
        int missMaxY = missing.stream().mapToInt(SettlementPieceDetector::decodeY).max().orElse(maxY);
        boolean cornerAnchored =
                (missMinX == minX && missMinY == minY)
                        || (missMinX == minX && missMaxY == maxY)
                        || (missMaxX == maxX && missMinY == minY)
                        || (missMaxX == maxX && missMaxY == maxY);
        return cornerAnchored && fillsRectangle(missing, missMinX, missMaxX, missMinY, missMaxY);
    }

    private static boolean isSideRectangle(Set<Long> missing, int minX, int maxX, int minY, int maxY) {
        int missMinX = missing.stream().mapToInt(SettlementPieceDetector::decodeX).min().orElse(minX);
        int missMaxX = missing.stream().mapToInt(SettlementPieceDetector::decodeX).max().orElse(maxX);
        int missMinY = missing.stream().mapToInt(SettlementPieceDetector::decodeY).min().orElse(minY);
        int missMaxY = missing.stream().mapToInt(SettlementPieceDetector::decodeY).max().orElse(maxY);
        boolean sideAnchored =
                (missMinY == minY && missMinX > minX && missMaxX < maxX)
                        || (missMaxY == maxY && missMinX > minX && missMaxX < maxX)
                        || (missMinX == minX && missMinY > minY && missMaxY < maxY)
                        || (missMaxX == maxX && missMinY > minY && missMaxY < maxY);
        return sideAnchored && fillsRectangle(missing, missMinX, missMaxX, missMinY, missMaxY);
    }

    private static boolean fillsRectangle(Set<Long> cells, int minX, int maxX, int minY, int maxY) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                if (!cells.contains(encode(x, y))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int findClosestBoundaryOrientation(Client client, Set<Long> occupiedTiles, SceneTile seedTile) {
        int bestDistance = Integer.MAX_VALUE;
        int bestOrientation = 1;
        for (long tileKey : occupiedTiles) {
            int x = decodeX(tileKey);
            int y = decodeY(tileKey);
            for (int[] dir : DIRECTIONS) {
                int nextX = x + dir[0];
                int nextY = y + dir[1];
                if (nextX >= 0 && nextY >= 0
                        && nextX < client.sceneGraph.width
                        && nextY < client.sceneGraph.length
                        && occupiedTiles.contains(encode(nextX, nextY))) {
                    continue;
                }
                SceneTile tile = client.sceneGraph.tiles[seedTile.plane][x][y];
                if (!edgeBlocked(tile, dir[0], dir[1])) {
                    continue;
                }
                int distance = Math.abs(seedTile.positionX - x) + Math.abs(seedTile.positionY - y);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestOrientation = directionFromDelta(dir[0], dir[1]);
                }
            }
        }
        return bestOrientation;
    }

    private static int resolveFrontOrientation(Client client, Set<Long> occupiedTiles, SceneTile seedTile,
                                               int minX, int maxX, int minY, int maxY, List<DetectedPieceMarker> doors) {
        if (doors != null && !doors.isEmpty()) {
            DetectedPieceMarker door = doors.get(0);
            int relativeX = door.x();
            int relativeY = door.y();
            if (relativeX <= -1) {
                return 2;
            }
            if (relativeX >= (maxX - minX + 1)) {
                return 0;
            }
            if (relativeY <= -1) {
                return 1;
            }
            if (relativeY >= (maxY - minY + 1)) {
                return 3;
            }
        }
        return findClosestBoundaryOrientation(client, occupiedTiles, seedTile);
    }

    private static List<DetectedPieceObject> collectInteriorObjects(Client client, Set<Long> occupiedTiles, int basePlane, int minX, int minY) {
        return collectObjects(client, occupiedTiles, basePlane, minX, minY, false);
    }

    private static List<DetectedPieceObject> collectObjects(
            Client client,
            Set<Long> occupiedTiles,
            int basePlane,
            int minX,
            int minY,
            boolean includeStructural
    ) {
        List<DetectedPieceObject> objects = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (long tileKey : occupiedTiles) {
            int x = decodeX(tileKey);
            int y = decodeY(tileKey);
            for (int plane = basePlane; plane < client.sceneGraph.tiles.length; plane++) {
                SceneTile tile = client.sceneGraph.tiles[plane][x][y];
                if (tile == null) {
                    continue;
                }
                appendObject(objects, seen, tile.groundDecoration, plane - basePlane, minX, minY, includeStructural);
                appendObject(objects, seen, tile.wall, plane - basePlane, minX, minY, includeStructural);
                appendObject(objects, seen, tile.wallDecoration, plane - basePlane, minX, minY, includeStructural);
                for (GameObject object : tile.gameObjects) {
                    appendObject(objects, seen, object, plane - basePlane, minX, minY, includeStructural);
                }
            }
        }
        return objects;
    }

    private static List<DetectedPieceMarker> collectDoorMarkers(Client client, Set<Long> occupiedTiles, int basePlane, int minX, int minY) {
        List<DetectedPieceMarker> markers = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<Integer> dominantWallIds = collectDominantEnvelopeWallIds(client, occupiedTiles, basePlane);
        for (long scanKey : collectExpandedEnvelopeTileKeys(occupiedTiles, 2)) {
            int x = decodeX(scanKey);
            int y = decodeY(scanKey);
            for (int plane = basePlane; plane < client.sceneGraph.tiles.length; plane++) {
                SceneTile tile = client.sceneGraph.tiles[plane][x][y];
                appendDoorMarker(markers, seen, tile == null ? null : tile.wall, plane - basePlane, minX, minY, occupiedTiles, dominantWallIds);
                if (tile != null) {
                    for (GameObject object : tile.gameObjects) {
                        appendDoorMarker(markers, seen, object, plane - basePlane, minX, minY, occupiedTiles, dominantWallIds);
                    }
                }
            }
        }
        markers.sort(Comparator.comparingInt(DetectedPieceMarker::z)
                .thenComparingInt(DetectedPieceMarker::y)
                .thenComparingInt(DetectedPieceMarker::x));
        return markers;
    }

    private static List<DetectedPieceMarker> collectWindowMarkers(Client client, Set<Long> occupiedTiles, int basePlane, int minX, int minY) {
        List<DetectedPieceMarker> markers = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (long scanKey : collectEnvelopeTileKeys(occupiedTiles)) {
            int x = decodeX(scanKey);
            int y = decodeY(scanKey);
            for (int plane = basePlane; plane < client.sceneGraph.tiles.length; plane++) {
                SceneTile tile = client.sceneGraph.tiles[plane][x][y];
                appendWindowMarker(markers, seen, tile == null ? null : tile.wallDecoration, plane - basePlane, minX, minY, occupiedTiles, true);
                if (tile != null) {
                    for (GameObject object : tile.gameObjects) {
                        appendWindowMarker(markers, seen, object, plane - basePlane, minX, minY, occupiedTiles, false);
                    }
                }
            }
        }
        markers.sort(Comparator.comparingInt(DetectedPieceMarker::z)
                .thenComparingInt(DetectedPieceMarker::y)
                .thenComparingInt(DetectedPieceMarker::x));
        return markers;
    }

    private static List<DetectedPieceMarker> extractLadderMarkers(List<DetectedPieceObject> objects) {
        List<DetectedPieceMarker> markers = new ArrayList<>();
        List<DetectedPieceObject> removable = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (DetectedPieceObject object : objects) {
            if (!isLadderLike(object.id())) {
                continue;
            }
            String uniqueKey = object.x() + ":" + object.y();
            if (seen.add(uniqueKey)) {
                markers.add(new DetectedPieceMarker(0, object.x(), object.y()));
            }
            removable.add(object);
        }
        objects.removeAll(removable);
        markers.sort(Comparator.comparingInt(DetectedPieceMarker::y)
                .thenComparingInt(DetectedPieceMarker::x));
        return markers;
    }

    private static int detectFloors(
            Client client,
            Set<Long> occupiedTiles,
            int basePlane,
            int minX,
            int maxX,
            int minY,
            int maxY,
            List<DetectedPieceObject> objects,
            List<DetectedPieceMarker> doors,
            List<DetectedPieceMarker> windows,
            List<DetectedPieceMarker> ladders
    ) {
        int maxZ = 0;
        for (DetectedPieceObject object : objects) {
            maxZ = Math.max(maxZ, object.z());
        }
        for (DetectedPieceMarker marker : doors) {
            maxZ = Math.max(maxZ, marker.z());
        }
        for (DetectedPieceMarker marker : windows) {
            maxZ = Math.max(maxZ, marker.z());
        }
        for (DetectedPieceMarker marker : ladders) {
            maxZ = Math.max(maxZ, marker.z());
        }
        maxZ = Math.max(maxZ, detectStructuralFloors(client, occupiedTiles, basePlane, minX, maxX, minY, maxY));
        return maxZ + 1;
    }

    private static List<String> buildMask(String shapeHint, Set<Long> occupiedTiles, int minX, int maxX, int minY, int maxY) {
        if ("RECT".equalsIgnoreCase(shapeHint)) {
            return null;
        }
        List<String> rows = new ArrayList<>();
        for (int y = minY; y <= maxY; y++) {
            StringBuilder row = new StringBuilder(maxX - minX + 1);
            for (int x = minX; x <= maxX; x++) {
                row.append(occupiedTiles.contains(encode(x, y)) ? '1' : '0');
            }
            rows.add(row.toString());
        }
        return rows;
    }

    private static Set<Long> collectBoundaryTileKeys(Set<Long> occupiedTiles) {
        Set<Long> boundaryTiles = new LinkedHashSet<>();
        for (long tileKey : occupiedTiles) {
            int x = decodeX(tileKey);
            int y = decodeY(tileKey);
            for (int[] direction : DIRECTIONS) {
                int boundaryX = x + direction[0];
                int boundaryY = y + direction[1];
                long boundaryKey = encode(boundaryX, boundaryY);
                if (!occupiedTiles.contains(boundaryKey)) {
                    boundaryTiles.add(boundaryKey);
                }
            }
        }
        return boundaryTiles;
    }

    private static Set<Long> collectEnvelopeTileKeys(Set<Long> occupiedTiles) {
        Set<Long> envelopeTiles = new LinkedHashSet<>(occupiedTiles);
        envelopeTiles.addAll(collectBoundaryTileKeys(occupiedTiles));
        return envelopeTiles;
    }

    private static Set<Long> collectExpandedEnvelopeTileKeys(Set<Long> occupiedTiles, int radius) {
        Set<Long> expanded = new LinkedHashSet<>(collectEnvelopeTileKeys(occupiedTiles));
        for (long tileKey : collectEnvelopeTileKeys(occupiedTiles)) {
            int x = decodeX(tileKey);
            int y = decodeY(tileKey);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    expanded.add(encode(x + dx, y + dy));
                }
            }
        }
        return expanded;
    }

    private static int detectStructuralFloors(Client client, Set<Long> occupiedTiles, int basePlane,
                                              int minX, int maxX, int minY, int maxY) {
        if (client == null || client.sceneGraph == null || client.sceneGraph.tiles == null) {
            return 0;
        }
        int maxRelativePlane = 0;
        for (long scanKey : collectEnvelopeTileKeys(occupiedTiles)) {
            int x = decodeX(scanKey);
            int y = decodeY(scanKey);
            if (x < 0 || y < 0 || x >= client.sceneGraph.width || y >= client.sceneGraph.length) {
                continue;
            }
            for (int plane = basePlane; plane < client.sceneGraph.tiles.length; plane++) {
                SceneTile tile = client.sceneGraph.tiles[plane][x][y];
                if (tile == null) {
                    continue;
                }
                if (hasStructureOnTile(tile, minX, maxX, minY, maxY, occupiedTiles)) {
                    maxRelativePlane = Math.max(maxRelativePlane, plane - basePlane);
                }
            }
        }
        return maxRelativePlane;
    }

    private static boolean hasStructureOnTile(SceneTile tile, int minX, int maxX, int minY, int maxY, Set<Long> occupiedTiles) {
        return isStructuralObjectInEnvelope(tile.wall, minX, maxX, minY, maxY, occupiedTiles)
                || isStructuralObjectInEnvelope(tile.wallDecoration, minX, maxX, minY, maxY, occupiedTiles)
                || isStructuralObjectInEnvelope(tile.groundDecoration, minX, maxX, minY, maxY, occupiedTiles)
                || hasStructuralGameObject(tile.gameObjects, minX, maxX, minY, maxY, occupiedTiles);
    }

    private static boolean hasStructuralGameObject(GameObject[] objects, int minX, int maxX, int minY, int maxY, Set<Long> occupiedTiles) {
        if (objects == null) {
            return false;
        }
        for (GameObject object : objects) {
            if (isStructuralObjectInEnvelope(object, minX, maxX, minY, maxY, occupiedTiles)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStructuralObjectInEnvelope(DefaultWorldObject object, int minX, int maxX, int minY, int maxY, Set<Long> occupiedTiles) {
        if (object == null) {
            return false;
        }
        ObjectKey key = object.getKey();
        if (key == null || !isWithinEnvelope(key.getX(), key.getY(), minX, maxX, minY, maxY, occupiedTiles)) {
            return false;
        }
        return true;
    }

    private static boolean isWithinEnvelope(int x, int y, int minX, int maxX, int minY, int maxY, Set<Long> occupiedTiles) {
        if (x < minX - 1 || x > maxX + 1 || y < minY - 1 || y > maxY + 1) {
            return false;
        }
        if (occupiedTiles.contains(encode(x, y))) {
            return true;
        }
        return x == minX - 1 || x == maxX + 1 || y == minY - 1 || y == maxY + 1
                || x == minX || x == maxX || y == minY || y == maxY;
    }

    private static boolean isWithinEnvelope(int x, int y, Set<Long> occupiedTiles) {
        int minX = occupiedTiles.stream().mapToInt(SettlementPieceDetector::decodeX).min().orElse(x);
        int maxX = occupiedTiles.stream().mapToInt(SettlementPieceDetector::decodeX).max().orElse(x);
        int minY = occupiedTiles.stream().mapToInt(SettlementPieceDetector::decodeY).min().orElse(y);
        int maxY = occupiedTiles.stream().mapToInt(SettlementPieceDetector::decodeY).max().orElse(y);
        return isWithinEnvelope(x, y, minX, maxX, minY, maxY, occupiedTiles);
    }

    private static void appendDoorMarker(
            List<DetectedPieceMarker> markers,
            Set<String> seen,
            DefaultWorldObject object,
            int relativePlane,
            int minX,
            int minY,
            Set<Long> occupiedTiles,
            Set<Integer> dominantWallIds
    ) {
        if (object == null) {
            return;
        }
        ObjectKey key = object.getKey();
        if (key == null || !isWithinEnvelope(key.getX(), key.getY(), occupiedTiles)) {
            return;
        }
        boolean nameMatch = isDoorLike(key.getId());
        boolean wallOutlier = object instanceof Wall && !dominantWallIds.contains(key.getId());
        if (!nameMatch && !wallOutlier) {
            return;
        }
        String uniqueKey = relativePlane + ":" + (key.getX() - minX) + ":" + (key.getY() - minY);
        if (!seen.add(uniqueKey)) {
            return;
        }
        markers.add(new DetectedPieceMarker(relativePlane, key.getX() - minX, key.getY() - minY));
    }

    private static void appendWindowMarker(
            List<DetectedPieceMarker> markers,
            Set<String> seen,
            DefaultWorldObject object,
            int relativePlane,
            int minX,
            int minY,
            Set<Long> occupiedTiles,
            boolean acceptAnyWallDecoration
    ) {
        if (object == null) {
            return;
        }
        ObjectKey key = object.getKey();
        if (key == null || !isWithinEnvelope(key.getX(), key.getY(), occupiedTiles)) {
            return;
        }
        boolean accept = acceptAnyWallDecoration && object instanceof WallDecoration;
        if (!accept && !isWindowLike(key.getId())) {
            return;
        }
        String uniqueKey = relativePlane + ":" + (key.getX() - minX) + ":" + (key.getY() - minY);
        if (!seen.add(uniqueKey)) {
            return;
        }
        markers.add(new DetectedPieceMarker(relativePlane, key.getX() - minX, key.getY() - minY));
    }

    private static Set<Integer> collectDominantEnvelopeWallIds(Client client, Set<Long> occupiedTiles, int basePlane) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (long scanKey : collectEnvelopeTileKeys(occupiedTiles)) {
            int x = decodeX(scanKey);
            int y = decodeY(scanKey);
            if (x < 0 || y < 0 || x >= client.sceneGraph.width || y >= client.sceneGraph.length) {
                continue;
            }
            SceneTile tile = client.sceneGraph.tiles[basePlane][x][y];
            if (tile == null || tile.wall == null || tile.wall.getKey() == null) {
                continue;
            }
            int id = tile.wall.getKey().getId();
            counts.merge(id, 1, Integer::sum);
        }
        List<Map.Entry<Integer, Integer>> sorted = counts.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .toList();
        Set<Integer> dominant = new HashSet<>();
        for (int index = 0; index < Math.min(2, sorted.size()); index++) {
            dominant.add(sorted.get(index).getKey());
        }
        return dominant;
    }

    private static void appendObject(
            List<DetectedPieceObject> objects,
            Set<String> seen,
            DefaultWorldObject object,
            int relativePlane,
            int minX,
            int minY,
            boolean includeStructural
    ) {
        if (object == null) {
            return;
        }
        if (!includeStructural && (object instanceof Wall || object instanceof WallDecoration)) {
            return;
        }
        ObjectKey key = object.getKey();
        if (key == null) {
            return;
        }
        if (!includeStructural && STRUCTURAL_OBJECT_TYPES.contains(key.getType())) {
            return;
        }
        String uniqueKey = object.getPlane() + ":" + key.getX() + ":" + key.getY() + ":" + key.getId() + ":" + key.getType() + ":" + key.getOrientation();
        if (!seen.add(uniqueKey)) {
            return;
        }
        objects.add(new DetectedPieceObject(
                relativePlane,
                key.getX() - minX,
                key.getY() - minY,
                key.getId(),
                key.getType(),
                key.getOrientation()
        ));
    }

    private static long encode(int x, int y) {
        return (((long) x) << 32) | (y & 0xffffffffL);
    }

    private static int decodeX(long key) {
        return (int) (key >> 32);
    }

    private static int decodeY(long key) {
        return (int) key;
    }

    private static boolean isDoorLike(int id) {
        String name = normalizedObjectName(id);
        return name.contains("door") || name.contains("gate");
    }

    private static boolean isWindowLike(int id) {
        String name = normalizedObjectName(id);
        return name.contains("window") || name.contains("shutter");
    }

    private static boolean isLadderLike(int id) {
        return normalizedObjectName(id).contains("ladder");
    }

    private static String normalizedObjectName(int id) {
        ObjectDefinition definition = ObjectDefinitionLoader.lookup(id);
        if (definition == null || definition.getName() == null) {
            return "";
        }
        return definition.getName().trim().toLowerCase();
    }

    private static final int[][] DIRECTIONS = {
            {1, 0},
            {0, -1},
            {-1, 0},
            {0, 1}
    };

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record DetectedSettlementPiece(
            String id,
            String role,
            int width,
            int height,
            int floors,
            double weight,
            String shapeHint,
            List<String> mask,
            int frontOrientation,
            List<DetectedPieceMarker> doors,
            List<DetectedPieceMarker> windows,
            List<DetectedPieceMarker> ladders,
            List<DetectedPieceObject> objects
    ) {
    }

    private record DetectedPieceMarker(
            int z,
            int x,
            int y
    ) {
    }

    private record DetectedPieceObject(
            int z,
            int x,
            int y,
            int id,
            int type,
            int face
    ) {
    }
}
