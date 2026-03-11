package com.hbm.physics.fusion;

/**
 * Greenwald Density Limit for Tokamak Plasmas
 *
 * The Greenwald limit is an empirical density limit discovered by Martin Greenwald.
 * Exceeding this limit typically leads to disruptions.
 *
 * Formula: n_Greenwald = I_p / (π × a²)
 *
 * Where:
 * - n_Greenwald: Maximum density (10^20 m^-3)
 * - I_p: Plasma current (MA)
 * - a: Minor radius (m)
 *
 * For ITER design:
 * - I_p = 15 MA
 * - a = 2.0 m
 * - n_Greenwald ≈ 1.19 × 10^20 m^-3
 *
 * References:
 * - Greenwald, M. (2002). "Density limits in toroidal plasmas". Plasma Physics and Controlled Fusion.
 * - https://www.nature.com/articles/s41586-024-07313-3
 * - https://hackaday.com/2026/01/14/pushing-chinas-east-tokamak-past-the-greenwald-density-limit/
 */
public class DensityLimit {

	/**
	 * Calculate the Greenwald density limit.
	 *
	 * n_G = I_p / (π × a²)  [in units of 10^20 m^-3]
	 *
	 * @param plasmaCurrent_MA Plasma current (MA)
	 * @param minorRadius Minor radius (m)
	 * @return Greenwald density limit (m^-3)
	 */
	public static double calculateGreenwaldLimit(double plasmaCurrent_MA, double minorRadius) {
		// Formula: n_Greenwald = Ip / (π × a²) in units of 10^20 m^-3
		double n_Greenwald_1e20 = plasmaCurrent_MA / (Math.PI * minorRadius * minorRadius);

		// Convert to m^-3
		return n_Greenwald_1e20 * 1.0e20;
	}

	/**
	 * Check if current density exceeds the Greenwald limit.
	 *
	 * @param currentDensity Current plasma density (m^-3)
	 * @param plasmaCurrent_MA Plasma current (MA)
	 * @param minorRadius Minor radius (m)
	 * @return true if Greenwald limit is exceeded
	 */
	public static boolean isGreenwaldLimitExceeded(double currentDensity, double plasmaCurrent_MA, double minorRadius) {
		double limit = calculateGreenwaldLimit(plasmaCurrent_MA, minorRadius);
		return currentDensity > limit;
	}

	/**
	 * Calculate the fraction of Greenwald limit currently reached.
	 *
	 * @param currentDensity Current plasma density (m^-3)
	 * @param plasmaCurrent_MA Plasma current (MA)
	 * @param minorRadius Minor radius (m)
	 * @return Fraction of limit (0.0 = empty, 1.0 = at limit, >1.0 = exceeded)
	 */
	public static double getGreenwaldFraction(double currentDensity, double plasmaCurrent_MA, double minorRadius) {
		double limit = calculateGreenwaldLimit(plasmaCurrent_MA, minorRadius);
		if (limit == 0) return 0.0;
		return currentDensity / limit;
	}

	/**
	 * Calculate the density margin before reaching Greenwald limit.
	 *
	 * @param currentDensity Current plasma density (m^-3)
	 * @param plasmaCurrent_MA Plasma current (MA)
	 * @param minorRadius Minor radius (m)
	 * @return Density margin (m^-3), negative if limit exceeded
	 */
	public static double getDensityMargin(double currentDensity, double plasmaCurrent_MA, double minorRadius) {
		double limit = calculateGreenwaldLimit(plasmaCurrent_MA, minorRadius);
		return limit - currentDensity;
	}

	/**
	 * Get ITER design Greenwald limit.
	 *
	 * @return ITER Greenwald limit (m^-3)
	 */
	public static double getITERGreenwaldLimit() {
		// ITER design parameters
		double Ip_ITER = 15.0;  // MA
		double a_ITER = 2.0;    // m
		return calculateGreenwaldLimit(Ip_ITER, a_ITER);
	}
}
