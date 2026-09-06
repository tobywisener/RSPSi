package com.rspsi.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jagex.Client;
import com.jagex.map.SceneGraph;
import com.jagex.map.tile.SceneTile;
import com.rspsi.misc.JsonUtil;
import com.rspsi.options.Options;

import java.util.ArrayList;
import java.util.List;

public final class SettlementPiecePlacer {

    private static final ObjectMapper JSON_MAPPER = JsonUtil.getDefaultMapper();

    private SettlementPiecePlacer() {
    }

    public static void insertFromJson(String json) throws Exception {
        if (json == null || json.isBlank()) {
            throw new Exception("No SettlementPiece JSON provided");
        }

        Client client = Client.getSingleton();
        if (client == null || client.sceneGraph == null || client.mapRegion == null || client.loadState != Client.LoadState.ACTIVE) {
            throw new Exception("Map is not active");
        }
        if (Options.currentHeight.get() != 0) {
            throw new Exception("SettlementPiece insertion currently only supports ground level");
        }

        List<SceneTile> selectedTiles = client.sceneGraph.getSelectedTiles();
        if (selectedTiles == null || selectedTiles.isEmpty()) {
            throw new Exception("No tile selected");
        }

        SceneTile anchorTile = selectedTiles.get(0);
        ParsedSettlementPiece parsed = JSON_MAPPER.readValue(json, ParsedSettlementPiece.class);
        CacheBiomeProfileMiner.SettlementPieceDefinition piece = toDefinition(parsed);
        if (piece.width <= 0 || piece.height <= 0) {
            throw new Exception("SettlementPiece must have positive width and height");
        }

        BuildingStyle activeStyle = BuildingGenerator.getSelectedStyle();
        if (activeStyle == null || activeStyle.usesHouseType()) {
            throw new Exception("Insert SettlementPiece requires a JSON settlement style. Select Pollnivneach or another settlement style first.");
        }
        boolean shouldStampShell = piece.shapeHint != null || (piece.mask != null && !piece.mask.isEmpty());
        int minX = anchorTile.positionX;
        int minY = anchorTile.positionY;
        int maxX = shouldStampShell ? minX + piece.width + 3 : minX + piece.width - 1;
        int maxY = shouldStampShell ? minY + piece.height + 3 : minY + piece.height - 1;
        int tileHeight = (int) Math.round(-client.mapRegion.tileHeights[0][anchorTile.positionX][anchorTile.positionY]);
        int doorOrientation = normalizeFrontOrientation(piece.frontOrientation);
        int doorCoord = doorOrientation == 0 || doorOrientation == 2
                ? minY + 2 + Math.max(0, piece.height / 2)
                : minX + 2 + Math.max(0, piece.width / 2);
        if (shouldStampShell) {
            BuildingGenerator.stampBuilding(
                    client.mapRegion,
                    client.sceneGraph,
                    activeStyle,
                    minX,
                    minY,
                    maxX,
                    maxY,
                    tileHeight,
                    Math.max(1, piece.floors),
                    doorOrientation,
                    doorCoord,
                    piece.shapeHint,
                    piece
            );
        }

        placePieceObjects(client, activeStyle, piece, minX, minY, shouldStampShell ? 2 : 0);
        refreshScene(client);
    }

    private static void placePieceObjects(Client client, BuildingStyle style,
                                          CacheBiomeProfileMiner.SettlementPieceDefinition piece,
                                          int minX, int minY, int originInset) {
        if (piece == null || piece.objects == null || piece.objects.isEmpty()) {
            return;
        }
        HouseStyleDefinition definition = style == null || style.usesHouseType() ? null : HouseStyleRepository.load(style);
        HouseStyleDefinition.Village village = definition == null || definition.proceduralRecipe == null ? null : definition.proceduralRecipe.village;
        for (CacheBiomeProfileMiner.SettlementPieceObject object : piece.objects) {
            if (object == null || object.z < 0 || object.z >= 4) {
                continue;
            }
            HouseStyleDefinition.Role resolvedRole = village == null || village.objectRoles == null || object.roleKey == null
                    ? null
                    : village.objectRoles.get(object.roleKey);
            int objectId = object.id > 0 ? object.id : resolvedRole == null ? 0 : resolvedRole.id;
            int objectType = object.type >= 0 ? object.type : resolvedRole == null ? -1 : resolvedRole.type;
            if (objectId <= 0 || objectType < 0) {
                continue;
            }
            client.mapRegion.spawnObjectToWorld(
                    client.sceneGraph,
                    objectId,
                    minX + originInset + object.x,
                    minY + originInset + object.y,
                    object.z,
                    objectType,
                    object.orientation,
                    false
            );
        }
    }

