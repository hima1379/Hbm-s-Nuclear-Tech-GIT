package com.hbm.physics.nuke;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * BURST TYPE DETERMINATION AND ENERGY COUPLING CALCULATOR
 *
 * Determines the type of nuclear burst based on detonation environment
 * and calculates energy coupling efficiency to ground/terrain.
 *
 * PHYSICAL BASIS:
 * - AIR BURST: Most energy dissipates in atmosphere (low ground coupling)
 * - SURFACE BURST: Direct ground contact increases coupling efficiency
 * - SUBSURFACE BURST: Bedrock confinement dramatically enhances coupling (2-5x)
 *
 * ENERGY COUPLING EFFICIENCIES:
 * Based on scientific literature:
 * - Glasstone & Dolan (1977): "Effects of Nuclear Weapons"
 * - National Academies (2005): "Effects of Nuclear Earth-Penetrator Weapons"
 * - FM 8-9: NATO NBC Defensive Operations Handbook
 *
 * References show that only a fraction of thermal and blast energy actually
 * couples to the ground to cause terrain destruction. Most energy is absorbed
 * by the atmosphere as heat.
 *
 * THERMAL ENERGY DISTRIBUTION:
 * - 70-80% consumed by initial air heating (X-ray absorption)
 * - 20-30% re-radiated as thermal pulse
 * - Only 10-60% of thermal pulse reaches ground (depending on burst type)
 *
 * BLAST ENERGY DISTRIBUTION:
 * - 70-80% dissipated as atmospheric heating (hysteresis)
 * - 20-30% remains as shock kinetic energy
 * - Only 12-75% of shock energy does mechanical work on ground
 *
 * @author HBM Nuclear Physics Team
 */
public class BurstTypeCalculator {

	// === BURST TYPE CLASSIFICATION ===

	/**
	 * Nuclear burst type classification based on detonation environment
	 */
	public enum BurstType {
		/** Air burst: Detonation above ground level (height > 100m typically)
		 *  Optimized for blast wave propagation through air
		 *  Lowest ground coupling efficiency */
		AIR_BURST,

		/** Surface burst: Detonation at or near ground level (height < 100m)
		 *  Creates crater, high fallout, moderate ground coupling
		 *  Used for hardened target destruction */
		SURFACE_BURST,

		/** Subsurface burst: Detonation underground, surrounded by bedrock
		 *  Extreme confinement effect, highest ground coupling (2-5x amplification)
		 *  Used for bunker-buster weapons */
		SUBSURFACE_BURST
	}

	// === COUPLING EFFICIENCY CONSTANTS ===
	// These values represent the fraction of thermal/blast energy that actually
	// couples to the ground to cause terrain destruction

	/** Thermal energy coupling for air burst (10-20% reaches ground effectively) */
	private static final double THERMAL_COUPLING_AIR = 0.15;

	/** Thermal energy coupling for surface burst (50-60% reaches ground) */
	private static final double THERMAL_COUPLING_SURFACE = 0.55;

	/** Thermal energy coupling for subsurface burst (70-90% trapped underground) */
	private static final double THERMAL_COUPLING_SUBSURFACE = 0.80;

	/** Blast energy coupling for air burst (12-24% does mechanical work on ground) */
	private static final double BLAST_COUPLING_AIR = 0.18;

	/** Blast energy coupling for surface burst (40-60% couples to ground) */
	private static final double BLAST_COUPLING_SURFACE = 0.50;

	/** Blast energy coupling for subsurface burst (60-90% trapped underground) */
	private static final double BLAST_COUPLING_SUBSURFACE = 0.75;

	// === CONFINEMENT CALCULATION PARAMETERS ===

	/** Scan radius for bedrock density calculation (meters) */
	private static final int BEDROCK_SCAN_RADIUS = 25; // 50m cube total

	/** Minimum bedrock confinement factor (no confinement) */
	private static final double CONFINEMENT_MIN = 1.0;

	/** Maximum bedrock confinement factor (complete confinement) */
	private static final double CONFINEMENT_MAX = 5.0;

	/** Bedrock density threshold for subsurface classification (fraction) */
	private static final double SUBSURFACE_THRESHOLD = 0.20; // 20% bedrock

	/** Height threshold for air burst vs surface burst (meters) */
	private static final double AIR_BURST_HEIGHT = 100.0;

	// === PUBLIC INTERFACE ===

