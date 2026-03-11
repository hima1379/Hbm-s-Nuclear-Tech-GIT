package com.hbm.physics;

/**
 * Physics-based radar wave propagation and detection range calculations.
 *
 * This class provides SPY-1D phased array radar performance modelling using
 * the standard radar range equation with ISA atmosphere-based attenuation.
 *
 * It is completely independent from the legacy IRadarDetectable /
 * TileEntityMachineRadar system.
 *
 * Radar Range Equation (Skolnik, "Introduction to Radar Systems"):
 *
 *   R_max^4 = (P_t × G^2 × λ^2 × σ) / ((4π)^3 × P_min × L_sys)
 *
 * Where:
 *   P_t    = Transmit power (W)
 *   G      = Antenna gain (linear)
 *   λ      = Wavelength (m)  = c / f
 *   σ      = Target RCS (m²)
 *   P_min  = Minimum detectable signal power (W)
 *   L_sys  = System losses (linear, ≥1)
 *
 * SPY-1D Reference Parameters (unclassified estimates):
 *   Band    : S-band
 *   Frequency: 3.1–3.5 GHz (use 3.3 GHz center)
 *   λ       : ~0.091 m
 *   P_t     : ~6 MW (peak)
 *   G       : ~42 dB  (~15,849 linear)
 *   Sensitivity: ~-130 dBm
 *   System losses: ~8 dB
 *
 * Atmospheric Attenuation (ITU-R P.676):
 *   S-band (3.3 GHz) clear weather: ~0.007 dB/km one-way
 *   Two-way: 2 × 0.007 = 0.014 dB/km
 *   Density-scaled via ISA atmosphere model.
 *
 * Radar Horizon (4/3 Earth radius model, IEEE Std 686):
 *   R_horizon = sqrt(2 × k_e × R_E) × (sqrt(h_radar) + sqrt(h_target))
 *   k_e = 4/3 (effective Earth radius factor)
 *   R_E = 6,371,000 m
 *
 * @see Atmosphere
 * @see IRCSProvider
 */
public class RadarWavePhysics {

    // ========== PHYSICAL CONSTANTS ==========

    /** Speed of light (m/s) */
    public static final double C = 299792458.0;

    /** Boltzmann's constant (J/K) */
    public static final double K_BOLTZMANN = 1.380649e-23;

    /** Earth radius (m) */
    private static final double EARTH_RADIUS = 6371000.0;

    /** Effective Earth radius factor for radar horizon (4/3 model) */
    private static final double K_EFFECTIVE = 4.0 / 3.0;

    // ========== SPY-1D PARAMETERS ==========

    /** SPY-1D center frequency (Hz) */
    public static final double SPY1_FREQUENCY = 3.3e9;

    /** SPY-1D wavelength (m) */
    public static final double SPY1_WAVELENGTH = C / SPY1_FREQUENCY; // ~0.0909 m

    /** SPY-1D peak transmit power (W) */
    public static final double SPY1_TRANSMIT_POWER = 6.0e6;

    /** SPY-1D antenna gain (dB) */
    public static final double SPY1_ANTENNA_GAIN_DB = 42.0;

    /** SPY-1D antenna gain (linear) */
    public static final double SPY1_ANTENNA_GAIN = Math.pow(10.0, SPY1_ANTENNA_GAIN_DB / 10.0);

    /** SPY-1D receiver sensitivity (dBm) */
    public static final double SPY1_SENSITIVITY_DBM = -130.0;

    /** SPY-1D minimum detectable power (W) */
    public static final double SPY1_MIN_POWER = Math.pow(10.0, SPY1_SENSITIVITY_DBM / 10.0) * 1e-3;

    /** SPY-1D system losses (dB) */
    public static final double SPY1_LOSSES_DB = 8.0;

    /** SPY-1D system losses (linear) */
    public static final double SPY1_LOSSES = Math.pow(10.0, SPY1_LOSSES_DB / 10.0);

    /**
     * S-band clear-weather atmospheric attenuation at sea level (dB/km, one-way).
     * Based on ITU-R P.676-12, 3.3 GHz, standard atmosphere.
     */
    private static final double S_BAND_ATTENUATION_SEA_LEVEL_DB_PER_KM = 0.007;

    // ========== CORE RADAR RANGE EQUATION ==========

