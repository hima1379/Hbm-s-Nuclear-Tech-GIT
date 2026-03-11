package com.hbm.explosion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.hbm.config.RealBombConfig;
import com.hbm.physics.nuke.MaterialPropertyDatabase;
import com.hbm.physics.nuke.ThermalErosionPhysics;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * THERMAL RADIATION PROCESSOR - FULL GRID ITERATION APPROACH
 *
 * Based on "Effects of Nuclear Weapons" (1977)
 *
 * PHYSICS:
 * - Nuclear weapons emit ~35% of total energy as thermal radiation
 * - Thermal radiation absorbed at SURFACE causes material ablation/vaporization
 * - Heat penetrates subsurface via conduction, causing thermal erosion
 * - Erosion depth depends on fluence and material thermal properties
 *
 * IMPLEMENTATION:
 * - Pre-calculate ALL surface columns in affected area
 * - For each (X,Z) position:
 *   1. Find surface level
 *   2. Check line-of-sight to fireball
 *   3. Calculate thermal fluence
 *   4. Calculate erosion depth
 *   5. Destroy blocks vertically from surface down
 * - Process sequentially for efficiency
 *
 * KEY DIFFERENCE FROM OLD VERSION:
 * - OLD: Circular ring sampling (~0.1% coverage) → slow, incomplete
 * - NEW: Full XZ grid iteration (100% coverage) → fast, complete
 *
 * Key formulas:
 * - Thermal fluence: Q = (Y × 0.35 × 4.184e12) / (4πr²) × exp(-μr) [J/m²]
 * - Erosion depth: d = k × ln(Q / Q_threshold) [meters]
 */
public class ThermalRadiationProcessor {

	// === CORE PARAMETERS ===
	private final World world;
	private final double centerX, centerY, centerZ;
	private final double yieldKilotons;
	private final double fireballRadiusMeters;
	private final double totalThermalEnergyJoules;

	// === YIELD-BASED SCALING ===
	private final YieldScalingConfig scalingConfig;

	// === PROCESSING STATE ===
	private boolean isComplete = false;
	private long totalBlocksDestroyed = 0;

	// === PRE-CALCULATED DESTRUCTION DATA ===
	private final List<SurfaceColumnEntry> allColumns;
	private int currentColumnIndex = 0;

	// === SURFACE LEVEL CACHE ===
	private final Map<String, Integer> surfaceLevelCache = new HashMap<>();

	// === PHYSICAL CONSTANTS ===
	private static final double THERMAL_FRACTION = 0.35; // 35% of yield as thermal
	private static final double ATMOSPHERIC_ABSORPTION = 0.00015; // μ ≈ 0.15 km⁻¹ = 0.00015 m⁻¹
	private static final double JOULES_PER_KILOTON = 4.184e12;

	// === TERRAIN ===
	private static final int MIN_Y = 1;
	private static final int MAX_Y = 255;

	/**
	 * Constructor - PRE-CALCULATES thermal destruction pattern
	 */
	public ThermalRadiationProcessor(World world, double x, double y, double z,
	                                  double yieldKt, double fireballRadius) {
		this.world = world;
		this.centerX = x;
		this.centerY = y;
		this.centerZ = z;
		this.yieldKilotons = yieldKt;
		this.fireballRadiusMeters = fireballRadius;

		// Initialize yield-based scaling configuration
		this.scalingConfig = new YieldScalingConfig(yieldKt);

		// Calculate total thermal energy
		this.totalThermalEnergyJoules = yieldKt * THERMAL_FRACTION * JOULES_PER_KILOTON;

		System.out.println("=== THERMAL RADIATION PROCESSOR ===");
		System.out.println("Method: Full XZ grid iteration (NOT circular sampling!)");
		System.out.println("Weapon yield: " + String.format("%.3f", yieldKilotons) + " kt");
		System.out.println("Fireball radius: " + (int) fireballRadius + " m");
		System.out.println("Total thermal energy: " + String.format("%.2e", totalThermalEnergyJoules) + " J");

		scalingConfig.printScalingInfo();

		// NO PRE-CALCULATION!
		// Initialize empty list (required for old processTick() compatibility)
		// Actual entries will be generated per-chunk by ChunkBasedDestructionExecutor
		// using calculateEntriesForChunk() method
		this.allColumns = new ArrayList<>();

		System.out.println("Initialization complete - entries will be generated per-chunk");
		System.out.println("Memory-efficient mode: No pre-calculation, chunk-based processing");
		System.out.println("=======================================");
	}

