package com.hbm.config;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

/**
 * IMPORTANT NAMING CONVENTION:
 *
 * Despite the variable names ending in "Radius", nuclear weapon values represent KILOTONS!
 * Variable names kept for backward compatibility with existing code.
 *
 * For historical nuclear weapons (Gadget, Fat Man, Ivy Mike, Tsar Bomba, etc.):
 *   - The value IS the yield in kilotons (NOT radius)
 *   - Radius is calculated automatically using: R = 90 * Y^(1/3) meters
 *
 * For non-nuclear weapons (Prototype, F.L.E.I.J.A., etc.):
 *   - The value IS the radius in blocks
 *   - Yield is calculated for visual effects only
 */
public class BombConfig {

	// ===== NUCLEAR WEAPONS (VALUES ARE KILOTONS, NOT RADIUS!) =====

	/** Gadget/Trinity test: 21 KILOTONS (variable name is misleading but kept for compatibility)
	 *  Calculated fireball radius: ~170m, 5 PSI radius: ~260m */
	public static int gadgetRadius = 21;

	/** Little Boy (Hiroshima): 15 KILOTONS
	 *  Calculated fireball radius: ~140m, 5 PSI radius: ~220m */
	public static int boyRadius = 15;

	/** Fat Man (Nagasaki): 21 KILOTONS
	 *  Calculated fireball radius: ~170m, 5 PSI radius: ~260m */
	public static int manRadius = 21;

	/** Ivy Mike (first H-bomb): 10,400 KILOTONS = 10.4 megatons
	 *  Calculated fireball radius: ~1,150m, 5 PSI radius: ~1,980m */
	public static int mikeRadius = 10400;

	/** Tsar Bomba: 50,000 KILOTONS = 50 megatons
	 *  Calculated fireball radius: ~1,920m, 5 PSI radius: ~3,300m */
	public static int tsarRadius = 50000;

	/** Standard ICBM warhead (W87): 300 KILOTONS
	 *  Calculated fireball radius: ~350m, 5 PSI radius: ~600m */
	public static int missileRadius = 300;

	/** MIRV warhead (W88): 170 KILOTONS
	 *  Calculated fireball radius: ~290m, 5 PSI radius: ~500m */
	public static int mirvRadius = 170;

	// ===== NON-NUCLEAR WEAPONS (VALUES ARE RADIUS IN BLOCKS) =====

	public static int prototypeRadius = 150;  // Non-nuclear prototype (actual radius)
	public static int fleijaRadius = 50;      // Non-nuclear F.L.E.I.J.A. (actual radius)
	public static int soliniumRadius = 150;   // Non-nuclear Solinium (actual radius)
	public static int n2Radius = 200;         // Non-nuclear N2 mine (actual radius)
	public static int fatmanRadius = 35;      // Fatman Launcher (actual radius)
	public static int nukaRadius = 25;        // Nuka grenade (actual radius)
	public static int aSchrabRadius = 20;     // Anti-schrabidium (actual radius)

	public static int riggedStarRange = 50;
	public static int riggedStarTicks = 60 * 20;

	// ===== CUSTOM WEAPON LIMITS =====

	/** Max TNT radius (non-nuclear): 150 blocks (actual radius) */
	public static int maxCustomTNTRadius = 150;

	/** Max fission bomb: 500 KILOTONS (not radius) */
	public static int maxCustomNukeRadius = 500;

	/** Max thermonuclear: 50,000 KILOTONS = 50 megatons (not radius) */
	public static int maxCustomHydroRadius = 50000;

	/** Max dirty bomb fallout: 100 KILOTONS equivalent (not radius) */
	public static int maxCustomDirtyRadius = 100;

	/** Max balefire radius (non-nuclear): 750 blocks (actual radius) */
	public static int maxCustomBaleRadius = 750;

	/** Max schrabidium radius (non-nuclear): 500 blocks (actual radius) */
	public static int maxCustomSchrabRadius = 500;

	/** Max solinium radius (non-nuclear): 1000 blocks (actual radius) */
	public static int maxCustomSolRadius = 1000;

	public static int maxCustomEuphLvl = 20;

	// ===== PROCESSING SETTINGS =====

	public static int mk5 = 40;
	public static int blastSpeed = 1024;
	public static int falloutRange = 100;
	public static int fChunkSpeed = 5;
	public static int falloutMS = 40;
	public static int limitExplosionLifespan = 0;
	public static boolean disableNuclear = false;
	public static boolean enableNukeClouds = true;
	public static boolean enableNukeNBTSaving = true;

