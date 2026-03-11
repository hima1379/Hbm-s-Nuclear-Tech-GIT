package com.hbm.config;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

/**
 * Physics-based nuclear weapon configuration using real-world kiloton yields.
 *
 * ALL VALUES CORRECTED TO MATCH SCIENTIFIC LITERATURE (October 2025)
 *
 * References:
 * - Glasstone & Dolan (1977): "The Effects of Nuclear Weapons", 3rd Edition, U.S. DOD/DOE
 *   https://www.fourmilab.ch/etexts/www/effects/
 * - Sublette, Carey (2007): "Nuclear Weapons FAQ"
 *   https://nuclearweaponarchive.org/
 * - Federation of American Scientists (FAS): Nuclear weapon data
 *   https://fas.org/issues/nuclear-weapons/
 * - FM 8-9: NATO Handbook on Medical Aspects of NBC Defensive Operations
 */
public class RealBombConfig {

	// === PHYSICAL CONSTANTS ===

	/** Energy per kiloton in Joules: 1 kt = 4.184 Ã— 10^12 J (by definition)
	 *  Source: NIST Special Publication 811 (1995) */
	public static final double JOULES_PER_KT = 4.184e12;

	/** Stefan-Boltzmann constant in W/(mÂ²Â·Kâ´)
	 *  Source: CODATA 2018 */
	public static final double STEFAN_BOLTZMANN = 5.67e-8;

	// === ENERGY DISTRIBUTION (AIR BURST) ===
	// Source: Glasstone & Dolan Chapter 1, FM 8-9 Chapter 3
	// NOTE: These values apply to BOTH fission and fusion weapons

	/** Thermal radiation fraction of total energy (Glasstone & Dolan, p. 276)
	 *  Typically 35% for air bursts
	 *  NOTE: Same for both fission and fusion weapons */
	public static final double THERMAL_FRACTION = 0.35;

	/** Blast wave fraction (Glasstone & Dolan, p. 276)
	 *  Typically 50% for air bursts
	 *  NOTE: Same for both fission and fusion weapons */
	public static final double BLAST_FRACTION = 0.50;

	/** Prompt radiation fraction (Glasstone & Dolan, p. 276)
	 *  Typically 5% for weapons > 20 kt
	 *  NOTE: Same for both fission and fusion weapons */
	public static final double RADIATION_FRACTION = 0.05;

	/** Residual radiation (fallout) fraction
	 *  Typically 10%
	 *  NOTE: Fusion weapons produce less radioactive fallout, but similar initial radiation */
	public static final double FALLOUT_FRACTION = 0.10;

	/** Blast energy fraction for fission weapons
	 *  Reference: Glasstone & Dolan, Table 1.47 */
	public static final double FISSION_BLAST_FRACTION = 0.50;

	/** Blast energy fraction for fusion weapons
	 *  Fusion weapons have similar energy distribution to fission
	 *  Reference: Glasstone & Dolan, Table 1.47 */
	public static final double FUSION_BLAST_FRACTION = 0.50;

	// === ENERGY COUPLING EFFICIENCIES ===
	// Source: Glasstone & Dolan Ch.III, VI; National Academies (2005); FM 8-9
	//
	// CRITICAL: The THERMAL_FRACTION and BLAST_FRACTION above represent the
	// total energy released in those forms. However, most of that energy is
	// absorbed by the ATMOSPHERE, not the ground!
	//
	// Only a fraction of thermal and blast energy actually couples to the
	// ground to cause terrain destruction. The rest heats the air.
	//
	// THERMAL ENERGY BUDGET (35% of total):
	//   - 70-80% consumed by initial air heating (X-ray absorption)
	//   - 20-30% re-radiated as thermal pulse
	//   - Of the pulse, only 10-60% reaches ground (depends on burst type)
	//
	// BLAST ENERGY BUDGET (50% of total):
	//   - 70-80% dissipated as atmospheric heating (hysteresis)
	//   - 20-30% remains as shock kinetic energy
	//   - Of the shock, only 12-75% does mechanical work on ground
	//
	// These coupling efficiencies correct for atmospheric absorption and
	// ensure realistic ground damage levels.

	/** Thermal energy coupling for air burst (only ~15% reaches ground effectively)
	 *  Air bursts optimize for blast wave, thermal energy mostly absorbed by air
	 *  Reference: Glasstone & Dolan Chapter VII */
	public static final double THERMAL_COUPLING_AIR_BURST = 0.15;

	/** Thermal energy coupling for surface burst (~55% reaches ground)
	 *  Surface contact increases thermal coupling to ground/structures
	 *  Reference: Glasstone & Dolan Chapter III */
	public static final double THERMAL_COUPLING_SURFACE_BURST = 0.55;