	/**
	 * Determine burst type based on detonation position and environment
	 *
	 * @param world The Minecraft world
	 * @param x Detonation X coordinate
	 * @param y Detonation Y coordinate
	 * @param z Detonation Z coordinate
	 * @return The burst type classification
	 */
	public static BurstType determineBurstType(World world, double x, double y, double z) {
		// Calculate bedrock density in surrounding volume
		double bedrockDensity = calculateBedrockDensity(world, x, y, z);

		// If significant bedrock presence, classify as subsurface
		if (bedrockDensity >= SUBSURFACE_THRESHOLD) {
			return BurstType.SUBSURFACE_BURST;
		}

		// Otherwise, classify based on height above ground
		// Note: In Minecraft, sea level is typically y=64
		// We use y=100 as threshold for air burst
		if (y >= AIR_BURST_HEIGHT) {
			return BurstType.AIR_BURST;
		} else {
			return BurstType.SURFACE_BURST;
		}
	}

	/**
	 * Get thermal energy coupling efficiency for given burst type
	 *
	 * @param burstType The burst type
	 * @return Coupling efficiency (0.0 to 1.0)
	 */
	public static double getThermalCoupling(BurstType burstType) {
		switch (burstType) {
			case AIR_BURST:
				return THERMAL_COUPLING_AIR;
			case SURFACE_BURST:
				return THERMAL_COUPLING_SURFACE;
			case SUBSURFACE_BURST:
				return THERMAL_COUPLING_SUBSURFACE;
			default:
				return THERMAL_COUPLING_SURFACE; // Default fallback
		}
	}

	/**
	 * Get blast energy coupling efficiency for given burst type
	 *
	 * @param burstType The burst type
	 * @return Coupling efficiency (0.0 to 1.0)
	 */
	public static double getBlastCoupling(BurstType burstType) {
		switch (burstType) {
			case AIR_BURST:
				return BLAST_COUPLING_AIR;
			case SURFACE_BURST:
				return BLAST_COUPLING_SURFACE;
			case SUBSURFACE_BURST:
				return BLAST_COUPLING_SUBSURFACE;
			default:
				return BLAST_COUPLING_SURFACE; // Default fallback
		}
	}

	/**
	 * Calculate confinement factor due to bedrock enclosure
	 *
	 * When a nuclear explosion is confined by bedrock, the blast waves
	 * reflect off the walls and reinforce each other, dramatically
	 * increasing the destructive effect.
	 *
	 * Confinement factor ranges from 1.0× (no confinement) to 5.0× (complete enclosure)
	 *
	 * Reference: National Academies (2005), Chapter 4 "Coupling of Weapon Energy to Ground"
	 *
	 * @param world The Minecraft world
	 * @param x Detonation X coordinate
	 * @param y Detonation Y coordinate
	 * @param z Detonation Z coordinate
	 * @return Confinement factor (1.0 to 5.0)
	 */
	public static double calculateConfinementFactor(World world, double x, double y, double z) {
		double bedrockDensity = calculateBedrockDensity(world, x, y, z);

		// Linear interpolation: 0% bedrock = 1.0×, 100% bedrock = 5.0×
		double confinementFactor = CONFINEMENT_MIN +
			(CONFINEMENT_MAX - CONFINEMENT_MIN) * bedrockDensity;

		return confinementFactor;
	}

	/**
	 * Get effective coupling efficiency including confinement
	 *
	 * For subsurface bursts, the coupling efficiency is multiplied
	 * by the confinement factor to account for wave reflection.
	 *
	 * @param world The Minecraft world
	 * @param x Detonation X coordinate
	 * @param y Detonation Y coordinate
	 * @param z Detonation Z coordinate
	 * @param isBlast True for blast energy, false for thermal energy
	 * @return Effective coupling efficiency
	 */
	public static double getEffectiveCoupling(World world, double x, double y, double z, boolean isBlast) {
		BurstType burstType = determineBurstType(world, x, y, z);
		double baseCoupling = isBlast ? getBlastCoupling(burstType) : getThermalCoupling(burstType);

		// Apply confinement factor for subsurface bursts
		if (burstType == BurstType.SUBSURFACE_BURST) {
			double confinementFactor = calculateConfinementFactor(world, x, y, z);
			return Math.min(baseCoupling * confinementFactor, 1.0); // Cap at 100%
		}

		return baseCoupling;
	}

	// === INTERNAL HELPER METHODS ===

