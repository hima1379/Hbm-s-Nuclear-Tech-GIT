package com.hbm.physics.air;

/**
 * Implements the SMART Gaussian plume atmospheric dispersion model for
 * radionuclide transport.
 *
 * Source: Madni et al., "A Simplified Model for Calculating Atmospheric
 *         Radionuclide Transport and Early Health Effects from Nuclear Reactor
 *         Accidents" (BNL report 116571-1983-IR).
 *
 * -------------------------------------------------------------------------
 * Core equation – ground-level air concentration (式7):
 *
 *   χ(x, y, 0) = Q / (π · ū · σy · σz)
 *                  · exp[ -(y² / 2σy²  +  H² / 2σz²) ]
 *
 *   χ  [Q/m²]    = ground-level air concentration (per unit source rate)
 *   Q  [game units/s] = source emission rate
 *   ū  [m/s]    = mean horizontal wind speed
 *   σy [m]      = lateral dispersion coefficient at downwind distance x
 *   σz [m]      = vertical dispersion coefficient at downwind distance x
 *   y  [m]      = lateral (crosswind) offset from centreline
 *   H  [m]      = effective source height above ground
 *   x  [m]      = downwind distance from source (must be > 0)
 *
 * -------------------------------------------------------------------------
 * Dry depletion factor (式14-15):
 *
 *   f_d(x) = exp[ -vd · x / (ū · z̄) ]
 *
 *   z̄ = √(π/2) · σz · exp[ H² / (2σz²) ]
 *
 *   vd = dry deposition velocity [m/s], default 0.01 m/s for particulates
 *
 * -------------------------------------------------------------------------
 * Wet scavenging (式17-18):
 *
 *   f_w(Δt) = exp( -Λ · Δt )
 *   Λ = C_s · R
 *
 *   Λ  [s⁻¹]  = scavenging coefficient
 *   Δt [s]    = time exposed to precipitation
 *   C_s       = washout ratio [s⁻¹/(mm/h)]  default 3.2e-5 s⁻¹/(mm/h)
 *   R  [mm/h] = rainfall rate
 *
 * -------------------------------------------------------------------------
 * Notes on coordinate system:
 *   x is measured along the wind direction (downwind is positive).
 *   y is measured perpendicular to the wind (positive = left of the wind
 *   direction when facing downwind).
 *   Both x and y are in world metres (1 Minecraft block = 1 m).
 */
public final class GaussianPlumeModel {

    private GaussianPlumeModel() {}

    // -----------------------------------------------------------------
    // Physical constants
    // -----------------------------------------------------------------

    /** Default dry deposition velocity for sub-micron fallout particles [m/s]. */
    public static final double DEFAULT_VD = 0.01;

    /**
     * Default washout coefficient C_s [s⁻¹/(mm·h⁻¹)].
     * Used in Λ = C_s · R.
     * Source: SMART code §式18; value from Engelmann (1968) for particulates.
     */
    public static final double DEFAULT_WASHOUT_COEFF = 3.2e-5;

    /** Minimum wind speed used in denominator to prevent division by zero [m/s]. */
    public static final double MIN_WIND_SPEED = 0.5;

    /** Maximum meaningful downwind distance [m]. */
    public static final double MAX_DOWNWIND_M = AtmosphericStabilityClass.MAX_DOWNWIND_M;

    // -----------------------------------------------------------------
    // Core dispersion function
    // -----------------------------------------------------------------

    /**
     * Computes the normalised ground-level air concentration factor at the
     * receptor point (x, y, 0) for a release at effective height H.
     *
     * Returns χ / Q  [m⁻²], so that multiplying by Q [game_rad·m²/s] gives
     * the ground-level game radiation rate [game_rad/s] at that point.
     *
     * Returns 0 if x ≤ 0 (receptor is upwind or directly at source) or if
     * the dispersion coefficients are degenerate.
     *
     * @param x     Downwind distance from source [m]; must be > 0
     * @param y     Crosswind offset [m]
     * @param H     Effective source height above ground [m]; >= 0
     * @param u     Mean wind speed [m/s]; clamped to MIN_WIND_SPEED
     * @param cls   Pasquill-Gifford stability class
     * @return Normalised ground concentration [m⁻²]
     */
    public static double groundConcentrationFactor(double x, double y, double H,
                                                    double u, AtmosphericStabilityClass cls) {
        if (x <= 0.0 || x > MAX_DOWNWIND_M) return 0.0;
        double uEff = Math.max(u, MIN_WIND_SPEED);

        double sigmaY = cls.getSigmaY(x);
        double sigmaZ = cls.getSigmaZ(x);
        if (sigmaY <= 0.0 || sigmaZ <= 0.0) return 0.0;

        double crosswind = -(y * y) / (2.0 * sigmaY * sigmaY);
        double vertical  = -(H * H) / (2.0 * sigmaZ * sigmaZ);

        return Math.exp(crosswind + vertical) / (Math.PI * uEff * sigmaY * sigmaZ);
    }

