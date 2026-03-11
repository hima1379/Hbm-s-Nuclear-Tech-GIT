package com.hbm.explosion;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;

/**
 * MATERIAL ENERGY CALCULATOR FOR REALISTIC BLOCK DESTRUCTION
 *
 * Calculates energy required to destroy blocks based on real-world material physics:
 * - Crushing/fragmentation energy for solids
 * - Phase change energy for liquids/ice
 * - Combustion energy for organic materials
 *
 * SCIENTIFIC BASIS:
 * - Concrete crushing: ~5-10 MJ/m³
 * - Rock fragmentation: ~5-8 MJ/m³
 * - Wood structural failure: ~2-3 MJ/m³
 * - Metal deformation: ~10-50 MJ/m³
 * - Water evaporation: 2.26 MJ/kg × 1000 kg/m³ = 2.26 MJ/m³
 * - Ice melting: 334 kJ/kg × 917 kg/m³ = 0.31 MJ/m³
 *
 * DEPTH-BASED PROTECTION:
 * Underground blocks are harder to destroy due to:
 * - Confining pressure from surrounding material
 * - Reduced shock wave transmission
 * - Nuclear shelter design (survive at depth)
 *
 * Protection multipliers (applied to energy cost):
 * - 0-5m depth: 1× → 3× (linear)
 * - 5-10m depth: 3× → 5× (linear)
 * - 10-20m depth: 5× → 10× (linear)
 * - >20m depth: 10× → 20× (logarithmic)
 */
public class MaterialEnergyCalculator {

	// === BLOCK-SPECIFIC ENERGY COSTS (J/m³) ===
	private static final Map<Block, Double> blockEnergyCosts = new HashMap<>();

	// === MATERIAL-TYPE ENERGY COSTS (J/m³) ===
	private static final Map<Material, Double> materialEnergyCosts = new HashMap<>();

	// === THERMAL DESTRUCTION MULTIPLIER ===
	// Thermal destruction requires more energy (vaporization vs. mechanical failure)
	private static final double THERMAL_MULTIPLIER = 3.0;

	static {
		initializeEnergyCosts();
	}