	/** Thermal energy coupling for subsurface burst (~80% trapped underground)
	 *  Underground detonation confines thermal energy
	 *  Reference: National Academies (2005) Chapter 4 */
	public static final double THERMAL_COUPLING_SUBSURFACE_BURST = 0.80;

	/** Blast energy coupling for air burst (~18% does mechanical work on ground)
	 *  Most blast energy propagates through and heats the atmosphere
	 *  Reference: Glasstone & Dolan Chapter VI */
	public static final double BLAST_COUPLING_AIR_BURST = 0.18;

	/** Blast energy coupling for surface burst (~50% couples to ground)
	 *  Direct ground contact increases blast coupling efficiency
	 *  Reference: Glasstone & Dolan Chapter VI */
	public static final double BLAST_COUPLING_SURFACE_BURST = 0.50;

	/** Blast energy coupling for subsurface burst (~75% trapped underground)
	 *  Bedrock confinement dramatically enhances blast coupling (can reach 90%)
	 *  Additional 2-5× amplification from wave reflection
	 *  Reference: National Academies (2005) "Nuclear Earth-Penetrator Weapons" */
	public static final double BLAST_COUPLING_SUBSURFACE_BURST = 0.75;

	// === ATMOSPHERIC PARAMETERS ===

	/** Atmospheric absorption coefficient for thermal radiation (mâ»Â¹)
	 *  For clear air with good visibility (>10 miles)
	 *  Source: Glasstone & Dolan Chapter 7
	 *
	 *  CORRECTED VALUE: 0.0001 mâ»Â¹ gives ~95% transmission per 500m
	 *  which matches observed data for clear atmospheric conditions */
	public static final double ATMOSPHERIC_ABSORPTION_CLEAR = 0.0001;

	/** Atmospheric absorption coefficient for hazy conditions (mâ»Â¹) */
	public static final double ATMOSPHERIC_ABSORPTION_HAZY = 0.0005;

	/** Atmospheric absorption coefficient for foggy conditions (mâ»Â¹) */
	public static final double ATMOSPHERIC_ABSORPTION_FOGGY = 0.005;

	// === HISTORICAL NUCLEAR WEAPON YIELDS (KILOTONS) ===
	// Source: Federation of American Scientists & Nuclear Weapons Archive

	/** Trinity test / Gadget device yield: 21 kt (actual: 20-22 kt)
	 *  Reference: FAS, Trinity Test results */
	public static double gadgetKt = 21.0;

	/** Fat Man (Nagasaki) yield: 21 kt (actual measurement)
	 *  Reference: Carey Sublette, "Nuclear Weapons FAQ" Section 8.1.3 */
	public static double manKt = 21.0;

	public static double boyKt = 15.0;

	/** Ivy Mike (first H-bomb) yield: 10,400 kt = 10.4 Mt
	 *  Reference: FAS, Operation Ivy */
	public static double mikeKt = 10400.0;

	/** Tsar Bomba yield: 50,000 kt = 50 Mt (tested yield, designed for 100 Mt)
	 *  Reference: FAS, Soviet Nuclear Weapons */
	public static double tsarKt = 50000.0;

	/** Standard strategic nuclear missile yield: 300 kt
	 *  Reference: W87 warhead (Minuteman III), FAS data */
	public static double missileKt = 300.0;

	/** MIRV (Multiple Independently targetable Reentry Vehicle) yield: 170 kt
	 *  Reference: W88 warhead (Trident II), FAS data */
	public static double mirvKt = 170.0;

	// === CUSTOM NUKE LIMITS (KILOTONS) ===

	/** Maximum fission bomb yield: 500 kt
	 *  Reference: Theoretical maximum for pure fission devices */
	public static double maxCustomNukeKt = 500.0;

	/** Maximum thermonuclear (H-bomb) yield: 50,000 kt = 50 Mt
	 *  Reference: Tsar Bomba as practical upper limit */
	public static double maxCustomHydroKt = 50000.0;

	/** Maximum dirty bomb additional fallout: 100 kt equivalent
	 *  Reference: Enhanced radiation weapon concepts */
	public static double maxCustomDirtyKt = 100.0;

	// === CALCULATION METHODS ===

	/**
	 * Calculate thermal energy in Joules for given kiloton yield
	 * @param kilotons Weapon yield in kt
	 * @return Thermal energy in Joules
	 */
	public static double getThermalEnergyJoules(double kilotons) {
		return kilotons * JOULES_PER_KT * THERMAL_FRACTION;
	}

	/**
	 * Calculate blast energy in Joules for given kiloton yield
	 * @param kilotons Weapon yield in kt
	 * @return Blast energy in Joules
	 */
	public static double getBlastEnergyJoules(double kilotons) {
		return kilotons * JOULES_PER_KT * BLAST_FRACTION;
	}

