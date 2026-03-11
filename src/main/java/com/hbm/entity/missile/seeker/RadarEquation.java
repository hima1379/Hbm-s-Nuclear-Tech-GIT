package com.hbm.entity.missile.seeker;

import com.hbm.radar.RadarCrossSection;

/**
 * Radar Range Equation for Active Seeker Performance Modeling.
 *
 * Integrated with com.hbm.radar.RadarCrossSection for unified RCS values.
 *
 * Implements fundamental radar physics to determine detection capability
 * of the SM-6 active radar seeker against various target types.
 *
 * The Radar Range Equation:
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Received Power:
 *   P_r = (P_t × G² × λ² × σ) / ((4π)³ × R⁴)
 *
 * Where:
 *   P_r = Received power (W)
 *   P_t = Transmit power (W)
 *   G   = Antenna gain (linear, not dB)
 *   λ   = Wavelength (m)
 *   σ   = Target radar cross-section (RCS, m²)
 *   R   = Range to target (m)
 *
 * Detection Criterion:
 *   Target detected when: SNR = P_r / N > SNR_min
 *
 * Noise Power:
 *   N = k × T_sys × B × F
 *
 *   k     = Boltzmann's constant (1.380649×10⁻²³ J/K)
 *   T_sys = System noise temperature (K)
 *   B     = Receiver bandwidth (Hz)
 *   F     = Noise figure (linear)
 *
 * Maximum Detection Range (solve for R when P_r = P_min):
 *
 *   R_max = [(P_t × G² × λ² × σ) / ((4π)³ × P_min)]^(1/4)
 *
 * SM-6 Active Seeker Specifications (Estimated):
 * ───────────────────────────────────────────────────────────────────────────
 *
 * Frequency: X-band, ~10 GHz
 *   λ = c / f ≈ 0.03 m
 *
 * Peak Transmit Power: 1500 W
 *   Average power (10% duty cycle): 150 W
 *
 * Antenna Gain: 42 dB
 *   G = 10^(42/10) ≈ 15,849
 *
 * Receiver Sensitivity: -110 dBm
 *   P_min = 10^(-110/10) × 10^(-3) = 1×10^(-14) W
 *
 * System Noise Temperature: 500 K (typical for X-band)
 *
 * Receiver Bandwidth: 10 MHz (moderate resolution)
 *
 * Detection Performance (Example Targets):
 * ───────────────────────────────────────────────────────────────────────────
 *
 * Fighter Aircraft (σ = 5 m²):
 *   R_max ≈ 22-25 km (head-on)
 *   R_max ≈ 18-20 km (beam aspect)
 *   R_max ≈ 12-15 km (tail aspect, reduced RCS)
 *
 * Cruise Missile (σ = 0.1 m²):
 *   R_max ≈ 8-10 km
 *
 * Stealth Aircraft (σ = 0.01 m²):
 *   R_max ≈ 4-5 km
 *
 * Large Bomber (σ = 40 m²):
 *   R_max ≈ 35-40 km
 *
 * Environmental Effects:
 * ───────────────────────────────────────────────────────────────────────────
 *
 * Atmospheric Attenuation (X-band, sea level):
 *   α ≈ 0.01 dB/km (clear weather)
 *   α ≈ 0.1-1 dB/km (rain, increasing with intensity)
 *
 * Two-way path loss:
 *   L = 10^(-2αR/10)
 *
 * Multipath Effects (low altitude):
 *   Interference patterns can cause ±6 dB fluctuations
 *
 * @author SM-6 Active Radar Seeker Model
 */
public class RadarEquation {

    // ========== PHYSICAL CONSTANTS ==========

    /** Speed of light (m/s) */
    private static final double C = 299792458.0;

    /** Boltzmann's constant (J/K) */
    private static final double K_BOLTZMANN = 1.380649e-23;

    // ========== SEEKER PARAMETERS ==========

    /** Transmit power (W, peak) */
    private final double transmitPower;

