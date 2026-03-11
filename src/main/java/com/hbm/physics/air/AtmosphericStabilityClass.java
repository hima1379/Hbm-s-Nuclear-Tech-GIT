package com.hbm.physics.air;

/**
 * Pasquill-Gifford atmospheric stability classes A through G with dispersion
 * coefficient formulas used by the SMART Gaussian plume model.
 *
 * Source: Madni et al., "A Simplified Model for Calculating Atmospheric
 *         Radionuclide Transport and Early Health Effects from Nuclear Reactor
 *         Accidents" (BNL report 116571), Table 1.
 *
 * Stability class determination follows the Pasquill (1961) methodology:
 *   - Wind speed at 10 m height
 *   - Incoming solar radiation (proxy: daytime vs. nighttime)
 *   - Precipitation / cloud cover (Minecraft weather state)
 *
 * Dispersion coefficients (x in metres, sigma in metres):
 *
 *   sigma_y(x) = Ky * x * (1 + 0.0001*x)^(-0.5)
 *   sigma_z(x) = Kz * x * f(x)
 *
 * where f(x) is a stability-class-specific correction term.
 * Valid range: 100 m to 10,000 m downwind distance.
 */
public enum AtmosphericStabilityClass {

    // -----------------------------------------------------------------
    // Class A – Very unstable (strong solar, light winds)
    // -----------------------------------------------------------------
    A(0.22, 0.20, SigmaZType.LINEAR, Double.MAX_VALUE),

    // -----------------------------------------------------------------
    // Class B – Unstable
    // -----------------------------------------------------------------
    B(0.16, 0.12, SigmaZType.LINEAR, Double.MAX_VALUE),

    // -----------------------------------------------------------------
    // Class C – Slightly unstable
    // -----------------------------------------------------------------
    C(0.11, 0.08, SigmaZType.SLIGHT, Double.MAX_VALUE),

    // -----------------------------------------------------------------
    // Class D – Neutral (overcast, rain, or strong wind)
    // -----------------------------------------------------------------
    D(0.08, 0.06, SigmaZType.NEUTRAL, Double.MAX_VALUE),

    // -----------------------------------------------------------------
    // Class E – Slightly stable
    // -----------------------------------------------------------------
    E(0.06, 0.03, SigmaZType.STABLE, Double.MAX_VALUE),

    // -----------------------------------------------------------------
    // Class F – Stable (calm night, clear sky)
    // -----------------------------------------------------------------
    F(0.04, 0.016, SigmaZType.STABLE, Double.MAX_VALUE),

    // -----------------------------------------------------------------
    // Class G – Very stable (calm night, very clear, light wind)
    // -----------------------------------------------------------------
    G(0.016, 0.005, SigmaZType.VERY_STABLE, Double.MAX_VALUE);

    // -----------------------------------------------------------------
    // sigma_z correction formula type
    // -----------------------------------------------------------------
    private enum SigmaZType {
        LINEAR,        // sigma_z = Kz * x                               (A, B)
        SLIGHT,        // sigma_z = Kz * x * (1 + 0.0002*x)^(-0.5)      (C)
        NEUTRAL,       // sigma_z = Kz * x * (1 + 0.00015*x)^(-0.5)     (D)
        STABLE,        // sigma_z = Kz * x * (1 + 0.0003*x)^(-1)        (E, F)
        VERY_STABLE    // sigma_z = Kz * x * (1 + 0.0003*x)^(-1)        (G) – same form, smaller Kz
    }

    /** Lateral dispersion coefficient factor [dimensionless]. */
    public final double Ky;
    /** Vertical dispersion coefficient factor [dimensionless]. */
    public final double Kz;
    /** Type of sigma_z correction formula. */
    private final SigmaZType szType;
    /** Upper cap on sigma_z to prevent unphysical values [m]. */
    private final double sigmaZCap;

    // Maximum meaningful downwind distance [m] – beyond this concentration is negligible.
    public static final double MAX_DOWNWIND_M = 50_000.0;

    AtmosphericStabilityClass(double ky, double kz, SigmaZType szType, double cap) {
        this.Ky = ky;
        this.Kz = kz;
        this.szType = szType;
        this.sigmaZCap = cap;
    }

    // -----------------------------------------------------------------
    // Dispersion coefficient calculations
    // -----------------------------------------------------------------

