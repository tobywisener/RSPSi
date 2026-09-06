package com.jagex.cache.def;

import com.jagex.io.Buffer;
import org.displee.cache.index.archive.Archive;
import org.displee.cache.index.archive.file.File;

public final class TextureDef
{
	private TextureDef()
	{
	}

	public static void unpackConfig(Archive streamLoader)
	{
		byte[] data = readTextureConfig(streamLoader);
		if (data == null || data.length < 2) {
			textures = new TextureDef[0];
			return;
		}

		Buffer buffer = new Buffer(data);
		int count = buffer.readUShort();
		textures = new TextureDef[count];
		for (int i = 0; i != count; ++i)
			if (buffer.readUByte() == 1)
				textures[i] = new TextureDef();


		for (int i = 0; i != count; ++i)
			if (textures[i] != null)
				textures[i].aBoolean1223 = buffer.readUByte() == 1;


		for (int i = 0; i != count; ++i)
			if (textures[i] != null)
				textures[i].aBoolean1204 = buffer.readUByte() == 1;


		for (int i = 0; i != count; ++i)
			if (textures[i] != null)
				textures[i].aBoolean1205 = buffer.readUByte() == 1;


		for (int i = 0; i != count; ++i)
			if (textures[i] != null)
				textures[i].aByte1217 = buffer.readByte();


		for (int i = 0; i != count; ++i)
			if (textures[i] != null)
				textures[i].aByte1225 = buffer.readByte();


		for (int i = 0; i != count; ++i)
			if (textures[i] != null)
				textures[i].aByte1214 = buffer.readByte();


		for (int i = 0; i != count; ++i)
			if (textures[i] != null)
				textures[i].aByte1213 = buffer.readByte();


		for (int i = 0; i != count; ++i)
			if (textures[i] != null)
				textures[i].aShort1221 = (short) buffer.readUShort();


		for (int i = 0; i != count; ++i)
			if (textures[i] != null)
				textures[i].aByte1211 = buffer.readByte();


		for (int i = 0; i != count; ++i)
			if (textures[i] != null)
				textures[i].aByte1203 = buffer.readByte();


		for (int i = 0; i != count; ++i)
			if (textures[i] != null)
				textures[i].aBoolean1222 = buffer.readUByte() == 1;


		for (int i = 0; i != count; ++i)
			if (textures[i] != null)
				textures[i].aBoolean1216 = buffer.readUByte() == 1;


		for (int i = 0; i != count; ++i)
			if (textures[i] != null)
				textures[i].aByte1207 = buffer.readByte();


		for (int i = 0; i != count; ++i)
			if (textures[i] != null)
				textures[i].aBoolean1212 = buffer.readUByte() == 1;


		for (int i = 0; i != count; ++i)
			if (textures[i] != null)
				textures[i].aBoolean1210 = buffer.readUByte() == 1;


		for (int i = 0; i != count; ++i)
			if (textures[i] != null)
				textures[i].aBoolean1215 = buffer.readUByte() == 1;


		for (int i = 0; i != count; ++i)
			if (textures[i] != null)
				textures[i].anInt1202 = buffer.readUByte();


		for (int i = 0; i != count; ++i)
			if (textures[i] != null)
				textures[i].anInt1206 = buffer.readInt();


		for (int i = 0; i != count; ++i)
			if (textures[i] != null)
				textures[i].anInt1226 = buffer.readUByte();


	}

	private static byte[] readTextureConfig(Archive archive) {
		if (archive == null) {
			return null;
		}

		byte[] data = archive.readFile("textures.dat");
		if (data != null) {
			return data;
		}

		// Some caches store textures.dat as unnamed file 0 inside a single-file archive.
		File[] files = archive.getFiles();
		if (files != null && files.length == 1) {
			File file = files[0];
			return file == null ? null : file.getData();
		}
		return null;
	}

	public static void ensureLoaded(int count) {
		if (count <= 0) {
			textures = new TextureDef[0];
			return;
		}

		if (textures == null) {
			textures = new TextureDef[count];
		} else if (textures.length < count) {
			TextureDef[] expanded = new TextureDef[count];
			System.arraycopy(textures, 0, expanded, 0, textures.length);
			textures = expanded;
		}

		for (int i = 0; i < textures.length; i++) {
			if (textures[i] == null) {
				textures[i] = new TextureDef();
			}
		}
	}

	public static void nullLoader()
	{
		textures = null;
	}

	public boolean aBoolean1223;
	public boolean aBoolean1204;
	public boolean aBoolean1205;
	public byte aByte1217;
	public byte aByte1225;
	public byte aByte1214;
	public byte aByte1213;
	public short aShort1221;
	public byte aByte1211;
	public byte aByte1203;
	public boolean aBoolean1222;
	public boolean aBoolean1216;
	public byte aByte1207;
	public boolean aBoolean1212;
	public boolean aBoolean1210;
	public boolean aBoolean1215;
	public int anInt1202;
	public int anInt1206;
	public int anInt1226;
	public static TextureDef[] textures;
}
