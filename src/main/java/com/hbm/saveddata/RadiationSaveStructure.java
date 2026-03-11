package com.hbm.saveddata;

import net.minecraft.nbt.NBTTagCompound;

public class RadiationSaveStructure {
	public int   chunkX;
	public int   chunkY;
	public float radiation;

	// Way-Wigner decay fields (Effects of Nuclear Weapons 1977, §9.147)
	// r1Reference: cumulative H+1 dose rate for all fallout deposited in this chunk [rads/hr].
	//   Decay formula: R(t) = r1Reference * t^(-1.2)
	// depositionTimeMs: wall-clock time (System.currentTimeMillis()) of first deposition.
	//   0L = no fallout directly deposited in this chunk.
	public float r1Reference     = 0F;
	public long  depositionTimeMs = 0L;

	public RadiationSaveStructure() { }

	public RadiationSaveStructure(int x, int y, float rad) {
		chunkX    = x;
		chunkY    = y;
		radiation = rad;
	}

	public void readFromNBT(NBTTagCompound nbt, int index) {
		chunkX    = nbt.getInteger("rad_" + index + "_x");
		chunkY    = nbt.getInteger("rad_" + index + "_y");
		radiation = nbt.getFloat("rad_" + index + "_level");
		// Read Way-Wigner fields if present; migrate legacy data if absent.
		if(nbt.hasKey("rad_" + index + "_r1")) {
			r1Reference     = nbt.getFloat("rad_" + index + "_r1");
			depositionTimeMs = nbt.getLong("rad_"  + index + "_depo");
		} else if(radiation > 0F) {
			// Legacy migration: treat current radiation as the H+1 value.
			// R(1) = r1Reference * 1^(-1.2) = r1Reference, so r1Reference = radiation.
			// Place depositionTimeMs 1 hour in the past so the formula returns the saved value.
			r1Reference     = radiation;
			depositionTimeMs = System.currentTimeMillis() - 3_600_000L;
		}
	}

	public void writeToNBT(NBTTagCompound nbt, int index) {
		nbt.setInteger("rad_" + index + "_x",     chunkX);
		nbt.setInteger("rad_" + index + "_y",     chunkY);
		nbt.setFloat(  "rad_" + index + "_level", radiation);
		// Persist Way-Wigner fields
		nbt.setFloat("rad_" + index + "_r1",   r1Reference);
		nbt.setLong( "rad_" + index + "_depo", depositionTimeMs);
	}
}
