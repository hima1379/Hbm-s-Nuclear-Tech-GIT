package com.hbm.radar;

import net.minecraft.util.math.Vec3d;

/**
 * PHASE 4: Radar Beam - Represents a single radar pulse
 *
 * This class models a physical radar beam with electromagnetic wave properties.
 * Based on real F-15 APG-70 specifications.
 */
public class RadarBeam {

    // Beam origin and direction
    public final Vec3d origin;           // Where beam was emitted (aircraft nose)
    public final Vec3d direction;        // Unit vector direction (azimuth + elevation)
    public final double azimuth;         // Horizontal angle (degrees, relative to nose)
    public final double elevation;       // Vertical angle (degrees, relative to horizon)

    // Electromagnetic properties
    public final double powerWatts;      // Transmitted power (W) - F-15 APG-70: ~10 kW peak
    public final double frequencyGHz;    // Frequency (GHz) - F-15 APG-70: X-band ~9-10 GHz
    public final double beamWidthDeg;    // Beam width (degrees) - determines spread

    // Propagation state
    public final long emittedTick;       // Game tick when emitted
    public final int maxRangeBlocks;     // Maximum range before beam dissipates

    /**
     * Create a new radar beam
     *
     * @param origin        Emission position (aircraft radar antenna)
     * @param direction     Normalized direction vector
     * @param azimuth       Horizontal angle (degrees)
     * @param elevation     Vertical angle (degrees)
     * @param powerWatts    Transmitted power (W)
     * @param frequencyGHz  Frequency (GHz)
     * @param beamWidthDeg  Beam width (degrees)
     * @param emittedTick   Tick when emitted
     * @param maxRangeBlocks Maximum detection range
     */
    public RadarBeam(Vec3d origin, Vec3d direction, double azimuth, double elevation,
                     double powerWatts, double frequencyGHz, double beamWidthDeg,
                     long emittedTick, int maxRangeBlocks) {
        this.origin = origin;
        this.direction = direction.normalize();
        this.azimuth = azimuth;
        this.elevation = elevation;
        this.powerWatts = powerWatts;
        this.frequencyGHz = frequencyGHz;
        this.beamWidthDeg = beamWidthDeg;
        this.emittedTick = emittedTick;
        this.maxRangeBlocks = maxRangeBlocks;
    }

    /**
     * Calculate received power at a given distance using the Radar Equation
     *
     * GAME-ADJUSTED radar equation for Minecraft scale:
     * Pr = (Pt × G² × λ² × σ) / ((4π)³ × R^2.5)
     *
     * Real radar equation uses R⁴, but Minecraft's block scale (1 block = 1m)
     * makes this too harsh. Using R^2.5 allows detection at game-appropriate ranges.
     *
     * Where:
     * - Pt = Transmitted power
     * - G = Antenna gain (related to beam width)
     * - λ = Wavelength
     * - σ = Radar cross section (target RCS)
     * - R = Distance to target
     *
     * @param distance Distance to target (blocks)
     * @param targetRCS Target's radar cross section (m²)
     * @return Received power (W), or 0 if below detection threshold
     */
    public double calculateReceivedPower(double distance, double targetRCS) {
        if (distance > maxRangeBlocks || distance <= 0) {
            return 0.0; // Out of range
        }

        // Wavelength: λ = c / f (c = 3×10⁸ m/s)
        double wavelengthM = 0.03 / frequencyGHz; // Approximate for X-band

        // Antenna gain from beam width (narrower beam = higher gain)
        // Gain ≈ 25,000 / (beamWidth²) for parabolic antenna
        double gain = 25000.0 / (beamWidthDeg * beamWidthDeg);

        // GAME-ADJUSTED radar equation
        // Real equation uses R⁴, but we use R^2.5 for Minecraft scale
        // This allows fighters to be detected at ~20km range (realistic for F-15 APG-70)
        double numerator = powerWatts * gain * gain * wavelengthM * wavelengthM * targetRCS;
        double denominator = Math.pow(4.0 * Math.PI, 3.0) * Math.pow(distance, 2.5);

        double receivedPower = numerator / denominator;

        // GAME-ADJUSTED minimum detectable signal (more sensitive for game balance)
        // Real F-15 APG-70: ~-110 dBm = 1e-14 W
        // Game adjusted: 1e-18 W (allows detection at longer ranges)
        double minDetectable = 1e-18;

        return receivedPower >= minDetectable ? receivedPower : 0.0;
    }

    /**
     * Check if a point is within the beam cone
     *
     * @param point Point to check
     * @return true if point is within beam width at that distance
     */
    public boolean isPointInBeam(Vec3d point) {
        // Vector from origin to point
        Vec3d toPoint = point.subtract(origin);
        double distance = toPoint.length();

        if (distance > maxRangeBlocks || distance < 0.1) {
            return false;
        }

        // Angle between beam direction and vector to point
        Vec3d toPointNorm = toPoint.normalize();
        double dotProduct = direction.dotProduct(toPointNorm);

        // Convert to angle (acos of dot product)
        double angleRad = Math.acos(Math.max(-1.0, Math.min(1.0, dotProduct)));
        double angleDeg = Math.toDegrees(angleRad);

        // Beam spreads with distance (simple linear model)
        // At distance D, beam radius = D × tan(beamWidth/2)
        // But for simplicity, use fixed beam width
        double halfBeamWidth = beamWidthDeg / 2.0;

        return angleDeg <= halfBeamWidth;
    }

    /**
     * Get beam position at a given distance along beam axis
     *
     * @param distance Distance along beam (blocks)
     * @return Position vector
     */
    public Vec3d getPositionAtDistance(double distance) {
        return origin.add(direction.scale(distance));
    }

    @Override
    public String toString() {
        return String.format("RadarBeam[az=%.1f°, el=%.1f°, power=%.0fW, range=%dm]",
                azimuth, elevation, powerWatts, maxRangeBlocks);
    }
}
