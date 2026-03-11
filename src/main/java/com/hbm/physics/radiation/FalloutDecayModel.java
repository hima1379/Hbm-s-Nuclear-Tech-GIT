package com.hbm.physics.radiation;

/**
 * Integrates the Way-Wigner decay physics with the HBM Nuclear Tech Mod radiation system.
 *
 * Source: The Effects of Nuclear Weapons, 3rd edition (1977)
 *         Samuel Glasstone & Philip J. Dolan
 *         §9.147 (Way-Wigner), §9.15 (7:10 Rule), §9.150 (cumulative dose)
 *
 * Time convention:
 *   All elapsed time is measured using real wall-clock time via System.currentTimeMillis().
 *   No artificial scaling or game-speed multipliers are applied.
 *   One real second of wall-clock time corresponds to one real second of nuclear decay.
 *
 *   t [hours] = (System.currentTimeMillis() - depositionTimeMs) / 3,600,000
 *
 * This class is responsible for:
 *   1. Converting wall-clock milliseconds to physical hours (and vice versa).
 *   2. Computing the current dose rate for a fallout pocket given its H+1 reference
 *      dose rate (r1Reference) and its deposition timestamp (depositionTimeMs).
 *   3. Providing a per-second multiplicative decay factor for incremental updates
 *      within the radiation pocket update loop.
 *   4. Inferring the effective elapsed time from a pocket's current radiation and
 *      r1Reference when an absolute timestamp is unavailable (e.g. migrating old saves).
 *   5. Delegating cumulative dose calculations to {@link WayWignerDecay}.
 *
 * Field conventions (used in RadPocket and RadiationSaveStructure):
 *   r1Reference     (float)  Dose rate at H+1 for the total fallout deposited in this pocket.
 *                            Accumulated via incrementRad(); set via setRadForCoord().
 *                            Units: same as the game's internal radiation unit [rads/hr].
 *   depositionTimeMs (long)  Wall-clock time in milliseconds (System.currentTimeMillis())
 *                            at which the first unit of fallout was deposited in this pocket.
 *                            0L indicates no fallout has been directly deposited.
 */
public final class FalloutDecayModel {

    private FalloutDecayModel() {}

    // -----------------------------------------------------------------------
    // Time conversion constants
    // -----------------------------------------------------------------------

    /** Milliseconds per hour (exact). */
    public static final double MS_PER_HOUR = 3_600_000.0;

    /** Seconds per hour (exact). Used for the per-second decay multiplier derivation. */
    public static final double SECONDS_PER_HOUR = 3600.0;

    /**
     * Minimum elapsed time applied to the Way-Wigner formula [hours].
     * Clamps t to the lower bound of the valid range so that the formula
     * is not evaluated at t < 0.5 h where its accuracy degrades (§9.151).
     */
    public static final double MIN_ELAPSED_HOURS = WayWignerDecay.MIN_VALID_HOURS;

    // -----------------------------------------------------------------------
    // Time conversion
    // -----------------------------------------------------------------------

    /**
     * Converts elapsed wall-clock milliseconds to hours.
     *
     * @param elapsedMs Elapsed time [ms]
     * @return Elapsed time [hours]
     */
    public static double msToHours(long elapsedMs) {
        return (double) elapsedMs / MS_PER_HOUR;
    }

    /**
     * Converts elapsed hours to wall-clock milliseconds.
     *
     * @param hours Elapsed time [hours]
     * @return Elapsed time [ms]
     */
    public static long hoursToMs(double hours) {
        return (long)(hours * MS_PER_HOUR);
    }

    // -----------------------------------------------------------------------
    // Elapsed time computation
    // -----------------------------------------------------------------------

    /**
     * Computes elapsed time in hours between a deposition timestamp and the current
     * wall-clock instant.
     *
     * t = (System.currentTimeMillis() - depositionTimeMs) / 3,600,000
     *
     * The result is clamped to {@link #MIN_ELAPSED_HOURS} (0.5 h) to remain within
     * the valid range of the Way-Wigner approximation (§9.151).
     *
     * @param depositionTimeMs Wall-clock time of fallout deposition [ms since epoch]
     * @return Elapsed time [hours], clamped to >= 0.5 h
     */
    public static double computeElapsedHours(long depositionTimeMs) {
        long elapsedMs = System.currentTimeMillis() - depositionTimeMs;
        double hours = msToHours(elapsedMs);
        return Math.max(hours, MIN_ELAPSED_HOURS);
    }

    // -----------------------------------------------------------------------
    // Dose rate computation
    // -----------------------------------------------------------------------