    /** Antenna gain (linear, not dB) */
    private final double antennaGain;

    /** Wavelength (m) */
    private final double wavelength;

    /** System noise temperature (K) */
    private final double noiseTemperature;

    /** Receiver bandwidth (Hz) */
    private final double bandwidth;

    /** Noise figure (linear) */
    private final double noiseFigure;

    /** Minimum detectable signal power (W) */
    private final double minDetectablePower;

    /** Required SNR for detection (linear, not dB) */
    private final double requiredSNR;

    // ========== CONSTRUCTOR ==========

    /**
     * Constructs radar equation calculator with SM-6 seeker parameters.
     */
    public RadarEquation() {
        // X-band radar at 10 GHz
        double frequency = 10.0e9; // Hz
        this.wavelength = C / frequency; // ~0.03 m

        this.transmitPower = 1500.0; // W (peak)
        this.antennaGain = Math.pow(10.0, 42.0 / 10.0); // 42 dB = ~15,849

        this.noiseTemperature = 500.0; // K
        this.bandwidth = 10.0e6; // 10 MHz
        this.noiseFigure = Math.pow(10.0, 3.0 / 10.0); // 3 dB = ~2.0

        // Sensitivity: -110 dBm
        this.minDetectablePower = Math.pow(10.0, -110.0 / 10.0) * 1e-3; // W

        // Required SNR: 13 dB for detection (Swerling Case 1)
        this.requiredSNR = Math.pow(10.0, 13.0 / 10.0); // ~20
    }

    /**
     * Custom constructor for testing different radar configurations.
     */
    public RadarEquation(
        double transmitPower,
        double antennaGainDB,
        double frequencyHz,
        double minDetectablePowerDBm,
        double requiredSNRdB
    ) {
        this.transmitPower = transmitPower;
        this.antennaGain = Math.pow(10.0, antennaGainDB / 10.0);
        this.wavelength = C / frequencyHz;

        this.noiseTemperature = 500.0;
        this.bandwidth = 10.0e6;
        this.noiseFigure = 2.0;

        this.minDetectablePower = Math.pow(10.0, minDetectablePowerDBm / 10.0) * 1e-3;
        this.requiredSNR = Math.pow(10.0, requiredSNRdB / 10.0);
    }

    // ========== RADAR EQUATION CALCULATIONS ==========

    /**
     * Calculate received power from target.
     *
     * @param range range to target (m)
     * @param targetRCS target radar cross-section (m²)
     * @return received power (W)
     */
    public double computeReceivedPower(double range, double targetRCS) {
        // Prevent division by zero
        if (range < 1.0) range = 1.0;

        // Radar range equation
        double numerator = transmitPower * antennaGain * antennaGain *
                          wavelength * wavelength * targetRCS;
        double denominator = Math.pow(4.0 * Math.PI, 3) * Math.pow(range, 4);

        return numerator / denominator;
    }

    /**
     * Calculate signal-to-noise ratio.
     *
     * @param receivedPower received signal power (W)
     * @return SNR (linear, not dB)
     */
    public double computeSNR(double receivedPower) {
        double noisePower = K_BOLTZMANN * noiseTemperature * bandwidth * noiseFigure;
        return receivedPower / noisePower;
    }

    /**
     * Determine if target is detectable at given range.
     *
     * @param range range to target (m)
     * @param targetRCS target RCS (m²)
     * @return true if target can be detected
     */
    public boolean canDetect(double range, double targetRCS) {
        double receivedPower = computeReceivedPower(range, targetRCS);
        double snr = computeSNR(receivedPower);
        return snr >= requiredSNR;
    }

