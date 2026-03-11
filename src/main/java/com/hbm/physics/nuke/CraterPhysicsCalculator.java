package com.hbm.physics.nuke;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * CRATER PHYSICS CALCULATOR
 *
 * Based on "Effects of Nuclear Weapons" (1977), Chapter 6 (pages 234-278)
 *
 * Implements realistic crater formation equations:
 * - Paraboloid crater shape (NOT hemisphere!)
 * - Depth/radius scaling: ∝ W^0.3
 * - Multiple destruction zones (true crater, rupture, plastic, thermal)
 * - Soil-dependent crater dimensions
 * - Bedrock protection with physical depth limits
 *
 * Key equations from PDF:
 * - Crater radius: Ra = k_r × W^0.3 meters
 * - Crater depth: Da = k_d × W^0.3 meters
 * - Paraboloid shape: z = Da × (1 - (r/Ra)²)
 * - Rupture zone: ~2.5 × Ra
 * - Plastic zone: ~3.5 × Ra
 */
public class CraterPhysicsCalculator {

	// === SOIL TYPE ENUM ===

	public enum SoilType {
		HARD_ROCK(15.2, 6.7),   // PDF Fig 6.72: Ra ≈ 50 ft, Da ≈ 22 ft for 1 kt
		DRY_SOIL(18.3, 9.1),    // PDF page 254: Ra ≈ 60 ft, Da ≈ 30 ft for 1 kt
		WET_SOIL(20.0, 12.0);   // PDF Fig 6.72b: Larger craters in wet soil

		public final double radiusCoefficient; // meters for 1 kt
		public final double depthCoefficient;  // meters for 1 kt

		SoilType(double radiusCoeff, double depthCoeff) {
			this.radiusCoefficient = radiusCoeff;
			this.depthCoefficient = depthCoeff;
		}
	}

	// === CRATER ZONE ENUM ===

	public enum CraterZone {
		TRUE_CRATER(1.00),    // Inside paraboloid crater bowl - complete destruction
		RUPTURE_ZONE(0.75),   // Fractured and cracked - high destruction
		PLASTIC_ZONE(0.40),   // Compressed and deformed - moderate destruction
		THERMAL_ONLY(0.00);   // Beyond shock zones - thermal radiation only

		public final double baseDestructionProbability;

		CraterZone(double baseProb) {
			this.baseDestructionProbability = baseProb;
		}
	}

	// === CRATER DIMENSIONS ===

	private final double yieldKilotons;
	private final SoilType soilType;
	private final double surfaceY;
	private final double centerX;
	private final double centerZ;

	// Calculated dimensions (meters)
	private final double craterRadius;
	private final double craterDepth;
	private final double ruptureRadius;
	private final double plasticRadius;
	private final double minY; // Minimum Y for crater (bedrock protection)

	// === ZONE RADIUS MULTIPLIERS (from PDF) ===
	private static final double RUPTURE_ZONE_MULTIPLIER = 2.5;  // PDF page 233
	private static final double PLASTIC_ZONE_MULTIPLIER = 3.5;

	// === BEDROCK PROTECTION ===
	private static final double BEDROCK_SAFETY_MARGIN = 5.0; // meters above bedrock
	private static final int ABSOLUTE_BEDROCK_LEVEL = 1;     // Y=1 is bedrock in Minecraft

	/**
	 * Constructor - analyzes soil and calculates crater dimensions
	 */
	public CraterPhysicsCalculator(World world, double x, double y, double z, double yieldKt) {
		this.yieldKilotons = yieldKt;
		this.centerX = x;
		this.surfaceY = y;
		this.centerZ = z;

		// Analyze soil type dynamically
		this.soilType = analyzeSoilType(world, new BlockPos(x, y, z));

		// Calculate crater dimensions using PDF equations
		// Ra = k_r × W^0.3
		// Da = k_d × W^0.3
		double scalingFactor = Math.pow(yieldKilotons, 0.3);
		this.craterRadius = soilType.radiusCoefficient * scalingFactor;
		this.craterDepth = soilType.depthCoefficient * scalingFactor;

		// Calculate zone radii
		this.ruptureRadius = craterRadius * RUPTURE_ZONE_MULTIPLIER;
		this.plasticRadius = craterRadius * PLASTIC_ZONE_MULTIPLIER;

		// Calculate minimum Y with bedrock protection
		double theoreticalMinY = surfaceY - craterDepth;
		double protectedMinY = ABSOLUTE_BEDROCK_LEVEL + BEDROCK_SAFETY_MARGIN;
		this.minY = Math.max(theoreticalMinY, protectedMinY);

		printCraterInfo();
	}

