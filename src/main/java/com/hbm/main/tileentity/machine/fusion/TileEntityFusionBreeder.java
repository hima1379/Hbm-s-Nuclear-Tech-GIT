package com.hbm.main.tileentity.machine.fusion;

import com.hbm.inventory.RBMKOutgasserRecipes;
import com.hbm.items.ModItems;
import com.hbm.items.machine.ItemFluidIcon;
import com.hbm.main.tileentity.TileEntityMachineBase;
import com.hbm.uninos.DirPos;
import com.hbm.uninos.GenNode;
import com.hbm.uninos.UniNodespace;
import com.hbm.uninos.networkproviders.PlasmaNetworkProvider;

import io.netty.buffer.ByteBuf;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Fusion Breeder - Uses neutron flux to breed fuel (adapted for 1.12.2).
 * @author Adapted for 1.12.2
 */
public class TileEntityFusionBreeder extends TileEntityMachineBase implements IFusionPowerReceiver, ITickable, IFluidHandler {

	protected GenNode plasmaNode;

	public FluidTank[] tanks;

	public double neutronEnergy;
	public double neutronEnergySync;
	public double progress;
	public static final double capacity = 10_000D;

	public TileEntityFusionBreeder() {
		super(3); // 3 inventory slots

		tanks = new FluidTank[2];
		tanks[0] = new FluidTank(16_000); // Input tank
		tanks[1] = new FluidTank(16_000); // Output tank
	}

	@Override
	public String getName() {
		return "container.fusionBreeder";
	}

	@Override
	public void update() {
		if(!world.isRemote) {
			// Save neutron energy for sync (batched packets)
			this.neutronEnergySync = this.neutronEnergy;

			// Create or get plasma node
			if(plasmaNode == null || plasmaNode.expired) {
				EnumFacing dir = EnumFacing.byIndex(this.getBlockMetadata() - 10).getOpposite();
				int nodeX = pos.getX() + dir.getXOffset() * 2;
				int nodeY = pos.getY() + 2;
				int nodeZ = pos.getZ() + dir.getZOffset() * 2;

				plasmaNode = UniNodespace.getNode(world, nodeX, nodeY, nodeZ, PlasmaNetworkProvider.THE_PROVIDER);

				if(plasmaNode == null) {
					BlockPos nodePos = new BlockPos(nodeX, nodeY, nodeZ);
					BlockPos connectionPos = new BlockPos(
						nodeX + dir.getXOffset(),
						nodeY,
						nodeZ + dir.getZOffset()
					);

					plasmaNode = new GenNode(PlasmaNetworkProvider.THE_PROVIDER, nodePos)
						.setConnections(new DirPos(connectionPos, dir));

					UniNodespace.createNode(world, plasmaNode);
				}
			}

			// Register as fusion power receiver
			if(plasmaNode != null && plasmaNode.hasValidNet()) {
				plasmaNode.net.addReceiver(this);
			}

			// Send output fluids to adjacent blocks via connection points
			for(DirPos conPos : getConPos()) {
				if(tanks[1].getFluidAmount() > 0) {
					BlockPos targetPos = conPos.getPos().offset(conPos.getDir());
					com.hbm.forgefluid.FFUtils.fillFluid(this, tanks[1], world, targetPos, 1000);
				}
			}

			// Reset neutron energy for next tick
			this.neutronEnergy = 0;
		}
	}

	public void doProgress() {
		if(!canProcess()) {
			this.progress = 0;
			return;
		}

		this.progress += this.neutronEnergy;

		int requiredFlux = RBMKOutgasserRecipes.getRequiredFlux(inventory.getStackInSlot(1));

		// Debug logging every second - show realistic breeding progress
		if(world.getTotalWorldTime() % 20 == 0 && !inventory.getStackInSlot(1).isEmpty()) {
			double progressPercent = (requiredFlux > 0) ? (progress / requiredFlux * 100.0) : 0.0;
			double ticksRemaining = (this.neutronEnergy > 0 && requiredFlux > 0) ?
				(requiredFlux - progress) / this.neutronEnergy : -1.0;
			double secondsRemaining = ticksRemaining / 20.0;

			System.out.println("[FusionBreeder] ===== BREEDING PROGRESS =====");
			System.out.println("[FusionBreeder] Item: " + inventory.getStackInSlot(1).getDisplayName());
			System.out.println("[FusionBreeder] Neutron flux: " + String.format("%.4f", this.neutronEnergy) + " recipe units/tick");
			System.out.println("[FusionBreeder] Progress: " + String.format("%.2f", progress) + " / " + requiredFlux +
				" (" + String.format("%.1f%%", progressPercent) + ")");
			if(secondsRemaining > 0) {
				System.out.println("[FusionBreeder] Est. time remaining: " + String.format("%.1f", secondsRemaining) + " seconds");
			}
		}

		if(requiredFlux > 0 && progress >= requiredFlux) {
			process();
			System.out.println("[FusionBreeder] ***** PROCESSING COMPLETE! ***** Converted: " + inventory.getStackInSlot(1).getDisplayName());
			this.markDirty();
		}
	}

