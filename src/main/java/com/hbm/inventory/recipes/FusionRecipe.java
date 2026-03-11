package com.hbm.inventory.recipes;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;

/**
 * Recipe definition for fusion reactor.
 * Contains input requirements, output values, and visual properties.
 *
 * @author Adapted for 1.12.2
 */
public class FusionRecipe {

	/** Recipe identifier/name */
	public String name;

	/** Input item stacks required for this recipe (max 3 slots) */
	public ItemStack[] inputs;

	/** Output item stack produced by this recipe */
	public ItemStack output;

	/** Input fluids required (4 tanks max) */
	public Fluid[] inputFluids;

	/** Input fluid amounts in mB */
	public int[] inputFluidAmounts;

	/** Output fluid produced */
	public Fluid outputFluid;

	/** Output fluid amount in mB */
	public int outputFluidAmount;

	/** Minimum klystron energy (KyU) required to ignite the plasma */
	public long ignitionTemp;

	/** Plasma output energy (TU) at full power */
	public long outputTemp;

	/** Neutron flux output (multiplier for breeding) */
	public double neutronFlux;

	/** Processing duration in ticks */
	public int duration;

	/** Plasma color - Red component (0.0-1.0) */
	public float r = 1.0F;

	/** Plasma color - Green component (0.0-1.0) */
	public float g = 0.2F;

	/** Plasma color - Blue component (0.0-1.0) */
	public float b = 0.6F;

	/**
	 * Creates a new fusion recipe.
	 *
	 * @param name Recipe identifier
	 */
	public FusionRecipe(String name) {
		this.name = name;
		this.inputs = new ItemStack[3];
		this.output = ItemStack.EMPTY;
		this.inputFluids = new Fluid[4];
		this.inputFluidAmounts = new int[4];
		this.outputFluid = null;
		this.outputFluidAmount = 0;
	}

	/**
	 * Sets the input items for this recipe.
	 *
	 * @param inputs Array of input ItemStacks (max 3)
	 * @return This recipe for method chaining
	 */
	public FusionRecipe setInputs(ItemStack... inputs) {
		this.inputs = inputs;
		return this;
	}

	/**
	 * Sets the output item for this recipe.
	 *
	 * @param output Output ItemStack
	 * @return This recipe for method chaining
	 */
	public FusionRecipe setOutput(ItemStack output) {
		this.output = output;
		return this;
	}

	/**
	 * Sets the ignition energy requirement.
	 *
	 * @param ignitionTemp Minimum klystron energy in KyU (Klystron Units)
	 * @return This recipe for method chaining
	 */
	public FusionRecipe setInputEnergy(long ignitionTemp) {
		this.ignitionTemp = ignitionTemp;
		return this;
	}

	/**
	 * Sets the plasma output energy.
	 *
	 * @param outputTemp Plasma energy output in TU (Thermal Units)
	 * @return This recipe for method chaining
	 */
	public FusionRecipe setOutputEnergy(long outputTemp) {
		this.outputTemp = outputTemp;
		return this;
	}

	/**
	 * Sets the neutron flux output.
	 *
	 * @param neutronFlux Neutron flux multiplier
	 * @return This recipe for method chaining
	 */
	public FusionRecipe setOutputFlux(double neutronFlux) {
		this.neutronFlux = neutronFlux;
		return this;
	}

	/**
	 * Sets the processing duration.
	 *
	 * @param duration Duration in ticks
	 * @return This recipe for method chaining
	 */
	public FusionRecipe setDuration(int duration) {
		this.duration = duration;
		return this;
	}

	/**
	 * Sets the plasma visual color (RGB).
	 *
	 * @param r Red component (0.0-1.0)
	 * @param g Green component (0.0-1.0)
	 * @param b Blue component (0.0-1.0)
	 * @return This recipe for method chaining
	 */
	public FusionRecipe setRGB(float r, float g, float b) {
		this.r = r;
		this.g = g;
		this.b = b;
		return this;
	}

	/**
	 * Sets the input fluids for this recipe.
	 *
	 * @param fluids Array of input fluids (max 4)
	 * @param amounts Array of input fluid amounts in mB (max 4)
	 * @return This recipe for method chaining
	 */
	public FusionRecipe setInputFluids(Fluid[] fluids, int[] amounts) {
		this.inputFluids = fluids;
		this.inputFluidAmounts = amounts;
		return this;
	}

	/**
	 * Sets the output fluid for this recipe.
	 *
	 * @param fluid Output fluid
	 * @param amount Output fluid amount in mB
	 * @return This recipe for method chaining
	 */
	public FusionRecipe setOutputFluid(Fluid fluid, int amount) {
		this.outputFluid = fluid;
		this.outputFluidAmount = amount;
		return this;
	}

	/**
	 * Gets the recipe name.
	 *
	 * @return Recipe identifier
	 */
	public String getName() {
		return this.name;
	}
}
