package com.hbm.uninos;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

/**
 * A directional position - combines BlockPos with EnumFacing for UNINOS connections.
 * Used to define connection points for network nodes.
 *
 * @author Adapted for 1.12.2
 */
public class DirPos {

	private final BlockPos pos;
	private final EnumFacing dir;

	public DirPos(BlockPos pos, EnumFacing dir) {
		this.pos = pos;
		this.dir = dir;
	}

	public DirPos(int x, int y, int z, EnumFacing dir) {
		this(new BlockPos(x, y, z), dir);
	}

	public BlockPos getPos() {
		return pos;
	}

	public EnumFacing getDir() {
		return dir;
	}

	public int getX() {
		return pos.getX();
	}

	public int getY() {
		return pos.getY();
	}

	public int getZ() {
		return pos.getZ();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (!(obj instanceof DirPos)) return false;
		DirPos other = (DirPos) obj;
		return pos.equals(other.pos) && dir == other.dir;
	}

	@Override
	public int hashCode() {
		return 31 * pos.hashCode() + dir.hashCode();
	}

	@Override
	public String toString() {
		return "DirPos[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ", " + dir + "]";
	}
}