	/**
	 * Analyze soil type by examining blocks around detonation point
	 */
	private SoilType analyzeSoilType(World world, BlockPos center) {
		int sampleRadius = 8; // Sample 8-block radius
		int stoneCount = 0;
		int soilCount = 0;
		int waterCount = 0;
		int totalSamples = 0;

		// Sample blocks in a sphere around detonation point
		for (int dx = -sampleRadius; dx <= sampleRadius; dx++) {
			for (int dy = -sampleRadius; dy <= sampleRadius; dy++) {
				for (int dz = -sampleRadius; dz <= sampleRadius; dz++) {
					double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
					if (dist > sampleRadius) continue;

					BlockPos pos = center.add(dx, dy, dz);
					Block block = world.getBlockState(pos).getBlock();
					Material material = block.getMaterial(world.getBlockState(pos));

					totalSamples++;

					// Categorize blocks
					if (block == Blocks.STONE || block == Blocks.COBBLESTONE ||
					    block == Blocks.SANDSTONE || block == Blocks.OBSIDIAN ||
					    material == Material.ROCK) {
						stoneCount++;
					} else if (block == Blocks.DIRT || block == Blocks.GRASS ||
					           block == Blocks.SAND || block == Blocks.GRAVEL ||
					           material == Material.GROUND || material == Material.SAND) {
						soilCount++;
					} else if (material == Material.WATER || material == Material.ICE) {
						waterCount++;
					}
				}
			}
		}

		// Determine soil type based on composition
		double stoneRatio = stoneCount / (double) totalSamples;
		double waterRatio = waterCount / (double) totalSamples;

		if (stoneRatio > 0.6) {
			return SoilType.HARD_ROCK;
		} else if (waterRatio > 0.3) {
			return SoilType.WET_SOIL;
		} else {
			return SoilType.DRY_SOIL;
		}
	}

	/**
	 * Determine which crater zone a block is in
	 * Uses paraboloid equation: z = Da × (1 - (r/Ra)²)
	 */
	public CraterZone getCraterZone(double blockX, double blockY, double blockZ) {
		// Calculate horizontal distance from crater center
		double dx = blockX - centerX;
		double dz = blockZ - centerZ;
		double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

		// Calculate depth below surface
		double depthBelowSurface = surfaceY - blockY;

		// Check if in TRUE_CRATER zone (inside paraboloid bowl)
		if (horizontalDistance <= craterRadius && depthBelowSurface >= 0) {
			// Paraboloid equation: z = Da × (1 - (r/Ra)²)
			double normalizedRadius = horizontalDistance / craterRadius;
			double expectedDepth = craterDepth * (1.0 - normalizedRadius * normalizedRadius);

			// Block is in true crater if it's within the paraboloid bowl
			if (depthBelowSurface <= expectedDepth) {
				return CraterZone.TRUE_CRATER;
			}
		}

		// Check RUPTURE_ZONE (extends beyond crater, affected by fracturing)
		if (horizontalDistance <= ruptureRadius && depthBelowSurface <= craterDepth * 1.5) {
			return CraterZone.RUPTURE_ZONE;
		}

		// Check PLASTIC_ZONE (compressed but not ruptured)
		if (horizontalDistance <= plasticRadius && depthBelowSurface <= craterDepth * 1.2) {
			return CraterZone.PLASTIC_ZONE;
		}

		// Beyond all shock zones - thermal radiation only
		return CraterZone.THERMAL_ONLY;
	}

	/**
	 * Calculate destruction probability for a block
	 * Combines crater zone physics with directional bias
	 */
	public double getDestructionProbability(double blockX, double blockY, double blockZ,
	                                         double directionalBias) {
		CraterZone zone = getCraterZone(blockX, blockY, blockZ);

		// Base probability from zone type
		double baseProbability = zone.baseDestructionProbability;

		// Apply directional bias (gravity effects)
		double finalProbability = baseProbability * directionalBias;

		return Math.min(1.0, finalProbability);
	}

	/**
	 * Check if a Y-coordinate is within the crater depth limit
	 */
	public boolean isWithinCraterDepth(double blockY) {
		return blockY >= minY;
	}

	/**
	 * Get maximum horizontal range to consider (plastic zone edge)
	 */
	public double getMaximumRange() {
		return plasticRadius;
	}

	// === GETTERS ===

	public double getCraterRadius() {
		return craterRadius;
	}

	public double getCraterDepth() {
		return craterDepth;
	}

	public double getRuptureRadius() {
		return ruptureRadius;
	}

	public double getPlasticRadius() {
		return plasticRadius;
	}

	public double getMinY() {
		return minY;
	}

	public SoilType getSoilType() {
		return soilType;
	}

	public double getSurfaceY() {
		return surfaceY;
	}

	// === DEBUG OUTPUT ===

	private void printCraterInfo() {
		System.out.println("=== CRATER PHYSICS CALCULATOR ===");
		System.out.println("Weapon yield: " + String.format("%.3f", yieldKilotons) + " kt");
		System.out.println("Soil type: " + soilType);
		System.out.println("Crater dimensions:");
		System.out.println("  Radius (Ra): " + String.format("%.1f", craterRadius) + " m");
		System.out.println("  Depth (Da): " + String.format("%.1f", craterDepth) + " m");
		System.out.println("  Depth/Radius ratio: " + String.format("%.2f", craterDepth / craterRadius));
		System.out.println("Crater zones:");
		System.out.println("  True crater: 0 - " + String.format("%.1f", craterRadius) + " m");
		System.out.println("  Rupture zone: 0 - " + String.format("%.1f", ruptureRadius) + " m");
		System.out.println("  Plastic zone: 0 - " + String.format("%.1f", plasticRadius) + " m");
		System.out.println("Depth limits:");
		System.out.println("  Surface Y: " + String.format("%.1f", surfaceY));
		System.out.println("  Min Y (with bedrock protection): " + String.format("%.1f", minY));
		System.out.println("  Crater bottom Y: " + String.format("%.1f", surfaceY - craterDepth));
		System.out.println("  Bedrock safety: " + (minY > ABSOLUTE_BEDROCK_LEVEL + BEDROCK_SAFETY_MARGIN - 0.1 ? "PROTECTED" : "WARNING"));
		System.out.println("==================================");
	}
}