    /**
     * Computes the actual ground-level game radiation rate [game_rad/s]
     * contributed by a plume source at the receptor.
     *
     * gameRad_rate = Q · groundConcentrationFactor(x, y, H, u, cls)
     *
     * @param Q     Source emission rate [game_rad·m²/s]
     * @param x     Downwind distance [m]
     * @param y     Crosswind offset [m]
     * @param H     Effective source height [m]
     * @param u     Wind speed [m/s]
     * @param cls   Stability class
     * @return Ground-level game radiation rate [game_rad/s]
     */
    public static double groundRadiationRate(double Q, double x, double y, double H,
                                              double u, AtmosphericStabilityClass cls) {
        if (Q <= 0.0) return 0.0;
        return Q * groundConcentrationFactor(x, y, H, u, cls);
    }

    // -----------------------------------------------------------------
    // Dry depletion
    // -----------------------------------------------------------------

    /**
     * Computes the mean mixing height z̄ for dry depletion, defined as the
     * height at which vertical dispersion is centred relative to the ground.
     *
     * z̄ = √(π/2) · σz · exp[ H² / (2σz²) ]
     *
     * Source: SMART 式15.
     *
     * @param sigmaZ Vertical dispersion coefficient at x [m]
     * @param H      Effective source height [m]
     * @return z̄ [m]
     */
    public static double meanMixingHeight(double sigmaZ, double H) {
        if (sigmaZ <= 0.0) return 1.0;
        double exponent = (H * H) / (2.0 * sigmaZ * sigmaZ);
        return Math.sqrt(Math.PI / 2.0) * sigmaZ * Math.exp(exponent);
    }

    /**
     * Computes the dry depletion factor f_d at downwind distance x.
     *
     * f_d(x) = exp[ -vd · x / (ū · z̄(x)) ]
     *
     * Source: SMART 式14-15.
     *
     * @param x   Downwind distance [m]
     * @param u   Wind speed [m/s]
     * @param H   Effective source height [m]
     * @param vd  Dry deposition velocity [m/s]
     * @param cls Stability class
     * @return Dry depletion fraction in [0, 1]; 1 = no depletion
     */
    public static double dryDepletionFactor(double x, double u, double H,
                                             double vd, AtmosphericStabilityClass cls) {
        if (x <= 0.0) return 1.0;
        double uEff   = Math.max(u, MIN_WIND_SPEED);
        double sigmaZ = cls.getSigmaZ(x);
        if (sigmaZ <= 0.0) return 1.0;

        double zBar   = meanMixingHeight(sigmaZ, H);
        double arg    = -vd * x / (uEff * zBar);
        return Math.exp(arg);
    }

    // -----------------------------------------------------------------
    // Wet scavenging
    // -----------------------------------------------------------------

    /**
     * Computes the wet scavenging (washout) depletion factor over time Δt.
     *
     * f_w(Δt) = exp( -Λ · Δt )
     * Λ = C_s · R
     *
     * Source: SMART 式17-18.
     *
     * @param rainfallMmPerHour Rainfall rate [mm/h]; 0 = no rain
     * @param deltaTimeSeconds  Duration of rainfall exposure [s]
     * @param washoutCoeff      C_s [s⁻¹/(mm/h)]; use DEFAULT_WASHOUT_COEFF
     * @return Wet depletion fraction in [0, 1]; 1 = no rain depletion
     */
    public static double wetScavengingFactor(double rainfallMmPerHour,
                                              double deltaTimeSeconds,
                                              double washoutCoeff) {
        if (rainfallMmPerHour <= 0.0 || deltaTimeSeconds <= 0.0) return 1.0;
        double lambda = washoutCoeff * rainfallMmPerHour;
        return Math.exp(-lambda * deltaTimeSeconds);
    }

    /**
     * Convenience overload using the default washout coefficient.
     */
    public static double wetScavengingFactor(double rainfallMmPerHour,
                                              double deltaTimeSeconds) {
        return wetScavengingFactor(rainfallMmPerHour, deltaTimeSeconds,
                                   DEFAULT_WASHOUT_COEFF);
    }

    // -----------------------------------------------------------------
    // Coordinate rotation
    // -----------------------------------------------------------------

