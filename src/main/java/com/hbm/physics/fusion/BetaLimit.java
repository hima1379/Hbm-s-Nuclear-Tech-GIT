package com.hbm.physics.fusion;

/**
 * Troyon Beta Limit for Tokamak Plasmas
 *
 * Beta (β) is the ratio of plasma pressure to magnetic pressure:
 * β = P_plasma / P_magnetic = (2μ₀ × P_plasma) / B²
 *
 * High beta is desirable for fusion (more efficient use of magnetic field),
 * but there's a limit above which MHD instabilities cause disruptions.
 *
 * Troyon limit: β_critical = β_N × I_p / (a × B_t)
 *
 * Where:
 * - β_N: Normalized beta (empirical constant, typically 2-4 for tokamaks)
 * - I_p: Plasma current (MA)
 * - a: Minor radius (m)
 * - B_t: Toroidal magnetic field (T)
 *
 * For ITER design:
 * - β_N ≈ 2.5
 * - I_p = 15 MA
 * - a = 2.0 m
 * - B_t = 5.3 T
 * - β_critical ≈ 3.5% (0.035)
 *
 * References:
 * - Troyon, F., et al. (1984). "MHD-Limits to Plasma Confinement". Plasma Physics and Controlled Fusion.
 * - https://www.nature.com/articles/s41586-024-07313-3
 */
public class BetaLimit {

	// Typical normalized beta values for tokamaks
	public static final double BETA_N_STANDARD = 2.5;    // Standard H-mode
	public static final double BETA_N_ADVANCED = 3.5;    // Advanced scenarios
	public static final double BETA_N_CONSERVATIVE = 2.0; // Conservative limit

	/**
	 * Calculate the Troyon beta limit.
	 *
	 * β_limit = β_N × I_p / (a × B_t)
	 *
	 * @param beta_N Normalized beta (dimensionless, typically 2-4)
	 * @param plasmaCurrent_MA Plasma current (MA)
	 * @param minorRadius Minor radius (m)
	 * @param toroidalField Toroidal magnetic field (T)
	 * @return Beta limit (dimensionless, e.g., 0.035 = 3.5%)
	 */
	public static double calculateBetaLimit(double beta_N, double plasmaCurrent_MA,
	                                         double minorRadius, double toroidalField) {
		if (toroidalField == 0 || minorRadius == 0) return 0.0;

		return beta_N * plasmaCurrent_MA / (minorRadius * toroidalField);
	}

	/**
	 * Check if current beta exceeds the Troyon limit.
	 *
	 * @param currentBeta Current plasma beta (dimensionless)
	 * @param plasmaCurrent_MA Plasma current (MA)
	 * @param minorRadius Minor radius (m)
	 * @param toroidalField Toroidal magnetic field (T)
	 * @return true if beta limit is exceeded
	 */
	public static boolean isBetaLimitExceeded(double currentBeta, double plasmaCurrent_MA,
	                                           double minorRadius, double toroidalField) {
		double limit = calculateBetaLimit(BETA_N_STANDARD, plasmaCurrent_MA, minorRadius, toroidalField);
		return currentBeta > limit;
	}

	/**
	 * Calculate the fraction of beta limit currently reached.
	 *
	 * @param currentBeta Current plasma beta (dimensionless)
	 * @param plasmaCurrent_MA Plasma current (MA)
	 * @param minorRadius Minor radius (m)
	 * @param toroidalField Toroidal magnetic field (T)
	 * @return Fraction of limit (0.0 = no pressure, 1.0 = at limit, >1.0 = exceeded)
	 */
	public static double getBetaFraction(double currentBeta, double plasmaCurrent_MA,
	                                      double minorRadius, double toroidalField) {
		double limit = calculateBetaLimit(BETA_N_STANDARD, plasmaCurrent_MA, minorRadius, toroidalField);
		if (limit == 0) return 0.0;
		return currentBeta / limit;
	}

	/**
	 * Calculate the beta margin before reaching limit.
	 *
	 * @param currentBeta Current plasma beta (dimensionless)
	 * @param plasmaCurrent_MA Plasma current (MA)
	 * @param minorRadius Minor radius (m)
	 * @param toroidalField Toroidal magnetic field (T)
	 * @return Beta margin (dimensionless), negative if limit exceeded
	 */
	public static double getBetaMargin(double currentBeta, double plasmaCurrent_MA,
	                                    double minorRadius, double toroidalField) {
		double limit = calculateBetaLimit(BETA_N_STANDARD, plasmaCurrent_MA, minorRadius, toroidalField);
		return limit - currentBeta;
	}

	/**
	 * Get ITER design beta limit.
	 *
	 * @return ITER beta limit (dimensionless, ~0.025-0.035)
	 */
	public static double getITERBetaLimit() {
		// ITER design parameters
		double beta_N = BETA_N_STANDARD;  // 2.5
		double Ip_ITER = 15.0;            // MA
		double a_ITER = 2.0;              // m
		double Bt_ITER = 5.3;             // T

		return calculateBetaLimit(beta_N, Ip_ITER, a_ITER, Bt_ITER);
	}

	/**
	 * Calculate magnetic pressure from field strength.
	 * P_magnetic = B² / (2μ₀)
	 *
	 * @param magneticField Magnetic field strength (T)
	 * @return Magnetic pressure (Pa)
	 */
	public static double calculateMagneticPressure(double magneticField) {
		double mu0 = 4.0 * Math.PI * 1.0e-7; // Permeability of free space
		return (magneticField * magneticField) / (2.0 * mu0);
	}

	/**
	 * Calculate beta from plasma and magnetic pressures.
	 * β = P_plasma / P_magnetic
	 *
	 * @param plasmaPressure Plasma pressure (Pa)
	 * @param magneticField Magnetic field strength (T)
	 * @return Beta (dimensionless)
	 */
	public static double calculateBeta(double plasmaPressure, double magneticField) {
		double P_magnetic = calculateMagneticPressure(magneticField);
		if (P_magnetic == 0) return 0.0;
		return plasmaPressure / P_magnetic;
	}
}
