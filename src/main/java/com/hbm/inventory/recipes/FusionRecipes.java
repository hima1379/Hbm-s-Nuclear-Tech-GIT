package com.hbm.inventory.recipes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.hbm.forgefluid.ModForgeFluids;
import com.hbm.items.ModItems;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;

/**
 * Registry for all fusion reactor recipes.
 * Manages recipe registration and lookup by name.
 *
 * @author Adapted for 1.12.2
 */
public class FusionRecipes {

	/** Singleton instance */
	public static final FusionRecipes INSTANCE = new FusionRecipes();

	/** Recipe map by name for quick lookup */
	private HashMap<String, FusionRecipe> recipeMap = new HashMap<>();

	/** Ordered list of all recipes */
	private List<FusionRecipe> recipeList = new ArrayList<>();

	/** Maximum ignition energy across all recipes (for creative klystron) */
	public long maxInput = 0;

	/**
	 * Registers a new fusion recipe.
	 *
	 * @param recipe The recipe to register
	 */
	public void register(FusionRecipe recipe) {
		recipeMap.put(recipe.getName(), recipe);
		recipeList.add(recipe);

		// Update max ignition energy
		if (recipe.ignitionTemp > maxInput) {
			maxInput = recipe.ignitionTemp;
		}
	}

	/**
	 * Gets a recipe by name.
	 *
	 * @param name Recipe name
	 * @return The recipe, or null if not found
	 */
	public FusionRecipe getRecipe(String name) {
		return recipeMap.get(name);
	}

	/**
	 * Gets a recipe by index in the ordered list.
	 *
	 * @param index Recipe index
	 * @return The recipe, or null if index out of bounds
	 */
	public FusionRecipe getRecipeByIndex(int index) {
		if (index >= 0 && index < recipeList.size()) {
			return recipeList.get(index);
		}
		return null;
	}

	/**
	 * Gets the total number of registered recipes.
	 *
	 * @return Recipe count
	 */
	public int getRecipeCount() {
		return recipeList.size();
	}

	/**
	 * Gets all registered recipes.
	 *
	 * @return List of all recipes
	 */
	public List<FusionRecipe> getAllRecipes() {
		return new ArrayList<>(recipeList);
	}