    /**
     * Computes the current dose rate for a fallout pocket using the Way-Wigner formula.
     *
     * R(t) = R₁ · t^(-1.2)
     *
     * where t is derived from the real elapsed wall-clock time since deposition.
     *
     * @param r1Reference    Dose rate at H+1 for this pocket [rads/hr]; must be > 0
     * @param depositionTimeMs Wall-clock deposition time [ms since epoch]
     * @return Current dose rate [rads/hr]; 0 if r1Reference <= 0
     */
    public static double getCurrentDoseRate(double r1Reference, long depositionTimeMs) {
        if (r1Reference <= 0.0) {
            return 0.0;
        }
        double tHours = computeElapsedHours(depositionTimeMs);
        return WayWignerDecay.getDoseRate(r1Reference, tHours);
    }

    // -----------------------------------------------------------------------
    // Per-second decay multiplier
    // -----------------------------------------------------------------------

    /**
     * Computes the multiplicative decay factor to apply to a pocket's radiation once
     * per server update (every 20 game ticks = 1 second of wall-clock time).
     *
     * Derivation:
     *   R(t) = R₁ · t^(-1.2)
     *
     *   After one real second (Δt = 1/3600 h):
     *     R(t + Δt) / R(t) = [(t + Δt) / t]^(-1.2)
     *
     * The multiplier is always in (0, 1) for positive elapsed time, ensuring monotonic decay.
     *
     * @param currentElapsedHours Elapsed time since deposition at the current update [hours];
     *                            must be > 0
     * @return Multiplicative decay factor for one second of elapsed time
     * @throws IllegalArgumentException if currentElapsedHours <= 0
     */
    public static double getPerSecondDecayMultiplier(double currentElapsedHours) {
        if (currentElapsedHours <= 0.0) {
            throw new IllegalArgumentException(
                "Elapsed time must be positive, got: " + currentElapsedHours);
        }
        // Δt = 1 second expressed in hours
        double dt = 1.0 / SECONDS_PER_HOUR;
        double nextT = currentElapsedHours + dt;
        // R(t+Δt)/R(t) = [(t+Δt)/t]^(-1.2)
        return Math.pow(nextT / currentElapsedHours, WayWignerDecay.WAY_WIGNER_EXPONENT);
    }

    // -----------------------------------------------------------------------
    // Elapsed time inference (for legacy migration)
    // -----------------------------------------------------------------------

    /**
     * Infers the effective elapsed time since deposition by inverting the Way-Wigner formula.
     *
     * Given R(t) = R₁ · t^(-1.2), solving for t:
     *
     *   t = (R₁ / R(t))^(1/1.2)  =  (R₁ / R)^(5/6)
     *
     * This allows assigning a physically consistent deposition timestamp to a pocket
     * whose depositionTimeMs was not recorded (e.g., data migrated from an older save).
     * The inferred depositionTimeMs can then be set as:
     *
     *   depositionTimeMs = System.currentTimeMillis() - hoursToMs(inferElapsedHours(...))
     *
     * Returns {@link #MIN_ELAPSED_HOURS} if the computed value falls below the valid range
     * or if either argument is non-positive.
     *
     * @param r1Reference      Dose rate at H+1 [rads/hr]; must be > 0
     * @param currentRadiation Current dose rate [rads/hr]; must be > 0
     * @return Inferred elapsed time [hours], clamped to >= 0.5 h
     */
    public static double inferElapsedHours(double r1Reference, double currentRadiation) {
        if (r1Reference <= 0.0 || currentRadiation <= 0.0) {
            return MIN_ELAPSED_HOURS;
        }
        // t = (R₁/R)^(1/1.2) = (R₁/R)^(5/6)
        // Equivalent to: (R₁/R)^(-1/WAY_WIGNER_EXPONENT) since exponent is -1.2
        double t = Math.pow(r1Reference / currentRadiation,
                            1.0 / (-WayWignerDecay.WAY_WIGNER_EXPONENT));
        return Math.max(t, MIN_ELAPSED_HOURS);
    }

    // -----------------------------------------------------------------------
    // Cumulative dose (delegation)
    // -----------------------------------------------------------------------

    /**
     * Calculates the cumulative dose received between two elapsed times.
     *
     * D = 5·R₁·(tₐ^(-0.2) - t_b^(-0.2))
     *
     * Delegates to {@link WayWignerDecay#getCumulativeDose(double, double, double)}.
     *
     * @param r1Reference Dose rate at H+1 [rads/hr]
     * @param taHours     Start of exposure period [hours]; must be > 0
     * @param tbHours     End of exposure period [hours]; must be > taHours
     * @return Cumulative absorbed dose [rads]
     */
    public static double getCumulativeDose(double r1Reference, double taHours, double tbHours) {
        return WayWignerDecay.getCumulativeDose(r1Reference, taHours, tbHours);
    }
}
