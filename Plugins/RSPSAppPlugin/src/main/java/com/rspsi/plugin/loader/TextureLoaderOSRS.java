package com.rspsi.plugin.loader;

import org.displee.cache.index.archive.Archive;
import org.displee.cache.index.archive.file.File;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.jagex.cache.graphics.IndexedImage;
import com.jagex.cache.loader.textures.TextureLoader;
import com.jagex.draw.textures.Texture;
import com.jagex.io.Buffer;
import com.rspsi.misc.FixedHashMap;

public class TextureLoaderOSRS extends TextureLoader {

	private static final int TEXTURE_SIZE = 128;

	private Texture[] textures = new Texture[0];
	private TextureDefinition[] definitions = new TextureDefinition[0];
	private boolean[] transparent = new boolean[0];
	private Archive textureArchive;
	private int textureArchiveCount = 0;
	private double brightness = 0.8;
	private final FixedHashMap<Integer, int[]> textureCache = new FixedHashMap<Integer, int[]>(20);

	@Override
	public Texture forId(int id) {
		if (id < 0) {
			return null;
		}

		if (id < textures.length && textures[id] != null) {
			return textures[id];
		}

		TextureDefinition definition = id < definitions.length ? definitions[id] : null;
		if (definition != null) {
			int[] pixels = decodeTexturePixels(definition);
			if (pixels != null) {
				ensureCapacity(id + 1);
				Texture decoded = new DecodedTexture(TEXTURE_SIZE, TEXTURE_SIZE, pixels);
				textures[id] = decoded;
				return decoded;
			}
		}

		Texture directTexture = loadTextureDirectById(id);
		if (directTexture != null) {
			ensureCapacity(id + 1);
			textures[id] = directTexture;
			return directTexture;
		}
		return id < textures.length ? textures[id] : null;
	}

	@Override
	public int[] getPixels(int textureId) {
		Texture texture = forId(textureId);
		if (texture == null) {
			return null;
		}

		if (textureCache.contains(textureId)) {
			return textureCache.get(textureId);
		}

		int[] texels = new int[0x10000];

		texture.setBrightness(brightness);
		if (texture.getWidth() == 64) {
			for (int y = 0; y < 128; y++) {
				for (int x = 0; x < 128; x++) {
					texels[x + (y << 7)] = texture.getPixel((x >> 1) + ((y >> 1) << 6));
				}
			}
		} else {
			for (int texelPtr = 0; texelPtr < 16384; texelPtr++) {
					texels[texelPtr] = texture.getPixel(texelPtr);
			}
		}

		if (textureId >= 0 && textureId < transparent.length) {
			transparent[textureId] = false;
		}
		for (int l1 = 0; l1 < 16384; l1++) {
			texels[l1] &= 0xf8f8ff;
			int k2 = texels[l1];
			if (k2 == 0 && textureId >= 0 && textureId < transparent.length) {
					transparent[textureId] = true;
			}
			texels[16384 + l1] = k2 - (k2 >>> 3) & 0xf8f8ff;
			texels[32768 + l1] = k2 - (k2 >>> 2) & 0xf8f8ff;
			texels[49152 + l1] = k2 - (k2 >>> 2) - (k2 >>> 3) & 0xf8f8ff;
		}

		textureCache.put(textureId, texels);
		return texels;
	}

	@Override
	public void init(Archive archive) {
		init(archive, archive);
	}

	public void init(Archive textureArchive, Archive configArchive) {
		this.textureArchive = textureArchive;
		textureArchiveCount = 0;
		if (textureArchive != null) {
			int highestId = textureArchive.getHighestId();
			if (highestId >= 0) {
				textureArchiveCount = highestId + 1;
			}
		}
		textureCache.clear();
		if (!loadFromTextureDefinitions(configArchive)) {
			loadLegacyByArchiveIds(textureArchive);
		}
	}

	private boolean loadFromTextureDefinitions(Archive configArchive) {
		byte[] textureConfig = readTextureConfig(configArchive);

		if (textureConfig == null || textureConfig.length < 2) {
			return false;
		}

		try {
			Buffer buffer = new Buffer(textureConfig);
			int definitionCount = buffer.readUShort();
			List<TextureDefinition> decodedDefinitions = new ArrayList<>(definitionCount);
			int maxTextureId = -1;

			for (int i = 0; i < definitionCount; i++) {
				TextureDefinition definition = decodeTextureDefinition(buffer);
				if (definition.id >= 0) {
					decodedDefinitions.add(definition);
					maxTextureId = Math.max(maxTextureId, definition.id);
				}
			}

			if (maxTextureId < 0) {
				return false;
			}

			textures = new Texture[maxTextureId + 1];
			definitions = new TextureDefinition[maxTextureId + 1];
			transparent = new boolean[maxTextureId + 1];
			for (TextureDefinition definition : decodedDefinitions) {
				if (definition.id < 0 || definition.id >= this.definitions.length) {
					continue;
				}
				this.definitions[definition.id] = definition;
				transparent[definition.id] = definition.transparent;
			}
			return true;
		} catch (Exception ex) {
			return false;
		}
	}

