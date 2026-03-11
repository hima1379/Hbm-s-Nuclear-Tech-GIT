package com.hbm.physics;

/**
 * International Standard Atmosphere (ISA) model implementation.
 *
 * Provides accurate atmospheric properties (temperature, pressure, density, speed of sound)
 * as a function of altitude for realistic missile aerodynamic and propulsion simulations.
 *
 * Atmospheric Layers (ISA 1976):
 * - Troposphere:    0 to 11,000 m  (linear temperature decrease)
 * - Tropopause:     11,000 to 20,000 m  (constant temperature)
 * - Stratosphere 1: 20,000 to 32,000 m  (linear temperature increase)
 * - Stratosphere 2: 32,000 to 47,000 m  (linear temperature increase)
 * - Stratopause:    47,000 to 51,000 m  (constant temperature)
 * - Mesosphere 1:   51,000 to 71,000 m  (linear temperature decrease)
 * - Mesosphere 2:   71,000 to 84,852 m  (linear temperature decrease)
 *
 * Reference: U.S. Standard Atmosphere 1976, NOAA/NASA/USAF
 *
 * @author SM6 Missile Physics Engine
 */
public class Atmosphere {

    // ========== PHYSICAL CONSTANTS ==========

    /** Standard gravity acceleration (m/s²) */
    private static final double G = 9.80665;

    /** Specific gas constant for dry air (J/(kg·K)) */
    private static final double R = 287.05287;

    /** Ratio of specific heats for air (dimensionless) */
    private static final double GAMMA = 1.4;

    // ========== SEA LEVEL CONDITIONS ==========

    /** Sea level temperature (K) */
    private static final double T0 = 288.15;

    /** Sea level pressure (Pa) */
    private static final double P0 = 101325.0;

    /** Sea level density (kg/m³) */
    private static final double RHO0 = 1.225;

    // ========== ATMOSPHERIC LAYERS ==========

    /** Layer altitude boundaries (m) */
    private static final double[] LAYER_ALTITUDES = {
        0.0,      // Sea level
        11000.0,  // Tropopause
        20000.0,  // Stratosphere 1
        32000.0,  // Stratosphere 2
        47000.0,  // Stratopause
        51000.0,  // Mesosphere 1
        71000.0,  // Mesosphere 2
        84852.0   // Top of model
    };

    /** Temperature lapse rates (K/m) for each layer */
    private static final double[] LAPSE_RATES = {
        -0.0065,   // Troposphere
        0.0,       // Tropopause (isothermal)
        0.001,     // Stratosphere 1
        0.0028,    // Stratosphere 2
        0.0,       // Stratopause (isothermal)
        -0.0028,   // Mesosphere 1
        -0.002,    // Mesosphere 2
    };

    /** Base temperatures at layer boundaries (K) */
    private static final double[] BASE_TEMPERATURES = {
        288.15,  // Sea level
        216.65,  // Tropopause (11 km)
        216.65,  // Stratosphere 1 (20 km)
        228.65,  // Stratosphere 2 (32 km)
        270.65,  // Stratopause (47 km)
        270.65,  // Mesosphere 1 (51 km)
        214.65,  // Mesosphere 2 (71 km)
        186.87   // Top (84.852 km)
    };

    /** Base pressures at layer boundaries (Pa) - computed on init */
    private static final double[] BASE_PRESSURES = new double[8];

    // Static initializer to compute base pressures
    static {
        BASE_PRESSURES[0] = P0;
        for (int i = 1; i < 8; i++) {
            double h0 = LAYER_ALTITUDES[i - 1];
            double h1 = LAYER_ALTITUDES[i];
            double T0 = BASE_TEMPERATURES[i - 1];
            double p0 = BASE_PRESSURES[i - 1];
            double L = LAPSE_RATES[i - 1];

            if (Math.abs(L) < 1e-10) {
                // Isothermal layer
                BASE_PRESSURES[i] = p0 * Math.exp(-G * (h1 - h0) / (R * T0));
            } else {
                // Gradient layer
                double T1 = BASE_TEMPERATURES[i];
                BASE_PRESSURES[i] = p0 * Math.pow(T1 / T0, -G / (R * L));
            }
        }
    }

