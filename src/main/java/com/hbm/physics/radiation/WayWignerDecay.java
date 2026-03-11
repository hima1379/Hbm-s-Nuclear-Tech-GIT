package com.hbm.physics.radiation;

/**
 * Implements the Way-Wigner approximation for the decay of nuclear fission products.
 *
 * Source: The Effects of Nuclear Weapons, 3rd edition (1977)
 *         Samuel Glasstone & Philip J. Dolan
 *         Section 9.147, 9.150, 9.151, 9.23
 *
 * Core formula (§9.147, Eq. 9.147.1):
 *
 *   R(t) = R₁ · t^(-1.2)
 *
 *   where:
 *     R(t)  = dose rate at time t after detonation           [rads/hr]
 *     R₁    = dose rate at H+1 (1 hour after detonation)    [rads/hr]
 *     t     = time since detonation                          [hours]
 *     -1.2  = Way-Wigner exponent
 *
 * Valid range (§9.151): 0.5 to 5000 hours (within ±25% of measured values).
 * Outside this range, observed exponents deviate from -1.2.
 *
 * Cumulative dose integral (§9.150, Eq. 9.150.1):
 *
 *   D = ∫[ta→tb] R₁ · t^(-1.2) dt = 5·R₁·(tₐ^(-0.2) - t_b^(-0.2))
 *
 * Total dose from H+1 to infinity (§9.23):
 *
 *   D∞ = 9.3 · R₁
 */
public final class WayWignerDecay {

    private WayWignerDecay() {}

    // -----------------------------------------------------------------------
    // Physical constants
    // -----------------------------------------------------------------------

    /**
     * Way-Wigner decay exponent.
     * Source: §9.147, Eq. 9.147.1
     */
    public static final double WAY_WIGNER_EXPONENT = -1.2;

    /**
     * Exponent in the cumulative dose integral.
     * Derived from: ∫ t^(-1.2) dt = t^(-0.2) / (-0.2)
     * Source: §9.150, Eq. 9.150.1
     */
    public static final double INTEGRAL_EXPONENT = -0.2;

    /**
     * Coefficient in the cumulative dose formula: 1 / 0.2 = 5.
     * D = 5·R₁·(tₐ^(-0.2) - t_b^(-0.2))
     * Source: §9.150, Eq. 9.150.1
     */
    public static final double INTEGRAL_COEFFICIENT = 5.0;

    /**
     * Total dose coefficient from H+1 to infinity.
     * D∞ = 9.3 · R₁
     * Source: §9.23
     */
    public static final double INFINITE_DOSE_COEFFICIENT = 9.3;

    /**
     * Lower bound of the valid time range [hours].
     * Below 0.5 h the Way-Wigner approximation underestimates real decay rates.
     * Source: §9.151
     */
    public static final double MIN_VALID_HOURS = 0.5;

    /**
     * Upper bound of the valid time range [hours].
     * Above 5000 h the approximation begins to overestimate dose rates.
     * Source: §9.151
     */
    public static final double MAX_VALID_HOURS = 5000.0;

    // -----------------------------------------------------------------------
    // Core physics methods
    // -----------------------------------------------------------------------

    /**
     * Calculates the dose rate at time t hours after detonation.
     *
     * R(t) = R₁ · t^(-1.2)
     *
     * @param r1     Dose rate at H+1 (1 hour after detonation) [rads/hr]; must be >= 0
     * @param tHours Time since detonation [hours]; must be > 0
     * @return Dose rate at time t [rads/hr]
     * @throws IllegalArgumentException if tHours <= 0
     */
    public static double getDoseRate(double r1, double tHours) {
        if (tHours <= 0.0) {
            throw new IllegalArgumentException("Time must be positive, got: " + tHours);
        }
        if (r1 <= 0.0) {
            return 0.0;
        }
        return r1 * Math.pow(tHours, WAY_WIGNER_EXPONENT);
    }