	/**
	 * Initialize energy costs for all materials
	 * Based on real-world physics and scaled to Minecraft blocks
	 */
	private static void initializeEnergyCosts() {
		// === ORGANIC MATERIALS (Low energy) ===
		// Easily burned/destroyed
		blockEnergyCosts.put(Blocks.LEAVES, 50_000.0);      // 50 kJ/m³
		blockEnergyCosts.put(Blocks.LEAVES2, 50_000.0);
		blockEnergyCosts.put(Blocks.TALLGRASS, 30_000.0);   // 30 kJ/m³
		blockEnergyCosts.put(Blocks.DEADBUSH, 20_000.0);
		blockEnergyCosts.put(Blocks.WOOL, 100_000.0);       // 100 kJ/m³
		blockEnergyCosts.put(Blocks.CARPET, 80_000.0);
		blockEnergyCosts.put(Blocks.VINE, 40_000.0);

		// === WOOD (Medium-low energy) ===
		// Structural breaking + combustion
		blockEnergyCosts.put(Blocks.LOG, 2_500_000.0);      // 2.5 MJ/m³
		blockEnergyCosts.put(Blocks.LOG2, 2_500_000.0);
		blockEnergyCosts.put(Blocks.PLANKS, 2_200_000.0);   // 2.2 MJ/m³
		blockEnergyCosts.put(Blocks.OAK_FENCE, 2_000_000.0);
		blockEnergyCosts.put(Blocks.SPRUCE_FENCE, 2_000_000.0);
		blockEnergyCosts.put(Blocks.BIRCH_FENCE, 2_000_000.0);
		blockEnergyCosts.put(Blocks.JUNGLE_FENCE, 2_000_000.0);
		blockEnergyCosts.put(Blocks.DARK_OAK_FENCE, 2_000_000.0);
		blockEnergyCosts.put(Blocks.ACACIA_FENCE, 2_000_000.0);
		blockEnergyCosts.put(Blocks.OAK_DOOR, 1_800_000.0);
		blockEnergyCosts.put(Blocks.SPRUCE_DOOR, 1_800_000.0);
		blockEnergyCosts.put(Blocks.BIRCH_DOOR, 1_800_000.0);
		blockEnergyCosts.put(Blocks.JUNGLE_DOOR, 1_800_000.0);
		blockEnergyCosts.put(Blocks.ACACIA_DOOR, 1_800_000.0);
		blockEnergyCosts.put(Blocks.DARK_OAK_DOOR, 1_800_000.0);

		// === ICE/SNOW (Phase change energy) ===
		blockEnergyCosts.put(Blocks.ICE, 334_000.0);        // 334 kJ/m³ (melting)
		blockEnergyCosts.put(Blocks.PACKED_ICE, 367_000.0); // 367 kJ/m³
		blockEnergyCosts.put(Blocks.SNOW, 167_000.0);       // 167 kJ/m³
		blockEnergyCosts.put(Blocks.SNOW_LAYER, 100_000.0);

		// === EARTH MATERIALS (Medium energy) ===
		// Displacement/excavation energy
		blockEnergyCosts.put(Blocks.DIRT, 800_000.0);       // 0.8 MJ/m³
		blockEnergyCosts.put(Blocks.GRASS, 800_000.0);
		blockEnergyCosts.put(Blocks.MYCELIUM, 800_000.0);
		blockEnergyCosts.put(Blocks.SAND, 500_000.0);       // 0.5 MJ/m³ (loose)
		blockEnergyCosts.put(Blocks.GRAVEL, 600_000.0);     // 0.6 MJ/m³
		blockEnergyCosts.put(Blocks.CLAY, 900_000.0);       // 0.9 MJ/m³

		// === GLASS (Brittle materials) ===
		// Shattering from thermal stress
		blockEnergyCosts.put(Blocks.GLASS, 1_500_000.0);    // 1.5 MJ/m³
		blockEnergyCosts.put(Blocks.GLASS_PANE, 1_200_000.0);
		blockEnergyCosts.put(Blocks.STAINED_GLASS, 1_500_000.0);
		blockEnergyCosts.put(Blocks.STAINED_GLASS_PANE, 1_200_000.0);

		// === STONE (High energy) ===
		// Crushing/fragmentation
		blockEnergyCosts.put(Blocks.STONE, 5_000_000.0);    // 5 MJ/m³
		blockEnergyCosts.put(Blocks.COBBLESTONE, 4_500_000.0);
		blockEnergyCosts.put(Blocks.BRICK_BLOCK, 6_000_000.0);
		blockEnergyCosts.put(Blocks.STONEBRICK, 6_000_000.0);
		blockEnergyCosts.put(Blocks.SANDSTONE, 4_000_000.0);
		blockEnergyCosts.put(Blocks.RED_SANDSTONE, 4_000_000.0);
		blockEnergyCosts.put(Blocks.NETHERRACK, 3_000_000.0);
		blockEnergyCosts.put(Blocks.END_STONE, 5_500_000.0);

		// === CONCRETE/ENGINEERED (High energy) ===
		blockEnergyCosts.put(Blocks.CONCRETE, 8_000_000.0); // 8 MJ/m³
		blockEnergyCosts.put(Blocks.CONCRETE_POWDER, 2_000_000.0);
		blockEnergyCosts.put(Blocks.HARDENED_CLAY, 7_000_000.0);
		blockEnergyCosts.put(Blocks.STAINED_HARDENED_CLAY, 7_000_000.0);

		// === METALS (Very high energy) ===
		// Melting/deformation
		blockEnergyCosts.put(Blocks.IRON_BLOCK, 30_000_000.0);   // 30 MJ/m³
		blockEnergyCosts.put(Blocks.GOLD_BLOCK, 25_000_000.0);   // 25 MJ/m³
		blockEnergyCosts.put(Blocks.IRON_ORE, 20_000_000.0);
		blockEnergyCosts.put(Blocks.GOLD_ORE, 18_000_000.0);
		blockEnergyCosts.put(Blocks.IRON_DOOR, 28_000_000.0);
		blockEnergyCosts.put(Blocks.IRON_BARS, 25_000_000.0);
		blockEnergyCosts.put(Blocks.IRON_TRAPDOOR, 26_000_000.0);

		// === DIAMOND/OBSIDIAN (Extremely high energy) ===
		blockEnergyCosts.put(Blocks.DIAMOND_BLOCK, 80_000_000.0); // 80 MJ/m³
		blockEnergyCosts.put(Blocks.OBSIDIAN, 50_000_000.0);      // 50 MJ/m³
		blockEnergyCosts.put(Blocks.DIAMOND_ORE, 60_000_000.0);

		// === LIQUIDS ===
		// Evaporation energy
		blockEnergyCosts.put(Blocks.WATER, 2_260_000.0);    // 2.26 MJ/m³
		blockEnergyCosts.put(Blocks.FLOWING_WATER, 2_260_000.0);
		blockEnergyCosts.put(Blocks.LAVA, Double.MAX_VALUE); // Cannot destroy lava
		blockEnergyCosts.put(Blocks.FLOWING_LAVA, Double.MAX_VALUE);

		// === INDESTRUCTIBLE ===
		blockEnergyCosts.put(Blocks.BEDROCK, Double.MAX_VALUE);
		blockEnergyCosts.put(Blocks.BARRIER, Double.MAX_VALUE);
		blockEnergyCosts.put(Blocks.COMMAND_BLOCK, Double.MAX_VALUE);

		// === MATERIAL TYPE DEFAULTS ===
		materialEnergyCosts.put(Material.WATER, 2_260_000.0);
		materialEnergyCosts.put(Material.LAVA, Double.MAX_VALUE);
		materialEnergyCosts.put(Material.WOOD, 2_500_000.0);
		materialEnergyCosts.put(Material.LEAVES, 50_000.0);
		materialEnergyCosts.put(Material.PLANTS, 40_000.0);
		materialEnergyCosts.put(Material.VINE, 40_000.0);
		materialEnergyCosts.put(Material.ICE, 334_000.0);
		materialEnergyCosts.put(Material.PACKED_ICE, 367_000.0);
		materialEnergyCosts.put(Material.SNOW, 167_000.0);
		materialEnergyCosts.put(Material.CRAFTED_SNOW, 167_000.0);
		materialEnergyCosts.put(Material.GLASS, 1_500_000.0);
		materialEnergyCosts.put(Material.ROCK, 5_000_000.0);
		materialEnergyCosts.put(Material.IRON, 30_000_000.0);
		materialEnergyCosts.put(Material.GROUND, 800_000.0);
		materialEnergyCosts.put(Material.SAND, 500_000.0);
		materialEnergyCosts.put(Material.CLAY, 900_000.0);
	}

