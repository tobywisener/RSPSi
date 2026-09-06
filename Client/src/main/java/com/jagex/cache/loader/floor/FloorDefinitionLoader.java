package com.jagex.cache.loader.floor;

import com.jagex.cache.def.Floor;
import com.jagex.cache.loader.DataLoaderBase;

public abstract class FloorDefinitionLoader implements DataLoaderBase<Floor>{
	
	public static FloorDefinitionLoader instance;
	
	public static Floor getOverlay(int id) {
		if (instance == null) {
			return null;
		}
		int count = instance.getSize(FloorType.OVERLAY);
		if (count <= 0) {
			return null;
		}
		if (id < 0) {
			id = 0;
		} else if (id >= count) {
			id = count - 1;
		}
		return instance.getFloor(id, FloorType.OVERLAY);
	}
	
	public static Floor getUnderlay(int id) {
		if (instance == null) {
			return null;
		}
		int count = instance.getSize(FloorType.UNDERLAY);
		if (count <= 0) {
			return null;
		}
		if (id < 0) {
			id = 0;
		} else if (id >= count) {
			id = count - 1;
		}
		return instance.getFloor(id, FloorType.UNDERLAY);
	}
	
	public static int getUnderlayCount() {
		return instance.getSize(FloorType.UNDERLAY);
	}
	
	public static int getOverlayCount() {
		return instance.getSize(FloorType.OVERLAY);
	}
	
	public abstract Floor getFloor(int id, FloorType type);
	public abstract int getSize(FloorType type);
	
	@Override
	public Floor forId(int id) {
		return null;
	}
	
	@Override
	public int count() {
		return 0;
	}

}
