package com.hbm.physics.nuke;

/**
 * KINGERY-BULMASH BLAST PARAMETER CALCULATOR
 *
 * Implements empirical equations for nuclear blast wave parameters based on:
 * - Kingery & Bulmash (1984) "Air Blast Parameters from TNT Spherical Air Burst and Hemispherical Surface Burst"
 * - Glasstone & Dolan (1977) "Effects of Nuclear Weapons"
 * - UFC 3-340-02 (2008) "Structures to Resist the Effects of Accidental Explosions"
 *
 * SCALING LAW:
 * All blast parameters are functions of scaled distance:
 *   Z = R / W^(1/3)
 * where R is distance in meters, W is yield in kg of TNT equivalent
 *
 * VALID RANGE:
 * 0.05 < Z < 40 m/kg^(1/3)
 *
 * OUTPUTS:
 * - Peak incident overpressure (Pso) [PSI]
 * - Dynamic pressure (q) [PSI]
 * - Reflected overpressure (Pr) [PSI]
 * - Shock wave velocity [m/s]
 * - Arrival time [seconds]
 * - Positive phase duration [seconds]
 */
public class KingeryBulmashCalculator {

	// === PHYSICAL CONSTANTS ===
	private static final double GAMMA_AIR = 1.4;              // Heat capacity ratio for air
	private static final double P_AMBIENT_PSI = 14.7;         // Atmospheric pressure (sea level)
	private static final double SOUND_SPEED_MS = 340.0;       // Speed of sound in air (m/s)

	// === CONVERSION CONSTANTS ===
	private static final double PSI_TO_KPA = 6.89476;
	private static final double KPA_TO_PSI = 1.0 / PSI_TO_KPA;

	/**
	 * Calculate peak incident overpressure using Kingery-Bulmash equations
	 *
	 * @param scaledDistance Z = R / W^(1/3) [m/kg^(1/3)]
	 * @return Peak overpressure in PSI
	 */
	public static double calculatePeakOverpressure(double scaledDistance) {
		double Z = scaledDistance;

		if (Z < 0.05) {
			// Very close: simple inverse relation
			// Pso ≈ 20000 / Z
			return 20000.0 / Z;
		} else if (Z < 0.2) {
			// Close range: steep power law decay
			// Pso ≈ 1800 × Z^(-2.5)
			return 1800.0 * Math.pow(Z, -2.5);
		} else if (Z < 1.0) {
			// Medium range: logarithmic polynomial fit
			// log10(Pso) = 2.78 - 1.95×log10(Z) - 0.62×log10(Z)²
			double logZ = Math.log10(Z);
			double logP = 2.78 - 1.95 * logZ - 0.62 * logZ * logZ;
			return Math.pow(10, logP);
		} else if (Z < 10.0) {
			// Far range: complex decay
			// log10(Pso) = 1.35 - 2.23×log10(Z) + 0.47×log10(Z)² - 0.035×log10(Z)³
			double logZ = Math.log10(Z);
			double logP = 1.35 - 2.23 * logZ + 0.47 * logZ * logZ - 0.035 * logZ * logZ * logZ;
			return Math.pow(10, logP);
		} else if (Z < 40.0) {
			// Very far: inverse cube decay
			// Pso ≈ 60 × Z^(-3)
			return 60.0 * Math.pow(Z, -3.0);
		} else {
			// Beyond effective range
			return 0.1; // Negligible pressure
		}
	}

	/**
	 * Calculate dynamic pressure (blast wind drag force)
	 * Using Rankine-Hugoniot relations
	 *
	 * @param peakOverpressurePSI Peak overpressure in PSI
	 * @return Dynamic pressure in PSI
	 */
	public static double calculateDynamicPressure(double peakOverpressurePSI) {
		double Pso = peakOverpressurePSI;

		// For weak shocks (Pso < 10 PSI):
		// q ≈ (5/2) × Pso² / (7 + Pso)
		if (Pso < 10.0) {
			return (2.5 * Pso * Pso) / (7.0 + Pso);
		}

		// For strong shocks (Pso ≥ 10 PSI):
		// q ≈ (5/12) × Pso
		return (5.0 / 12.0) * Pso;
	}

