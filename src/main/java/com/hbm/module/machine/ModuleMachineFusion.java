package com.hbm.module.machine;

import com.hbm.inventory.recipes.FusionRecipe;
import com.hbm.inventory.recipes.FusionRecipes;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;

/**
 * Fusion reactor processing module (simplified for 1.12.2).
 * Handles recipe selection, progress tracking, and bonus system.
 *
 * @author Adapted for 1.12.2
 */
public class ModuleMachineFusion {

	public int index;
	public ItemStack[] slots;
	public FluidTank[] inputTanks;
	public FluidTank[] outputTanks;
	public int[] outputSlots;

	/** Currently selected recipe name */
	public String recipe = "null";

	/** Recipe progress (0.0 to 1.0) */
	public double progress;

	/** Bonus progress from collectors (0.0 to 1.5 max) */
	public double bonus;

	/** Processing speed multiplier (from resource availability) */
	public double processSpeed = 1D;

	/** Bonus speed from collectors */
	public double bonusSpeed = 0D;

	/** Fuel rate multiplier (1 = default, 2 = 2x consumption, etc.) */
	public int fuelRate = 1;

	/** Flag indicating if processing occurred this tick */
	public boolean didProcess = false;

	/** Flag indicating inventory changed */
	public boolean markDirty = false;

	public ModuleMachineFusion(int index, Object battery, ItemStack[] slots) {
		this.index = index;
		this.slots = slots;
		this.inputTanks = new FluidTank[3];
		this.outputTanks = new FluidTank[1];
		this.outputSlots = new int[1];
	}

	public ModuleMachineFusion itemOutput(int slot) {
		outputSlots[0] = slot;
		return this;
	}

	public ModuleMachineFusion fluidInput(FluidTank a, FluidTank b, FluidTank c) {
		inputTanks[0] = a;
		inputTanks[1] = b;
		inputTanks[2] = c;
		return this;
	}

	public ModuleMachineFusion fluidOutput(FluidTank a) {
		outputTanks[0] = a;
		return this;
	}

	/**
	 * Setup phase - must run before update().
	 */
	public void preUpdate(double processSpeed, double bonusSpeed) {
		this.processSpeed = processSpeed;
		this.bonusSpeed = bonusSpeed;
	}

	/**
	 * Gets the currently selected recipe.
	 */
	public FusionRecipe getRecipe() {
		if("null".equals(recipe)) return null;
		return FusionRecipes.INSTANCE.getRecipe(recipe);
	}

	/**
	 * Main update method.
	 *
	 * @param speed Speed multiplier
	 * @param power Power multiplier
	 * @param canRun Whether reactor can run (cooling OK && ignition OK)
	 * @param blueprintSlot Blueprint slot for recipe selection
	 */
	public void update(double speed, double power, boolean canRun, ItemStack blueprintSlot) {
		this.didProcess = false;
		this.markDirty = false;

		FusionRecipe currentRecipe = getRecipe();

		// Can't process if no recipe or can't run
		if(!canRun || currentRecipe == null || processSpeed <= 0) {
			this.progress = 0;
			return;
		}

		// Check if we have enough input
		if(!hasInput(currentRecipe)) {
			this.progress = 0;
			return;
		}

		// Check if we can fit output
		if(!canFitOutput(currentRecipe)) {
			this.progress = 0;
			return;
		}

		// Process
		double step = Math.min(speed / currentRecipe.duration * processSpeed, 1D);
		this.progress += step;
		this.bonus += step * this.bonusSpeed;
		this.bonus = Math.min(this.bonus, 1.5D); // Max 50% buffer

		// Consume fuel continuously with fuel rate multiplier
		// fuelRate = 1: default consumption, fuelRate = 2: 2x consumption, etc.
		consumeFuel(currentRecipe, this.fuelRate);

		this.didProcess = true;

		// Complete main cycle
		if(this.progress >= 1D) {
			produceOutput(currentRecipe);
			this.progress -= 1D;
			this.markDirty = true;
		}

		// Complete bonus cycle
		if(this.bonus >= 1D && canFitOutput(currentRecipe)) {
			produceOutput(currentRecipe);
			this.bonus -= 1D;
			this.markDirty = true;
		}
	}

	protected boolean hasInput(FusionRecipe recipe) {
		if(processSpeed <= 0) return false;

		// Check fluid inputs
		if(recipe.inputFluids != null && recipe.inputFluidAmounts != null) {
			for(int i = 0; i < Math.min(recipe.inputFluids.length, 3); i++) {
				if(recipe.inputFluids[i] != null && recipe.inputFluidAmounts[i] > 0) {
					FluidStack stack = inputTanks[i].getFluid();
					if(stack == null || stack.getFluid() != recipe.inputFluids[i] || stack.amount < recipe.inputFluidAmounts[i]) {
						return false;
					}
				}
			}
		}

		return true;
	}

	protected boolean canFitOutput(FusionRecipe recipe) {
		// Check item output slot
		if(!recipe.output.isEmpty()) {
			ItemStack stack = slots[outputSlots[0]];
			if(!stack.isEmpty()) {
				if(stack.getItem() != recipe.output.getItem()) return false;
				if(stack.getMetadata() != recipe.output.getMetadata()) return false;
				if(stack.getCount() + recipe.output.getCount() > stack.getMaxStackSize()) return false;
			}
		}

		// Check fluid output tank (prevent overflow)
		if(recipe.outputFluid != null && recipe.outputFluidAmount > 0 && outputTanks[0] != null) {
			if(recipe.outputFluidAmount + outputTanks[0].getFluidAmount() > outputTanks[0].getCapacity()) {
				return false;
			}
		}

		return true;
	}

	protected void consumeFuel(FusionRecipe recipe) {
		consumeFuel(recipe, 1); // Default: no multiplier
	}

	protected void consumeFuel(FusionRecipe recipe, int fuelRateMultiplier) {
		// Fusion consumes fuel continuously during processing
		// fuelRateMultiplier: 1 = default, 2 = 2x consumption, 9998 = 9998x consumption
		if(recipe.inputFluids != null && recipe.inputFluidAmounts != null) {
			for(int i = 0; i < Math.min(recipe.inputFluids.length, 3); i++) {
				if(recipe.inputFluids[i] != null && recipe.inputFluidAmounts[i] > 0) {
					// Drain fluid from input tanks with fuel rate multiplier
					int consumptionAmount = recipe.inputFluidAmounts[i] * fuelRateMultiplier;
					inputTanks[i].drain(consumptionAmount, true);
				}
			}
		}
	}

	protected void produceOutput(FusionRecipe recipe) {
		// Produce item output
		if(!recipe.output.isEmpty()) {
			ItemStack stack = slots[outputSlots[0]];
			if(stack.isEmpty()) {
				slots[outputSlots[0]] = recipe.output.copy();
			} else {
				stack.grow(recipe.output.getCount());
			}
		}

		// Produce fluid output
		if(recipe.outputFluid != null && recipe.outputFluidAmount > 0 && outputTanks[0] != null) {
			outputTanks[0].fill(new FluidStack(recipe.outputFluid, recipe.outputFluidAmount), true);
		}
	}

	public void readFromNBT(NBTTagCompound nbt) {
		this.recipe = nbt.getString("recipe" + index);
		this.progress = nbt.getDouble("progress" + index);
		this.bonus = nbt.getDouble("bonus" + index);
	}

	public void writeToNBT(NBTTagCompound nbt) {
		nbt.setString("recipe" + index, recipe);
		nbt.setDouble("progress" + index, progress);
		nbt.setDouble("bonus" + index, bonus);
	}
}
