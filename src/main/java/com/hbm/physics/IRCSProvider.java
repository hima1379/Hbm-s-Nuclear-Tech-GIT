package com.hbm.physics;

/**
 * Interface for entities that have a defined Radar Cross Section (RCS).
 *
 * This is part of the physics-based realistic radar system (SPY-1 / SM-6 ARH seeker).
 * It is completely independent from the legacy IRadarDetectable / TileEntityMachineRadar system.
 *
 * Any entity that does NOT implement this interface is treated as having the default
 * RCS of 1.0 m² (generic small target / standard reference cross section).
 *
 * RCS Reference Values (IEEE / Skolnik "Radar Handbook"):
 *   Large bomber (B-29 class)  : ~100 m²
 *   Fighter aircraft           :  ~5 m²
 *   Helicopter                 :  ~3 m²
 *   Small UAV / cruise missile :  ~0.1 m²
 *   Stealth fighter (F-117)    :  ~0.0001 m²
 *   Ballistic missile warhead  :  ~0.5 m²
 *   Default (generic target)   :  1.0 m²
 *
 * Implements the 4th-root scaling law:
 *   R_max ∝ σ^(1/4)
 * Doubling RCS increases detection range by only ~19%.
 */
public interface IRCSProvider {

    /**
     * Returns the radar cross section (RCS) of this entity in square meters (m²).
     *
     * Default value is 1.0 m² representing a generic small target.
     * Override this method to specify a different RCS for a particular entity class.
     *
     * @return RCS in m² (must be > 0)
     */
    default double getRadarCrossSection() {
        return 1.0;
    }
}