    /**
     * Calculates the dimensionless decay factor at time t, normalised to H+1.
     *
     * factor(t) = t^(-1.2)
     *
     * At t = 1 h: factor = 1.0 (reference point H+1).
     * At t = 7 h: factor ≈ 0.1 (7:10 rule approximation).
     *
     * @param tHours Time since detonation [hours]; must be > 0
     * @return Dimensionless decay factor
     * @throws IllegalArgumentException if tHours <= 0
     */
    public static double getDecayFactor(double tHours) {
        if (tHours <= 0.0) {
            throw new IllegalArgumentException("Time must be positive, got: " + tHours);
        }
        return Math.pow(tHours, WAY_WIGNER_EXPONENT);
    }

    /**
     * Extrapolates the dose rate from a known measurement at t1 to a target time t2.
     *
     * R(t2) = R(t1) · (t2 / t1)^(-1.2)
     *
     * This form does not require knowledge of R₁; it uses any measured reference.
     *
     * @param rAtT1   Known dose rate at time t1 [rads/hr]; must be >= 0
     * @param t1Hours Reference time [hours]; must be > 0
     * @param t2Hours Target time [hours]; must be > 0
     * @return Dose rate at t2 [rads/hr]
     * @throws IllegalArgumentException if either time is <= 0
     */
    public static double extrapolate(double rAtT1, double t1Hours, double t2Hours) {
        if (t1Hours <= 0.0 || t2Hours <= 0.0) {
            throw new IllegalArgumentException(
                "Times must be positive: t1=" + t1Hours + ", t2=" + t2Hours);
        }
        if (rAtT1 <= 0.0) {
            return 0.0;
        }
        return rAtT1 * Math.pow(t2Hours / t1Hours, WAY_WIGNER_EXPONENT);
    }

    /**
     * Calculates the cumulative dose received between times tₐ and t_b hours after detonation.
     *
     * D = ∫[tₐ→t_b] R₁ · t^(-1.2) dt = 5·R₁·(tₐ^(-0.2) - t_b^(-0.2))
     *
     * Source: §9.150, Equation 9.150.1
     *
     * Valid when both tₐ and t_b are within the range [0.5, 5000] hours.
     *
     * @param r1      Dose rate at H+1 [rads/hr]; must be >= 0
     * @param taHours Start of exposure period [hours]; must be > 0
     * @param tbHours End of exposure period [hours]; must be > taHours
     * @return Cumulative absorbed dose [rads]
     * @throws IllegalArgumentException if the time range is invalid
     */
    public static double getCumulativeDose(double r1, double taHours, double tbHours) {
        if (taHours <= 0.0) {
            throw new IllegalArgumentException("Start time must be positive, got: " + taHours);
        }
        if (tbHours <= taHours) {
            throw new IllegalArgumentException(
                "End time must be greater than start time: ta=" + taHours + ", tb=" + tbHours);
        }
        if (r1 <= 0.0) {
            return 0.0;
        }
        return INTEGRAL_COEFFICIENT * r1
            * (Math.pow(taHours, INTEGRAL_EXPONENT) - Math.pow(tbHours, INTEGRAL_EXPONENT));
    }

    /**
     * Calculates the total dose from H+1 (t = 1 h) to infinity.
     *
     * D∞ = 9.3 · R₁
     *
     * Derived from the improper integral ∫[1→∞] t^(-1.2) dt = 1/0.2 · 1^(-0.2) = 5,
     * combined with the H+1 reference, giving the coefficient 9.3.
     * Source: §9.23
     *
     * @param r1 Dose rate at H+1 [rads/hr]; must be >= 0
     * @return Total absorbed dose from H+1 to infinity [rads]
     */
    public static double getInfiniteDose(double r1) {
        if (r1 <= 0.0) {
            return 0.0;
        }
        return INFINITE_DOSE_COEFFICIENT * r1;
    }

    // -----------------------------------------------------------------------
    // Validity check
    // -----------------------------------------------------------------------

    /**
     * Checks whether the given time falls within the valid range of the Way-Wigner approximation.
     *
     * Valid range: 0.5 to 5000 hours (±25% accuracy, §9.151).
     *
     * @param tHours Time since detonation [hours]
     * @return true if within [0.5, 5000] hours
     */
    public static boolean isInValidRange(double tHours) {
        return tHours >= MIN_VALID_HOURS && tHours <= MAX_VALID_HOURS;
    }
}
