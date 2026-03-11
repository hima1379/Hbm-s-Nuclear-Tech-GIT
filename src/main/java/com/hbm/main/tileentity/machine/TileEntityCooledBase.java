package com.hbm.main.tileentity.machine;

import com.hbm.forgefluid.ModForgeFluids;
import com.hbm.main.tileentity.TileEntityMachineBase;
import com.hbm.uninos.DirPos;

import api.hbm.energy.IEnergyUser;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;

/**
 * Base class for cooled machines - Adapted for 1.12.2 with Forge FluidTank system.
 *
 * Provides temperature management and coolant heat exchange functionality.
 * Child classes must implement getConPos() to specify fluid connection positions.
 *
 * @author Adapted for 1.12.2
 */
public abstract class TileEntityCooledBase extends TileEntityMachineBase implements IEnergyUser, ITickable, IFluidHandler {

	public FluidTank[] coolantTanks; // originally just named "tanks" which would confuse the fuck out of me when working with child classes

	public long power;

	public static final float KELVIN = 273F;
	public float temperature = KELVIN + 20;
	public static final float temperature_target = KELVIN - 150F;
	public static final float temp_change_per_mb = 0.5F;
	public static final float temp_passive_heating = 2.5F;
	public static final float temp_change_max = 5F + temp_passive_heating;

	public TileEntityCooledBase(int slotCount) {
		super(slotCount);
		coolantTanks = new FluidTank[2];
		coolantTanks[0] = new FluidTank(4_000); // Cold coolant input
		coolantTanks[1] = new FluidTank(4_000); // Hot coolant output

		// Set fluid types if they exist
		if(ModForgeFluids.PERFLUOROMETHYL_COLD != null) {
			coolantTanks[0].setFluid(new FluidStack(ModForgeFluids.PERFLUOROMETHYL_COLD, 0));
		}
		if(ModForgeFluids.PERFLUOROMETHYL_HOT != null) {
			coolantTanks[1].setFluid(new FluidStack(ModForgeFluids.PERFLUOROMETHYL_HOT, 0));
		}
	}

	@Override
	public void update() {

		if(!world.isRemote) {
			// Temperature increases passively
			this.temperature += this.temp_passive_heating;
			if(this.temperature > KELVIN + 20) this.temperature = KELVIN + 20;

			// Cool down if temperature exceeds target and coolant is available
			if(this.temperature > this.temperature_target) {
				int cyclesTemp = (int) Math.ceil((Math.min(this.temperature - temperature_target, temp_change_max)) / temp_change_per_mb);
				int cyclesCool = coolantTanks[0].getFluidAmount();
				int cyclesHot = coolantTanks[1].getCapacity() - coolantTanks[1].getFluidAmount();
				int cycles = Math.min(Math.min(cyclesTemp, cyclesCool), cyclesHot);

				if(cycles > 0) {
					coolantTanks[0].drain(cycles, true);

					// Fill hot tank with hot coolant
					if(ModForgeFluids.PERFLUOROMETHYL_HOT != null) {
						coolantTanks[1].fill(new FluidStack(ModForgeFluids.PERFLUOROMETHYL_HOT, cycles), true);
					}

					this.temperature -= this.temp_change_per_mb * cycles;
				}
			}

			// Network sync
			NBTTagCompound data = new NBTTagCompound();
			data.setFloat("temperature", temperature);
			data.setLong("power", power);
			for(int i = 0; i < 2; i++) {
				if(coolantTanks[i].getFluid() != null) {
					data.setInteger("tank" + i, coolantTanks[i].getFluidAmount());
				}
			}
			this.networkPack(data, 50);
		}
	}

	public boolean isCool() {
		return this.temperature <= this.temperature_target;
	}

	/**
	 * Returns an array of connection positions for fluid/energy transfer.
	 * Child classes must implement this to specify where pipes can connect.
	 */
	public abstract DirPos[] getConPos();

	@Override
	public void networkUnpack(NBTTagCompound nbt) {
		super.networkUnpack(nbt);

		this.temperature = nbt.getFloat("temperature");
		this.power = nbt.getLong("power");

		for(int i = 0; i < 2; i++) {
			if(nbt.hasKey("tank" + i)) {
				int amount = nbt.getInteger("tank" + i);
				FluidStack fluid = coolantTanks[i].getFluid();
				if(fluid != null) {
					fluid.amount = amount;
				} else {
					// Initialize with correct fluid type
					if(i == 0 && ModForgeFluids.PERFLUOROMETHYL_COLD != null) {
						coolantTanks[i].setFluid(new FluidStack(ModForgeFluids.PERFLUOROMETHYL_COLD, amount));
					} else if(i == 1 && ModForgeFluids.PERFLUOROMETHYL_HOT != null) {
						coolantTanks[i].setFluid(new FluidStack(ModForgeFluids.PERFLUOROMETHYL_HOT, amount));
					}
				}
			}
		}
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt) {
		super.readFromNBT(nbt);

		if(nbt.hasKey("ct0")) {
			coolantTanks[0].readFromNBT(nbt.getCompoundTag("ct0"));
		}
		if(nbt.hasKey("ct1")) {
			coolantTanks[1].readFromNBT(nbt.getCompoundTag("ct1"));
		}

		this.temperature = nbt.getFloat("temperature");
		this.power = nbt.getLong("power");
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
		super.writeToNBT(nbt);

		if(coolantTanks[0].getFluid() != null) {
			nbt.setTag("ct0", coolantTanks[0].writeToNBT(new NBTTagCompound()));
		}
		if(coolantTanks[1].getFluid() != null) {
			nbt.setTag("ct1", coolantTanks[1].writeToNBT(new NBTTagCompound()));
		}

		nbt.setFloat("temperature", temperature);
		nbt.setLong("power", power);

		return nbt;
	}

	// IEnergyUser implementation
	@Override public long getPower() { return this.power; }
	@Override public void setPower(long power) { this.power = power; }
	@Override public long getMaxPower() { return 0; } // Override in child classes

	// ===== IFluidHandler Implementation (Coolant System) =====

	@Override
	public IFluidTankProperties[] getTankProperties() {
		// Use FluidTank's default getTankProperties() implementation
		// This reports all tanks as fillable and drainable
		// Actual input/output control is done in fill() and drain() methods
		IFluidTankProperties[] props = new IFluidTankProperties[2];
		props[0] = coolantTanks[0].getTankProperties()[0];
		props[1] = coolantTanks[1].getTankProperties()[0];
		return props;
	}

	@Override
	public int fill(FluidStack resource, boolean doFill) {
		if(resource == null || resource.getFluid() != ModForgeFluids.PERFLUOROMETHYL_COLD) {
			return 0;
		}
		return coolantTanks[0].fill(resource, doFill);
	}

	@Override
	public FluidStack drain(FluidStack resource, boolean doDrain) {
		if(resource == null || resource.getFluid() != ModForgeFluids.PERFLUOROMETHYL_HOT) {
			return null;
		}
		return coolantTanks[1].drain(resource.amount, doDrain);
	}

	@Override
	public FluidStack drain(int maxDrain, boolean doDrain) {
		return coolantTanks[1].drain(maxDrain, doDrain);
	}

	// ===== Capability Support =====

	@Override
	public boolean hasCapability(Capability<?> capability, EnumFacing facing) {
		return capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY || super.hasCapability(capability, facing);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T getCapability(Capability<T> capability, EnumFacing facing) {
		if(capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
			return (T) this;
		}
		return super.getCapability(capability, facing);
	}
}
