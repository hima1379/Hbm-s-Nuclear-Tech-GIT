package com.hbm.physics.nuke;

/**
 * FIREBALL RISE MODEL
 *
 * Models the upward motion of a nuclear fireball during expansion.
 *
 * Physical basis (from "Effects of Nuclear Weapons" Chapter 2):
 * - Fireball rises while expanding due to buoyancy
 * - Rise velocity: ~250-350 ft/s at 10 seconds for 1-megaton
 * - After 1 minute: rises ~4.5 miles (7.2 km) from burst point
 * - Scaling: velocities and heights scale as W^0.4
 *
 * Key effects:
 * 1. Asymmetric energy coupling (downward bias)
 * 2. Non-spherical crater formation
 * 3. Fireball doesn't stay centered on detonation point
 *
 * This creates MORE REALISTIC crater patterns:
 * - Not a perfect sphere
 * - Downward-biased destruction
 * - Irregular shape due to moving energy source
 */
public class FireballRiseModel {

	// === PHYSICAL CONSTANTS ===

	/** Base rise velocity at 10 seconds for 1 kt weapon (m/s)
	 *  Reference: Glasstone & Dolan Chapter 2
	 *  For 1 Mt: 250-350 ft/s ≈ 76-107 m/s
	 *  For 1 kt: scaled by W^0.4 factor */
	private static final double BASE_RISE_VELOCITY_1KT = 10.0; // m/s at 10 seconds

	/** Scaling exponent for rise velocity
	 *  V ∝ W^0.4 (same as fireball radius scaling) */
	private static final double VELOCITY_SCALING_EXPONENT = 0.4;

	/** Acceleration phase duration (seconds)
	 *  Fireball accelerates for first ~5 seconds, then rises at ~constant velocity */
	private static final double ACCELERATION_DURATION = 5.0;

	/** Time scaling factor
	 *  Times scale as W^0.4 */
	private static final double TIME_SCALING_EXPONENT = 0.4;

	// === INSTANCE PARAMETERS ===
	private final double yieldKilotons;
	private final double initialCenterY;
	private final double terminalRiseVelocity; // m/s
	private final double accelerationDuration;

	/**
	 * Constructor
	 * @param yieldKilotons Weapon yield in kilotons
	 * @param initialCenterY Initial burst height (Y coordinate)
	 */
	public FireballRiseModel(double yieldKilotons, double initialCenterY) {
		this.yieldKilotons = yieldKilotons;
		this.initialCenterY = initialCenterY;

		// Calculate terminal rise velocity based on yield
		// V = V_base × W^0.4
		this.terminalRiseVelocity = BASE_RISE_VELOCITY_1KT * Math.pow(yieldKilotons, VELOCITY_SCALING_EXPONENT);

		// Scale acceleration duration with yield
		this.accelerationDuration = ACCELERATION_DURATION * Math.pow(yieldKilotons, TIME_SCALING_EXPONENT);
	}

	/**
	 * Calculate fireball center Y coordinate at given time
	 *
	 * Motion model:
	 * - Phase 1 (0 to ~5s): Accelerating upward
	 * - Phase 2 (>5s): Rising at terminal velocity
	 *
	 * @param elapsedSeconds Time since detonation (seconds)
	 * @return Y coordinate of fireball center
	 */
	public double getCenterY(double elapsedSeconds) {
		if (elapsedSeconds < 0) {
			return initialCenterY;
		}

		double displacement;

		if (elapsedSeconds <= accelerationDuration) {
			// PHASE 1: Accelerating
			// Use quadratic acceleration: d = 0.5 * a * t²
			// Where a chosen such that v_terminal is reached at t_accel
			double acceleration = terminalRiseVelocity / accelerationDuration;
			displacement = 0.5 * acceleration * elapsedSeconds * elapsedSeconds;
		} else {
			// PHASE 2: Terminal velocity
			// First calculate distance during acceleration phase
			double accelDistance = 0.5 * terminalRiseVelocity * accelerationDuration;

			// Then add linear motion at terminal velocity
			double coastingTime = elapsedSeconds - accelerationDuration;
			double coastDistance = terminalRiseVelocity * coastingTime;

			displacement = accelDistance + coastDistance;
		}

		return initialCenterY + displacement;
	}

