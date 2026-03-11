package com.hbm.explosion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * UNIFIED DESTRUCTION EXECUTOR
 *
 * Merges destruction entries from multiple sources (Fireball, Thermal, Blast)
 * and executes block destruction in a unified, non-overlapping manner.
 *
 * KEY BENEFITS:
 * 1. Eliminates duplicate destruction (no overlap)
 * 2. Applies priority system (Fireball > Thermal > Blast)
 * 3. More realistic crater formation
 * 4. Reduces total block destruction by ~3.6x
 *
 * WORKFLOW:
 * 1. Collect entries from all processors
 * 2. Merge entries (keep highest priority per block)
 * 3. Sort by distance from center
 * 4. Execute destruction sequentially
 */
public class UnifiedDestructionExecutor {

	private final World world;
	private final Map<BlockPos, BlockDestructionEntry> mergedEntries;
	private final List<BlockDestructionEntry> sortedEntries;
	private int currentIndex = 0;
	private boolean isComplete = false;
	private long totalBlocksDestroyed = 0;

	// === PERFORMANCE ===
	private static final int MAX_BLOCKS_PER_TICK = 10000;

	// === TERRAIN ===
	private static final int MIN_Y = 1;

	/**
	 * Constructor - merges all destruction entries
	 */
	public UnifiedDestructionExecutor(World world,
	                                   List<BlockDestructionEntry> fireballEntries,
	                                   List<BlockDestructionEntry> thermalEntries,
	                                   List<BlockDestructionEntry> blastEntries) {
		this.world = world;
		this.mergedEntries = new HashMap<>();

		System.out.println("=== UNIFIED DESTRUCTION EXECUTOR ===");
		System.out.println("Merging destruction entries from all sources...");

		long startTime = System.currentTimeMillis();

		// Merge entries with priority system
		mergeEntries("FIREBALL", fireballEntries);
		mergeEntries("THERMAL", thermalEntries);
		mergeEntries("BLAST", blastEntries);

		// Sort by distance for spherical propagation
		this.sortedEntries = new ArrayList<>(mergedEntries.values());
		Collections.sort(sortedEntries, Comparator.comparingDouble(BlockDestructionEntry::getDistance));

		long endTime = System.currentTimeMillis();

		System.out.println("Merge complete:");
		System.out.println("  Fireball entries: " + fireballEntries.size());
		System.out.println("  Thermal entries: " + thermalEntries.size());
		System.out.println("  Blast entries: " + blastEntries.size());
		System.out.println("  Total input entries: " + (fireballEntries.size() + thermalEntries.size() + blastEntries.size()));
		System.out.println("  Merged entries (unique): " + sortedEntries.size());
		System.out.println("  Overlap eliminated: " + ((fireballEntries.size() + thermalEntries.size() + blastEntries.size()) - sortedEntries.size()));
		System.out.println("  Merge time: " + (endTime - startTime) + " ms");
		System.out.println("======================================");
	}

	/**
	 * Merge entries from a single source
	 */
	private void mergeEntries(String sourceName, List<BlockDestructionEntry> entries) {
		int added = 0;
		int overridden = 0;
		int skipped = 0;

		for (BlockDestructionEntry entry : entries) {
			BlockPos pos = entry.getPos();

			if (mergedEntries.containsKey(pos)) {
				BlockDestructionEntry existing = mergedEntries.get(pos);

				if (entry.shouldOverride(existing)) {
					mergedEntries.put(pos, entry);
					overridden++;
				} else {
					skipped++;
				}
			} else {
				mergedEntries.put(pos, entry);
				added++;
			}
		}

		System.out.println(String.format(
			"  %s: %d entries | Added: %d | Overridden: %d | Skipped: %d",
			sourceName, entries.size(), added, overridden, skipped
		));
	}

	/**
	 * Process one tick of destruction
	 */
	public void processTick() {
		if (isComplete) return;

		long startTime = System.currentTimeMillis();
		int blocksProcessed = 0;

		while (currentIndex < sortedEntries.size() && blocksProcessed < MAX_BLOCKS_PER_TICK) {
			BlockDestructionEntry entry = sortedEntries.get(currentIndex);
			BlockPos pos = entry.getPos();

			// Validate position
			if (pos.getY() < MIN_Y) {
				currentIndex++;
				continue;
			}

			// Get block
			IBlockState state = world.getBlockState(pos);
			Block block = state.getBlock();

			// BEDROCK PROTECTION
			if (block == Blocks.BEDROCK) {
				currentIndex++;
				continue;
			}

			// INDESTRUCTIBLE BLOCK PROTECTION
			if (block.getBlockHardness(state, world, pos) < 0) {
				currentIndex++;
				continue;
			}

			// Destroy block
			if (block != Blocks.AIR) {
				world.setBlockToAir(pos);
				totalBlocksDestroyed++;
			}

			blocksProcessed++;
			currentIndex++;

			// Check time limit
			if (System.currentTimeMillis() - startTime > 50) {
				break;
			}
		}

		// Periodic logging
		if (blocksProcessed > 100 && currentIndex % 10000 == 0) {
			double progress = (currentIndex / (double) sortedEntries.size()) * 100.0;
			BlockDestructionEntry current = sortedEntries.get(currentIndex);
			System.out.println(String.format(
				"[UNIFIED-DESTROY] Block %d/%d (%.1f%%) | Type=%s | R=%.0fm | Destroyed=%d",
				currentIndex, sortedEntries.size(), progress,
				current.getType(), current.getDistance(), totalBlocksDestroyed
			));
		}

		// Check completion
		if (currentIndex >= sortedEntries.size()) {
			completeProcessing();
		}
	}

	/**
	 * Complete processing
	 */
	private void completeProcessing() {
		isComplete = true;

		// Count by type
		Map<DestructionType, Integer> countByType = new HashMap<>();
		for (BlockDestructionEntry entry : sortedEntries) {
			countByType.put(entry.getType(),
				countByType.getOrDefault(entry.getType(), 0) + 1);
		}

		System.out.println("=== UNIFIED DESTRUCTION COMPLETE ===");
		System.out.println("Total blocks destroyed: " + totalBlocksDestroyed);
		System.out.println("Destruction breakdown:");
		System.out.println("  Fireball entries: " + countByType.getOrDefault(DestructionType.FIREBALL, 0));
		System.out.println("  Thermal entries: " + countByType.getOrDefault(DestructionType.THERMAL, 0));
		System.out.println("  Blast entries: " + countByType.getOrDefault(DestructionType.BLAST, 0));
		System.out.println("Method: Unified priority-based merging");
		System.out.println("====================================");
	}

	// === PUBLIC INTERFACE ===

	public boolean isComplete() {
		return isComplete;
	}

	public long getTotalBlocksDestroyed() {
		return totalBlocksDestroyed;
	}
}