    private static void refreshScene(Client client) {
        client.sceneGraph.updateHeights(0, 0, client.sceneGraph.width, client.sceneGraph.length);
        client.mapRegion.updateTiles();
        Client.updateChunkTiles();
        SceneGraph.minimapUpdate = true;
        client.sceneGraph.tileQueue.clear();
        client.forceMapUpdate();
    }

    private static int normalizeFrontOrientation(int frontOrientation) {
        return frontOrientation >= 0 ? (frontOrientation & 3) : 1;
    }

    private static CacheBiomeProfileMiner.SettlementPieceDefinition toDefinition(ParsedSettlementPiece parsed) throws Exception {
        if (parsed == null) {
            throw new Exception("Invalid SettlementPiece JSON");
        }
        HouseStyleDefinition.FootprintShape shapeHint = null;
        if (parsed.shapeHint != null && !parsed.shapeHint.isBlank()) {
            shapeHint = HouseStyleDefinition.FootprintShape.valueOf(parsed.shapeHint.trim().toUpperCase());
        }
        return new CacheBiomeProfileMiner.SettlementPieceDefinition(
                parsed.id == null || parsed.id.isBlank() ? "inserted_piece" : parsed.id,
                parsed.role == null || parsed.role.isBlank() ? "FEATURE" : parsed.role,
                parsed.width,
                parsed.height,
                Math.max(1, parsed.floors),
                parsed.weight > 0.0 ? parsed.weight : 1.0,
                shapeHint,
                parsed.mask == null ? null : parsed.mask,
                parsed.frontOrientation,
                mapMarkers(parsed.doors),
                mapMarkers(parsed.windows),
                mapMarkers(parsed.ladders),
                mapObjects(parsed.objects)
        );
    }

    private static List<CacheBiomeProfileMiner.SettlementPieceMarker> mapMarkers(List<ParsedMarker> markers) {
        if (markers == null) {
            return null;
        }
        if (markers.isEmpty()) {
            return List.of();
        }
        List<CacheBiomeProfileMiner.SettlementPieceMarker> mapped = new ArrayList<>(markers.size());
        for (ParsedMarker marker : markers) {
            if (marker == null) {
                continue;
            }
            mapped.add(new CacheBiomeProfileMiner.SettlementPieceMarker(marker.z, marker.x, marker.y));
        }
        return mapped;
    }

    private static List<CacheBiomeProfileMiner.SettlementPieceObject> mapObjects(List<ParsedObject> objects) {
        if (objects == null) {
            return null;
        }
        if (objects.isEmpty()) {
            return List.of();
        }
        List<CacheBiomeProfileMiner.SettlementPieceObject> mapped = new ArrayList<>(objects.size());
        for (ParsedObject object : objects) {
            if (object == null) {
                continue;
            }
            mapped.add(new CacheBiomeProfileMiner.SettlementPieceObject(
                    object.z,
                    object.x,
                    object.y,
                    object.roleKey,
                    object.id,
                    object.type,
                    object.face
            ));
        }
        return mapped;
    }

    private static final class ParsedSettlementPiece {
        public String id;
        public String role;
        public int width;
        public int height;
        public int floors;
        public double weight;
        public String shapeHint;
        public List<String> mask;
        public int frontOrientation;
        public List<ParsedMarker> doors;
        public List<ParsedMarker> windows;
        public List<ParsedMarker> ladders;
        public List<ParsedObject> objects;
    }

    private static final class ParsedMarker {
        public int z;
        public int x;
        public int y;
    }

    private static final class ParsedObject {
        public int z;
        public int x;
        public int y;
        public String roleKey;
        public int id;
        public int type;
        public int face;
    }
}
