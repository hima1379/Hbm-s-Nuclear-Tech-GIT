package com.hbm.explosion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.hbm.config.RealBombConfig;
import com.hbm.physics.nuke.KingeryBulmashCalculator;
import com.hbm.physics.nuke.MaterialPropertyDatabase;
import com.hbm.physics.nuke.CraterPhysics;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * BLAST PRESSURE FIELD PROCESSOR - VOLUME ITERATION APPROACH
 *
 * Based on "Effects of Nuclear Weapons" (1977), Chapter 3 (pages 130-157)
 *
 * PHYSICS:
 * - Blast wave propagates SPHERICALLY from center
 * - Peak overpressure decreases with distance: P(Z) from Kingery-Bulmash
 * - Scaled distance: Z = r / W^(1/3) [m/kg^(1/3)]
 * - Material failure thresholds: 200 PSI (vaporize), 20 PSI (concrete), 5 PSI (brick), 2.5 PSI (wood)
 *
 * IMPLEMENTATION:
 * - Pre-calculate ALL blocks in affected volume
 * - Sort by distance from center (CRITICAL for spherical propagation!)
 * - Process sequentially: center → outward
 * - Result: Perfect spherical blast wave expansion
 *
 * KEY DIFFERENCE FROM OLD VERSION:
 * - OLD: Spherical shell sampling (~0.1% coverage) → random holes
 * - NEW: Full volume iteration (100% coverage) → smooth spherical pattern
 */
public class BlastPressureFieldProcessor {

	// === CORE PARAMETERS ===
	private final World world;
	private final double centerX, centerY, centerZ;
	private final double yieldKilotons;
	private final double tntEquivalentKg;
	private final double wCubeRoot;

	// === YIELD-BASED SCALING ===
	private final YieldScalingConfig scalingConfig;

	// === PROCESSING STATE ===
	private boolean isComplete = false;
	private long totalBlocksDestroyed = 0;

	// === DISTANCE-SORTED DESTRUCTION DATA ===
	private final List<BlockDestructionEntry> allBlocks;
	private int currentBlockIndex = 0;

	// === CACHING ===
	private final Map<String, Integer> surfaceLevelCache = new HashMap<>();

	// === PRESSURE THRESHOLDS ===
	private static final double PSI_MINIMUM = 2.5; // Below this, no damage

	// === TERRAIN ===
	private static final int MIN_Y = 1;
	private static final int MAX_Y = 255;

	/**
	 * Constructor - PRE-CALCULATES blast destruction pattern
	 */
	public BlastPressureFieldProcessor(World world, double x, double y, double z, double yieldKt) {
		this.world = world;
		this.centerX = x;
		this.centerY = y;
		this.centerZ = z;
		this.yieldKilotons = yieldKt;

		// Convert to TNT equivalent
		this.tntEquivalentKg = yieldKt * 1_000_000.0; // 1 kt = 10^6 kg TNT
		this.wCubeRoot = Math.pow(tntEquivalentKg, 1.0 / 3.0);

		// Initialize yield-based scaling configuration
		this.scalingConfig = new YieldScalingConfig(yieldKt);

		System.out.println("=== BLAST PRESSURE FIELD PROCESSOR ===");
		System.out.println("Method: PDF-accurate Kingery-Bulmash equations");
		System.out.println("Weapon yield: " + String.format("%.3f", yieldKilotons) + " kt");
		System.out.println("Ground zero: " + (int) centerX + ", " + (int) centerY + ", " + (int) centerZ);

		printDamageRadii();
		scalingConfig.printScalingInfo();

		// NO PRE-CALCULATION!
		// Initialize empty list (required for old processTick() compatibility)
		// Actual entries will be generated per-chunk by ChunkBasedDestructionExecutor
		// using calculateEntriesForChunk() method
		this.allBlocks = new ArrayList<>();

		System.out.println("Initialization complete - entries will be generated per-chunk");
		System.out.println("Memory-efficient mode: No pre-calculation, chunk-based processing");
		System.out.println("=======================================");
	}

