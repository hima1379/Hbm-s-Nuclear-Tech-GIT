package com.hbm.physics.nuke;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;

/**
 * MATERIAL PROPERTY DATABASE FOR NUCLEAR EFFECTS
 *
 * Provides material-specific thresholds for:
 * 1. Thermal radiation (J/m² fluence required for destruction)
 * 2. Blast overpressure (PSI required for structural failure)
 *
 * Based on physical properties and nuclear weapons effects literature:
 * - Glasstone & Dolan "Effects of Nuclear Weapons" (1977)
 * - FEMA-196 "Risks and Hazards: A State by State Guide" (1990)
 * - NATO "Nuclear Weapons Effects" (1996)
 *
 * THERMAL THRESHOLDS (J/m²):
 * - Wood ignition: 2.5×10⁵ J/m² (~60 cal/cm²)
 * - Skin flash burns (3rd degree): 1.7×10⁵ J/m²
 * - Concrete spalling: 1.2×10⁶ J/m²
 * - Stone ablation: 3.5×10⁶ J/m²
 * - Steel melting: 8.0×10⁶ J/m²
 *
 * BLAST THRESHOLDS (PSI):
 * - Wood frame collapse: 2.5 PSI
 * - Brick/stone structure failure: 5 PSI
 * - Reinforced concrete failure: 20 PSI
 * - Complete vaporization: 200 PSI
 */
public class MaterialPropertyDatabase {

	// === THERMAL FLUENCE THRESHOLDS (J/m²) ===
	private static final Map<Block, Double> THERMAL_THRESHOLDS_BLOCK = new HashMap<>();
	private static final Map<Material, Double> THERMAL_THRESHOLDS_MATERIAL = new HashMap<>();

	// === BLAST OVERPRESSURE THRESHOLDS (PSI) ===
	private static final Map<Block, Double> BLAST_THRESHOLDS_BLOCK = new HashMap<>();
	private static final Map<Material, Double> BLAST_THRESHOLDS_MATERIAL = new HashMap<>();

	// Default thresholds for unknown materials
	private static final double DEFAULT_THERMAL_THRESHOLD = 1.0e6; // 1 MJ/m²
	private static final double DEFAULT_BLAST_THRESHOLD = 5.0; // 5 PSI

	static {
		initializeThermalThresholds();
		initializeBlastThresholds();
	}

