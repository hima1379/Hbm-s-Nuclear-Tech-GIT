package com.hbm.explosion;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.math.BlockPos;

/**
 * PROCESSOR ENTRY ADAPTER
 *
 * Converts internal processor entries to unified BlockDestructionEntry format.
 * This allows gradual migration from the old system to the new unified system
 * without rewriting all processors at once.
 *
 * USAGE:
 * 1. Processor pre-calculates destruction pattern (existing code)
 * 2. Adapter extracts entries and converts to unified format
 * 3. UnifiedDestructionExecutor merges and executes
 */
public class ProcessorEntryAdapter {

	/**
	 * Extract entries from FireballVolumeProcessor
	 * (Internal access via reflection or getter method)
	 */
	public static List<BlockDestructionEntry> extractFireballEntries(
		FireballVolumeProcessor processor) {

		// TODO: Access internal allBlocks list via getter
		// For now, return empty list - will implement after adding getter to processor
		return new ArrayList<>();
	}

	/**
	 * Extract entries from ThermalRadiationProcessor
	 */
	public static List<BlockDestructionEntry> extractThermalEntries(
		ThermalRadiationProcessor processor) {

		return new ArrayList<>();
	}

	/**
	 * Extract entries from BlastPressureFieldProcessor
	 */
	public static List<BlockDestructionEntry> extractBlastEntries(
		BlastPressureFieldProcessor processor) {

		return new ArrayList<>();
	}

	/**
	 * Convert internal entry to unified entry
	 * (Helper method for processors to use directly)
	 */
	public static BlockDestructionEntry createEntry(
		int x, int y, int z, double distance, DestructionType type) {

		BlockPos pos = new BlockPos(x, y, z);
		return new BlockDestructionEntry(pos, type, 1.0, distance);
	}

	/**
	 * Convert internal entry with custom strength
	 */
	public static BlockDestructionEntry createEntry(
		int x, int y, int z, double distance, DestructionType type, double strength) {

		BlockPos pos = new BlockPos(x, y, z);
		return new BlockDestructionEntry(pos, type, strength, distance);
	}
}