	/**
	 * Calculate destruction pattern by iterating through ENTIRE volume
	 * This is the KEY fix - evaluates ALL blocks, not sampled points
	 */
	private void calculateDestructionPattern() {
		// Calculate maximum effective range (2.5 PSI threshold)
		int maxRadius = calculateRadiusForPressure(PSI_MINIMUM);

		// CRITICAL: Cap with yield-based scaling config to prevent massive grids
		maxRadius = Math.min(maxRadius, scalingConfig.getMaxRangeMeters());

		System.out.println("[BLAST-PRECALC] Maximum blast range: " + maxRadius + " m (capped by yield scaling)");

		// Calculate bounds
		int minX = (int) Math.floor(centerX) - maxRadius;
		int maxX = (int) Math.ceil(centerX) + maxRadius;
		int minZ = (int) Math.floor(centerZ) - maxRadius;
		int maxZ = (int) Math.ceil(centerZ) + maxRadius;

		// Y bounds: reasonable range for structures
		int minY = Math.max(MIN_Y, (int) (centerY - 50));
		int maxY = Math.min(MAX_Y, (int) (centerY + 50));

		// Progress tracking
		int totalPositions = (maxX - minX + 1) * (maxZ - minZ + 1) * (maxY - minY + 1);
		int processedPositions = 0;
		long lastLogTime = System.currentTimeMillis();

		System.out.println("[BLAST-PRECALC] Volume size: " + (maxX - minX + 1) + " × " + (maxY - minY + 1) + " × " + (maxZ - minZ + 1) + " = " + totalPositions + " positions");

		// Iterate through ALL blocks in volume
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				// Get surface level for this XZ column
				int surfaceY = getSurfaceLevel(x, z);

				// Calculate Y range for this column
				int columnMinY = Math.max(MIN_Y, surfaceY - 50); // Up to 50 blocks underground
				int columnMaxY = Math.min(MAX_Y, surfaceY + 30);  // Up to 30 blocks above surface

				for (int y = columnMinY; y <= columnMaxY; y++) {
					processedPositions++;

					// Log progress every 100,000 positions or every 5 seconds
					if (processedPositions % 100000 == 0 ||
					    (System.currentTimeMillis() - lastLogTime) > 5000) {

						double progress = (processedPositions / (double) totalPositions) * 100.0;
						System.out.println(String.format(
							"[BLAST-PRECALC] Position %d/%d (%.2f%%) | Blocks found: %d",
							processedPositions, totalPositions, progress, allBlocks.size()
						));
						lastLogTime = System.currentTimeMillis();
					}

					// Calculate 3D distance from blast center
					double dx = x - centerX;
					double dy = y - centerY;
					double dz = z - centerZ;
					double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

					// NO SAMPLING FOR BLAST - Pure deterministic pressure-based destruction
					// Blast destruction is based on Kingery-Bulmash pressure thresholds
					// Sampling would randomize deterministic physics calculations

					// Skip if beyond blast range
					if (distance > maxRadius) continue;

					// Calculate scaled distance
					double Z = distance / wCubeRoot;

					// Calculate pressure using Kingery-Bulmash
					double peakOverpressure = KingeryBulmashCalculator.calculatePeakOverpressure(Z);
					double dynamicPressure = KingeryBulmashCalculator.calculateDynamicPressure(peakOverpressure);

					// Apply Mach stem enhancement for ground bursts
					if (centerY < 100.0) {
						double machFactor = KingeryBulmashCalculator.calculateMachStemFactor(
							distance, centerY, yieldKilotons);
						peakOverpressure *= machFactor;
					}

					// Determine if above/below ground
					boolean aboveGround = y > surfaceY;
					boolean underground = y < surfaceY;

					// Calculate effective pressure
					double effectivePressure = peakOverpressure;
					double effectiveDynamic = dynamicPressure;

					// Reflected pressure for surfaces facing the blast
					if (isSurfaceFacingBlast(x, y, z)) {
						effectivePressure = KingeryBulmashCalculator.calculateReflectedPressure(
							peakOverpressure, dynamicPressure);
					}

					// Above ground: full exposure
					if (aboveGround) {
						effectivePressure *= 1.0; // Full pressure
						effectiveDynamic *= 1.2;  // Enhanced dynamic pressure
					}

					// Underground: exponential attenuation
					if (underground) {
						double depthBelowSurface = surfaceY - y;
						double attenuationLength = 3.0;
						double attenuationFactor = Math.exp(-depthBelowSurface / attenuationLength);
						effectivePressure *= attenuationFactor;
						effectiveDynamic *= attenuationFactor;
					}

					// Check if block should be destroyed
					BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, y, z);
					IBlockState state = world.getBlockState(pos);
					Block block = state.getBlock();

					// Skip air and bedrock
					if (block == Blocks.AIR || block == Blocks.BEDROCK) continue;

					// Check blast threshold
					if (shouldBlastDestroy(block, state, effectivePressure, effectiveDynamic)) {
						// Add to destruction list (BLAST type)
						BlockPos entryPos = pos.toImmutable();
						allBlocks.add(new BlockDestructionEntry(entryPos, DestructionType.BLAST, 1.0, distance));
					}
				}
			}
		}
	}

	/**
	 * Process one tick of blast destruction
	 */
	public void processTick() {
		if (isComplete) return;

		long startTime = System.currentTimeMillis();
		int blocksProcessed = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		// Process blocks in distance order (center → outward)
		while (currentBlockIndex < allBlocks.size() && blocksProcessed < scalingConfig.getBlocksPerTick()) {
			BlockDestructionEntry entry = allBlocks.get(currentBlockIndex);
			BlockPos entryPos = entry.getPos();
			pos.setPos(entryPos.getX(), entryPos.getY(), entryPos.getZ());

			// Get block state
			IBlockState state = world.getBlockState(pos);
			Block block = state.getBlock();

			// BEDROCK PROTECTION
			if (block == Blocks.BEDROCK) {
				currentBlockIndex++;
				continue;
			}

			// INDESTRUCTIBLE BLOCK PROTECTION
			if (block.getBlockHardness(state, world, pos) < 0) {
				currentBlockIndex++;
				continue;
			}

			// Destroy block
			if (block != Blocks.AIR) {
				world.setBlockToAir(pos);
				totalBlocksDestroyed++;
			}

			currentBlockIndex++;
			blocksProcessed++;

			// Check time limit
			if (System.currentTimeMillis() - startTime > 50) {
				break;
			}
		}

		// Check completion
		if (currentBlockIndex >= allBlocks.size()) {
			completeProcessing();
		}

		// Periodic logging
		if (blocksProcessed > 100 && currentBlockIndex % 10000 == 0) {
			double progress = (currentBlockIndex / (double) allBlocks.size()) * 100.0;
			double currentDistance = allBlocks.get(currentBlockIndex).getDistance();
			double Z = currentDistance / wCubeRoot;
			double P = KingeryBulmashCalculator.calculatePeakOverpressure(Z);
			System.out.println(String.format(
				"[BLAST] Block %d/%d (%.1f%%) | R=%.0fm | Z=%.2f | P=%.2f PSI | Destroyed=%d",
				currentBlockIndex, allBlocks.size(), progress,
				currentDistance, Z, P, totalBlocksDestroyed
			));
		}
	}

	/**
	 * Determine if blast should destroy block
	 */
	private boolean shouldBlastDestroy(Block block, IBlockState state,
	                                     double peakOverpressure, double dynamicPressure) {
		// Get material blast threshold
		double threshold = MaterialPropertyDatabase.getBlastThreshold(state, block);

		// Check if overpressure OR dynamic pressure exceeds threshold
		return (peakOverpressure >= threshold) || (dynamicPressure >= threshold * 0.5);
	}

	/**
	 * Check if block is surface facing blast
	 */
	private boolean isSurfaceFacingBlast(int x, int y, int z) {
		// Simple heuristic: check if block has air adjacent toward blast center
		double dx = centerX - x;
		double dy = centerY - y;
		double dz = centerZ - z;

		// Check primary direction
		BlockPos checkPos;
		if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > Math.abs(dz)) {
			checkPos = new BlockPos(x + (dx > 0 ? 1 : -1), y, z);
		} else if (Math.abs(dy) > Math.abs(dz)) {
			checkPos = new BlockPos(x, y + (dy > 0 ? 1 : -1), z);
		} else {
			checkPos = new BlockPos(x, y, z + (dz > 0 ? 1 : -1));
		}

		return world.getBlockState(checkPos).getBlock() == Blocks.AIR;
	}

	/**
	 * Calculate radius for specific pressure threshold using binary search
	 */
	private int calculateRadiusForPressure(double targetPSI) {
		int low = 1;
		int high = 50000; // 50 km maximum

		while (high - low > 1) {
			int mid = (low + high) / 2;
			double Z = mid / wCubeRoot;
			double pressure = KingeryBulmashCalculator.calculatePeakOverpressure(Z);

			if (pressure > targetPSI) {
				low = mid;
			} else {
				high = mid;
			}
		}

		return low;
	}

	/**
	 * Get surface level with caching
	 */
	private int getSurfaceLevel(int x, int z) {
		String key = x + "," + z;
		Integer cached = surfaceLevelCache.get(key);
		if (cached != null) return cached;

		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = MAX_Y - 1; y >= MIN_Y; y--) {
			pos.setPos(x, y, z);
			IBlockState state = world.getBlockState(pos);
			if (state.getBlock() != Blocks.AIR && state.isOpaqueCube()) {
				surfaceLevelCache.put(key, y);
				return y;
			}
		}

		int defaultY = 63;
		surfaceLevelCache.put(key, defaultY);
		return defaultY;
	}

	/**
	 * Complete processing
	 */
	private void completeProcessing() {
		isComplete = true;

		int r20 = calculateRadiusForPressure(20.0);
		int r5 = calculateRadiusForPressure(5.0);
		int r2_5 = calculateRadiusForPressure(2.5);

		System.out.println("=== BLAST WAVE COMPLETE ===");
		System.out.println("Weapon yield: " + yieldKilotons + " kt");
		System.out.println("Total blocks destroyed: " + totalBlocksDestroyed);
		System.out.println("Critical radii:");
		System.out.println("  20 PSI (heavy damage): " + r20 + " m");
		System.out.println("  5 PSI (moderate damage): " + r5 + " m");
		System.out.println("  2.5 PSI (light damage): " + r2_5 + " m");
		System.out.println("Method: Spherical volume iteration");
		System.out.println("===========================");
	}

	private void printDamageRadii() {
		int r20 = calculateRadiusForPressure(20.0);
		int r5 = calculateRadiusForPressure(5.0);
		int r2_5 = calculateRadiusForPressure(2.5);

		System.out.println("Damage radii:");
		System.out.println("  20 PSI (heavy): " + r20 + " m");
		System.out.println("  5 PSI (moderate): " + r5 + " m");
		System.out.println("  2.5 PSI (light): " + r2_5 + " m");
	}

	// === PUBLIC INTERFACE ===

	public boolean isComplete() {
		return isComplete;
	}

	public long getTotalBlocksDestroyed() {
		return totalBlocksDestroyed;
	}

	public int getCurrentRadius() {
		if (currentBlockIndex >= allBlocks.size()) {
			return calculateRadiusForPressure(PSI_MINIMUM);
		}
		if (currentBlockIndex == 0) {
			return 0;
		}
		return (int) allBlocks.get(currentBlockIndex).getDistance();
	}

	// === PUBLIC INTERFACE FOR UNIFIED SYSTEM ===

	/**
	 * Get all destruction entries for unified executor
	 */
	public List<BlockDestructionEntry> getEntries() {
		return new ArrayList<>(allBlocks);
	}

	/**
	 * Check if pre-calculation is complete
	 */
	public boolean isPreCalculationComplete() {
		return allBlocks != null && !allBlocks.isEmpty();
	}

	// === CHUNK-BASED PROCESSING ===

	/**
	 * Calculate destruction entries for a specific chunk (memory-efficient)
	 *
	 * @param chunkX Chunk X coordinate
	 * @param chunkZ Chunk Z coordinate
	 * @return List of destruction entries for this chunk
	 */
	public List<BlockDestructionEntry> calculateEntriesForChunk(int chunkX, int chunkZ) {
		List<BlockDestructionEntry> chunkEntries = new ArrayList<>();

		// Convert chunk coordinates to block coordinates
		int minX = chunkX << 4;
		int maxX = minX + 15;
		int minZ = chunkZ << 4;
		int maxZ = minZ + 15;

		// Iterate through chunk blocks
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				// Get surface level for this XZ column
				int surfaceY = getSurfaceLevel(x, z);

				// Calculate Y range for this column
				int columnMinY = Math.max(MIN_Y, surfaceY - 50);
				int columnMaxY = Math.min(MAX_Y, surfaceY + 30);

				for (int y = columnMinY; y <= columnMaxY; y++) {
					// Calculate 3D distance
					double dx = x - centerX;
					double dy = y - centerY;
					double dz = z - centerZ;
					double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

					// NO SAMPLING FOR BLAST - Pure deterministic pressure-based destruction
					// Blast destruction is based on Kingery-Bulmash pressure thresholds
					// Sampling would randomize deterministic physics calculations

					// Skip if beyond blast range
					int maxRadius = calculateRadiusForPressure(PSI_MINIMUM);
					maxRadius = Math.min(maxRadius, scalingConfig.getMaxRangeMeters());
					if (distance > maxRadius) continue;

					// Calculate scaled distance
					double Z = distance / wCubeRoot;

					// Calculate pressure
					double peakOverpressure = KingeryBulmashCalculator.calculatePeakOverpressure(Z);
					double dynamicPressure = KingeryBulmashCalculator.calculateDynamicPressure(peakOverpressure);

					// Apply Mach stem enhancement
					if (centerY < 100.0) {
						double machFactor = KingeryBulmashCalculator.calculateMachStemFactor(
							distance, centerY, yieldKilotons);
						peakOverpressure *= machFactor;
					}

					// Determine if above/below ground
					boolean aboveGround = y > surfaceY;
					boolean underground = y < surfaceY;

					// Calculate effective pressure
					double effectivePressure = peakOverpressure;
					double effectiveDynamic = dynamicPressure;

					// Reflected pressure
					if (isSurfaceFacingBlast(x, y, z)) {
						effectivePressure = KingeryBulmashCalculator.calculateReflectedPressure(
							peakOverpressure, dynamicPressure);
					}

					// Above ground: full exposure
					if (aboveGround) {
						effectivePressure *= 1.0;
						effectiveDynamic *= 1.2;
					}

					// Underground: exponential attenuation
					if (underground) {
						double depthBelowSurface = surfaceY - y;
						double attenuationLength = 3.0;
						double attenuationFactor = Math.exp(-depthBelowSurface / attenuationLength);
						effectivePressure *= attenuationFactor;
						effectiveDynamic *= attenuationFactor;
					}

					// Check if block should be destroyed
					BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, y, z);
					IBlockState state = world.getBlockState(pos);
					Block block = state.getBlock();

					// Skip air and bedrock
					if (block == Blocks.AIR || block == Blocks.BEDROCK) continue;

					// Check blast threshold
					if (shouldBlastDestroy(block, state, effectivePressure, effectiveDynamic)) {
						BlockPos entryPos = pos.toImmutable();
						chunkEntries.add(new BlockDestructionEntry(entryPos, DestructionType.BLAST, 1.0, distance));
					}
				}
			}
		}

		return chunkEntries;
	}
}