	/**
	 * Calculate energy cost for blast destruction
	 *
	 * @param state Block state
	 * @param block Block
	 * @param depthBelowSurface Depth below surface in meters (0 = surface)
	 * @param isUnderground Whether block is underground
	 * @return Energy cost in Joules
	 */
	public static double calculateEnergyCost(IBlockState state, Block block,
											 double depthBelowSurface, boolean isUnderground) {
		// Get base energy cost
		double baseCost = getBaseEnergyCost(state, block);

		// Apply depth protection multiplier
		if (isUnderground) {
			double depthMultiplier = calculateDepthMultiplier(depthBelowSurface, true);
			baseCost *= depthMultiplier;
		}

		return baseCost;
	}

	/**
	 * Calculate energy cost for thermal destruction
	 * Thermal destruction requires more energy (vaporization vs. mechanical failure)
	 *
	 * @param state Block state
	 * @param block Block
	 * @param depthBelowSurface Depth below surface in meters
	 * @param isUnderground Whether block is underground
	 * @return Energy cost in Joules
	 */
	public static double calculateThermalEnergyCost(IBlockState state, Block block,
													double depthBelowSurface, boolean isUnderground) {
		double baseCost = getBaseEnergyCost(state, block);

		// Thermal destruction requires more energy
		baseCost *= THERMAL_MULTIPLIER;

		// Apply depth protection
		if (isUnderground) {
			double depthMultiplier = calculateDepthMultiplier(depthBelowSurface, true);
			baseCost *= depthMultiplier;
		}

		return baseCost;
	}