	private byte[] readTextureConfig(Archive configArchive) {
		if (configArchive == null) {
			return null;
		}

		byte[] textureConfig = configArchive.readFile("textures.dat");
		if (textureConfig != null) {
			return textureConfig;
		}

		// Some caches may keep textures.dat unnamed as file 0 only when archive is single-file.
		File[] files = configArchive.getFiles();
		if (files != null && files.length == 1) {
			File file = files[0];
			return file == null ? null : file.getData();
		}
		return null;
	}

	private void loadLegacyByArchiveIds(Archive archive) {
		File[] files = archive == null ? null : archive.getFiles();
		if (files == null || files.length == 0) {
			textures = new Texture[0];
			definitions = new TextureDefinition[0];
			transparent = new boolean[0];
			return;
		}

		int maxFileId = archive.getHighestId();
		if (maxFileId < 0) {
			textures = new Texture[0];
			definitions = new TextureDefinition[0];
			transparent = new boolean[0];
			return;
		}

		textures = new Texture[maxFileId + 1];
		definitions = new TextureDefinition[maxFileId + 1];
		transparent = new boolean[maxFileId + 1];
		for (File file : files) {
			if (file == null) {
				continue;
			}
			int fileId = file.getId();
			if (fileId < 0 || fileId >= textures.length) {
				continue;
			}
			try {
				IndexedImage texture = new IndexedImage(archive, String.valueOf(fileId), 0);
				texture.resize();
				textures[fileId] = new DecodedTexture(texture.getWidth(), texture.getHeight(), toPixels(texture));
			} catch (Exception ignored) {
			}
		}
	}

	private Texture loadTextureDirectById(int textureId) {
		if (textureArchive == null || textureId < 0) {
			return null;
		}
		try {
			IndexedImage texture = new IndexedImage(textureArchive, String.valueOf(textureId), 0);
			texture.resize();
			return new DecodedTexture(texture.getWidth(), texture.getHeight(), toPixels(texture));
		} catch (Exception ignored) {
			return null;
		}
	}

	private void ensureCapacity(int targetSize) {
		if (targetSize <= textures.length) {
			return;
		}
		textures = Arrays.copyOf(textures, targetSize);
		transparent = Arrays.copyOf(transparent, targetSize);
	}

	private int[] toPixels(IndexedImage image) {
		byte[] imageRaster = image.getImageRaster();
		int[] imagePalette = image.getPalette();
		int[] pixels = new int[imageRaster.length];
		for (int i = 0; i < imageRaster.length; i++) {
			int paletteIndex = imageRaster[i] & 0xff;
			if (paletteIndex < 0 || paletteIndex >= imagePalette.length) {
				paletteIndex = 0;
			}
			pixels[i] = imagePalette[paletteIndex];
		}
		return pixels;
	}

	private int[] decodeTexturePixels(TextureDefinition definition) {
		if (textureArchive == null || definition.fileIds == null || definition.fileIds.length == 0) {
			return null;
		}

		int pixelCount = TEXTURE_SIZE * TEXTURE_SIZE;
		int[] pixels = new int[pixelCount];

		for (int index = 0; index < definition.fileIds.length; index++) {
			IndexedImage image;
			try {
				int fileId = definition.fileIds[index];
				IndexedImage texture = new IndexedImage(textureArchive, String.valueOf(fileId), 0);
				texture.resize();
				image = texture;
			} catch (Exception ex) {
				return null;
			}

			byte[] palettePixels = image.getImageRaster();
			int[] palette = Arrays.copyOf(image.getPalette(), image.getPalette().length);
			for (int paletteIndex = 0; paletteIndex < palette.length; paletteIndex++) {
				if (palette[paletteIndex] == 0xff00ff) {
					palette[paletteIndex] = 0;
				}
			}

			applyColourTransform(palette, definition.getColourTransform(index));
			int blendMode = index == 0 ? 0 : definition.getBlendMode(index - 1);
			if (blendMode != 0) {
				continue;
			}

			if (TEXTURE_SIZE == image.getWidth()) {
				for (int pixel = 0; pixel < pixelCount; pixel++) {
					pixels[pixel] = palette[palettePixels[pixel] & 0xff];
				}
			} else if (image.getWidth() == 64 && TEXTURE_SIZE == 128) {
				int pixel = 0;
				for (int y = 0; y < TEXTURE_SIZE; y++) {
					for (int x = 0; x < TEXTURE_SIZE; x++) {
						pixels[pixel++] = palette[palettePixels[(y >> 1 << 6) + (x >> 1)] & 0xff];
					}
				}
			} else if (image.getWidth() == 128 && TEXTURE_SIZE == 64) {
				int pixel = 0;
				for (int y = 0; y < TEXTURE_SIZE; y++) {
					for (int x = 0; x < TEXTURE_SIZE; x++) {
						pixels[pixel++] = palette[palettePixels[(x << 1) + (y << 1 << 7)] & 0xff];
					}
				}
			} else {
				return null;
			}
		}

		return pixels;
	}