	/**
	 * Calculate reflected overpressure for normal incidence
	 * (blast wave hitting perpendicular surface)
	 *
	 * @param peakOverpressurePSI Peak incident overpressure in PSI
	 * @param dynamicPressurePSI Dynamic pressure in PSI
	 * @return Reflected overpressure in PSI
	 */
	public static double calculateReflectedPressure(double peakOverpressurePSI, double dynamicPressurePSI) {
		double Pso = peakOverpressurePSI;
		double q = dynamicPressurePSI;

		// Normal reflection formula (moderate pressures)
		// Pr = 2×Pso + (γ+1)×q
		double Pr = 2.0 * Pso + (GAMMA_AIR + 1.0) * q;

		// For very high pressures, use exact Rankine-Hugoniot
		if (Pso > 50.0) {
			double ratio = Pso / P_AMBIENT_PSI;
			Pr = Pso * (7.0 * ratio + 4.0) / (ratio + 6.0);
		}

		return Pr;
	}

	/**
	 * Calculate shock wave velocity at given overpressure
	 *
	 * @param peakOverpressurePSI Peak overpressure in PSI
	 * @return Shock wave velocity in m/s
	 */
	public static double calculateShockVelocity(double peakOverpressurePSI) {
		// U/a = sqrt(1 + ((γ+1)/(2γ)) × (Pso/P0))
		// where a = sound speed, γ = 1.4
		double ratio = peakOverpressurePSI / P_AMBIENT_PSI;
		double machNumber = Math.sqrt(1.0 + ((GAMMA_AIR + 1.0) / (2.0 * GAMMA_AIR)) * ratio);
		return machNumber * SOUND_SPEED_MS;
	}

	/**
	 * Calculate blast wave arrival time
	 *
	 * @param distance Distance from explosion in meters
	 * @param yieldKg Yield in kg of TNT equivalent
	 * @return Arrival time in seconds
	 */
	public static double calculateArrivalTime(double distance, double yieldKg) {
		double Z = distance / Math.pow(yieldKg, 1.0 / 3.0);

		// Empirical fit for arrival time
		// ta/W^(1/3) = f(Z)
		double tScaled;

		if (Z < 1.0) {
			// Close range: nearly sonic
			tScaled = Z / 340.0; // Approximately speed of sound
		} else if (Z < 10.0) {
			// Medium range
			double logZ = Math.log10(Z);
			double logT = -0.54 + 1.0 * logZ + 0.13 * logZ * logZ;
			tScaled = Math.pow(10, logT);
		} else {
			// Far range: subsonic
			tScaled = Z / 340.0 * 1.2; // Slower propagation
		}

		return tScaled * Math.pow(yieldKg, 1.0 / 3.0);
	}

	/**
	 * Calculate positive phase duration (compression phase)
	 *
	 * @param scaledDistance Z = R / W^(1/3)
	 * @return Positive phase duration in seconds
	 */
	public static double calculatePositivePhaseDuration(double scaledDistance, double yieldKg) {
		double Z = scaledDistance;

		// Empirical fit for positive phase duration
		// t+/W^(1/3) = f(Z)
		double tPlusScaled;

		if (Z < 0.2) {
			tPlusScaled = 0.2 * Z;
		} else if (Z < 10.0) {
			double logZ = Math.log10(Z);
			double logT = -0.73 + 0.96 * logZ + 0.13 * logZ * logZ;
			tPlusScaled = Math.pow(10, logT);
		} else {
			tPlusScaled = Z * 0.15;
		}

		return tPlusScaled * Math.pow(yieldKg, 1.0 / 3.0);
	}

