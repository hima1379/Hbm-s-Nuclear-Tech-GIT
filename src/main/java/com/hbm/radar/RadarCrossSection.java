package com.hbm.radar;

import com.hbm.physics.IRCSProvider;
import net.minecraft.entity.Entity;

/**
 * Radar Cross Section (RCS) calculations for realistic radar detection
 *
 * RCS determines how "visible" an aircraft is to radar.
 * Smaller RCS = harder to detect (stealth aircraft)
 * Larger RCS = easier to detect (large bombers, transports)
 */
public class RadarCrossSection {

    // RCS values in square meters (m²)

    /**
     * Stealth fighters and bombers (F-22, F-35, B-2)
     * Extremely low RCS - very hard to detect
     */
    public static final double RCS_STEALTH_FIGHTER = 0.0001;  // 0.0001 m²

    /**
     * Modern 4th generation fighters (F-15, F-16, F-18, Su-27, MiG-29, etc.)
     * Standard RCS - reference value for radar calculations
     */
    public static final double RCS_FIGHTER = 5.0;  // 5 m²

    /**
     * Large bombers and transport aircraft (B-52, C-130, An-225, etc.)
     * High RCS - very easy to detect
     */
    public static final double RCS_LARGE_AIRCRAFT = 100.0;  // 100 m²

    /**
     * Attack helicopters and light aircraft
     * Low-medium RCS
     */
    public static final double RCS_HELICOPTER = 3.0;  // 3 m²

    /**
     * Small UAVs and drones
     * Very low RCS due to small size
     */
    public static final double RCS_SMALL_UAV = 0.1;  // 0.1 m²

    /**
     * Calculate RCS for a given entity based on its name
     *
     * @param entity The entity
     * @return RCS value in square meters
     */
    public static double calculateRCS(Entity entity) {
        if (entity == null) {
            return 1.0; // Default: generic small target (1 m²)
        }

        // Physics-based RCS: entity explicitly declares its cross-section
        if (entity instanceof IRCSProvider) {
            return ((IRCSProvider) entity).getRadarCrossSection();
        }

        // Fallback: name-based heuristic for entities that don't implement IRCSProvider
        String entityName = entity.getName().toLowerCase();
        return calculateRCS(entityName);
    }

    /**
     * Calculate RCS for a given aircraft based on its name
     *
     * @param aircraftName The aircraft name
     * @return RCS value in square meters
     */
    public static double calculateRCS(String aircraftName) {
        if (aircraftName == null) {
            return 1.0; // Default: generic small target (1 m²)
        }

        // Convert to lowercase for case-insensitive matching
        String name = aircraftName.toLowerCase();

        // Stealth aircraft detection
        if (isStealth(name)) {
            return RCS_STEALTH_FIGHTER;
        }

        // Large aircraft detection
        if (isLargeAircraft(name)) {
            return RCS_LARGE_AIRCRAFT;
        }

        // Helicopter detection
        if (isHelicopter(name)) {
            return RCS_HELICOPTER;
        }

        // Small UAV detection
        if (isSmallUAV(name)) {
            return RCS_SMALL_UAV;
        }

        // Default: generic small target (1 m²)
        return 1.0;
    }

    /**
     * Check if aircraft is a stealth type
     */
    private static boolean isStealth(String name) {
        return name.contains("f-22") || name.contains("f22") || name.contains("raptor") ||
               name.contains("f-35") || name.contains("f35") || name.contains("lightning") ||
               name.contains("b-2") || name.contains("spirit") || name.contains("stealth") ||
               name.contains("pak-fa") || name.contains("pakfa") || name.contains("t-50") ||
               name.contains("j-20") || name.contains("j20");
    }

    /**
     * Check if aircraft is a large type (bomber/transport)
     */
    private static boolean isLargeAircraft(String name) {
        return name.contains("b-52") || name.contains("b52") || name.contains("stratofortress") ||
               name.contains("c-130") || name.contains("c130") || name.contains("hercules") ||
               name.contains("c-17") || name.contains("c17") || name.contains("globemaster") ||
               name.contains("c-5") || name.contains("c5") || name.contains("galaxy") ||
               name.contains("an-225") || name.contains("an225") || name.contains("mriya") ||
               name.contains("an-124") || name.contains("an124") ||
               name.contains("b-1") || name.contains("b1") || name.contains("lancer") ||
               name.contains("tu-95") || name.contains("tu95") || name.contains("bear") ||
               name.contains("tu-160") || name.contains("tu160") || name.contains("blackjack") ||
               name.contains("bomber") || name.contains("transport");
    }

    /**
     * Check if aircraft is a helicopter
     */
    private static boolean isHelicopter(String name) {
        return name.contains("ah-") || name.contains("uh-") || name.contains("ch-") ||
               name.contains("mi-") || name.contains("ka-") ||
               name.contains("apache") || name.contains("blackhawk") || name.contains("chinook") ||
               name.contains("cobra") || name.contains("huey") || name.contains("hind") ||
               name.contains("havoc") || name.contains("hokum") ||
               name.contains("helicopter") || name.contains("heli");
    }

    /**
     * Check if aircraft is a small UAV/drone
     */
    private static boolean isSmallUAV(String name) {
        return name.contains("mq-") || name.contains("rq-") ||
               name.contains("predator") || name.contains("reaper") ||
               name.contains("global hawk") || name.contains("uav") ||
               (name.contains("drone") && !name.contains("target"));
    }

    /**
     * Get human-readable description of RCS category
     */
    public static String getRCSCategory(double rcs) {
        if (rcs < 0.01) {
            return "STEALTH";
        } else if (rcs < 1.0) {
            return "VERY LOW";
        } else if (rcs < 10.0) {
            return "LOW-MEDIUM";
        } else if (rcs < 50.0) {
            return "MEDIUM-HIGH";
        } else {
            return "VERY HIGH";
        }
    }
}
