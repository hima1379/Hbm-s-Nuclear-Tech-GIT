package com.hbm.physics.nuke;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;

/**
 * THERMAL EROSION PHYSICS
 *
 * Models the depth of material ablation/erosion caused by intense thermal radiation
 * from nuclear explosions.
 *
 * Physical basis:
 * - Thermal radiation absorbed at surface causes vaporization/ablation
 * - Heat penetrates into subsurface layers via conduction
 * - Erosion depth depends on:
 *   1. Thermal fluence (energy per unit area)
 *   2. Material thermal properties (specific heat, latent heat, conductivity)
 *   3. Absorption characteristics
 *
 * Mathematical model:
 * - Erosion depth increases logarithmically with fluence
 * - depth(Q) = k_material × ln(Q / Q_threshold)
 * - Where:
 *   - Q = thermal fluence at surface (J/m²)
 *   - Q_threshold = minimum fluence for erosion
 *   - k_material = erosion coefficient (material-dependent)
 *
 * References:
 * - "Effects of Nuclear Weapons" (1977), Chapter VII
 * - "Thermal Radiation Phenomena" Volume 1 (DTIC)
 * - "Surface Effects of Nuclear Air Bursts" (1968)
 *
 * Key differences from blast crater:
 * - Thermal erosion occurs BEFORE blast wave arrives
 * - Affects surface layers primarily (top 0.5-5 meters typically)
 * - More pronounced for low-density materials (soil, sand)
 * - Less pronounced for high-density materials (rock, concrete)
 */
public class ThermalErosionPhysics {

	// === EROSION COEFFICIENTS (meters per natural log unit) ===
	// These determine how deeply different materials erode for a given fluence

	// Organic materials - very high erosion (vaporize easily)
	private static final double K_ORGANIC = 2.5;  // Wood, leaves, plants

	// Soil/sand - high erosion (low density, low thermal mass)
	private static final double K_SOIL = 3.0;     // Dirt, sand, gravel
	private static final double K_CLAY = 2.5;     // Clay, hardened clay

	// Stone - moderate erosion (surface ablation)
	private static final double K_STONE = 1.5;    // Stone, cobblestone
	private static final double K_BRICK = 1.3;    // Brick, stone brick
	private static final double K_SANDSTONE = 2.0; // Sandstone (softer)

	// Concrete - low erosion (spalling, not deep ablation)
	private static final double K_CONCRETE = 1.0;

	// Metal - very low erosion (excellent heat conduction dissipates energy)
	private static final double K_METAL = 0.5;

	// Glass - moderate erosion (fractures under thermal stress)
	private static final double K_GLASS = 1.2;

	// Ice/snow - high erosion (phase change)
	private static final double K_ICE = 3.5;
	private static final double K_SNOW = 4.0;

	// Water - special case (evaporates)
	private static final double K_WATER = 2.0;

	// Default for unknown materials
	private static final double K_DEFAULT = 2.0;

	// === MINIMUM FLUENCE FOR EROSION (J/m²) ===
	// Below this threshold, no significant erosion occurs
	private static final double Q_MIN_EROSION = 1.0e5; // 100 kJ/m²

	// === MAXIMUM EROSION DEPTH (safety limit) ===
	private static final double MAX_EROSION_DEPTH = 15.0; // 15 meters maximum

	/**
	 * Calculate thermal erosion depth at a surface location
	 *
	 * @param thermalFluence Thermal fluence at this location (J/m²)
	 * @param surfaceState Block state at surface
	 * @param surfaceBlock Block at surface
	 * @return Erosion depth in meters (0 if below threshold)
	 */
	public static double calculateErosionDepth(double thermalFluence,
	                                            IBlockState surfaceState,
	                                            Block surfaceBlock) {
		// No erosion below threshold
		if (thermalFluence < Q_MIN_EROSION) {
			return 0.0;
		}

		// Get material-specific erosion coefficient
		double k = getErosionCoefficient(surfaceState, surfaceBlock);

		// Get material thermal threshold
		double Q_threshold = MaterialPropertyDatabase.getThermalThreshold(surfaceState, surfaceBlock);

		// If fluence doesn't exceed threshold, no erosion
		if (thermalFluence < Q_threshold) {
			return 0.0;
		}

		// Calculate erosion depth using logarithmic model
		// depth = k × ln(Q / Q_threshold)
		double depth = k * Math.log(thermalFluence / Q_threshold);

		// Apply physical limits
		depth = Math.max(0.0, depth); // No negative erosion
		depth = Math.min(depth, MAX_EROSION_DEPTH); // Cap at maximum

		return depth;
	}