	/**
	 * Checks if the breeder can process the current input.
	 * Uses RBMKOutgasserRecipes system (same as RBMK Outgasser).
	 */
	private boolean canProcess() {
		// Check if input slot has an item
		if(inventory.getStackInSlot(1).isEmpty())
			return false;

		// Check if recipe exists
		int requiredFlux = RBMKOutgasserRecipes.getRequiredFlux(inventory.getStackInSlot(1));
		if(requiredFlux == -1)
			return false;

		ItemStack output = RBMKOutgasserRecipes.getOutput(inventory.getStackInSlot(1));
		if(output == null)
			return false;

		// Check if output is fluid
		if(output.getItem() == ModItems.fluid_icon) {
			Fluid fluidType = ItemFluidIcon.getFluid(output);
			int amount = ItemFluidIcon.getQuantity(output);

			if(fluidType == null)
				return false;

			// Check if output tank has wrong fluid type
			if(tanks[1].getFluid() != null && tanks[1].getFluid().getFluid() != fluidType)
				return false;

			// Check if output tank has space
			if(tanks[1].getFluidAmount() + amount > tanks[1].getCapacity())
				return false;

			return true;
		}

		// Check if output is item
		if(inventory.getStackInSlot(2).isEmpty())
			return true;

		return inventory.getStackInSlot(2).getItem() == output.getItem()
			&& inventory.getStackInSlot(2).getItemDamage() == output.getItemDamage()
			&& inventory.getStackInSlot(2).getCount() + output.getCount() <= inventory.getStackInSlot(2).getMaxStackSize();
	}

	/**
	 * Processes the breeding recipe.
	 * Consumes input and produces output (item or fluid).
	 */
	private void process() {
		ItemStack output = RBMKOutgasserRecipes.getOutput(inventory.getStackInSlot(1));
		inventory.getStackInSlot(1).shrink(1);
		this.progress = 0;

		// Handle fluid output
		if(output.getItem() == ModItems.fluid_icon) {
			Fluid fluidType = ItemFluidIcon.getFluid(output);
			int amount = ItemFluidIcon.getQuantity(output);
			tanks[1].fill(new FluidStack(fluidType, amount), true);
			return;
		}

		// Handle item output
		if(inventory.getStackInSlot(2).isEmpty()) {
			inventory.setStackInSlot(2, output.copy());
		} else {
			inventory.getStackInSlot(2).grow(output.getCount());
		}
	}

	@Override
	public boolean receivesFusionPower() {
		return false; // Only uses neutrons, not plasma power
	}

	@Override
	public void receiveFusionPower(double thermalPowerWatts, double plasmaTemperatureK, double neutronFlux) {
		// NEW: Receive physics-based values from Torus
		// - thermalPowerWatts: Ignored by Breeder (only uses neutrons)
		// - plasmaTemperatureK: Ignored by Breeder
		// - neutronFlux: Neutron flux for nuclear transmutation

		this.neutronEnergy = neutronFlux;

		// Debug logging for realistic neutron flux (every second)
		if(world != null && !world.isRemote && world.getTotalWorldTime() % 20 == 0 && neutronFlux > 0) {
			System.out.println("[FusionBreeder] Receiving realistic neutron flux:");
			System.out.println("[FusionBreeder]   Thermal power: " + String.format("%.2e W (%.1f MW)", thermalPowerWatts, thermalPowerWatts/1e6) + " (ignored)");
			System.out.println("[FusionBreeder]   Neutron flux (recipe flux): " + String.format("%.4f", neutronFlux) + " units/tick");
			System.out.println("[FusionBreeder]   Current progress: " + String.format("%.2f", progress) + " / " +
				RBMKOutgasserRecipes.getRequiredFlux(inventory.getStackInSlot(1)));
		}

		doProgress();
	}