    /**
     * Lateral (y-direction) dispersion coefficient sigma_y at downwind
     * distance x [m].
     *
     * sigma_y = Ky * x * (1 + 0.0001*x)^(-0.5)
     *
     * Source: EPA (1984) workbook Table B-2; equivalent to SMART Table 1.
     *
     * @param x Downwind distance from source [m]; must be > 0
     * @return sigma_y [m]
     */
    public double getSigmaY(double x) {
        if (x <= 0.0) return 0.0;
        return Ky * x / Math.sqrt(1.0 + 0.0001 * x);
    }

    /**
     * Vertical (z-direction) dispersion coefficient sigma_z at downwind
     * distance x [m].
     *
     * The formula depends on stability class:
     *   A, B : Kz * x
     *   C    : Kz * x * (1 + 0.0002*x)^(-0.5)
     *   D    : Kz * x * (1 + 0.00015*x)^(-0.5)
     *   E, F : Kz * x * (1 + 0.0003*x)^(-1)
     *   G    : Kz * x * (1 + 0.0003*x)^(-1)   (smaller Kz)
     *
     * Source: EPA (1984); SMART Table 1.
     *
     * @param x Downwind distance from source [m]; must be > 0
     * @return sigma_z [m], capped at sigmaZCap
     */
    public double getSigmaZ(double x) {
        if (x <= 0.0) return 0.0;
        double sz;
        switch (szType) {
            case LINEAR:
                sz = Kz * x;
                break;
            case SLIGHT:
                sz = Kz * x / Math.sqrt(1.0 + 0.0002 * x);
                break;
            case NEUTRAL:
                sz = Kz * x / Math.sqrt(1.0 + 0.00015 * x);
                break;
            case STABLE:
            case VERY_STABLE:
                sz = Kz * x / (1.0 + 0.0003 * x);
                break;
            default:
                sz = Kz * x;
                break;
        }
        return Math.min(sz, sigmaZCap);
    }

    // -----------------------------------------------------------------
    // Stability class selection
    // -----------------------------------------------------------------

    /**
     * Selects the appropriate Pasquill-Gifford stability class based on
     * meteorological conditions, following the Pasquill (1961) tables.
     *
     * Simplified rules for Minecraft conditions:
     *
     *   Thunderstorm/Rain:
     *     Any wind speed → D (neutral – strong mixing by precipitation)
     *
     *   Daytime (solar heating present):
     *     wind < 2 m/s → A
     *     2 ≤ wind < 3 → B
     *     3 ≤ wind < 5 → C
     *     5 ≤ wind < 6 → C
     *     wind ≥ 6    → D
     *
     *   Night-time (no solar heating):
     *     wind < 2 m/s → G
     *     2 ≤ wind < 3 → F
     *     3 ≤ wind < 5 → E
     *     wind ≥ 5    → D
     *
     * @param windSpeedMs  Horizontal wind speed at 10 m [m/s]
     * @param isDaytime    True if the Minecraft world clock is in daytime (ticks 0–12000)
     * @param isRaining    True if world.isRaining() or world.isThundering()
     * @return Pasquill-Gifford stability class A–G
     */
    public static AtmosphericStabilityClass determine(double windSpeedMs,
                                                       boolean isDaytime,
                                                       boolean isRaining) {
        if (isRaining) {
            // Precipitation drives vertical mixing → neutral
            return D;
        }

        if (isDaytime) {
            // Solar heating destabilises the boundary layer
            if (windSpeedMs < 2.0)  return A;
            if (windSpeedMs < 3.0)  return B;
            if (windSpeedMs < 5.0)  return C;
            if (windSpeedMs < 6.0)  return C;
            return D;
        } else {
            // Night – radiative cooling stabilises the boundary layer
            if (windSpeedMs < 2.0)  return G;
            if (windSpeedMs < 3.0)  return F;
            if (windSpeedMs < 5.0)  return E;
            return D;
        }
    }

    // -----------------------------------------------------------------
    // NBT ordinal helpers
    // -----------------------------------------------------------------

    /** Returns the ordinal of this class (0=A … 6=G) for NBT storage. */
    public int toNBT() {
        return this.ordinal();
    }

    /**
     * Reconstructs a stability class from an NBT ordinal.
     * Returns D (neutral) as a safe default if the value is out of range.
     */
    public static AtmosphericStabilityClass fromNBT(int ordinal) {
        AtmosphericStabilityClass[] vals = values();
        if (ordinal < 0 || ordinal >= vals.length) return D;
        return vals[ordinal];
    }
}
