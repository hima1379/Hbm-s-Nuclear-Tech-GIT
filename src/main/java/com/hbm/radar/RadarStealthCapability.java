package com.hbm.radar;

import net.minecraft.entity.Entity;

/**
 * Stealth capability calculations for radar detection
 * Extends RadarCrossSection system with stealth factor
 *
 * STEALTH FACTOR:
 * - 0.0 = No stealth (fully detectable)
 * - 0.5 = Moderate stealth (50% RCS reduction)
 * - 0.9 = High stealth (90% RCS reduction, very hard to detect)
 * - 1.0 = Perfect stealth (theoretically undetectable)
 *
 * EFFECTIVE RCS CALCULATION:
 * Effective RCS = Base RCS × (1 - Stealth Factor)
 *
 * Example:
 * - Fighter (RCS=5 m²) with 0.0 stealth → 5 m² (normal detection)
 * - Fighter (RCS=5 m²) with 0.9 stealth → 0.5 m² (stealth fighter range)
 */
public class RadarStealthCapability {

    /**
     * Stealth factor values for different aircraft types
     */
    public static final double STEALTH_NONE = 0.0;           // No stealth features
    public static final double STEALTH_LOW = 0.2;            // Some RCS reduction (curved surfaces)
    public static final double STEALTH_MODERATE = 0.5;       // Moderate stealth (F-18E/F, Rafale)
    public static final double STEALTH_HIGH = 0.8;           // High stealth (Su-57, J-20)
    public static final double STEALTH_VERY_HIGH = 0.9;      // Very high stealth (F-22, F-35)
    public static final double STEALTH_EXTREME = 0.95;       // Extreme stealth (B-2, RQ-170)

    /**
     * Calculate stealth factor for an entity based on its name
     *
     * @param entity The entity
     * @return Stealth factor (0.0 to 1.0)
     */
    public static double calculateStealthFactor(Entity entity) {
        if (entity == null) {
            return STEALTH_NONE;
        }

        String entityName = entity.getName().toLowerCase();
        return calculateStealthFactor(entityName);
    }

    /**
     * Calculate stealth factor for an aircraft based on its name
     *
     * @param aircraftName The aircraft name
     * @return Stealth factor (0.0 to 1.0)
     */
    public static double calculateStealthFactor(String aircraftName) {
        if (aircraftName == null) {
            return STEALTH_NONE;
        }

        String name = aircraftName.toLowerCase();

        // EXTREME STEALTH - Dedicated stealth platforms
        if (isExtremeStealthAircraft(name)) {
            return STEALTH_EXTREME;
        }

        // VERY HIGH STEALTH - 5th generation fighters
        if (isVeryHighStealthAircraft(name)) {
            return STEALTH_VERY_HIGH;
        }

        // HIGH STEALTH - Advanced 4.5+ gen with reduced RCS
        if (isHighStealthAircraft(name)) {
            return STEALTH_HIGH;
        }

        // MODERATE STEALTH - Modern aircraft with some RCS reduction
        if (isModerateStealthAircraft(name)) {
            return STEALTH_MODERATE;
        }

        // LOW STEALTH - Older aircraft with basic RCS reduction
        if (isLowStealthAircraft(name)) {
            return STEALTH_LOW;
        }

        // NO STEALTH - Default
        return STEALTH_NONE;
    }

    /**
     * Calculate effective RCS considering stealth factor
     *
     * @param entity The entity
     * @return Effective RCS in square meters
     */
    public static double calculateEffectiveRCS(Entity entity) {
        double baseRCS = RadarCrossSection.calculateRCS(entity);
        double stealthFactor = calculateStealthFactor(entity);

        // Effective RCS = Base RCS × (1 - Stealth Factor)
        return baseRCS * (1.0 - stealthFactor);
    }

    /**
     * Calculate effective RCS for aircraft name
     *
     * @param aircraftName The aircraft name
     * @return Effective RCS in square meters
     */
    public static double calculateEffectiveRCS(String aircraftName) {
        double baseRCS = RadarCrossSection.calculateRCS(aircraftName);
        double stealthFactor = calculateStealthFactor(aircraftName);

        return baseRCS * (1.0 - stealthFactor);
    }

    /**
     * Check if aircraft has extreme stealth (B-2, RQ-170, etc.)
     */
    private static boolean isExtremeStealthAircraft(String name) {
        return name.contains("b-2") || name.contains("spirit") ||
               name.contains("rq-170") || name.contains("sentinel") ||
               name.contains("x-47") || name.contains("neuron") ||
               (name.contains("stealth") && name.contains("bomber"));
    }

    /**
     * Check if aircraft has very high stealth (5th gen fighters)
     */
    private static boolean isVeryHighStealthAircraft(String name) {
        return name.contains("f-22") || name.contains("f22") || name.contains("raptor") ||
               name.contains("f-35") || name.contains("f35") || name.contains("lightning") ||
               (name.contains("stealth") && name.contains("fighter"));
    }

    /**
     * Check if aircraft has high stealth (advanced 4.5+ gen)
     */
    private static boolean isHighStealthAircraft(String name) {
        return name.contains("su-57") || name.contains("su57") || name.contains("felon") ||
               name.contains("j-20") || name.contains("j20") || name.contains("mighty dragon") ||
               name.contains("pak-fa") || name.contains("pakfa") || name.contains("t-50") ||
               name.contains("fc-31") || name.contains("fc31");
    }

    /**
     * Check if aircraft has moderate stealth (modern 4.5 gen)
     */
    private static boolean isModerateStealthAircraft(String name) {
        return name.contains("f-18e") || name.contains("f-18f") || name.contains("super hornet") ||
               name.contains("rafale") || name.contains("typhoon") || name.contains("eurofighter") ||
               name.contains("gripen e") || name.contains("gripen ng") ||
               name.contains("su-35") || name.contains("su35") || name.contains("flanker-e");
    }

    /**
     * Check if aircraft has low stealth (older gen with basic RCS reduction)
     */
    private static boolean isLowStealthAircraft(String name) {
        return name.contains("f-15e") || name.contains("strike eagle") ||
               name.contains("f-16c") || name.contains("f-16d") || name.contains("block 50") ||
               name.contains("f-18c") || name.contains("f-18d") || name.contains("legacy hornet") ||
               name.contains("su-30") || name.contains("su30");
    }

    /**
     * Get human-readable description of stealth category
     */
    public static String getStealthCategory(double stealthFactor) {
        if (stealthFactor >= 0.95) {
            return "EXTREME STEALTH";
        } else if (stealthFactor >= 0.85) {
            return "VERY HIGH STEALTH";
        } else if (stealthFactor >= 0.7) {
            return "HIGH STEALTH";
        } else if (stealthFactor >= 0.4) {
            return "MODERATE STEALTH";
        } else if (stealthFactor >= 0.1) {
            return "LOW STEALTH";
        } else {
            return "NO STEALTH";
        }
    }

    /**
     * Get detection range multiplier based on stealth
     * Lower multiplier = harder to detect = shorter detection range
     *
     * @param stealthFactor Stealth factor (0.0 to 1.0)
     * @return Range multiplier (e.g., 0.5 = 50% detection range)
     */
    public static double getDetectionRangeMultiplier(double stealthFactor) {
        // Detection range scales with 4th root of RCS (radar range equation)
        // Range_stealth = Range_normal × (RCS_effective / RCS_base)^0.25
        // Since RCS_effective = RCS_base × (1 - stealth), we get:
        // Range_multiplier = (1 - stealth)^0.25

        return Math.pow(1.0 - stealthFactor, 0.25);
    }
}