	/**
	 * Get base energy cost for block
	 *
	 * @param state Block state
	 * @param block Block
	 * @return Base energy cost in Joules (before modifiers)
	 */
	private static double getBaseEnergyCost(IBlockState state, Block block) {
		// Check block-specific cost
		Double cost = blockEnergyCosts.get(block);
		if (cost != null) return cost;

		// Check material-type cost
		Material material = state.getMaterial();
		cost = materialEnergyCosts.get(material);
		if (cost != null) return cost;

		// Fallback: use explosion resistance
		float resistance = block.getExplosionResistance(null);
		if (resistance >= 2_000_000) return Double.MAX_VALUE; // Indestructible

		// Empirical formula: 1 MJ base + 0.5 MJ per resistance point
		return 1_000_000.0 + (resistance * 500_000.0);
	}

	/**
	 * Calculate depth-based protection multiplier
	 *
	 * REALISTIC UNDERGROUND PROTECTION:
	 * - Nuclear shelters at 10m depth survive nearby blasts
	 * - Deep underground structures highly resistant
	 * - Confining pressure increases resistance
	 *
	 * @param depth Depth below surface in meters (0 = surface)
	 * @param isUnderground Whether block is underground
	 * @return Multiplier for energy cost (1.0 = no protection, higher = more protection)
	 */
	public static double calculateDepthMultiplier(double depth, boolean isUnderground) {
		if (!isUnderground || depth <= 0) {
			return 1.0; // No protection at surface
		}

		// Graduated protection based on depth
		if (depth < 5.0) {
			// 0-5m: Linear 1× → 3×
			return 1.0 + (depth / 5.0) * 2.0;
		} else if (depth < 10.0) {
			// 5-10m: Linear 3× → 5×
			return 3.0 + ((depth - 5.0) / 5.0) * 2.0;
		} else if (depth < 20.0) {
			// 10-20m: Linear 5× → 10×
			return 5.0 + ((depth - 10.0) / 10.0) * 5.0;
		} else {
			// >20m: Logarithmic 10× → 20×
			// At 40m depth: ~15× protection
			// At 100m depth: ~20× protection
			return 10.0 + Math.log10(1.0 + (depth - 20.0)) * 5.0;
		}
	}

