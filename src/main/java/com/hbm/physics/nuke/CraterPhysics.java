package com.hbm.physics.nuke;

/**
 * CRATER PHYSICS CALCULATIONS
 *
 * Based on "The Effects of Nuclear Weapons" (1977), Chapter VI
 *
 * Implements crater formation physics including:
 * - Apparent crater dimensions (§6.09, 6.72)
 * - Variable depth profiles (§6.70)
 * - Scaling laws (W^0.3 for crater dimensions)
 *
 * Key equations from PDF:
 * - Apparent radius: R_a ≈ 60 × W^0.3 feet (1 kt dry soil baseline)
 * - Apparent depth: D_a ≈ 30 × W^0.3 feet (1 kt dry soil baseline)
 * - Depth profile: Parabolic (deepest at center, zero at edge)
 */
public class CraterPhysics {

	// ===SCALING CONSTANTS (from PDF §6.09, 6.72) ===

	// Baseline crater dimensions for 1 kt surface burst in dry soil
	private static final double BASELINE_RADIUS_DRY_SOIL = 60.0;  // feet for 1 kt
	private static final double BASELINE_DEPTH_DRY_SOIL = 30.0;   // feet for 1 kt

	// Wet soil produces larger craters (§6.72)
	private static final double BASELINE_RADIUS_WET_SOIL = 82.0;  // feet for 1 kt
	private static final double BASELINE_DEPTH_WET_SOIL = 31.0;   // feet for 1 kt

	// Hard rock produces smaller craters
	private static final double BASELINE_RADIUS_HARD_ROCK = 49.0; // feet for 1 kt
	private static final double BASELINE_DEPTH_HARD_ROCK = 22.0;  // feet for 1 kt

	// Scaling exponent for crater dimensions (§6.70)
	private static final double CRATER_SCALING_EXPONENT = 0.3;

	// Conversion: feet to meters
	private static final double FEET_TO_METERS = 0.3048;

	// === CRATER DIMENSION CALCULATIONS ===

	/**
	 * Calculate apparent crater radius for surface burst
	 *
	 * Based on PDF §6.09: R_a = baseline × W^0.3
	 *
	 * @param yieldKilotons Weapon yield in kilotons
	 * @param soilType SoilType enum (DRY_SOIL, WET_SOIL, HARD_ROCK)
	 * @return Apparent crater radius in meters
	 */
	public static double getApparentCraterRadius(double yieldKilotons, SoilType soilType) {
		double baselineRadiusFeet;

		switch (soilType) {
			case WET_SOIL:
				baselineRadiusFeet = BASELINE_RADIUS_WET_SOIL;
				break;
			case HARD_ROCK:
				baselineRadiusFeet = BASELINE_RADIUS_HARD_ROCK;
				break;
			case DRY_SOIL:
			default:
				baselineRadiusFeet = BASELINE_RADIUS_DRY_SOIL;
				break;
		}

		// Apply scaling law: R_a = baseline × W^0.3
		double radiusFeet = baselineRadiusFeet * Math.pow(yieldKilotons, CRATER_SCALING_EXPONENT);

		// Convert to meters
		return radiusFeet * FEET_TO_METERS;
	}

	/**
	 * Calculate apparent crater depth at center (maximum depth)
	 *
	 * Based on PDF §6.09: D_a = baseline × W^0.3
	 *
	 * @param yieldKilotons Weapon yield in kilotons
	 * @param soilType SoilType enum (DRY_SOIL, WET_SOIL, HARD_ROCK)
	 * @return Apparent crater depth at center in meters
	 */
	public static double getApparentCraterDepth(double yieldKilotons, SoilType soilType) {
		double baselineDepthFeet;

		switch (soilType) {
			case WET_SOIL:
				baselineDepthFeet = BASELINE_DEPTH_WET_SOIL;
				break;
			case HARD_ROCK:
				baselineDepthFeet = BASELINE_DEPTH_HARD_ROCK;
				break;
			case DRY_SOIL:
			default:
				baselineDepthFeet = BASELINE_DEPTH_DRY_SOIL;
				break;
		}

		// Apply scaling law: D_a = baseline × W^0.3
		double depthFeet = baselineDepthFeet * Math.pow(yieldKilotons, CRATER_SCALING_EXPONENT);

		// Convert to meters
		return depthFeet * FEET_TO_METERS;
	}