    /**
     * Compute maximum detection range using the radar range equation.
     *
     * R_max = [(P_t × G^2 × λ^2 × σ) / ((4π)^3 × P_min × L_sys)]^(1/4)
     *
     * @param transmitPowerW  transmit power (W)
     * @param antennaGainLinear  antenna gain (linear, not dB)
     * @param wavelengthM  wavelength (m)
     * @param rcsSqM  target radar cross section (m²)
     * @param minDetectablePowerW  minimum detectable signal (W)
     * @param systemLossesLinear  total system losses (linear, ≥1)
     * @return maximum detection range (m), ignoring atmospheric attenuation
     */
    public static double computeMaxRange(
            double transmitPowerW,
            double antennaGainLinear,
            double wavelengthM,
            double rcsSqM,
            double minDetectablePowerW,
            double systemLossesLinear) {

        if (rcsSqM <= 0.0 || minDetectablePowerW <= 0.0) return 0.0;

        double numerator = transmitPowerW
                * antennaGainLinear * antennaGainLinear
                * wavelengthM * wavelengthM
                * rcsSqM;
        double denominator = Math.pow(4.0 * Math.PI, 3)
                * minDetectablePowerW
                * systemLossesLinear;

        return Math.pow(numerator / denominator, 0.25);
    }

    /**
     * Compute received power from a target at given range.
     *
     * P_r = (P_t × G^2 × λ^2 × σ) / ((4π)^3 × R^4 × L_sys)
     *
     * @param transmitPowerW  transmit power (W)
     * @param antennaGainLinear  antenna gain (linear)
     * @param wavelengthM  wavelength (m)
     * @param rcsSqM  target RCS (m²)
     * @param rangeM  range to target (m)
     * @param systemLossesLinear  system losses (linear)
     * @return received power (W)
     */
    public static double computeReceivedPower(
            double transmitPowerW,
            double antennaGainLinear,
            double wavelengthM,
            double rcsSqM,
            double rangeM,
            double systemLossesLinear) {

        if (rangeM < 1.0) rangeM = 1.0;

        double numerator = transmitPowerW
                * antennaGainLinear * antennaGainLinear
                * wavelengthM * wavelengthM
                * rcsSqM;
        double denominator = Math.pow(4.0 * Math.PI, 3)
                * Math.pow(rangeM, 4)
                * systemLossesLinear;

        return numerator / denominator;
    }

    // ========== SPY-1D CONVENIENCE METHODS ==========

    /**
     * Compute SPY-1D maximum detection range for a target with given RCS.
     *
     * Uses official SPY-1D unclassified parameter estimates.
     * Does not include atmospheric attenuation (use computeEffectiveMaxRange for that).
     *
     * @param rcsSqM  target RCS in m²
     * @return maximum detection range in meters
     */
    public static double computeSPY1MaxRange(double rcsSqM) {
        return computeMaxRange(
                SPY1_TRANSMIT_POWER,
                SPY1_ANTENNA_GAIN,
                SPY1_WAVELENGTH,
                rcsSqM,
                SPY1_MIN_POWER,
                SPY1_LOSSES);
    }

    /**
     * Compute SPY-1D effective maximum detection range including atmospheric attenuation.
     *
     * Uses ISA atmosphere density to scale attenuation with altitude.
     * Iterates because attenuation depends on range, range depends on attenuation.
     * Converges in 3-5 iterations for typical cases.
     *
     * @param rcsSqM  target RCS (m²)
     * @param radarAltM  radar altitude above sea level (m)
     * @param targetAltM  target altitude above sea level (m)
     * @return effective maximum detection range (m)
     */
    public static double computeEffectiveMaxRange(double rcsSqM, double radarAltM, double targetAltM) {
        double freeSpaceRange = computeSPY1MaxRange(rcsSqM);

        // Mean altitude along slant path
        double meanAlt = (radarAltM + targetAltM) / 2.0;
        double densityRatio = Atmosphere.getDensityRatio(meanAlt);

        // Attenuation at mean altitude (density-scaled from sea level)
        double attenuationDbPerKm = S_BAND_ATTENUATION_SEA_LEVEL_DB_PER_KM * densityRatio;

        // Iterative solution: range affects attenuation loss
        double rangeKm = freeSpaceRange / 1000.0;
        for (int i = 0; i < 5; i++) {
            // Two-way atmospheric path loss (dB)
            double twoWayLossDb = 2.0 * attenuationDbPerKm * rangeKm;
            double atmosphericLossLinear = Math.pow(10.0, twoWayLossDb / 10.0);

            // Total effective losses
            double effectiveLoss = SPY1_LOSSES * atmosphericLossLinear;

            // Recompute range with new effective losses
            double newRange = computeMaxRange(
                    SPY1_TRANSMIT_POWER,
                    SPY1_ANTENNA_GAIN,
                    SPY1_WAVELENGTH,
                    rcsSqM,
                    SPY1_MIN_POWER,
                    effectiveLoss);

            rangeKm = newRange / 1000.0;
        }

        return rangeKm * 1000.0;
    }

