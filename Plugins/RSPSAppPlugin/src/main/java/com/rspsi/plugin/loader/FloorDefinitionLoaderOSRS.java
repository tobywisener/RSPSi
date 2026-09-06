package com.rspsi.plugin.loader;

import org.displee.cache.index.archive.Archive;

import java.nio.ByteBuffer;

import com.jagex.cache.def.Floor;
import com.jagex.cache.loader.floor.FloorDefinitionLoader;
import com.jagex.cache.loader.floor.FloorType;

public class FloorDefinitionLoaderOSRS extends FloorDefinitionLoader {

	private Floor[] overlays;
	private Floor[] underlays;

	@Override
	public void init(Archive archive) {
		ByteBuffer buffer = ByteBuffer.wrap(archive.readFile("flo.dat"));
		int underlayAmount = buffer.getShort();
		System.out.println("Underlay Floors Loaded: " + underlayAmount);
		underlays = new Floor[underlayAmount];
		for (int i = 0; i < underlayAmount; i++) {
			underlays[i] = decodeUnderlay(buffer);
			underlays[i].generateHsl();
		}
		int overlayAmount = buffer.getShort();
		System.out.println("Overlay Floors Loaded: " + overlayAmount);
		overlays = new Floor[overlayAmount];
		for (int i = 0; i < overlayAmount; i++) {
			
			overlays[i] = decodeOverlay(buffer);
			overlays[i].generateHsl();
		}
	}

	@Override
	public void init(byte[] data) {
		ByteBuffer buffer = ByteBuffer.wrap(data);
		int underlayAmount = buffer.getShort();
		System.out.println("Underlay Floors Loaded: " + underlayAmount);
		underlays = new Floor[underlayAmount];
		for (int i = 0; i < underlayAmount; i++) {
			underlays[i] = decodeUnderlay(buffer);
			underlays[i].generateHsl();
		}
		int overlayAmount = buffer.getShort();
		System.out.println("Overlay Floors Loaded: " + overlayAmount);
		overlays = new Floor[overlayAmount];
		for (int i = 0; i < overlayAmount; i++) {
			
			overlays[i] = decodeOverlay(buffer);
			overlays[i].generateHsl();
		}
	}

	public Floor decodeUnderlay(ByteBuffer buffer) {
		Floor floor = new Floor();
		while (true){
			int opcode = buffer.get();
			if (opcode == 0) {
				break;
			} else if (opcode == 1) {
				int rgb = ((buffer.get() & 0xff) << 16) + ((buffer.get() & 0xff) << 8) + (buffer.get() & 0xff);
				floor.setRgb(rgb);
			} else if (opcode == 2) {
				floor.setTexture(buffer.getShort() & 0xffff);
			} else {
				System.out.println("Error unrecognised underlay code: " + opcode);
			}
		}
		return floor;
	}

	public Floor decodeOverlay(ByteBuffer buffer) {
		Floor floor = new Floor();
		while (true) {
			int opcode = buffer.get();
			if (opcode == 0) {
				break;
			} else if (opcode == 1) {
				int rgb = ((buffer.get() & 0xff) << 16) + ((buffer.get() & 0xff) << 8) + (buffer.get() & 0xff);
				floor.setRgb(rgb);
			} else if (opcode == 2) {
				int texture = buffer.get() & 0xff;
				floor.setTexture(texture);
			} else if (opcode == 5) {
				floor.setShadowed(false);
			} else if (opcode == 7) {
				int anotherRgb = ((buffer.get() & 0xff) << 16) + ((buffer.get() & 0xff) << 8) + (buffer.get() & 0xff);
				floor.setAnotherRgb(anotherRgb);
			} else if (opcode == 8) {
				// no-op in some cache variants
			} else if (opcode == 9) {
				buffer.getShort();
			} else if (opcode == 10) {
				// no-op in some cache variants
			} else if (opcode == 11) {
				buffer.get();
			} else if (opcode == 12) {
				// no-op in some cache variants
			} else if (opcode == 13) {
				buffer.get();
				buffer.get();
				buffer.get();
			} else if (opcode == 14) {
				buffer.get();
			} else if (opcode == 15) {
				buffer.getShort();
			} else if (opcode == 16) {
				buffer.get();
			} else {
				System.out.println("Error unrecognised overlay code: " + opcode);
			}
		}
		return floor;
	}
	
	@Override
	public Floor getFloor(int id, FloorType type) {
		Floor[] floors = type == FloorType.OVERLAY ? overlays : underlays;
		if (floors == null || floors.length == 0) {
			return null;
		}
		if (id < 0) {
			id = 0;
		} else if (id >= floors.length) {
			id = floors.length - 1;
		}
		return floors[id];
	}


	@Override
	public int getSize(FloorType type) {
		if (type == FloorType.OVERLAY) {
			return overlays == null ? 0 : overlays.length;
		}
		return underlays == null ? 0 : underlays.length;
	}

	@Override
	public int count() {
		return 0;
	}

	@Override
	public Floor forId(int arg0) {
		return null;
	}


}