    // ========== PUBLIC API ==========

    /**
     * Get atmospheric properties at specified altitude.
     *
     * @param altitude altitude above sea level (meters)
     * @return atmospheric properties
     */
    public static AtmosphericProperties getProperties(double altitude) {
        // Clamp altitude to valid range
        altitude = Math.max(0.0, Math.min(altitude, 84852.0));

        // Determine atmospheric layer
        int layer = 0;
        for (int i = 0; i < LAYER_ALTITUDES.length - 1; i++) {
            if (altitude >= LAYER_ALTITUDES[i] && altitude < LAYER_ALTITUDES[i + 1]) {
                layer = i;
                break;
            }
        }

        // Get layer parameters
        double h0 = LAYER_ALTITUDES[layer];
        double T0 = BASE_TEMPERATURES[layer];
        double p0 = BASE_PRESSURES[layer];
        double L = LAPSE_RATES[layer];

        // Calculate temperature
        double temperature = T0 + L * (altitude - h0);

        // Calculate pressure
        double pressure;
        if (Math.abs(L) < 1e-10) {
            // Isothermal layer: p = p0 × exp(-g × Δh / (R × T))
            pressure = p0 * Math.exp(-G * (altitude - h0) / (R * T0));
        } else {
            // Gradient layer: p = p0 × (T / T0)^(-g / (R × L))
            pressure = p0 * Math.pow(temperature / T0, -G / (R * L));
        }

        // Calculate density: ρ = p / (R × T)
        double density = pressure / (R * temperature);

        // Calculate speed of sound: a = sqrt(γ × R × T)
        double speedOfSound = Math.sqrt(GAMMA * R * temperature);

        // Calculate dynamic viscosity (Sutherland's formula)
        double dynamicViscosity = calculateDynamicViscosity(temperature);

        // Calculate kinematic viscosity: ν = μ / ρ
        double kinematicViscosity = dynamicViscosity / density;

        return new AtmosphericProperties(
            altitude,
            temperature,
            pressure,
            density,
            speedOfSound,
            dynamicViscosity,
            kinematicViscosity
        );
    }

    /**
     * Calculate dynamic viscosity using Sutherland's formula.
     *
     * μ = μ0 × (T / T0)^(3/2) × (T0 + S) / (T + S)
     *
     * where:
     * - μ0 = 1.716e-5 Pa·s (reference viscosity at T0)
     * - T0 = 273.15 K (reference temperature)
     * - S = 110.4 K (Sutherland's constant for air)
     *
     * @param temperature temperature (K)
     * @return dynamic viscosity (Pa·s)
     */
    private static double calculateDynamicViscosity(double temperature) {
        final double MU0 = 1.716e-5;  // Reference viscosity (Pa·s)
        final double T_REF = 273.15;   // Reference temperature (K)
        final double S = 110.4;        // Sutherland's constant (K)

        return MU0 * Math.pow(temperature / T_REF, 1.5) * (T_REF + S) / (temperature + S);
    }

    // ========== CONVENIENCE METHODS ==========

    /**
     * Get air density at altitude.
     *
     * @param altitude altitude (m)
     * @return density (kg/m³)
     */
    public static double getDensity(double altitude) {
        return getProperties(altitude).density;
    }

    /**
     * Get temperature at altitude.
     *
     * @param altitude altitude (m)
     * @return temperature (K)
     */
    public static double getTemperature(double altitude) {
        return getProperties(altitude).temperature;
    }

    /**
     * Get pressure at altitude.
     *
     * @param altitude altitude (m)
     * @return pressure (Pa)
     */
    public static double getPressure(double altitude) {
        return getProperties(altitude).pressure;
    }