    /**
     * Rotates world-space displacement (dx, dz) into plume-aligned coordinates
     * (x_downwind, y_crosswind).
     *
     * WindField stores wind direction as a meteorological FROM direction
     * (the compass bearing from which the wind originates), following the
     * standard convention used by SMART and WMO:
     *
     *   windDirDeg = 0   → wind comes FROM north  (+Z); blows south (-Z)
     *   windDirDeg = 90  → wind comes FROM east   (+X); blows west  (-X)
     *   windDirDeg = 180 → wind comes FROM south  (-Z); blows north (+Z)
     *   windDirDeg = 225 → wind comes FROM SW; blows toward NE  ← typical default
     *   windDirDeg = 270 → wind comes FROM west   (-X); blows east  (+X)
     *
     * To obtain the DOWNWIND (TO) direction unit vector we add 180°:
     *   downwind_unit = (sin(θ+180°), cos(θ+180°)) = (-sin(θ), -cos(θ))
     *
     * Receptor offset from source: dx = rx - sx, dz = rz - sz.
     * Positive x_downwind → receptor is downwind (receives plume).
     * Positive x_downwind is required for non-zero Gaussian concentration.
     *
     * @param dx          World X offset (receptor.x - source.x) [m]
     * @param dz          World Z offset (receptor.z - source.z) [m]
     * @param windDirDeg  Wind FROM direction [degrees, clockwise from +Z axis]
     * @return double[2] = { x_downwind [m], y_crosswind [m] }
     */
    public static double[] worldToPlume(double dx, double dz, double windDirDeg) {
        // Convert FROM direction to TO direction by adding 180°.
        double rad   = Math.toRadians(windDirDeg);
        double sinTh = Math.sin(rad);
        double cosTh = Math.cos(rad);

        // Downwind unit vector = (-sinTh, -cosTh) [FROM → TO conversion]
        // Project receptor offset onto downwind and crosswind axes.
        double x = -(dx * sinTh + dz * cosTh);    // downwind  (positive = downwind)
        double y = -(dx * cosTh - dz * sinTh);    // crosswind (right-hand system)
        return new double[] { x, y };
    }

    // -----------------------------------------------------------------
    // Combined deposition rate with depletion factors
    // -----------------------------------------------------------------

    /**
     * Computes the ground-level game radiation deposition rate at receptor
     * (rx, rz) from a plume source at (sx, sz) with effective height H,
     * accounting for dry depletion and (optionally) wet scavenging.
     *
     * Depletion factors are applied to the source strength Q before the
     * Gaussian distribution is evaluated, following SMART §2.3.
     *
     * Steps:
     *   1. Rotate world coords to plume frame (x downwind, y crosswind)
     *   2. Compute dry depletion factor f_d at x
     *   3. Compute wet depletion factor f_w over elapsed time
     *   4. Effective Q_eff = Q · f_d · f_w
     *   5. Ground concentration = Q_eff · gaussian factor
     *
     * @param Q                   Source emission rate [game_rad·m²/s]
     * @param sx                  Source world X position [m]
     * @param sz                  Source world Z position [m]
     * @param H                   Effective source height [m]
     * @param rx                  Receptor world X position [m]
     * @param rz                  Receptor world Z position [m]
     * @param windSpeedMs         Wind speed [m/s]
     * @param windDirDeg          Wind direction [degrees, clockwise from North]
     * @param cls                 Pasquill-Gifford stability class
     * @param vd                  Dry deposition velocity [m/s]
     * @param rainfallMmPerHour   Current rainfall rate [mm/h]; 0 = dry
     * @param exposureTimeSeconds Time the plume has been active [s]
     * @return Ground-level deposition rate at receptor [game_rad/s]
     */
    public static double depositionRate(double Q, double sx, double sz, double H,
                                         double rx, double rz,
                                         double windSpeedMs, double windDirDeg,
                                         AtmosphericStabilityClass cls,
                                         double vd, double rainfallMmPerHour,
                                         double exposureTimeSeconds) {
        if (Q <= 0.0) return 0.0;

        double dx = rx - sx;
        double dz = rz - sz;
        double[] plume = worldToPlume(dx, dz, windDirDeg);
        double x = plume[0];  // downwind
        double y = plume[1];  // crosswind

        if (x <= 0.0 || x > MAX_DOWNWIND_M) return 0.0;

        double fd = dryDepletionFactor(x, windSpeedMs, H, vd, cls);
        double fw = wetScavengingFactor(rainfallMmPerHour, exposureTimeSeconds);
        double Qeff = Q * fd * fw;

        return groundRadiationRate(Qeff, x, y, H, windSpeedMs, cls);
    }

    /**
     * Returns the integrated ground deposition over time interval deltaSeconds
     * by treating the plume as quasi-steady-state during that interval.
     *
     * integral = depositionRate(...) * deltaSeconds
     *
     * @param deltaSeconds Length of integration interval [s]
     * @see #depositionRate
     */
    public static double integratedDeposition(double Q, double sx, double sz, double H,
                                               double rx, double rz,
                                               double windSpeedMs, double windDirDeg,
                                               AtmosphericStabilityClass cls,
                                               double vd, double rainfallMmPerHour,
                                               double exposureTimeSeconds,
                                               double deltaSeconds) {
        if (deltaSeconds <= 0.0) return 0.0;
        double rate = depositionRate(Q, sx, sz, H, rx, rz, windSpeedMs, windDirDeg,
                                     cls, vd, rainfallMmPerHour, exposureTimeSeconds);
        return rate * deltaSeconds;
    }
}