	@Override
	public void invalidate() {
		super.invalidate();
		if(!world.isRemote) {
			if(this.plasmaNode != null) {
				UniNodespace.destroyNode(world, plasmaNode);
			}
		}
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
		super.writeToNBT(nbt);

		nbt.setDouble("progress", this.progress);
		
		if(tanks[0].getFluid() != null) {
			NBTTagCompound tank0 = new NBTTagCompound();
			tanks[0].writeToNBT(tank0);
			nbt.setTag("tank0", tank0);
		}
		
		if(tanks[1].getFluid() != null) {
			NBTTagCompound tank1 = new NBTTagCompound();
			tanks[1].writeToNBT(tank1);
			nbt.setTag("tank1", tank1);
		}

		return nbt;
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt) {
		super.readFromNBT(nbt);

		this.progress = nbt.getDouble("progress");
		
		if(nbt.hasKey("tank0")) {
			tanks[0].readFromNBT(nbt.getCompoundTag("tank0"));
		}
		
		if(nbt.hasKey("tank1")) {
			tanks[1].readFromNBT(nbt.getCompoundTag("tank1"));
		}
	}

	// Network synchronization will be handled by NBT packets

	/**
	 * Returns connection positions for fluid input/output.
	 * 5 connection points around the breeder structure - matches 1.7.10 exactly!
	 */
	public DirPos[] getConPos() {
		EnumFacing dir = EnumFacing.byIndex(this.getBlockMetadata() - 10);
		EnumFacing rot = dir.rotateY();

		return new DirPos[] {
			new DirPos(pos.add(dir.getXOffset() * 3, 2, dir.getZOffset() * 3), dir),
			new DirPos(pos.add(rot.getXOffset() * 2, 0, rot.getZOffset() * 2), rot),
			new DirPos(pos.add(-rot.getXOffset() * 2, 0, -rot.getZOffset() * 2), rot.getOpposite()),
			new DirPos(pos.add(dir.getXOffset() + rot.getXOffset() * 2, 0, dir.getZOffset() + rot.getZOffset() * 2), rot),
			new DirPos(pos.add(dir.getXOffset() - rot.getXOffset() * 2, 0, dir.getZOffset() - rot.getZOffset() * 2), rot.getOpposite())
		};
	}

	// ===== IFluidHandler Implementation =====

	@Override
	public IFluidTankProperties[] getTankProperties() {
		return new IFluidTankProperties[] {
			tanks[0].getTankProperties()[0],
			tanks[1].getTankProperties()[0]
		};
	}

	@Override
	public int fill(FluidStack resource, boolean doFill) {
		// Accept any fluid in tank 0 (input tank for potential future coolant/etc)
		if(resource == null) {
			return 0;
		}
		int filled = tanks[0].fill(resource, doFill);
		if(filled > 0 && doFill) {
			this.markDirty();
		}
		return filled;
	}

	@Override
	public FluidStack drain(FluidStack resource, boolean doDrain) {
		// Only drain from tank 1 (output tank)
		if(resource == null) {
			return null;
		}
		FluidStack drained = tanks[1].drain(resource.amount, doDrain);
		if(drained != null && drained.amount > 0 && doDrain) {
			this.markDirty();
		}
		return drained;
	}

	@Override
	public FluidStack drain(int maxDrain, boolean doDrain) {
		// Drain from tank 1 (output tank)
		FluidStack drained = tanks[1].drain(maxDrain, doDrain);
		if(drained != null && drained.amount > 0 && doDrain) {
			this.markDirty();
		}
		return drained;
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

	private AxisAlignedBB bb = null;

	@Override
	public AxisAlignedBB getRenderBoundingBox() {
		if(bb == null) {
			bb = new AxisAlignedBB(
				pos.getX() - 2,
				pos.getY(),
				pos.getZ() - 2,
				pos.getX() + 3,
				pos.getY() + 4,
				pos.getZ() + 3
			);
		}
		return bb;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public double getMaxRenderDistanceSquared() {
		return 65536.0D;
	}
}