    /**
     * Calculate maximum detection range for given target.
     *
     * @param targetRCS target radar cross-section (m²)
     * @return maximum detection range (m)
     */
    public double computeMaxRange(double targetRCS) {
        // Solve radar equation for range when P_r = P_min × requiredSNR
        double noisePower = K_BOLTZMANN * noiseTemperature * bandwidth * noiseFigure;
        double minReceivedPower = noisePower * requiredSNR;

        double numerator = transmitPower * antennaGain * antennaGain *
                          wavelength * wavelength * targetRCS;
        double denominator = Math.pow(4.0 * Math.PI, 3) * minReceivedPower;

        return Math.pow(numerator / denominator, 0.25);
    }

    /**
     * Apply atmospheric attenuation.
     *
     * @param range range through atmosphere (m)
     * @param attenuationDBperKM attenuation coefficient (dB/km)
     * @return attenuation factor (linear, 0-1)
     */
    public static double atmosphericAttenuation(double range, double attenuationDBperKM) {
        double rangeKM = range / 1000.0;
        double totalAttenuationDB = 2.0 * attenuationDBperKM * rangeKM; // Two-way path
        return Math.pow(10.0, -totalAttenuationDB / 10.0);
    }

    // ========== TYPICAL RCS VALUES ==========

    /**
     * Get typical RCS for different target types.
     *
     * Uses values from RadarCrossSection class for consistency.
     */
    public static double getRCS(TargetType type, AspectAngle aspect) {
        switch (type) {
            case FIGHTER_AIRCRAFT:
                // Use standard fighter RCS from RadarCrossSection
                switch (aspect) {
                    case HEAD_ON: return RadarCrossSection.RCS_FIGHTER;
                    case BEAM: return RadarCrossSection.RCS_FIGHTER * 2.0; // Larger from side
                    case TAIL: return RadarCrossSection.RCS_FIGHTER * 0.4; // Smaller from rear
                }
                break;

            case BOMBER:
                // Use large aircraft RCS from RadarCrossSection
                switch (aspect) {
                    case HEAD_ON: return RadarCrossSection.RCS_LARGE_AIRCRAFT * 0.4;
                    case BEAM: return RadarCrossSection.RCS_LARGE_AIRCRAFT;
                    case TAIL: return RadarCrossSection.RCS_LARGE_AIRCRAFT * 0.3;
                }
                break;

            case CRUISE_MISSILE:
                return RadarCrossSection.RCS_SMALL_UAV; // Similar to small UAV

            case STEALTH_AIRCRAFT:
                // Use stealth fighter RCS from RadarCrossSection
                switch (aspect) {
                    case HEAD_ON: return RadarCrossSection.RCS_STEALTH_FIGHTER;
                    case BEAM: return RadarCrossSection.RCS_STEALTH_FIGHTER * 1000.0; // Much higher from side
                    case TAIL: return RadarCrossSection.RCS_STEALTH_FIGHTER * 500.0; // Higher from rear
                }
                break;

            case BALLISTIC_MISSILE:
                return 0.5; // Slender body

            default:
                return RadarCrossSection.RCS_FIGHTER;
        }
        return RadarCrossSection.RCS_FIGHTER;
    }

    public enum TargetType {
        FIGHTER_AIRCRAFT,
        BOMBER,
        CRUISE_MISSILE,
        STEALTH_AIRCRAFT,
        BALLISTIC_MISSILE
    }

    public enum AspectAngle {
        HEAD_ON,  // 0-30 degrees
        BEAM,     // 60-120 degrees
        TAIL      // 150-180 degrees
    }

    // ========== UTILITY METHODS ==========

    public double getWavelength() { return wavelength; }
    public double getMaxRangeFighterHeadOn() { return computeMaxRange(5.0); }

    @Override
    public String toString() {
        return String.format(
            "RadarEquation[λ=%.4fm, Pt=%.0fW, G=%.1fdB, Sens=%.1fdBm, R_max(5m²)=%.1fkm]",
            wavelength,
            transmitPower,
            10.0 * Math.log10(antennaGain),
            10.0 * Math.log10(minDetectablePower / 1e-3),
            computeMaxRange(5.0) / 1000.0
        );
    }
}