	private void applyColourTransform(int[] palette, int colourTransform) {
		if ((colourTransform & -16777216) != 50331648) {
			return;
		}

		int transformA = colourTransform & 16711935;
		int transformB = colourTransform >> 8 & 255;
		for (int index = 0; index < palette.length; index++) {
			int colour = palette[index];
			if (colour >> 8 == (colour & 65535)) {
				colour &= 255;
				palette[index] = transformA * colour >> 8 & 16711935 | transformB * colour & 65280;
			}
		}
	}

	private TextureDefinition decodeTextureDefinition(Buffer buffer) {
		TextureDefinition definition = new TextureDefinition();
		int fileCount = 0;
		for (;;) {
			int opcode = buffer.readUByte();
			if (opcode == 0) {
				return definition;
			} else if (opcode == 1) {
				definition.id = buffer.readUShort();
			} else if (opcode == 2) {
				definition.transparent = buffer.readUByte() == 1;
			} else if (opcode == 3) {
				fileCount = buffer.readUByte();
				definition.fileCount = fileCount;
			} else if (opcode == 4) {
				definition.fileIds = new int[fileCount];
				for (int index = 0; index < fileCount; index++) {
					definition.fileIds[index] = buffer.readUShort();
				}
			} else if (opcode == 5 && fileCount > 1) {
				definition.blendModes = new int[fileCount - 1];
				for (int index = 0; index < fileCount - 1; index++) {
					definition.blendModes[index] = buffer.readUByte();
				}
			} else if (opcode == 6 && fileCount > 1) {
				for (int index = 0; index < fileCount - 1; index++) {
					buffer.readUByte();
				}
			} else if (opcode == 7) {
				definition.colourTransforms = new int[fileCount];
				for (int index = 0; index < fileCount; index++) {
					definition.colourTransforms[index] = buffer.readInt();
				}
			} else if (opcode == 8) {
				definition.animationSpeed = buffer.readUShort();
			} else if (opcode == 9) {
				definition.animationDirection = buffer.readUShort();
			} else if (opcode == 10) {
				definition.averageRgb = buffer.readUTriByte();
			} else {
				throw new IllegalStateException("Unknown texture opcode " + opcode);
			}
		}
	}

	private static final class TextureDefinition {
		private int id = -1;
		private int fileCount = 0;
		private boolean transparent = false;
		private int[] fileIds = new int[0];
		private int[] blendModes = new int[0];
		private int[] colourTransforms = new int[0];
		private int animationSpeed = 0;
		private int animationDirection = 0;
		private int averageRgb = 0;

		private int getBlendMode(int index) {
			if (index < 0 || index >= blendModes.length) {
				return 0;
			}
			return blendModes[index];
		}

		private int getColourTransform(int index) {
			if (index < 0 || index >= colourTransforms.length) {
				return 0;
			}
			return colourTransforms[index];
		}
	}

	private static final class DecodedTexture extends Texture {
		private DecodedTexture(int width, int height, int[] pixels) {
			super(width, height);
			int length = Math.min(originalPixels.length, pixels.length);
			System.arraycopy(pixels, 0, originalPixels, 0, length);
			generatePalette();
		}

		@Override
		public boolean supportsAlpha() {
			return false;
		}
	}

	@Override
	public boolean isTransparent(int id) {
		if (id < 0 || id >= transparent.length) {
			return false;
		}
		return transparent[id];
	}

	@Override
	public void setBrightness(double brightness) {
		textureCache.clear();
		this.brightness = brightness;
	}

	@Override
	public int count() {
		return Math.max(Math.max(textures.length, definitions.length), textureArchiveCount);
	}

	@Override
	public void init(byte[] arg0) {
		// TODO Auto-generated method stub
	}

}