	/**
	 * Get erosion coefficient for a material
	 *
	 * @param state Block state
	 * @param block Block
	 * @return Erosion coefficient k (meters per ln unit)
	 */
	private static double getErosionCoefficient(IBlockState state, Block block) {
		Material material = state.getMaterial();

		// === BLOCK-SPECIFIC EROSION COEFFICIENTS ===

		// Organic materials
		if (block == Blocks.LEAVES || block == Blocks.LEAVES2 ||
		    block == Blocks.TALLGRASS || block == Blocks.DEADBUSH ||
		    block == Blocks.YELLOW_FLOWER || block == Blocks.RED_FLOWER) {
			return K_ORGANIC;
		}

		// Wood
		if (block == Blocks.LOG || block == Blocks.LOG2 ||
		    block == Blocks.PLANKS || material == Material.WOOD) {
			return K_ORGANIC;
		}

		// Soil/dirt
		if (block == Blocks.DIRT || block == Blocks.GRASS ||
		    block == Blocks.FARMLAND || block == Blocks.GRASS_PATH) {
			return K_SOIL;
		}

		// Sand/gravel
		if (block == Blocks.SAND || block == Blocks.GRAVEL) {
			return K_SOIL;
		}

		// Clay
		if (block == Blocks.CLAY || block == Blocks.HARDENED_CLAY ||
		    block == Blocks.STAINED_HARDENED_CLAY) {
			return K_CLAY;
		}

		// Stone
		if (block == Blocks.STONE || block == Blocks.COBBLESTONE) {
			return K_STONE;
		}

		// Brick
		if (block == Blocks.BRICK_BLOCK || block == Blocks.STONEBRICK ||
		    block == Blocks.NETHER_BRICK) {
			return K_BRICK;
		}

		// Sandstone
		if (block == Blocks.SANDSTONE || block == Blocks.RED_SANDSTONE) {
			return K_SANDSTONE;
		}

		// Glass
		if (block == Blocks.GLASS || block == Blocks.GLASS_PANE ||
		    block == Blocks.STAINED_GLASS || block == Blocks.STAINED_GLASS_PANE) {
			return K_GLASS;
		}

		// Ice
		if (block == Blocks.ICE || block == Blocks.PACKED_ICE) {
			return K_ICE;
		}

		// Snow
		if (block == Blocks.SNOW || block == Blocks.SNOW_LAYER) {
			return K_SNOW;
		}

		// Water
		if (block == Blocks.WATER || block == Blocks.FLOWING_WATER) {
			return K_WATER;
		}

		// Metal
		if (block == Blocks.IRON_BLOCK || block == Blocks.GOLD_BLOCK ||
		    block == Blocks.IRON_ORE || block == Blocks.GOLD_ORE ||
		    material == Material.IRON) {
			return K_METAL;
		}

		// Obsidian - very resistant
		if (block == Blocks.OBSIDIAN) {
			return K_METAL * 0.3;
		}

		// Bedrock - indestructible
		if (block == Blocks.BEDROCK) {
			return 0.0;
		}

		// === MATERIAL-BASED FALLBACKS ===

		if (material == Material.LEAVES || material == Material.PLANTS ||
		    material == Material.VINE) {
			return K_ORGANIC;
		}

		if (material == Material.GROUND || material == Material.GRASS) {
			return K_SOIL;
		}

		if (material == Material.SAND) {
			return K_SOIL;
		}

		if (material == Material.CLAY) {
			return K_CLAY;
		}

		if (material == Material.ROCK) {
			return K_STONE;
		}

		if (material == Material.GLASS) {
			return K_GLASS;
		}

		if (material == Material.ICE || material == Material.PACKED_ICE) {
			return K_ICE;
		}

		if (material == Material.SNOW || material == Material.CRAFTED_SNOW) {
			return K_SNOW;
		}

		if (material == Material.WATER) {
			return K_WATER;
		}

		// Default
		return K_DEFAULT;
	}

	/**
	 * Calculate smoothed erosion depth with distance-based tapering
	 *
	 * This creates a smooth crater profile rather than stepped layers
	 *
	 * @param baseFluence Fluence at this horizontal distance from center
	 * @param surfaceState Surface block state
	 * @param surfaceBlock Surface block
	 * @param depthBelowSurface Current depth below surface (meters)
	 * @return Erosion factor [0.0-1.0] (1.0 = destroy this block)
	 */
	public static double getErosionFactor(double baseFluence,
	                                       IBlockState surfaceState,
	                                       Block surfaceBlock,
	                                       double depthBelowSurface) {
		// Calculate total erosion depth at this surface location
		double totalErosionDepth = calculateErosionDepth(baseFluence, surfaceState, surfaceBlock);

		if (totalErosionDepth <= 0.0) {
			return 0.0; // No erosion
		}

		if (depthBelowSurface >= totalErosionDepth) {
			return 0.0; // Below erosion depth
		}

		// Smooth erosion profile: exponential decay with depth
		// This creates gradual transition rather than sharp cutoff
		double normalized = depthBelowSurface / totalErosionDepth;
		double erosionFactor = 1.0 - Math.pow(normalized, 0.7);

		return Math.max(0.0, Math.min(1.0, erosionFactor));
	}

	/**
	 * Print erosion parameters for debugging
	 */
	public static void printErosionParameters(double yieldKilotons, double maxFluence) {
		System.out.println("=== THERMAL EROSION PARAMETERS ===");
		System.out.println("Weapon yield: " + String.format("%.1f", yieldKilotons) + " kt");
		System.out.println("Maximum fluence: " + String.format("%.2e", maxFluence) + " J/m²");
		System.out.println("Minimum erosion threshold: " + String.format("%.2e", Q_MIN_EROSION) + " J/m²");
		System.out.println("Maximum erosion depth: " + MAX_EROSION_DEPTH + " m");
		System.out.println("Erosion model: Logarithmic (depth ∝ ln(Q))");
		System.out.println("Material coefficients:");
		System.out.println("  Organic: " + K_ORGANIC + " m/ln");
		System.out.println("  Soil/sand: " + K_SOIL + " m/ln");
		System.out.println("  Stone: " + K_STONE + " m/ln");
		System.out.println("  Concrete: " + K_CONCRETE + " m/ln");
		System.out.println("  Metal: " + K_METAL + " m/ln");
		System.out.println("==================================");
	}
}
