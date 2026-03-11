package com.hbm.physics;

/**
 * Data class holding atmospheric properties at a specific altitude.
 *
 * Based on the International Standard Atmosphere (ISA) model.
 *
 * @author SM6 Missile Physics Engine
 */
public class AtmosphericProperties {

    /** Altitude above sea level (meters) */
    public final double altitude;

    /** Temperature (Kelvin) */
    public final double temperature;

    /** Pressure (Pascals) */
    public final double pressure;

    /** Air density (kg/m³) */
    public final double density;

    /** Speed of sound (m/s) */
    public final double speedOfSound;

    /** Dynamic viscosity (Pa·s) */
    public final double dynamicViscosity;

    /** Kinematic viscosity (m²/s) */
    public final double kinematicViscosity;

    /**
     * Constructs atmospheric properties for a given altitude.
     */
    public AtmosphericProperties(
        double altitude,
        double temperature,
        double pressure,
        double density,
        double speedOfSound,
        double dynamicViscosity,
        double kinematicViscosity
    ) {
        this.altitude = altitude;
        this.temperature = temperature;
        this.pressure = pressure;
        this.density = density;
        this.speedOfSound = speedOfSound;
        this.dynamicViscosity = dynamicViscosity;
        this.kinematicViscosity = kinematicViscosity;
    }

    /**
     * Compute Mach number from velocity.
     *
     * @param velocity velocity magnitude (m/s)
     * @return Mach number
     */
    public double getMachNumber(double velocity) {
        return velocity / speedOfSound;
    }

    /**
     * Compute dynamic pressure (q).
     *
     * q = 0.5 × ρ × V²
     *
     * CRITICAL for: Aerodynamic force calculations
     *
     * @param velocity velocity magnitude (m/s)
     * @return dynamic pressure (Pa)
     */
    public double getDynamicPressure(double velocity) {
        return 0.5 * density * velocity * velocity;
    }

    /**
     * Compute Reynolds number.
     *
     * Re = ρ × V × L / μ
     *
     * Used for: Aerodynamic regime determination (laminar vs turbulent)
     *
     * @param velocity characteristic velocity (m/s)
     * @param length characteristic length (m)
     * @return Reynolds number (dimensionless)
     */
    public double getReynoldsNumber(double velocity, double length) {
        return density * velocity * length / dynamicViscosity;
    }

    @Override
    public String toString() {
        return String.format(
            "Atmosphere[h=%.1fm, T=%.2fK, p=%.1fPa, ρ=%.6fkg/m³, a=%.2fm/s, M@V=340m/s:%.3f]",
            altitude, temperature, pressure, density, speedOfSound, getMachNumber(340.0)
        );
    }
}