	/**
	 * Initialize thermal radiation thresholds
	 * Values represent J/m² (joules per square meter) of thermal fluence
	 */
	private static void initializeThermalThresholds() {
		// === ORGANIC MATERIALS - IGNITE EASILY ===
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.LEAVES, 8.0e4);      // 80 kJ/m²
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.LEAVES2, 8.0e4);
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.TALLGRASS, 5.0e4);   // 50 kJ/m²
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.DEADBUSH, 4.0e4);
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.WOOL, 1.0e5);        // 100 kJ/m²
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.CARPET, 1.0e5);

		// === WOOD - COMBUSTIBLE ===
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.LOG, 2.5e5);         // 250 kJ/m² (ignition point)
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.LOG2, 2.5e5);
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.PLANKS, 2.2e5);      // 220 kJ/m²
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.OAK_FENCE, 2.0e5);
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.OAK_STAIRS, 2.2e5);
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.WOODEN_SLAB, 2.0e5);

		// === ICE/SNOW - MELTS ===
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.ICE, 3.34e5);        // 334 kJ/m² (latent heat of fusion)
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.PACKED_ICE, 3.67e5);
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.SNOW, 1.67e5);
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.SNOW_LAYER, 1.0e5);

		// === WATER - EVAPORATES ===
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.WATER, 2.26e6);      // 2.26 MJ/m² (latent heat of vaporization)
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.FLOWING_WATER, 2.26e6);

		// === GLASS - THERMAL STRESS FRACTURE ===
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.GLASS, 4.0e5);       // 400 kJ/m²
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.GLASS_PANE, 3.0e5);
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.STAINED_GLASS, 4.0e5);
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.STAINED_GLASS_PANE, 3.0e5);

		// === EARTH MATERIALS ===
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.DIRT, 1.5e6);        // 1.5 MJ/m²
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.GRASS, 1.5e6);
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.SAND, 2.0e6);
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.GRAVEL, 1.8e6);
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.CLAY, 1.8e6);

		// === STONE - HIGH THERMAL MASS ===
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.STONE, 3.5e6);       // 3.5 MJ/m² (surface ablation)
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.COBBLESTONE, 3.2e6);
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.BRICK_BLOCK, 4.0e6);
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.STONEBRICK, 4.0e6);
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.SANDSTONE, 2.8e6);
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.RED_SANDSTONE, 2.8e6);

		// === CONCRETE (Minecraft doesn't have concrete in 1.12, but for future) ===
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.HARDENED_CLAY, 1.2e6); // 1.2 MJ/m² (spalling)
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.STAINED_HARDENED_CLAY, 1.2e6);

		// === METALS - EXCELLENT HEAT CONDUCTORS ===
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.IRON_BLOCK, 8.0e6);  // 8 MJ/m² (melting point energy)
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.GOLD_BLOCK, 4.0e6);  // Gold melts at lower temp
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.IRON_ORE, 6.0e6);
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.GOLD_ORE, 3.5e6);
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.IRON_DOOR, 7.0e6);
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.IRON_BARS, 6.0e6);

		// === OBSIDIAN - VOLCANIC GLASS ===
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.OBSIDIAN, 1.0e7);    // 10 MJ/m² (very heat resistant)

		// === BEDROCK - INDESTRUCTIBLE ===
		THERMAL_THRESHOLDS_BLOCK.put(Blocks.BEDROCK, Double.MAX_VALUE);

		// === MATERIAL DEFAULTS (fallback) ===
		THERMAL_THRESHOLDS_MATERIAL.put(Material.WATER, 2.26e6);
		THERMAL_THRESHOLDS_MATERIAL.put(Material.LAVA, Double.MAX_VALUE); // Lava doesn't burn
		THERMAL_THRESHOLDS_MATERIAL.put(Material.WOOD, 2.5e5);
		THERMAL_THRESHOLDS_MATERIAL.put(Material.LEAVES, 8.0e4);
		THERMAL_THRESHOLDS_MATERIAL.put(Material.PLANTS, 8.0e4);
		THERMAL_THRESHOLDS_MATERIAL.put(Material.VINE, 8.0e4);
		THERMAL_THRESHOLDS_MATERIAL.put(Material.ICE, 3.34e5);
		THERMAL_THRESHOLDS_MATERIAL.put(Material.PACKED_ICE, 3.67e5);
		THERMAL_THRESHOLDS_MATERIAL.put(Material.SNOW, 1.67e5);
		THERMAL_THRESHOLDS_MATERIAL.put(Material.CRAFTED_SNOW, 1.67e5);
		THERMAL_THRESHOLDS_MATERIAL.put(Material.GLASS, 4.0e5);
		THERMAL_THRESHOLDS_MATERIAL.put(Material.ROCK, 3.5e6);
		THERMAL_THRESHOLDS_MATERIAL.put(Material.IRON, 8.0e6);
		THERMAL_THRESHOLDS_MATERIAL.put(Material.GROUND, 1.5e6);
		THERMAL_THRESHOLDS_MATERIAL.put(Material.SAND, 2.0e6);
		THERMAL_THRESHOLDS_MATERIAL.put(Material.CLAY, 1.8e6);
		THERMAL_THRESHOLDS_MATERIAL.put(Material.CLOTH, 1.0e5);
	}

	/**
	 * Initialize blast overpressure thresholds
	 * Values represent PSI (pounds per square inch) required for destruction
	 */
	private static void initializeBlastThresholds() {
		// === WEAK MATERIALS - LOW PRESSURE THRESHOLD ===
		BLAST_THRESHOLDS_BLOCK.put(Blocks.LEAVES, 0.3);          // 0.3 PSI - leaves blown away
		BLAST_THRESHOLDS_BLOCK.put(Blocks.LEAVES2, 0.3);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.TALLGRASS, 0.2);       // 0.2 PSI
		BLAST_THRESHOLDS_BLOCK.put(Blocks.DEADBUSH, 0.2);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.WOOL, 1.0);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.CARPET, 0.5);

		// === GLASS - SHATTERS EASILY ===
		BLAST_THRESHOLDS_BLOCK.put(Blocks.GLASS, 0.5);           // 0.5 PSI - window breakage
		BLAST_THRESHOLDS_BLOCK.put(Blocks.GLASS_PANE, 0.4);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.STAINED_GLASS, 0.5);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.STAINED_GLASS_PANE, 0.4);

		// === WOOD STRUCTURES ===
		BLAST_THRESHOLDS_BLOCK.put(Blocks.PLANKS, 2.5);          // 2.5 PSI - wood frame collapse
		BLAST_THRESHOLDS_BLOCK.put(Blocks.LOG, 3.0);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.LOG2, 3.0);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.OAK_FENCE, 2.0);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.OAK_STAIRS, 2.5);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.WOODEN_SLAB, 2.0);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.OAK_DOOR, 2.0);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.SPRUCE_DOOR, 2.0);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.BIRCH_DOOR, 2.0);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.JUNGLE_DOOR, 2.0);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.ACACIA_DOOR, 2.0);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.DARK_OAK_DOOR, 2.0);

		// === EARTH MATERIALS ===
		BLAST_THRESHOLDS_BLOCK.put(Blocks.DIRT, 4.0);            // 4 PSI
		BLAST_THRESHOLDS_BLOCK.put(Blocks.GRASS, 4.0);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.SAND, 3.0);            // Sand is weaker
		BLAST_THRESHOLDS_BLOCK.put(Blocks.GRAVEL, 3.5);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.CLAY, 4.0);

		// === SNOW/ICE ===
		BLAST_THRESHOLDS_BLOCK.put(Blocks.SNOW, 1.0);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.SNOW_LAYER, 0.5);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.ICE, 2.0);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.PACKED_ICE, 2.5);

		// === WATER ===
		BLAST_THRESHOLDS_BLOCK.put(Blocks.WATER, 0.5);           // Water easily displaced
		BLAST_THRESHOLDS_BLOCK.put(Blocks.FLOWING_WATER, 0.5);

		// === STONE/BRICK - MODERATE RESISTANCE ===
		BLAST_THRESHOLDS_BLOCK.put(Blocks.COBBLESTONE, 5.0);     // 5 PSI - unreinforced masonry
		BLAST_THRESHOLDS_BLOCK.put(Blocks.STONE, 8.0);           // 8 PSI - solid stone
		BLAST_THRESHOLDS_BLOCK.put(Blocks.BRICK_BLOCK, 5.0);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.STONEBRICK, 8.0);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.SANDSTONE, 4.0);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.RED_SANDSTONE, 4.0);

		// === CONCRETE/HARDENED CLAY ===
		BLAST_THRESHOLDS_BLOCK.put(Blocks.HARDENED_CLAY, 12.0);  // 12 PSI - reinforced concrete
		BLAST_THRESHOLDS_BLOCK.put(Blocks.STAINED_HARDENED_CLAY, 12.0);

		// === METAL - HIGH RESISTANCE ===
		BLAST_THRESHOLDS_BLOCK.put(Blocks.IRON_BLOCK, 20.0);     // 20 PSI - heavy steel structures
		BLAST_THRESHOLDS_BLOCK.put(Blocks.GOLD_BLOCK, 15.0);     // Gold is softer
		BLAST_THRESHOLDS_BLOCK.put(Blocks.IRON_ORE, 12.0);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.GOLD_ORE, 10.0);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.IRON_DOOR, 18.0);
		BLAST_THRESHOLDS_BLOCK.put(Blocks.IRON_BARS, 15.0);

		// === OBSIDIAN - VERY HIGH RESISTANCE ===
		BLAST_THRESHOLDS_BLOCK.put(Blocks.OBSIDIAN, 30.0);       // 30 PSI

		// === BEDROCK - INDESTRUCTIBLE ===
		BLAST_THRESHOLDS_BLOCK.put(Blocks.BEDROCK, Double.MAX_VALUE);

		// === MATERIAL DEFAULTS (fallback) ===
		BLAST_THRESHOLDS_MATERIAL.put(Material.LEAVES, 0.3);
		BLAST_THRESHOLDS_MATERIAL.put(Material.PLANTS, 0.3);
		BLAST_THRESHOLDS_MATERIAL.put(Material.VINE, 0.3);
		BLAST_THRESHOLDS_MATERIAL.put(Material.CLOTH, 1.0);
		BLAST_THRESHOLDS_MATERIAL.put(Material.GLASS, 0.5);
		BLAST_THRESHOLDS_MATERIAL.put(Material.WOOD, 2.5);
		BLAST_THRESHOLDS_MATERIAL.put(Material.SAND, 3.0);
		BLAST_THRESHOLDS_MATERIAL.put(Material.SNOW, 1.0);
		BLAST_THRESHOLDS_MATERIAL.put(Material.CRAFTED_SNOW, 1.0);
		BLAST_THRESHOLDS_MATERIAL.put(Material.GROUND, 4.0);
		BLAST_THRESHOLDS_MATERIAL.put(Material.GRASS, 4.0);
		BLAST_THRESHOLDS_MATERIAL.put(Material.ROCK, 8.0);
		BLAST_THRESHOLDS_MATERIAL.put(Material.IRON, 20.0);
		BLAST_THRESHOLDS_MATERIAL.put(Material.WATER, 0.5);
		BLAST_THRESHOLDS_MATERIAL.put(Material.LAVA, 5.0);
		BLAST_THRESHOLDS_MATERIAL.put(Material.ICE, 2.0);
		BLAST_THRESHOLDS_MATERIAL.put(Material.PACKED_ICE, 2.5);
		BLAST_THRESHOLDS_MATERIAL.put(Material.CLAY, 4.0);
	}

	/**
	 * Get thermal fluence threshold for block
	 * @param state Block state
	 * @param block Block
	 * @return Thermal fluence in J/m² required to destroy this block
	 */
	public static double getThermalThreshold(IBlockState state, Block block) {
		// Check block-specific threshold first
		Double threshold = THERMAL_THRESHOLDS_BLOCK.get(block);
		if (threshold != null) return threshold;

		// Check material-based threshold
		Material material = state.getMaterial();
		threshold = THERMAL_THRESHOLDS_MATERIAL.get(material);
		if (threshold != null) return threshold;

		// Fallback: use explosion resistance as proxy
		float resistance = block.getExplosionResistance(null);
		if (resistance >= 2_000_000) return Double.MAX_VALUE; // Indestructible

		// Estimate: 1 MJ/m² + 200 kJ per resistance point
		return DEFAULT_THERMAL_THRESHOLD + (resistance * 2.0e5);
	}

	/**
	 * Get blast overpressure threshold for block
	 * @param state Block state
	 * @param block Block
	 * @return Overpressure in PSI required to destroy this block
	 */
	public static double getBlastThreshold(IBlockState state, Block block) {
		// Check block-specific threshold first
		Double threshold = BLAST_THRESHOLDS_BLOCK.get(block);
		if (threshold != null) return threshold;

		// Check material-based threshold
		Material material = state.getMaterial();
		threshold = BLAST_THRESHOLDS_MATERIAL.get(material);
		if (threshold != null) return threshold;

		// Fallback: use explosion resistance as proxy
		float resistance = block.getExplosionResistance(null);
		if (resistance >= 2_000_000) return Double.MAX_VALUE; // Indestructible

		// Estimate: 5 PSI + 0.5 PSI per resistance point
		return DEFAULT_BLAST_THRESHOLD + (resistance * 0.5);
	}

	/**
	 * Check if material is flammable (for fire spawning)
	 */
	public static boolean isFlammable(IBlockState state, Block block) {
		Material material = state.getMaterial();
		return material == Material.WOOD ||
		       material == Material.LEAVES ||
		       material == Material.PLANTS ||
		       material == Material.VINE ||
		       material == Material.CLOTH ||
		       material == Material.CARPET ||
		       block == Blocks.TNT;
	}
}
