package com.hbm.explosion;

import net.minecraft.util.math.BlockPos;

/**
 * BLOCK DESTRUCTION ENTRY
 *
 * Represents a single block scheduled for destruction with metadata
 * about the destruction type, strength, and position.
 *
 * USAGE:
 * 1. Each processor (Fireball, Thermal, Blast) creates entries
 * 2. Entries are collected without destroying blocks
 * 3. Entries are merged (highest priority wins for duplicates)
 * 4. Unified executor destroys blocks in distance order
 */
public class BlockDestructionEntry {

	private final BlockPos pos;
	private final DestructionType type;
	private final double strength;
	private final double distance;

	/**
	 * Create a destruction entry
	 *
	 * @param pos Block position
	 * @param type Destruction mechanism type
	 * @param strength Destruction strength (0.0-1.0)
	 * @param distance Distance from explosion center
	 */
	public BlockDestructionEntry(BlockPos pos, DestructionType type,
	                              double strength, double distance) {
		this.pos = pos;
		this.type = type;
		this.strength = Math.max(0.0, Math.min(1.0, strength));
		this.distance = distance;
	}

	/**
	 * Create a destruction entry with full strength
	 */
	public BlockDestructionEntry(BlockPos pos, DestructionType type, double distance) {
		this(pos, type, 1.0, distance);
	}

	// === GETTERS ===

	public BlockPos getPos() {
		return pos;
	}

	public DestructionType getType() {
		return type;
	}

	public double getStrength() {
		return strength;
	}

	public double getDistance() {
		return distance;
	}

	// === PRIORITY COMPARISON ===

	/**
	 * Check if this entry should override another entry for the same block
	 * Priority rules:
	 * 1. Higher DestructionType priority wins
	 * 2. If same type, higher strength wins
	 * 3. If same strength, closer distance wins
	 */
	public boolean shouldOverride(BlockDestructionEntry other) {
		if (this.type.getPriority() != other.type.getPriority()) {
			return this.type.getPriority() > other.type.getPriority();
		}

		if (Math.abs(this.strength - other.strength) > 0.001) {
			return this.strength > other.strength;
		}

		return this.distance < other.distance;
	}

	@Override
	public String toString() {
		return String.format("DestructionEntry[pos=%s, type=%s, str=%.2f, dist=%.1fm]",
			pos, type, strength, distance);
	}
}