	/**
	 * Calculate Mach stem enhancement factor for ground bursts
	 * Mach stem is constructive interference between incident and reflected waves
	 *
	 * @param distance Horizontal distance from ground zero (meters)
	 * @param burstHeight Height of burst above ground (meters)
	 * @param yieldKt Yield in kilotons
	 * @return Pressure enhancement factor (1.0 = no enhancement, 2.8 = maximum)
	 */
	public static double calculateMachStemFactor(double distance, double burstHeight, double yieldKt) {
		// Triple point height (where Mach stem forms)
		// Htp ≈ 0.3 × W^0.4 meters
		double triplePointHeight = 0.3 * Math.pow(yieldKt, 0.4);

		// High air burst: no Mach stem
		if (burstHeight > triplePointHeight * 3.0) {
			return 1.0;
		}

		// Mach stem region boundaries
		double machStart = burstHeight * 2.0;           // Starts at ~2× burst height
		double machEnd = 13.0 * Math.pow(yieldKt, 0.33); // Ends far from GZ

		if (distance < machStart) {
			// Too close: regular reflection only
			return 1.0;
		} else if (distance > machEnd) {
			// Too far: Mach stem dissipated
			return 1.0;
		} else {
			// Within Mach stem: enhanced pressure
			double fraction = (distance - machStart) / (machEnd - machStart);
			// Peak enhancement at ~30% of range (factor of 2.8)
			double enhancement = 2.8 - Math.abs(fraction - 0.3) * 2.5;
			return Math.max(1.0, Math.min(2.8, enhancement));
		}
	}

	/**
	 * Calculate terrain shielding factor
	 * Blast pressure reduced when blocked by terrain or structures
	 *
	 * @param isShielded Whether the location is shielded by terrain
	 * @param shieldingAngle Angle of terrain obstruction (radians)
	 * @return Pressure reduction factor (0.0 = fully shielded, 1.0 = no shielding)
	 */
	public static double calculateTerrainShielding(boolean isShielded, double shieldingAngle) {
		if (!isShielded) {
			return 1.0; // No shielding
		}

		// Pressure reduction based on shielding angle
		// 0° (horizontal) = 1.0 (no effect)
		// 90° (vertical wall) = 0.1 (90% reduction)
		double angleEffect = 1.0 - (shieldingAngle / (Math.PI / 2.0)) * 0.9;

		return Math.max(0.1, angleEffect); // Minimum 10% pressure
	}

	/**
	 * Complete blast parameters calculation
	 */
	public static class BlastParameters {
		public double peakOverpressure;      // PSI
		public double dynamicPressure;       // PSI
		public double reflectedPressure;     // PSI
		public double shockVelocity;         // m/s
		public double arrivalTime;           // seconds
		public double positivePhaseDuration; // seconds

		@Override
		public String toString() {
			return String.format(
				"Pso=%.2f PSI, q=%.2f PSI, Pr=%.2f PSI, U=%.1f m/s, ta=%.3f s, t+=%.3f s",
				peakOverpressure, dynamicPressure, reflectedPressure,
				shockVelocity, arrivalTime, positivePhaseDuration
			);
		}
	}

	/**
	 * Calculate all blast parameters for given distance and yield
	 *
	 * @param distance Distance from explosion (meters)
	 * @param yieldKg Yield in kg of TNT equivalent
	 * @return Complete blast parameters
	 */
	public static BlastParameters calculateBlastParameters(double distance, double yieldKg) {
		BlastParameters params = new BlastParameters();

		// Scaled distance
		double Z = distance / Math.pow(yieldKg, 1.0 / 3.0);

		// Calculate all parameters
		params.peakOverpressure = calculatePeakOverpressure(Z);
		params.dynamicPressure = calculateDynamicPressure(params.peakOverpressure);
		params.reflectedPressure = calculateReflectedPressure(
			params.peakOverpressure, params.dynamicPressure);
		params.shockVelocity = calculateShockVelocity(params.peakOverpressure);
		params.arrivalTime = calculateArrivalTime(distance, yieldKg);
		params.positivePhaseDuration = calculatePositivePhaseDuration(Z, yieldKg);

		return params;
	}
}
