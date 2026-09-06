package com.rspsi.tools;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.major.map.RenderFlags;

import com.jagex.Client;
import com.jagex.Client.LoadState;
import com.jagex.map.SceneGraph;
import com.jagex.map.tile.SceneTile;
import com.jagex.util.BitFlag;
import com.rspsi.options.Options;

public class BridgeBuilder {

	private static final int BRIDGE_OVERLAY_ID = 10;
	private static final int BRIDGE_SIDE_WALL_ID = 982;
	private static final int BRIDGE_SIDE_WALL_TYPE = 0;
	
	public static void buildBridge() throws Exception {
		Client client = Client.getSingleton();
		if(client.loadState == LoadState.ACTIVE) {
			if(Options.currentHeight.get() == 3) {
				throw new Exception("Invalid height");
			}
			List<SceneTile> selectedTiles = client.sceneGraph.getSelectedTiles();
			if (selectedTiles == null || selectedTiles.isEmpty()) {
				throw new Exception("No tiles selected");
			}
			int oldHeight = Options.tileHeightLevel.get();
			BitFlag oldFlag = Options.tileFlags.get();
			int oldOverlayShape = Options.overlayPaintShapeId.get();
			int oldOverlayId = Options.overlayPaintId.get();
			
			int lowerZ = Options.currentHeight.get();
			List<SceneTile> tilesAbove = selectedTiles.stream()
					.map(tile -> ensureTile(client, tile.plane + 1, tile.positionX, tile.positionY))
					.filter(Objects::nonNull)
					.collect(Collectors.toList());
			if (tilesAbove.isEmpty()) {
				throw new Exception("Could not create bridge tiles");
			}
			List<SceneTile> tilesAround = tilesAbove
					.stream()
					.flatMap(tile -> IntStream
										.rangeClosed(-1, 1)
										.boxed()
										.flatMap(x -> IntStream.rangeClosed(-1, 1)
												.mapToObj(y -> ensureTile(client, tile.plane, tile.positionX + x, tile.positionY + y))))
					.filter(Objects::nonNull)
					.filter(tile -> !tilesAbove.contains(tile))
					.distinct()
					.collect(Collectors.toList());
			int highestHeight = selectedTiles.stream().mapToInt(tile -> -client.mapRegion.tileHeights[lowerZ][tile.positionX][tile.positionY]).max().getAsInt();

			BitFlag bridgeFlag = new BitFlag();
			bridgeFlag.flag(RenderFlags.BRIDGE_TILE);
			
			Options.tileHeightLevel.set(highestHeight);
			Options.tileFlags.set(bridgeFlag);
			Options.overlayPaintShapeId.set(1);
			Options.overlayPaintId.set(BRIDGE_OVERLAY_ID);
			
			System.out.println(tilesAround.size());
			System.out.println("Setting abs values to " + Options.tileHeightLevel.get());
			client.sceneGraph.setTileFlags(tilesAbove);
			client.sceneGraph.setTileHeights(tilesAbove, true);
			client.sceneGraph.setTileHeights(tilesAround, true);
			client.sceneGraph.setTileOverlays(tilesAbove);
 
			Options.tileHeightLevel.set(oldHeight);
			Options.tileFlags.set(oldFlag);
			Options.overlayPaintShapeId.set(oldOverlayShape);
			Options.overlayPaintId.set(oldOverlayId);
			
			int minX = selectedTiles.stream().mapToInt(tile -> tile.positionX).min().orElse(0);
			int maxX = selectedTiles.stream().mapToInt(tile -> tile.positionX).max().orElse(0);
			int minY = selectedTiles.stream().mapToInt(tile -> tile.positionY).min().orElse(0);
			int maxY = selectedTiles.stream().mapToInt(tile -> tile.positionY).max().orElse(0);
			client.sceneGraph.updateHeights(minX - 3, minY - 3, (maxX - minX) + 6, (maxY - minY) + 6);
			client.mapRegion.updateTiles();
			Client.updateChunkTiles();
			SceneGraph.onCycleEnd.add(() -> {
				spawnBridgeSideWalls(client, selectedTiles, lowerZ + 1);
				client.mapRegion.updateTiles();
				SceneGraph.minimapUpdate = true;
				client.sceneGraph.tileQueue.clear();
				client.forceMapUpdate();
			});
			com.jagex.map.SceneGraph.minimapUpdate = true;
			Options.allHeightsVisible.set(true);
			client.sceneGraph.tileQueue.clear();
			
		}
	}

	private static SceneTile ensureTile(Client client, int plane, int x, int y) {
		if (client == null || client.sceneGraph == null || client.sceneGraph.tiles == null) {
			return null;
		}
		if (plane < 0 || plane >= client.sceneGraph.tiles.length) {
			return null;
		}
		if (x < 0 || y < 0 || x >= client.sceneGraph.width || y >= client.sceneGraph.length) {
			return null;
		}
		SceneTile tile = client.sceneGraph.tiles[plane][x][y];
		if (tile == null) {
			tile = new SceneTile(x, y, plane);
			client.sceneGraph.tiles[plane][x][y] = tile;
		}
		return tile;
	}

	private static void spawnBridgeSideWalls(Client client, List<SceneTile> selectedTiles, int bridgePlane) {
		if (client == null || client.mapRegion == null || client.sceneGraph == null || selectedTiles == null || selectedTiles.isEmpty()) {
			return;
		}
		int minX = selectedTiles.stream().mapToInt(tile -> tile.positionX).min().orElse(0);
		int maxX = selectedTiles.stream().mapToInt(tile -> tile.positionX).max().orElse(0);
		int minY = selectedTiles.stream().mapToInt(tile -> tile.positionY).min().orElse(0);
		int maxY = selectedTiles.stream().mapToInt(tile -> tile.positionY).max().orElse(0);
		int spanX = maxX - minX;
		int spanY = maxY - minY;

		if (spanX >= spanY) {
			for (int x = minX; x <= maxX; x++) {
				client.mapRegion.spawnObjectToWorld(client.sceneGraph, BRIDGE_SIDE_WALL_ID, x, minY, bridgePlane, BRIDGE_SIDE_WALL_TYPE, 3, false);
				client.mapRegion.spawnObjectToWorld(client.sceneGraph, BRIDGE_SIDE_WALL_ID, x, maxY, bridgePlane, BRIDGE_SIDE_WALL_TYPE, 1, false);
			}
		} else {
			for (int y = minY; y <= maxY; y++) {
				client.mapRegion.spawnObjectToWorld(client.sceneGraph, BRIDGE_SIDE_WALL_ID, minX, y, bridgePlane, BRIDGE_SIDE_WALL_TYPE, 0, false);
				client.mapRegion.spawnObjectToWorld(client.sceneGraph, BRIDGE_SIDE_WALL_ID, maxX, y, bridgePlane, BRIDGE_SIDE_WALL_TYPE, 2, false);
			}
		}
	}

}