	/**
	 * Get thermal fluence threshold for block
	 *
	 * 熱フルエンス閾値: ブロックが熱線で破壊されるために必要な総熱エネルギー密度 (J/m²)
	 *
	 * SCIENTIFIC BASIS:
	 * - Leaves/Plants: 100 kJ/m² (immediate ignition)
	 * - Wood: 500 kJ/m² (wood ignition point ~300°C)
	 * - Cloth: 200 kJ/m²
	 * - Glass: 2 MJ/m² (thermal shock shattering)
	 * - Stone: 10 MJ/m² (structural failure from thermal stress)
	 * - Metal: 20 MJ/m² (melting point)
	 * - Diamond/Obsidian: 50-80 MJ/m²
	 *
	 * @param block Block to check
	 * @return Thermal fluence threshold in J/m²
	 */
	public static double getThermalFluenceThreshold(Block block) {
		// === ORGANIC MATERIALS (Very low threshold) ===
		// Immediate ignition from thermal radiation
		if (block == Blocks.LEAVES || block == Blocks.LEAVES2) {
			return 100_000.0; // 100 kJ/m²
		}
		if (block == Blocks.TALLGRASS || block == Blocks.DEADBUSH || block == Blocks.VINE) {
			return 80_000.0; // 80 kJ/m²
		}
		if (block == Blocks.WOOL || block == Blocks.CARPET) {
			return 200_000.0; // 200 kJ/m²
		}

		// === WOOD (Low threshold) ===
		// Wood ignition point ~300°C
		if (block == Blocks.LOG || block == Blocks.LOG2) {
			return 500_000.0; // 500 kJ/m²
		}
		if (block == Blocks.PLANKS) {
			return 450_000.0; // 450 kJ/m²
		}
		if (block == Blocks.OAK_FENCE || block == Blocks.SPRUCE_FENCE ||
			block == Blocks.BIRCH_FENCE || block == Blocks.JUNGLE_FENCE ||
			block == Blocks.DARK_OAK_FENCE || block == Blocks.ACACIA_FENCE) {
			return 400_000.0; // 400 kJ/m²
		}
		if (block == Blocks.OAK_DOOR || block == Blocks.SPRUCE_DOOR ||
			block == Blocks.BIRCH_DOOR || block == Blocks.JUNGLE_DOOR ||
			block == Blocks.ACACIA_DOOR || block == Blocks.DARK_OAK_DOOR) {
			return 400_000.0; // 400 kJ/m²
		}

		// === ICE/SNOW (Low-medium threshold) ===
		// Melting/sublimation
		if (block == Blocks.ICE) {
			return 800_000.0; // 800 kJ/m²
		}
		if (block == Blocks.PACKED_ICE) {
			return 1_000_000.0; // 1 MJ/m²
		}
		if (block == Blocks.SNOW || block == Blocks.SNOW_LAYER) {
			return 600_000.0; // 600 kJ/m²
		}

		// === GLASS (Medium threshold) ===
		// Thermal shock shattering
		if (block == Blocks.GLASS || block == Blocks.STAINED_GLASS) {
			return 2_000_000.0; // 2 MJ/m²
		}
		if (block == Blocks.GLASS_PANE || block == Blocks.STAINED_GLASS_PANE) {
			return 1_500_000.0; // 1.5 MJ/m²
		}

		// === EARTH MATERIALS (Medium-high threshold) ===
		// Thermal stress cracking
		if (block == Blocks.DIRT || block == Blocks.GRASS || block == Blocks.MYCELIUM) {
			return 3_000_000.0; // 3 MJ/m²
		}
		if (block == Blocks.SAND || block == Blocks.GRAVEL) {
			return 2_500_000.0; // 2.5 MJ/m²
		}
		if (block == Blocks.CLAY) {
			return 4_000_000.0; // 4 MJ/m²
		}

		// === STONE (High threshold) ===
		// Structural failure from thermal expansion
		if (block == Blocks.STONE || block == Blocks.COBBLESTONE) {
			return 10_000_000.0; // 10 MJ/m²
		}
		if (block == Blocks.BRICK_BLOCK || block == Blocks.STONEBRICK) {
			return 12_000_000.0; // 12 MJ/m²
		}
		if (block == Blocks.SANDSTONE || block == Blocks.RED_SANDSTONE) {
			return 8_000_000.0; // 8 MJ/m²
		}
		if (block == Blocks.NETHERRACK) {
			return 6_000_000.0; // 6 MJ/m²
		}
		if (block == Blocks.END_STONE) {
			return 11_000_000.0; // 11 MJ/m²
		}

		// === CONCRETE/ENGINEERED (High threshold) ===
		if (block == Blocks.CONCRETE) {
			return 15_000_000.0; // 15 MJ/m²
		}
		if (block == Blocks.CONCRETE_POWDER) {
			return 5_000_000.0; // 5 MJ/m²
		}
		if (block == Blocks.HARDENED_CLAY || block == Blocks.STAINED_HARDENED_CLAY) {
			return 14_000_000.0; // 14 MJ/m²
		}

		// === METALS (Very high threshold) ===
		// Melting point
		if (block == Blocks.IRON_BLOCK || block == Blocks.IRON_ORE) {
			return 20_000_000.0; // 20 MJ/m²
		}
		if (block == Blocks.GOLD_BLOCK || block == Blocks.GOLD_ORE) {
			return 18_000_000.0; // 18 MJ/m² (gold melts easier)
		}
		if (block == Blocks.IRON_DOOR || block == Blocks.IRON_BARS || block == Blocks.IRON_TRAPDOOR) {
			return 19_000_000.0; // 19 MJ/m²
		}

		// === DIAMOND/OBSIDIAN (Extremely high threshold) ===
		if (block == Blocks.DIAMOND_BLOCK || block == Blocks.DIAMOND_ORE) {
			return 80_000_000.0; // 80 MJ/m²
		}
		if (block == Blocks.OBSIDIAN) {
			return 50_000_000.0; // 50 MJ/m²
		}

		// === LIQUIDS ===
		if (block == Blocks.WATER || block == Blocks.FLOWING_WATER) {
			return 5_000_000.0; // 5 MJ/m² (evaporation)
		}
		if (block == Blocks.LAVA || block == Blocks.FLOWING_LAVA) {
			return Double.MAX_VALUE; // Cannot destroy lava
		}

		// === INDESTRUCTIBLE ===
		if (block == Blocks.BEDROCK || block == Blocks.BARRIER || block == Blocks.COMMAND_BLOCK) {
			return Double.MAX_VALUE;
		}

		// === FALLBACK: Use material type ===
		Material material = block.getDefaultState().getMaterial();

		if (material == Material.LEAVES || material == Material.PLANTS || material == Material.VINE) {
			return 100_000.0; // 100 kJ/m²
		} else if (material == Material.WOOD) {
			return 500_000.0; // 500 kJ/m²
		} else if (material == Material.CLOTH) {
			return 200_000.0; // 200 kJ/m²
		} else if (material == Material.GLASS) {
			return 2_000_000.0; // 2 MJ/m²
		} else if (material == Material.ROCK) {
			return 10_000_000.0; // 10 MJ/m²
		} else if (material == Material.IRON) {
			return 20_000_000.0; // 20 MJ/m²
		} else if (material == Material.ICE || material == Material.PACKED_ICE) {
			return 800_000.0; // 800 kJ/m²
		} else if (material == Material.SNOW || material == Material.CRAFTED_SNOW) {
			return 600_000.0; // 600 kJ/m²
		} else if (material == Material.GROUND || material == Material.SAND || material == Material.CLAY) {
			return 3_000_000.0; // 3 MJ/m²
		} else if (material == Material.WATER) {
			return 5_000_000.0; // 5 MJ/m²
		} else if (material == Material.LAVA) {
			return Double.MAX_VALUE;
		} else {
			// Default fallback: 5 MJ/m²
			return 5_000_000.0;
		}
	}

	/**
	 * Get example protection multipliers for various depths
	 * Useful for debugging and calibration
	 */
	public static void printDepthProtectionExamples() {
		System.out.println("=== DEPTH PROTECTION MULTIPLIERS ===");
		double[] depths = {0, 2.5, 5, 7.5, 10, 15, 20, 30, 40, 60, 100};
		for (double depth : depths) {
			double multiplier = calculateDepthMultiplier(depth, depth > 0);
			System.out.println(String.format("  %4.1fm depth: %.2f× protection", depth, multiplier));
		}
		System.out.println("====================================");
	}
}