	/**
	 * Calculate MAXIMUM FIREBALL RADIUS using Sedov-Taylor scaling
	 *
	 * CORRECTED FORMULA: R = 52 * Y^(1/3) meters
	 * where Y is yield in kilotons
	 *
	 * This is the physically correct cube-root scaling derived from
	 * dimensional analysis (Taylor-Sedov-von Neumann blast wave theory)
	 *
	 * Reference: Glasstone & Dolan, Chapter II, Equation 2.101
	 *
	 * Examples:
	 * - 1 kt:     52 meters
	 * - 20 kt:    141 meters (Hiroshima/Nagasaki class)
	 * - 1 Mt:     522 meters
	 * - 50 Mt:    1,919 meters (Tsar Bomba)
	 *
	 * @param kilotons Weapon yield in kt
	 * @return Maximum fireball radius in meters
	 */
	public static double getFireballRadius(double kilotons) {
		// CORRECTED: Changed from Y^0.4 to Y^(1/3)
		// The cube root scaling is fundamental to blast wave physics
		return 52.0 * Math.pow(kilotons, 1.0/3.0);
	}

	/**
	 * Calculate 5 PSI blast overpressure radius
	 *
	 * 5 PSI (34.5 kPa) is the threshold for severe structural damage
	 * to typical wood-frame buildings
	 *
	 * Formula: R â‰ˆ 90 * Y^(1/3) meters (for optimal air burst)
	 *
	 * Reference: Glasstone & Dolan, Chapter III
	 *
	 * @param kilotons Weapon yield in kt
	 * @return 5 PSI radius in meters
	 */
	public static double getBlastRadius5PSI(double kilotons) {
		return 90.0 * Math.pow(kilotons, 1.0/3.0);
	}

	/**
	 * Calculate 1 PSI blast overpressure radius
	 *
	 * 1 PSI (6.9 kPa) causes moderate damage - window breakage,
	 * minor structural damage
	 *
	 * Formula: R â‰ˆ 180 * Y^(1/3) meters (for optimal air burst)
	 *
	 * @param kilotons Weapon yield in kt
	 * @return 1 PSI radius in meters
	 */
	public static double getBlastRadius1PSI(double kilotons) {
		return 180.0 * Math.pow(kilotons, 1.0/3.0);
	}

	/**
	 * Calculate thermal radiation intensity at distance r (in meters)
	 * Using inverse square law with atmospheric attenuation
	 *
	 * CORRECTED: Now includes Beer-Lambert atmospheric absorption
	 * I(r) = E_thermal / (4Ï€rÂ²) * exp(-Î¼ * r)
	 *
	 * where Î¼ is atmospheric absorption coefficient
	 *
	 * Reference: Glasstone & Dolan, Chapter VII, Equations 7.07 and 7.54
	 *
	 * @param kilotons Weapon yield in kt
	 * @param distance Distance from ground zero in meters
	 * @return Thermal energy flux in J/mÂ²
	 */
	public static double getThermalFluxAtDistance(double kilotons, double distance) {
		return getThermalFluxAtDistance(kilotons, distance, ATMOSPHERIC_ABSORPTION_CLEAR);
	}

	/**
	 * Calculate thermal radiation intensity with custom atmospheric conditions
	 *
	 * @param kilotons Weapon yield in kt
	 * @param distance Distance from ground zero in meters
	 * @param atmosphericCoeff Atmospheric absorption coefficient (mâ»Â¹)
	 * @return Thermal energy flux in J/mÂ²
	 */
	public static double getThermalFluxAtDistance(double kilotons, double distance,
												  double atmosphericCoeff) {
		if (distance < 0.01) distance = 0.01; // Prevent division by zero

		double thermalEnergy = getThermalEnergyJoules(kilotons);

		// Inverse square law
		double inverseSquare = 1.0 / (4.0 * Math.PI * distance * distance);

		// Beer-Lambert atmospheric attenuation
		double atmosphericTransmission = Math.exp(-atmosphericCoeff * distance);

		return thermalEnergy * inverseSquare * atmosphericTransmission;
	}

	/**
	 * Calculate third-degree burn radius
	 *
	 * Third-degree burns require approximately 8-10 cal/cmÂ² (3.3-4.2 Ã— 10^5 J/mÂ²)
	 *
	 * Using scaling law: R_burn â‰ˆ 280 * Y^0.41 meters
	 *
	 * Reference: Glasstone & Dolan, Chapter VII
	 *
	 * @param kilotons Weapon yield in kt
	 * @return Third-degree burn radius in meters
	 */
	public static double getThermalBurnRadius(double kilotons) {
		return 280.0 * Math.pow(kilotons, 0.41);
	}