	/**
	 * Registers all default fusion recipes.
	 * Called during mod initialization.
	 *
	 * NOTE: Input/output items will need to be updated with actual 1.12.2 items
	 * once the item registration system is in place.
	 */
	public void registerDefaults() {
		long solenoid = 25_000; // Power consumption per tick

		// Deuterium-Deuterium fusion (DD)
		// Early recipe, produces helium-4 and some neutrons
		register(new FusionRecipe("fus.dd")
				.setInputFluids(new Fluid[] {ModForgeFluids.DEUTERIUM, null, null, null}, new int[] {20, 0, 0, 0})
				.setOutputFluid(ModForgeFluids.HELIUM4, 1000)
				.setInputEnergy(750_000)
				.setOutputEnergy(1_000_000)
				.setOutputFlux(50.0)
				.setDuration(100)
				.setRGB(1.0F, 0.2F, 0.2F));

		// Deuterium-Oxygen fusion (DO)
		// Early fuel with lower ignition point
		register(new FusionRecipe("fus.do")
				.setInputFluids(new Fluid[] {ModForgeFluids.DEUTERIUM, ModForgeFluids.OXYGEN, null, null}, new int[] {10, 10, 0, 0})
				.setOutput(new ItemStack(ModItems.pellet_charged, 1))
				.setOutputFluid(ModForgeFluids.STEAM, 100)
				.setInputEnergy(250_000)
				.setOutputEnergy(1_250_000)
				.setOutputFlux(50.0)
				.setDuration(100)
				.setRGB(1.0F, 0.2F, 0.6F));

		// Deuterium-Tritium fusion (DT)
		// Medium fuel, good energy output
		register(new FusionRecipe("fus.dt")
				.setInputFluids(new Fluid[] {ModForgeFluids.DEUTERIUM, ModForgeFluids.TRITIUM, null, null}, new int[] {10, 10, 0, 0})
				.setOutputFluid(ModForgeFluids.HELIUM4, 1000)
				.setInputEnergy(750_000)
				.setOutputEnergy(3_750_000)
				.setOutputFlux(100.0)
				.setDuration(100)
				.setRGB(1.0F, 0.2F, 0.6F));

		// Tritium-Chlorine fusion (TCL)
		// Medium-high fuel
		register(new FusionRecipe("fus.tcl")
				.setInputFluids(new Fluid[] {ModForgeFluids.TRITIUM, ModForgeFluids.CHLORINE, null, null}, new int[] {10, 10, 0, 0})
				.setOutput(new ItemStack(ModItems.powder_chlorophyte, 1))
				.setOutputFluid(ModForgeFluids.HELIUM3, 50)
				.setInputEnergy(2_500_000)
				.setOutputEnergy(6_250_000)
				.setOutputFlux(500.0)
				.setDuration(100)
				.setRGB(0.8F, 0.6F, 0.4F));

		// Helium-3 fusion (H3)
		// Aneutronic fusion (no neutron output)
		register(new FusionRecipe("fus.h3")
				.setInputFluids(new Fluid[] {ModForgeFluids.HELIUM3, null, null, null}, new int[] {20, 0, 0, 0})
				.setOutputFluid(ModForgeFluids.HELIUM4, 1000)
				.setInputEnergy(500_000)
				.setOutputEnergy(3_750_000)
				.setOutputFlux(0.0)
				.setDuration(100)
				.setRGB(0.2F, 0.2F, 1.0F));

		// Tritium-Helium-4 fusion (TH4)
		// Medium-high fuel
		register(new FusionRecipe("fus.th4")
				.setInputFluids(new Fluid[] {ModForgeFluids.TRITIUM, ModForgeFluids.HELIUM4, null, null}, new int[] {10, 10, 0, 0})
				.setOutput(new ItemStack(ModItems.pellet_charged, 1))
				.setOutputFluid(ModForgeFluids.HELIUM3, 100)
				.setInputEnergy(875_000)
				.setOutputEnergy(4_000_000)
				.setOutputFlux(500.0)
				.setDuration(100)
				.setRGB(0.2F, 0.2F, 1.0F));

		// Chlorine fusion (CL)
		// High fuel, requires bootstrap from TH4 or H3
		register(new FusionRecipe("fus.cl")
				.setInputFluids(new Fluid[] {ModForgeFluids.CHLORINE, null, null, null}, new int[] {20, 0, 0, 0})
				.setOutput(new ItemStack(ModItems.powder_chlorophyte, 1))
				.setOutputFluid(ModForgeFluids.OXYGEN, 200)
				.setInputEnergy(3_750_000)
				.setOutputEnergy(10_000_000)
				.setOutputFlux(1000.0)
				.setDuration(100)
				.setRGB(1.0F, 0.6F, 0.2F));

		// Dense Hydrogen Compound fusion (DHC)
		// Very high fuel
		register(new FusionRecipe("fus.dhc")
				.setInputFluids(new Fluid[] {ModForgeFluids.DHC, null, null, null}, new int[] {20, 0, 0, 0})
				.setOutput(new ItemStack(ModItems.powder_chlorophyte, 1))
				.setOutputFluid(ModForgeFluids.HELIUM4, 500)
				.setInputEnergy(10_000_000)
				.setOutputEnergy(25_000_000)
				.setOutputFlux(2000.0)
				.setDuration(100)
				.setRGB(0.2F, 0.8F, 0.8F));

		// Balefire fusion (BF)
		// Exotic fuel with moderate ignition
		register(new FusionRecipe("fus.bf")
				.setInputFluids(new Fluid[] {ModForgeFluids.BALEFIRE, ModForgeFluids.AMAT, null, null}, new int[] {15, 5, 0, 0})
				.setOutput(new ItemStack(ModItems.powder_balefire, 1))
				.setOutputFluid(ModForgeFluids.STEAM, 1000)
				.setInputEnergy(1_000_000)
				.setOutputEnergy(12_500_000)
				.setOutputFlux(2000.0)
				.setDuration(100)
				.setRGB(0.2F, 1.0F, 0.2F));

		// Stellar Flux fusion (STELLAR)
		// Ultimate fuel, highest output
		register(new FusionRecipe("fus.stellar")
				.setInputFluids(new Fluid[] {ModForgeFluids.STELLAR_FLUX, null, null, null}, new int[] {10, 0, 0, 0})
				.setOutput(new ItemStack(ModItems.powder_gold, 1))
				.setOutputFluid(ModForgeFluids.HELIUM4, 2000)
				.setInputEnergy(10_000_000)
				.setOutputEnergy(50_000_000)
				.setOutputFlux(10000.0)
				.setDuration(100)
				.setRGB(1.0F, 0.4F, 0.1F));
	}

	/**
	 * Clears all registered recipes.
	 * Used for reloading or testing.
	 */
	public void clear() {
		recipeMap.clear();
		recipeList.clear();
		maxInput = 0;
	}
}