	/**
	 * Calculate destruction pattern by iterating through ENTIRE XZ grid
	 */
	private void calculateDestructionPattern() {
		// Calculate maximum effective thermal range
		int maxRadius = calculateMaxThermalRange();

		System.out.println("[THERMAL-PRECALC] Maximum thermal range: " + maxRadius + " m");

		// Calculate bounds
		int minX = (int) Math.floor(centerX) - maxRadius;
		int maxX = (int) Math.ceil(centerX) + maxRadius;
		int minZ = (int) Math.floor(centerZ) - maxRadius;
		int maxZ = (int) Math.ceil(centerZ) + maxRadius;

		// Progress tracking
		int totalPositions = (maxX - minX + 1) * (maxZ - minZ + 1);
		int processedPositions = 0;
		long lastLogTime = System.currentTimeMillis();

		System.out.println("[THERMAL-PRECALC] Grid size: " + (maxX - minX + 1) + " × " + (maxZ - minZ + 1) + " = " + totalPositions + " positions");

		// Iterate through ALL XZ positions in grid
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				processedPositions++;

				// Log progress every 10,000 positions or every 5 seconds
				if (processedPositions % 10000 == 0 ||
				    (System.currentTimeMillis() - lastLogTime) > 5000) {

					double progress = (processedPositions / (double) totalPositions) * 100.0;
					System.out.println(String.format(
						"[THERMAL-PRECALC] Position %d/%d (%.2f%%) | Columns found: %d",
						processedPositions, totalPositions, progress, allColumns.size()
					));
					lastLogTime = System.currentTimeMillis();
				}
				// Calculate horizontal distance
				double dx = x - centerX;
				double dz = z - centerZ;
				double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

				// Skip if beyond thermal range
				if (horizontalDistance > maxRadius) continue;

				// Skip if inside fireball (already vaporized)
				if (horizontalDistance < fireballRadiusMeters) continue;

				// Find surface level at this XZ position
				int surfaceY = getSurfaceLevel(x, z);

				// Calculate 3D distance from surface to fireball center
				double dy = surfaceY - centerY;
				double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

				// Calculate thermal fluence at this surface location
				double fluence = calculateThermalFluence(distance);

				// Check if fluence is above minimum threshold
				if (fluence < 5.0e4) continue; // 50 kJ/m² minimum

				// Check line-of-sight from surface to fireball center
				if (!hasLineOfSight(x, surfaceY, z)) {
					continue; // Blocked by terrain
				}

				// Get surface block
				BlockPos surfacePos = new BlockPos(x, surfaceY, z);
				IBlockState surfaceState = world.getBlockState(surfacePos);
				Block surfaceBlock = surfaceState.getBlock();

				// Skip if air
				if (surfaceBlock == Blocks.AIR) continue;

				// Calculate thermal erosion depth at this surface location
				double erosionDepth = ThermalErosionPhysics.calculateErosionDepth(
					fluence, surfaceState, surfaceBlock);

				if (erosionDepth <= 0.0) continue; // No erosion

				// Add to destruction list
				allColumns.add(new SurfaceColumnEntry(x, surfaceY, z, distance, erosionDepth));
			}
		}
	}

	/**
	 * Calculate maximum thermal radiation range
	 * CAPPED by yield-based scaling config for performance
	 */
	private int calculateMaxThermalRange() {
		// Find radius where fluence = 5×10⁴ J/m² (minimum threshold)
		double minFluence = 5.0e4;

		int maxAllowedRange = scalingConfig.getMaxRangeMeters();

		// CRITICAL: Cap at yield-based range to prevent massive grids
		for (int r = (int) fireballRadiusMeters; r < Math.min(50000, maxAllowedRange); r += 10) {
			double fluence = calculateThermalFluence(r);
			if (fluence < minFluence) {
				return r;
			}
		}

		// Fallback: use smaller of calculated or capped range
		return Math.min((int) (fireballRadiusMeters * 3.0), maxAllowedRange);
	}

	/**
	 * Calculate thermal fluence at distance
	 */
	private double calculateThermalFluence(double distance) {
		if (distance < fireballRadiusMeters) {
			return Double.MAX_VALUE; // Inside fireball
		}

		// Inverse square law: Q ∝ 1/r²
		double fluence = totalThermalEnergyJoules / (4.0 * Math.PI * distance * distance);

		// Atmospheric attenuation: exp(-μr)
		double attenuation = Math.exp(-ATMOSPHERIC_ABSORPTION * distance);

		return fluence * attenuation;
	}

	/**
	 * Check line-of-sight from surface to fireball center
	 */
	private boolean hasLineOfSight(int x, int surfaceY, int z) {
		Vec3d surfacePos = new Vec3d(x + 0.5, surfaceY + 1.0, z + 0.5);
		Vec3d fireballCenter = new Vec3d(centerX, centerY, centerZ);

		RayTraceResult ray = world.rayTraceBlocks(surfacePos, fireballCenter, false, true, false);

		// If no hit, clear LOS
		if (ray == null) return true;

		// If hit something, check if it's the surface block itself
		if (ray.typeOfHit == RayTraceResult.Type.BLOCK) {
			BlockPos hitPos = ray.getBlockPos();
			// Allow if hitting the surface block itself
			if (hitPos.getY() == surfaceY || hitPos.getY() == surfaceY + 1) {
				return true;
			}
			return false; // Blocked by terrain
		}

		return true;
	}

	/**
	 * Process one tick of thermal radiation destruction
	 */
	public void processTick() {
		if (isComplete) return;

		long startTime = System.currentTimeMillis();
		int blocksProcessed = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		// Process columns sequentially
		while (currentColumnIndex < allColumns.size() && blocksProcessed < scalingConfig.getBlocksPerTick()) {
			SurfaceColumnEntry entry = allColumns.get(currentColumnIndex);

			// Process vertical column from surface downward
			int maxDepthBlocks = (int) Math.ceil(entry.erosionDepth);

			for (int depth = 0; depth <= maxDepthBlocks; depth++) {
				int y = entry.surfaceY - depth;
				if (y < MIN_Y) break;

				pos.setPos(entry.x, y, entry.z);
				IBlockState state = world.getBlockState(pos);
				Block block = state.getBlock();

				// BEDROCK PROTECTION
				if (block == Blocks.BEDROCK) {
					break; // Stop erosion at bedrock
				}

				// INDESTRUCTIBLE BLOCK PROTECTION
				if (block.getBlockHardness(state, world, pos) < 0) {
					break; // Stop erosion at indestructible blocks
				}

				// Erode block
				if (block != Blocks.AIR) {
					world.setBlockToAir(pos);
					totalBlocksDestroyed++;
				}

				blocksProcessed++;
			}

			currentColumnIndex++;

			// Check time limit
			if (System.currentTimeMillis() - startTime > 50) {
				break;
			}
		}

		// Check completion
		if (currentColumnIndex >= allColumns.size()) {
			completeProcessing();
		}

		// Periodic logging
		if (blocksProcessed > 100 && currentColumnIndex % 10000 == 0) {
			double progress = (currentColumnIndex / (double) allColumns.size()) * 100.0;
			double currentDistance = allColumns.get(currentColumnIndex).distance;
			double fluence = calculateThermalFluence(currentDistance);
			System.out.println(String.format(
				"[THERMAL] Column %d/%d (%.1f%%) | R=%.0fm | Q=%.2e J/m² | Destroyed=%d",
				currentColumnIndex, allColumns.size(), progress,
				currentDistance, fluence, totalBlocksDestroyed
			));
		}
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

		System.out.println("=== THERMAL RADIATION COMPLETE ===");
		System.out.println("Weapon yield: " + yieldKilotons + " kt");
		System.out.println("Total blocks destroyed: " + totalBlocksDestroyed);
		System.out.println("Method: Full XZ grid iteration");
		System.out.println("===================================");
	}

	// === PUBLIC INTERFACE ===

	public boolean isComplete() {
		return isComplete;
	}

	public long getTotalBlocksDestroyed() {
		return totalBlocksDestroyed;
	}

	// === PUBLIC INTERFACE FOR UNIFIED SYSTEM ===

	/**
	 * Get all destruction entries for unified executor
	 * Expands surface columns into individual block entries
	 */
	public List<BlockDestructionEntry> getEntries() {
		List<BlockDestructionEntry> entries = new ArrayList<>();

		for (SurfaceColumnEntry column : allColumns) {
			int maxDepthBlocks = (int) Math.ceil(column.erosionDepth);

			for (int depth = 0; depth <= maxDepthBlocks; depth++) {
				int y = column.surfaceY - depth;
				if (y < MIN_Y) break;

				BlockPos pos = new BlockPos(column.x, y, column.z);

				// Strength decreases with depth (surface = 1.0, bottom = 0.0)
				double strength = 1.0 - (depth / (double) maxDepthBlocks);

				entries.add(new BlockDestructionEntry(
					pos, DestructionType.THERMAL, strength, column.distance
				));
			}
		}

		return entries;
	}

	/**
	 * Check if pre-calculation is complete
	 */
	public boolean isPreCalculationComplete() {
		return allColumns != null && !allColumns.isEmpty();
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

		// Iterate through XZ positions in chunk
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				// Calculate horizontal distance
				double dx = x - centerX;
				double dz = z - centerZ;
				double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

				// Skip if beyond thermal range
				int maxRange = calculateMaxThermalRange();
				if (horizontalDistance > maxRange) continue;

				// Skip if inside fireball
				if (horizontalDistance < fireballRadiusMeters) continue;

				// Find surface level
				int surfaceY = getSurfaceLevel(x, z);

				// Calculate 3D distance
				double dy = surfaceY - centerY;
				double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

				// Calculate thermal fluence
				double fluence = calculateThermalFluence(distance);

				// Check minimum threshold
				if (fluence < 5.0e4) continue;

				// Check line-of-sight
				if (!hasLineOfSight(x, surfaceY, z)) {
					continue;
				}

				// Get surface block
				BlockPos surfacePos = new BlockPos(x, surfaceY, z);
				IBlockState surfaceState = world.getBlockState(surfacePos);
				Block surfaceBlock = surfaceState.getBlock();

				// Skip if air
				if (surfaceBlock == Blocks.AIR) continue;

				// Calculate erosion depth
				double erosionDepth = ThermalErosionPhysics.calculateErosionDepth(
					fluence, surfaceState, surfaceBlock);

				if (erosionDepth <= 0.0) continue;

				// Create entries for vertical column
				int maxDepthBlocks = (int) Math.ceil(erosionDepth);

				for (int depth = 0; depth <= maxDepthBlocks; depth++) {
					int y = surfaceY - depth;
					if (y < MIN_Y) break;

					BlockPos pos = new BlockPos(x, y, z);

					// Strength decreases with depth
					double strength = 1.0 - (depth / (double) maxDepthBlocks);

					chunkEntries.add(new BlockDestructionEntry(
						pos, DestructionType.THERMAL, strength, distance
					));
				}
			}
		}

		return chunkEntries;
	}

	// === DATA STRUCTURES ===

	private static class SurfaceColumnEntry {
		final int x, surfaceY, z;
		final double distance;
		final double erosionDepth;

		SurfaceColumnEntry(int x, int surfaceY, int z, double distance, double erosionDepth) {
			this.x = x;
			this.surfaceY = surfaceY;
			this.z = z;
			this.distance = distance;
			this.erosionDepth = erosionDepth;
		}
	}
}