	/**
	 * Estimate surface temperature from thermal flux
	 *
	 * SIMPLIFIED calculation using Stefan-Boltzmann law: T = (I / Ïƒ)^(1/4)
	 * Where Ïƒ = 5.67 Ã— 10^-8 W/(mÂ²Â·Kâ´)
	 *
	 * NOTE: This is a simplified estimate assuming instantaneous absorption
	 * Real calculation requires time integration and material properties
	 *
	 * @param thermalFlux Thermal energy flux in J/mÂ²
	 * @return Estimated surface temperature in Kelvin
	 */
	public static double estimateTemperature(double thermalFlux) {
		// Assume 1 second exposure for simplification
		double power = thermalFlux; // W/mÂ² if flux is per second
		return Math.pow(power / STEFAN_BOLTZMANN, 0.25);
	}

	/**
	 * Get atmospheric absorption coefficient based on visibility
	 *
	 * @param visibilityKm Visibility in kilometers
	 * @return Atmospheric absorption coefficient (mâ»Â¹)
	 */
	public static double getAtmosphericCoefficient(double visibilityKm) {
		if (visibilityKm >= 10.0) {
			return ATMOSPHERIC_ABSORPTION_CLEAR;  // Clear: >10 km
		} else if (visibilityKm >= 5.0) {
			return ATMOSPHERIC_ABSORPTION_HAZY;   // Hazy: 5-10 km
		} else {
			return ATMOSPHERIC_ABSORPTION_FOGGY;  // Foggy: <5 km
		}
	}

	/**
	 * Calculate required thermal energy density to destroy material
	 *
	 * @param blockResistance Minecraft block explosion resistance
	 * @return Required thermal flux in J/mÂ²
	 */
	public static double getRequiredThermalFlux(float blockResistance) {
		// Base energy: 1 MJ/mÂ² for minimum destruction
		// Additional energy: 1.67 MJ per resistance unit
		return 1.0e6 + (blockResistance * 1.67e6);
	}

	// === CONFIGURATION LOADING ===

	public static void loadFromConfig(Configuration config) {
		final String CATEGORY = "realistic_nuclear_physics";

		Property propGadget = config.get(CATEGORY, "gadgetYieldKt", 21.0);
		propGadget.setComment("Gadget (Trinity test) yield in kilotons. Historical value: 21 kt");
		gadgetKt = propGadget.getDouble();

		Property propBoy = config.get(CATEGORY, "fatManYieldKt", 15.0);
		propBoy.setComment("Little Boy yield in kilotons. Historical value: 15 kt");
		boyKt = propBoy.getDouble();

		Property propMan = config.get(CATEGORY, "fatManYieldKt", 21.0);
		propMan.setComment("Fat Man (Nagasaki) yield in kilotons. Historical value: 21 kt");
		manKt = propMan.getDouble();

		Property propMike = config.get(CATEGORY, "ivyMikeYieldKt", 10400.0);
		propMike.setComment("Ivy Mike (first H-bomb) yield in kilotons. Historical value: 10,400 kt (10.4 Mt)");
		mikeKt = propMike.getDouble();

		Property propTsar = config.get(CATEGORY, "tsarBombaYieldKt", 50000.0);
		propTsar.setComment("Tsar Bomba yield in kilotons. Historical value: 50,000 kt (50 Mt)");
		tsarKt = propTsar.getDouble();

		Property propMissile = config.get(CATEGORY, "nuclearMissileYieldKt", 300.0);
		propMissile.setComment("Standard strategic nuclear missile yield in kilotons. Reference: W87 warhead, 300 kt");
		missileKt = propMissile.getDouble();

		Property propMirv = config.get(CATEGORY, "mirvYieldKt", 170.0);
		propMirv.setComment("MIRV warhead yield in kilotons. Reference: W88 warhead, 170 kt");
		mirvKt = propMirv.getDouble();

		Property propMaxNuke = config.get(CATEGORY, "maxCustomFissionKt", 500.0);
		propMaxNuke.setComment("Maximum custom fission bomb yield in kilotons");
		maxCustomNukeKt = propMaxNuke.getDouble();

		Property propMaxHydro = config.get(CATEGORY, "maxCustomThermonuclearKt", 50000.0);
		propMaxHydro.setComment("Maximum custom thermonuclear bomb yield in kilotons");
		maxCustomHydroKt = propMaxHydro.getDouble();

		Property propMaxDirty = config.get(CATEGORY, "maxCustomDirtyKt", 100.0);
		propMaxDirty.setComment("Maximum custom dirty bomb fallout in kilotons equivalent");
		maxCustomDirtyKt = propMaxDirty.getDouble();
	}
}