	/**
	 * Calculate bedrock density in a cubic volume around the detonation point
	 *
	 * Samples a 50m×50m×50m cube (±25m in each direction) and counts
	 * the fraction of blocks that are bedrock.
	 *
	 * Uses sparse sampling (every 5 blocks) for performance.
	 *
	 * @param world The Minecraft world
	 * @param centerX Center X coordinate
	 * @param centerY Center Y coordinate
	 * @param centerZ Center Z coordinate
	 * @return Bedrock density (0.0 to 1.0)
	 */
	private static double calculateBedrockDensity(World world, double centerX, double centerY, double centerZ) {
		int bedrockCount = 0;
		int totalSamples = 0;

		// Sample every 5 blocks to reduce performance impact
		int sampleInterval = 5;

		for (int dx = -BEDROCK_SCAN_RADIUS; dx <= BEDROCK_SCAN_RADIUS; dx += sampleInterval) {
			for (int dy = -BEDROCK_SCAN_RADIUS; dy <= BEDROCK_SCAN_RADIUS; dy += sampleInterval) {
				for (int dz = -BEDROCK_SCAN_RADIUS; dz <= BEDROCK_SCAN_RADIUS; dz += sampleInterval) {
					BlockPos checkPos = new BlockPos(
						centerX + dx,
						centerY + dy,
						centerZ + dz
					);

					// Check if world position is loaded before accessing
					if (world.isBlockLoaded(checkPos)) {
						Block block = world.getBlockState(checkPos).getBlock();
						if (block == Blocks.BEDROCK) {
							bedrockCount++;
						}
						totalSamples++;
					}
				}
			}
		}

		// Return fraction of samples that were bedrock
		if (totalSamples == 0) {
			return 0.0; // No samples taken (shouldn't happen)
		}

		return (double) bedrockCount / (double) totalSamples;
	}

	/**
	 * Get a human-readable description of the burst type
	 *
	 * @param burstType The burst type
	 * @return Description string
	 */
	public static String getBurstTypeDescription(BurstType burstType) {
		switch (burstType) {
			case AIR_BURST:
				return "AIR BURST (high altitude, low ground coupling)";
			case SURFACE_BURST:
				return "SURFACE BURST (ground level, moderate coupling)";
			case SUBSURFACE_BURST:
				return "SUBSURFACE BURST (underground, extreme confinement)";
			default:
				return "UNKNOWN";
		}
	}

	/**
	 * Print detailed energy coupling report to console
	 * Used for debugging and validation
	 *
	 * @param world The Minecraft world
	 * @param x Detonation X coordinate
	 * @param y Detonation Y coordinate
	 * @param z Detonation Z coordinate
	 * @param yieldKilotons Weapon yield in kilotons
	 */
	public static void printCouplingReport(World world, double x, double y, double z, double yieldKilotons) {
		BurstType burstType = determineBurstType(world, x, y, z);
		double thermalCoupling = getThermalCoupling(burstType);
		double blastCoupling = getBlastCoupling(burstType);
		double confinement = calculateConfinementFactor(world, x, y, z);
		double bedrockDensity = calculateBedrockDensity(world, x, y, z);

		System.out.println("=== NUCLEAR BURST TYPE ANALYSIS ===");
		System.out.println("Location: (" + x + ", " + y + ", " + z + ")");
		System.out.println("Yield: " + yieldKilotons + " kt");
		System.out.println("Burst type: " + getBurstTypeDescription(burstType));
		System.out.println("Bedrock density: " + String.format("%.1f%%", bedrockDensity * 100));
		System.out.println("Confinement factor: " + String.format("%.2f×", confinement));
		System.out.println();
		System.out.println("ENERGY COUPLING EFFICIENCIES:");
		System.out.println("  Thermal coupling: " + String.format("%.1f%%", thermalCoupling * 100));
		System.out.println("  Blast coupling: " + String.format("%.1f%%", blastCoupling * 100));
		System.out.println();
		System.out.println("EFFECTIVE GROUND DAMAGE:");
		System.out.println("  Total yield energy: 100%");
		System.out.println("  Thermal (35% × " + String.format("%.1f%%", thermalCoupling * 100) +
			"): " + String.format("%.1f%%", 35.0 * thermalCoupling));
		System.out.println("  Blast (50% × " + String.format("%.1f%%", blastCoupling * 100) +
			"): " + String.format("%.1f%%", 50.0 * blastCoupling));
		System.out.println("  TOTAL GROUND COUPLING: " +
			String.format("%.1f%%", (35.0 * thermalCoupling + 50.0 * blastCoupling)));

		if (burstType == BurstType.SUBSURFACE_BURST) {
			System.out.println();
			System.out.println("  With confinement (" + String.format("%.2f×", confinement) + "): " +
				String.format("%.1f%%", (35.0 * thermalCoupling + 50.0 * blastCoupling) * confinement));
		}

		System.out.println("====================================");
	}
}