    // ========== ATMOSPHERIC ATTENUATION ==========

    /**
     * Compute two-way atmospheric attenuation factor for a radar signal.
     *
     * Uses ISA atmosphere density scaling to account for lower attenuation
     * at high altitude (thinner atmosphere).
     *
     * @param frequencyHz  radar frequency (Hz)
     * @param rangeM  slant range (m)
     * @param meanAltM  mean altitude along path (m)
     * @return attenuation factor (linear, 0–1; 1 = no attenuation)
     */
    public static double computeAtmosphericAttenuation(double frequencyHz, double rangeM, double meanAltM) {
        // Select base attenuation coefficient by band
        double baseAttDb;
        if (frequencyHz < 2.0e9) {
            // L-band (~1 GHz): very low attenuation
            baseAttDb = 0.002;
        } else if (frequencyHz < 6.0e9) {
            // S-band (2–6 GHz)
            baseAttDb = S_BAND_ATTENUATION_SEA_LEVEL_DB_PER_KM;
        } else if (frequencyHz < 12.0e9) {
            // X-band (6–12 GHz): slightly higher
            baseAttDb = 0.01;
        } else {
            // Ku/Ka-band: higher
            baseAttDb = 0.04;
        }

        // Scale by air density ratio (ISA model)
        double densityRatio = Atmosphere.getDensityRatio(meanAltM);
        double scaledAttDb = baseAttDb * densityRatio;

        // Two-way path loss in dB
        double rangeKm = rangeM / 1000.0;
        double twoWayLossDb = 2.0 * scaledAttDb * rangeKm;

        // Convert to linear attenuation factor
        return Math.pow(10.0, -twoWayLossDb / 10.0);
    }

    // ========== RADAR HORIZON ==========

    /**
     * Compute line-of-sight radar horizon range using the 4/3 Earth radius model.
     *
     * R_horizon = sqrt(2 × k_e × R_E) × (sqrt(h_radar) + sqrt(h_target))
     *
     * Beyond this range, the target is below the geometric horizon even with
     * 4/3 Earth radius atmospheric refraction correction.
     *
     * @param radarAltM  radar height above ground (m)
     * @param targetAltM  target height above ground (m)
     * @return horizon range (m)
     */
    public static double computeHorizonRange(double radarAltM, double targetAltM) {
        double factor = Math.sqrt(2.0 * K_EFFECTIVE * EARTH_RADIUS);
        return factor * (Math.sqrt(Math.max(0, radarAltM)) + Math.sqrt(Math.max(0, targetAltM)));
    }

    /**
     * Check whether a target at given altitude is beyond the radar horizon.
     *
     * @param radarAltM  radar altitude (m)
     * @param targetAltM  target altitude (m)
     * @param rangeM  actual slant range to target (m)
     * @return true if target is below the radar horizon (not detectable)
     */
    public static boolean isBeyondHorizon(double radarAltM, double targetAltM, double rangeM) {
        return rangeM > computeHorizonRange(radarAltM, targetAltM);
    }

    // ========== RCS SCALING UTILITIES ==========

    /**
     * Scale a reference detection range for a new target RCS.
     *
     * Uses the 4th-root scaling law:
     *   R_new = R_ref × (σ_new / σ_ref)^(1/4)
     *
     * @param referenceRangeM  known detection range at referenceRCS (m)
     * @param referenceRCS  RCS at which referenceRangeM was measured (m²)
     * @param targetRCS  actual target RCS (m²)
     * @return scaled detection range (m)
     */
    public static double scaleRangeForRCS(double referenceRangeM, double referenceRCS, double targetRCS) {
        if (referenceRCS <= 0.0 || targetRCS <= 0.0) return referenceRangeM;
        return referenceRangeM * Math.pow(targetRCS / referenceRCS, 0.25);
    }
}
