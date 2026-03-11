package com.hbm.physics.fusion;

/**
 * Magnetic Confinement Physics for ITER-style Tokamak
 *
 * Handles magnetic field power requirements, field strength calculations,
 * and confinement loss when power is insufficient.
 *
 * Based on ITER specifications:
 * - Toroidal field: 5.3 Tesla
 * - Cryogenic cooling power: 20 MW (superconducting coils)
 * - Stored magnetic energy: 51 GJ
 *
 * References:
 * - https://www.iter.org/mag/5/40
 * - https://www.iter.org/machine/magnets
 */
public class MagneticConfinement {

	// ITER magnetic field parameters
	public static final double TOROIDAL_FIELD_NOMINAL = 5.3;        // Tesla
	public static final double POLOIDAL_FIELD_NOMINAL = 0.5;        // Tesla (approximate)
	// ITER cryogenic power for superconducting magnets: 20 MW
	public static final double CRYOGENIC_POWER_NOMINAL = 20.0e6;    // W (20 MW for 5.3T)
	public static final double PULSE_POWER = 500.0e6;               // W (500 MW for 30s pulses)

	// Minimum field for plasma confinement
	public static final double MIN_FIELD_FOR_CONFINEMENT = 0.5;     // Tesla
	public static final double MIN_POWER_FRACTION = 0.1;            // 10% minimum to prevent collapse

	/**
	 * Calculate required magnetic field maintenance power.
	 * Power scales with B² for superconducting coils (cryogenic load).
	 *
	 * @param magneticField Current field strength (T)
	 * @return Required power (W)
	 */
	public static double calculateMagnetPower(double magneticField) {
		// Power scales with B² for cryogenic cooling
		double nominalB = TOROIDAL_FIELD_NOMINAL;
		double powerRatio = (magneticField * magneticField) / (nominalB * nominalB);
		return CRYOGENIC_POWER_NOMINAL * powerRatio;
	}

	/**
	 * Calculate required power per tick (for Minecraft game loop: 20 ticks/second).
	 *
	 * @param magneticField Desired field strength (T)
	 * @return Required power per tick (HE/tick, where 1 HE ≈ 1 Watt-tick)
	 */
	public static long calculateMagnetPowerPerTick(double magneticField) {
		double powerPerSecond = calculateMagnetPower(magneticField);
		// 20 ticks per second
		return (long)(powerPerSecond / 20.0);
	}

	/**
	 * Check if magnetic field can be maintained with available power.
	 *
	 * @param availablePower Power available (W or HE/tick)
	 * @param magneticField Desired field strength (T)
	 * @return true if sufficient power to maintain field
	 */
	public static boolean canMaintainField(double availablePower, double magneticField) {
		double requiredPower = calculateMagnetPower(magneticField);
		return availablePower >= requiredPower * MIN_POWER_FRACTION;
	}

	/**
	 * Calculate actual magnetic field achievable with available power.
	 * If power is insufficient, field strength is reduced proportionally.
	 *
	 * @param availablePower Power available (W)
	 * @return Actual achievable field strength (T)
	 */
	public static double calculateActualField(double availablePower) {
		if (availablePower < CRYOGENIC_POWER_NOMINAL * MIN_POWER_FRACTION) {
			// Below 10% of nominal power, field collapses
			return 0.0;
		}

		// B ∝ √P (from P ∝ B²)
		double nominalB = TOROIDAL_FIELD_NOMINAL;
		double powerRatio = availablePower / CRYOGENIC_POWER_NOMINAL;

		// Clamp to prevent exceeding nominal field
		powerRatio = Math.min(1.0, powerRatio);

		return nominalB * Math.sqrt(powerRatio);
	}

	/**
	 * Calculate magnetic energy stored in the field.
	 * E_magnetic = B²/(2μ₀) × V
	 *
	 * @param B Magnetic field strength (T)
	 * @param volume Plasma volume (m³)
	 * @return Magnetic energy stored (J)
	 */
	public static double calculateMagneticEnergy(double B, double volume) {
		double mu0 = 4.0 * Math.PI * 1.0e-7; // Permeability of free space
		// E_mag = B²/(2μ₀) × V
		return (B * B / (2.0 * mu0)) * volume;
	}

	/**
	 * Calculate magnetic pressure (force per unit area).
	 * P_magnetic = B²/(2μ₀)
	 *
	 * @param B Magnetic field strength (T)
	 * @return Magnetic pressure (Pa)
	 */
	public static double calculateMagneticPressure(double B) {
		double mu0 = 4.0 * Math.PI * 1.0e-7;
		return (B * B) / (2.0 * mu0);
	}

	/**
	 * Check if field is sufficient for plasma confinement.
	 *
	 * @param magneticField Current field strength (T)
	 * @return true if field is strong enough for confinement
	 */
	public static boolean isFieldSufficient(double magneticField) {
		return magneticField >= MIN_FIELD_FOR_CONFINEMENT;
	}

	/**
	 * Calculate time constant for magnetic field decay when power is lost.
	 * For superconducting coils: τ = L/R where L is inductance, R is resistance.
	 *
	 * For ITER-scale: τ ≈ 50-100 ms (fast decay in emergency)
	 *
	 * @return Decay time constant (seconds)
	 */
	public static double getFieldDecayTime() {
		return 0.05; // 50 milliseconds for thermal quench scenario
	}

	/**
	 * Calculate field strength after power loss with exponential decay.
	 * B(t) = B₀ × exp(-t/τ)
	 *
	 * @param initialField Initial field strength (T)
	 * @param elapsedTime Time since power loss (s)
	 * @return Current field strength (T)
	 */
	public static double calculateFieldAfterPowerLoss(double initialField, double elapsedTime) {
		double tau = getFieldDecayTime();
		return initialField * Math.exp(-elapsedTime / tau);
	}
}