	/**
	 * Calculate crater depth at specific distance from center
	 *
	 * Uses PARABOLIC DEPTH PROFILE (§6.70):
	 * depth(r) = D_a × (1 - (r/R_a)²)
	 *
	 * This creates a bowl-shaped crater:
	 * - Maximum depth D_a at center (r=0)
	 * - Zero depth at crater radius (r=R_a)
	 * - Smooth parabolic transition
	 *
	 * @param distanceFromCenter Horizontal distance from ground zero (meters)
	 * @param centerX Ground zero X coordinate
	 * @param centerZ Ground zero Z coordinate
	 * @param blockX Block X coordinate
	 * @param blockZ Block Z coordinate
	 * @param yieldKilotons Weapon yield in kilotons
	 * @param soilType SoilType enum
	 * @return Crater depth at this location (meters), 0 if outside crater
	 */
	public static double getCraterDepthAt(double centerX, double centerZ,
	                                       double blockX, double blockZ,
	                                       double yieldKilotons, SoilType soilType) {
		// Calculate distance from center
		double dx = blockX - centerX;
		double dz = blockZ - centerZ;
		double r = Math.sqrt(dx * dx + dz * dz);

		// Get crater parameters
		double R_a = getApparentCraterRadius(yieldKilotons, soilType);
		double D_a = getApparentCraterDepth(yieldKilotons, soilType);

		// Outside crater radius: no depression
		if (r >= R_a) {
			return 0.0;
		}

		// Inside crater: parabolic depth profile
		// depth(r) = D_a × (1 - (r/R_a)²)
		double normalizedDistance = r / R_a;  // Range [0, 1]
		double depthFactor = 1.0 - (normalizedDistance * normalizedDistance);

		return D_a * depthFactor;
	}

	/**
	 * Calculate crater lip height above original ground level
	 *
	 * Based on PDF §6.71: H_al ≈ 0.25 × D_a
	 *
	 * @param yieldKilotons Weapon yield in kilotons
	 * @param soilType SoilType enum
	 * @return Lip height in meters
	 */
	public static double getCraterLipHeight(double yieldKilotons, SoilType soilType) {
		double D_a = getApparentCraterDepth(yieldKilotons, soilType);
		return 0.25 * D_a;
	}

	/**
	 * Calculate radius to crater lip crest
	 *
	 * Based on PDF §6.71: R_al ≈ 1.25 × R_a
	 *
	 * @param yieldKilotons Weapon yield in kilotons
	 * @param soilType SoilType enum
	 * @return Lip crest radius in meters
	 */
	public static double getCraterLipRadius(double yieldKilotons, SoilType soilType) {
		double R_a = getApparentCraterRadius(yieldKilotons, soilType);
		return 1.25 * R_a;
	}

	/**
	 * Calculate true crater radius (extends beyond apparent crater)
	 *
	 * Based on PDF §6.70: R_t ≈ 1.25 × R_a
	 *
	 * @param yieldKilotons Weapon yield in kilotons
	 * @param soilType SoilType enum
	 * @return True crater radius in meters
	 */
	public static double getTrueCraterRadius(double yieldKilotons, SoilType soilType) {
		double R_a = getApparentCraterRadius(yieldKilotons, soilType);
		return 1.25 * R_a;
	}

	/**
	 * Soil type enum for crater calculations
	 * Different soil types produce different crater sizes (PDF §6.72)
	 */
	public enum SoilType {
		DRY_SOIL,   // Baseline
		WET_SOIL,   // Larger craters (higher moisture increases plasticity)
		HARD_ROCK   // Smaller craters (less energy coupling)
	}

	/**
	 * Print crater parameters for debugging
	 */
	public static void printCraterParameters(double yieldKilotons, SoilType soilType) {
		System.out.println("=== CRATER PARAMETERS (PDF-based) ===");
		System.out.println("Yield: " + String.format("%.1f", yieldKilotons) + " kt");
		System.out.println("Soil type: " + soilType);
		System.out.println("Apparent crater radius (R_a): " +
			String.format("%.1f", getApparentCraterRadius(yieldKilotons, soilType)) + " m");
		System.out.println("Apparent crater depth (D_a): " +
			String.format("%.1f", getApparentCraterDepth(yieldKilotons, soilType)) + " m");
		System.out.println("Lip height (H_al): " +
			String.format("%.1f", getCraterLipHeight(yieldKilotons, soilType)) + " m");
		System.out.println("Lip radius (R_al): " +
			String.format("%.1f", getCraterLipRadius(yieldKilotons, soilType)) + " m");
		System.out.println("True crater radius (R_t): " +
			String.format("%.1f", getTrueCraterRadius(yieldKilotons, soilType)) + " m");
		System.out.println("Depth profile: Parabolic (§6.70)");
		System.out.println("Scaling: W^0.3 (§6.70)");
		System.out.println("=====================================");
	}
}