    /**
     * Get speed of sound at altitude.
     *
     * @param altitude altitude (m)
     * @return speed of sound (m/s)
     */
    public static double getSpeedOfSound(double altitude) {
        return getProperties(altitude).speedOfSound;
    }

    /**
     * Get Mach number for given velocity at altitude.
     *
     * @param velocity velocity magnitude (m/s)
     * @param altitude altitude (m)
     * @return Mach number
     */
    public static double getMachNumber(double velocity, double altitude) {
        return velocity / getSpeedOfSound(altitude);
    }

    /**
     * Get dynamic pressure for given velocity at altitude.
     *
     * q = 0.5 × ρ × V²
     *
     * @param velocity velocity magnitude (m/s)
     * @param altitude altitude (m)
     * @return dynamic pressure (Pa)
     */
    public static double getDynamicPressure(double velocity, double altitude) {
        double density = getDensity(altitude);
        return 0.5 * density * velocity * velocity;
    }

    /**
     * Calculate pressure ratio (for thrust altitude compensation).
     *
     * Used in rocket motor thrust calculations:
     * T(h) = T_sl + (T_vac - T_sl) × (1 - p_ratio)
     *
     * @param altitude altitude (m)
     * @return pressure ratio p(h) / p(0)
     */
    public static double getPressureRatio(double altitude) {
        return getPressure(altitude) / P0;
    }

    /**
     * Calculate density ratio (for aerodynamic scaling).
     *
     * @param altitude altitude (m)
     * @return density ratio ρ(h) / ρ(0)
     */
    public static double getDensityRatio(double altitude) {
        return getDensity(altitude) / RHO0;
    }

    /**
     * Estimate altitude from pressure (inverse ISA).
     *
     * Useful for altimeter simulation.
     * Uses troposphere approximation for simplicity.
     *
     * @param pressure pressure (Pa)
     * @return estimated altitude (m)
     */
    public static double altitudeFromPressure(double pressure) {
        // Troposphere approximation (valid up to ~11 km)
        if (pressure > BASE_PRESSURES[1]) {
            // h = (T0 / L) × [1 - (p / p0)^(R × L / g)]
            return (T0 / (-LAPSE_RATES[0])) * (1.0 - Math.pow(pressure / P0, R * (-LAPSE_RATES[0]) / G));
        } else {
            // Above troposphere - use iterative search
            // (simplified for now - returns approximate value)
            return 11000.0 + R * BASE_TEMPERATURES[1] * Math.log(BASE_PRESSURES[1] / pressure) / G;
        }
    }

    // ========== DIAGNOSTIC METHODS ==========

    /**
     * Get atmospheric layer name for altitude.
     *
     * @param altitude altitude (m)
     * @return layer name
     */
    public static String getLayerName(double altitude) {
        if (altitude < 11000) return "Troposphere";
        if (altitude < 20000) return "Tropopause";
        if (altitude < 32000) return "Stratosphere-1";
        if (altitude < 47000) return "Stratosphere-2";
        if (altitude < 51000) return "Stratopause";
        if (altitude < 71000) return "Mesosphere-1";
        if (altitude < 84852) return "Mesosphere-2";
        return "Above Model";
    }

    /**
     * Print atmospheric table for debugging.
     *
     * @param minAltitude minimum altitude (m)
     * @param maxAltitude maximum altitude (m)
     * @param step altitude step (m)
     */
    public static void printAtmosphereTable(double minAltitude, double maxAltitude, double step) {
        System.out.println("Altitude(m)  Temp(K)   Pressure(Pa)  Density(kg/m³)  SpeedOfSound(m/s)  Mach@340m/s");
        System.out.println("========================================================================================");

        for (double h = minAltitude; h <= maxAltitude; h += step) {
            AtmosphericProperties props = getProperties(h);
            System.out.printf("%10.0f  %7.2f  %12.2f  %14.6f  %17.2f  %11.3f%n",
                props.altitude,
                props.temperature,
                props.pressure,
                props.density,
                props.speedOfSound,
                props.getMachNumber(340.0)
            );
        }
    }
}