	public static void loadFromConfig(Configuration config) {
		final String CATEGORY_NUKES = "03_nukes";

		// === NUCLEAR WEAPONS (VALUES ARE KILOTONS, NOT RADIUS!) ===

		Property propGadget = config.get(CATEGORY_NUKES, "3.00_gadgetRadius", 21);
		propGadget.setComment("Gadget (Trinity) yield in KILOTONS (not radius). Historical: 21 kt. Fireball: ~170m, 5 PSI: ~260m");
		gadgetRadius = propGadget.getInt();

		Property propBoy = config.get(CATEGORY_NUKES, "3.01_boyRadius", 15);
		propBoy.setComment("Little Boy (Hiroshima) yield in KILOTONS (not radius). Historical: 15 kt. Fireball: ~140m, 5 PSI: ~220m");
		boyRadius = propBoy.getInt();

		Property propMan = config.get(CATEGORY_NUKES, "3.02_manRadius", 21);
		propMan.setComment("Fat Man (Nagasaki) yield in KILOTONS (not radius). Historical: 21 kt. Fireball: ~170m, 5 PSI: ~260m");
		manRadius = propMan.getInt();

		Property propMike = config.get(CATEGORY_NUKES, "3.03_mikeRadius", 10400);
		propMike.setComment("Ivy Mike (first H-bomb) yield in KILOTONS (not radius). Historical: 10,400 kt (10.4 Mt). Fireball: ~1,150m");
		mikeRadius = propMike.getInt();

		Property propTsar = config.get(CATEGORY_NUKES, "3.04_tsarRadius", 50000);
		propTsar.setComment("Tsar Bomba yield in KILOTONS (not radius). Historical: 50,000 kt (50 Mt). Fireball: ~1,920m");
		tsarRadius = propTsar.getInt();

		Property propMissile = config.get(CATEGORY_NUKES, "3.07_missileRadius", 300);
		propMissile.setComment("ICBM warhead (W87) yield in KILOTONS (not radius). Reference: 300 kt. Fireball: ~350m");
		missileRadius = propMissile.getInt();

		Property propMirv = config.get(CATEGORY_NUKES, "3.08_mirvRadius", 170);
		propMirv.setComment("MIRV warhead (W88) yield in KILOTONS (not radius). Reference: 170 kt. Fireball: ~290m");
		mirvRadius = propMirv.getInt();

		// === NON-NUCLEAR WEAPONS (VALUES ARE ACTUAL RADIUS IN BLOCKS) ===

		Property propPrototype = config.get(CATEGORY_NUKES, "3.05_prototypeRadius", 150);
		propPrototype.setComment("Prototype (non-nuclear) radius in BLOCKS (this IS actual radius)");
		prototypeRadius = propPrototype.getInt();

		Property propFleija = config.get(CATEGORY_NUKES, "3.06_fleijaRadius", 50);
		propFleija.setComment("F.L.E.I.J.A. (non-nuclear) radius in BLOCKS (this IS actual radius)");
		fleijaRadius = propFleija.getInt();

		Property propSolinium = config.get(CATEGORY_NUKES, "3.12_soliniumRadius", 150);
		propSolinium.setComment("Solinium (non-nuclear) radius in BLOCKS (this IS actual radius)");
		soliniumRadius = propSolinium.getInt();

		Property propN2 = config.get(CATEGORY_NUKES, "3.13_n2Radius", 200);
		propN2.setComment("N2 mine (non-nuclear) radius in BLOCKS (this IS actual radius)");
		n2Radius = propN2.getInt();

		Property propFatman = config.get(CATEGORY_NUKES, "3.09_fatmanRadius", 35);
		propFatman.setComment("Fatman Launcher (non-nuclear) radius in BLOCKS (this IS actual radius)");
		fatmanRadius = propFatman.getInt();

		Property propNuka = config.get(CATEGORY_NUKES, "3.10_nukaRadius", 25);
		propNuka.setComment("Nuka grenade (non-nuclear) radius in BLOCKS (this IS actual radius)");
		nukaRadius = propNuka.getInt();

		Property propASchrab = config.get(CATEGORY_NUKES, "3.11_aSchrabRadius", 20);
		propASchrab.setComment("Anti-schrabidium (non-nuclear) radius in BLOCKS (this IS actual radius)");
		aSchrabRadius = propASchrab.getInt();

		Property propRS1 = config.get(CATEGORY_NUKES, "3.14_riggedStarRadius", 50);
		propRS1.setComment("Rigged Star Blaster radius in BLOCKS");
		riggedStarRange = propRS1.getInt();

		Property propRS2 = config.get(CATEGORY_NUKES, "3.15_riggedStarFuse", 1200);
		propRS2.setComment("Rigged Star fuse time in ticks (default 60s = 1200 ticks)");
		riggedStarTicks = propRS2.getInt();

		// === CUSTOM LIMITS ===

		Property propTNT = config.get(CATEGORY_NUKES, "4.00_maxCustomTNTRadius", 150);
		propTNT.setComment("Maximum custom TNT radius in BLOCKS (non-nuclear, this IS actual radius)");
		maxCustomTNTRadius = propTNT.getInt();

		Property propNuke = config.get(CATEGORY_NUKES, "4.01_maxCustomNukeRadius", 500);
		propNuke.setComment("Maximum fission bomb yield in KILOTONS (not radius)");
		maxCustomNukeRadius = propNuke.getInt();

		Property propHydro = config.get(CATEGORY_NUKES, "4.02_maxCustomHydroRadius", 50000);
		propHydro.setComment("Maximum thermonuclear yield in KILOTONS (not radius). 50,000 kt = 50 Mt");
		maxCustomHydroRadius = propHydro.getInt();

		Property propDirty = config.get(CATEGORY_NUKES, "4.04_maxCustomDirtyRadius", 100);
		propDirty.setComment("Maximum dirty bomb fallout in KILOTONS equivalent (not radius)");
		maxCustomDirtyRadius = propDirty.getInt();

		Property propBale = config.get(CATEGORY_NUKES, "4.03_maxCustomBaleRadius", 750);
		propBale.setComment("Maximum balefire radius in BLOCKS (non-nuclear, this IS actual radius)");
		maxCustomBaleRadius = propBale.getInt();

		Property propSchrab = config.get(CATEGORY_NUKES, "4.05_maxCustomSchrabRadius", 500);
		propSchrab.setComment("Maximum Antischrabidium radius in BLOCKS (non-nuclear, this IS actual radius)");
		maxCustomSchrabRadius = propSchrab.getInt();

		Property propSol = config.get(CATEGORY_NUKES, "4.06_maxCustomSolRadius", 1000);
		propSol.setComment("Maximum Solinium radius in BLOCKS (non-nuclear, this IS actual radius)");
		maxCustomSolRadius = propSol.getInt();

		Property propEuph = config.get(CATEGORY_NUKES, "4.07_maxCustomEuphLvl", 20);
		propEuph.setComment("Maximum Euphemium Level (1 Lvl = 100 Rays)");
		maxCustomEuphLvl = propEuph.getInt();

		// === PROCESSING SETTINGS ===

		final String CATEGORY_NUKE = "06_explosions";

		Property propLimitExplosionLifespan = config.get(CATEGORY_NUKE, "6.00_limitExplosionLifespan", 0);
		propLimitExplosionLifespan.setComment("Explosion unload timeout in seconds (0 = disabled)");
		limitExplosionLifespan = propLimitExplosionLifespan.getInt();

		Property propBlastSpeed = config.get(CATEGORY_NUKE, "6.01_blastSpeed", 1024);
		propBlastSpeed.setComment("MK3 system detonation speed (Blocks/tick)");
		blastSpeed = propBlastSpeed.getInt();

		Property propMk5Time = config.get(CATEGORY_NUKE, "6.02_mk5BlastTime", 40);
		propMk5Time.setComment("Maximum milliseconds per tick for MK5 chunk processing");
		mk5 = propMk5Time.getInt();

		Property falloutRangeProp = config.get(CATEGORY_NUKE, "6.03_falloutRange", 100);
		falloutRangeProp.setComment("Fallout area radius (base radius * value in percent)");
		falloutRange = falloutRangeProp.getInt();

		Property falloutChunkSpeed = config.get(CATEGORY_NUKE, "6.04_falloutChunkSpeed", 5);
		falloutChunkSpeed.setComment("Process a chunk every nth tick (fallout rain)");
		fChunkSpeed = falloutChunkSpeed.getInt();

		Property falloutMSProp = config.get(CATEGORY_NUKE, "6.05_falloutTime", 30);
		falloutMSProp.setComment("Maximum milliseconds per tick for fallout chunk processing");
		falloutMS = falloutMSProp.getInt();

		Property disableNuclearP = config.get(CATEGORY_NUKE, "6.06_disableNuclear", false);
		disableNuclearP.setComment("Disable nuclear effects (fallout/radiation)");
		disableNuclear = disableNuclearP.getBoolean();

		enableNukeClouds = config.get(CATEGORY_NUKE, "6.07_enableMushroomClouds", true).getBoolean(true);

		Property enableNukeNBTSavingP = config.get(CATEGORY_NUKE, "6.08_enableNukeNBTSaving", true);
		enableNukeNBTSavingP.setComment("Save nuke destruction data for resume after crash/reload");
		enableNukeNBTSaving = enableNukeNBTSavingP.getBoolean();
	}
}