	/**
	 * Calculate current rise velocity at given time
	 * @param elapsedSeconds Time since detonation (seconds)
	 * @return Vertical velocity (m/s)
	 */
	public double getRiseVelocity(double elapsedSeconds) {
		if (elapsedSeconds < 0) {
			return 0.0;
		}

		if (elapsedSeconds <= accelerationDuration) {
			// Accelerating: v = a * t
			double acceleration = terminalRiseVelocity / accelerationDuration;
			return acceleration * elapsedSeconds;
		} else {
			// Terminal velocity
			return terminalRiseVelocity;
		}
	}

	/**
	 * Calculate directional energy bias factor
	 *
	 * Physical basis:
	 * - Energy couples more strongly DOWNWARD (toward ground)
	 * - Less energy couples UPWARD (fireball rising away)
	 * - Horizontal coupling is intermediate
	 *
	 * This creates asymmetric, more realistic craters.
	 *
	 * @param blockX Block X coordinate
	 * @param blockY Block Y coordinate
	 * @param blockZ Block Z coordinate
	 * @param centerX Current fireball center X
	 * @param centerY Current fireball center Y (time-dependent)
	 * @param centerZ Current fireball center Z
	 * @return Energy coupling factor [0.0 - 2.0]
	 */
	public static double getDirectionalBiasFactor(double blockX, double blockY, double blockZ,
	                                               double centerX, double centerY, double centerZ) {
		// Calculate direction vector from fireball center to block
		double dx = blockX - centerX;
		double dy = blockY - centerY;
		double dz = blockZ - centerZ;

		double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
		double totalDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);

		if (totalDistance < 0.01) {
			return 1.0; // At center
		}

		// Calculate vertical angle
		// angle > 0: block is ABOVE fireball center
		// angle < 0: block is BELOW fireball center
		double verticalAngle = Math.asin(dy / totalDistance);

		// Apply directional bias
		// Based on physical principles:
		// - DOWNWARD (negative angle): Strong coupling (factor > 1.0)
		// - HORIZONTAL (angle ≈ 0): Normal coupling (factor ≈ 1.0)
		// - UPWARD (positive angle): Weak coupling (factor < 1.0)

		double biasFactor;

		if (verticalAngle < 0) {
			// BELOW fireball - ENHANCED destruction
			// Factor ranges from 1.0 (horizontal) to 2.0 (directly below)
			double downwardness = Math.abs(verticalAngle) / (Math.PI / 2.0); // 0 to 1
			biasFactor = 1.0 + downwardness; // 1.0 to 2.0
		} else {
			// ABOVE fireball - REDUCED destruction
			// Factor ranges from 1.0 (horizontal) to 0.3 (directly above)
			double upwardness = verticalAngle / (Math.PI / 2.0); // 0 to 1
			biasFactor = 1.0 - (0.7 * upwardness); // 1.0 to 0.3
		}

		return biasFactor;
	}

	/**
	 * Print model parameters for debugging
	 */
	public void printModelInfo() {
		System.out.println("=== FIREBALL RISE MODEL ===");
		System.out.println("Weapon yield: " + String.format("%.1f", yieldKilotons) + " kt");
		System.out.println("Initial burst height: " + (int) initialCenterY + " m");
		System.out.println("Terminal rise velocity: " + String.format("%.1f", terminalRiseVelocity) + " m/s");
		System.out.println("Acceleration duration: " + String.format("%.1f", accelerationDuration) + " s");
		System.out.println("Physics: Buoyant rise with directional bias");
		System.out.println("===========================");
	}

	// === ACCESSORS ===

	public double getTerminalRiseVelocity() {
		return terminalRiseVelocity;
	}

	public double getInitialCenterY() {
		return initialCenterY;
	}
}
