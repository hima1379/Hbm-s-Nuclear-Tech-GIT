package com.hbm.main.tileentity;

import api.hbm.energy.IEnergyUser;

import api.hbm.tile.IHeatSource;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.items.CapabilityItemHandler;

public class TileEntityProxyCombo extends TileEntityProxyBase implements IEnergyUser, IHeatSource {

	TileEntity tile;
	boolean inventory;
	boolean power;
	boolean fluid;

	boolean heat;

	public TileEntityProxyCombo() {
	}

	public TileEntityProxyCombo(boolean inventory, boolean power, boolean fluid) {
		this.inventory = inventory;
		this.power = power;
		this.fluid = fluid;
		this.heat = false;
	}

	public TileEntityProxyCombo(boolean inventory, boolean power, boolean fluid, boolean heat) {
		this.inventory = inventory;
		this.power = power;
		this.fluid = fluid;
		this.heat = heat;
	}

	// Fluent API methods for easy chaining (1.7.10 compatibility)
	public TileEntityProxyCombo inventory() {
		this.inventory = true;
		return this;
	}

	public TileEntityProxyCombo power() {
		this.power = true;
		return this;
	}

	public TileEntityProxyCombo fluid() {
		this.fluid = true;
		return this;
	}

	public TileEntityProxyCombo heatSource() {
		this.heat = true;
		return this;
	}

	// fewer messy recursive operations
	public TileEntity getTile() {

		if(tile == null) {
			tile = this.getTE();
		}

		return tile;
	}

	@Override
	public <T> T getCapability(Capability<T> capability, EnumFacing facing) {
		if(tile == null) {
			tile = this.getTE();
			if(tile == null){
				System.out.println("[ProxyCombo] getCapability: Core tile is null at " + pos);
				return super.getCapability(capability, facing);
			}
		}
		if(inventory && capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY){
			System.out.println("[ProxyCombo] getCapability: Returning core's ITEM_HANDLER");
			return tile.getCapability(capability, facing);
		}
		if(power && capability == CapabilityEnergy.ENERGY){
			System.out.println("[ProxyCombo] getCapability: Returning core's ENERGY");
			return tile.getCapability(capability, facing);
		}
		if(fluid && capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY){
			System.out.println("[ProxyCombo] getCapability: Returning core's FLUID_HANDLER (fluid flag=" + fluid + ")");
			return tile.getCapability(capability, facing);
		}
		return super.getCapability(capability, facing);
	}

	@Override
	public boolean hasCapability(Capability<?> capability, EnumFacing facing) {
		if(tile == null) {
			tile = this.getTE();
			if(tile == null){
				System.out.println("[ProxyCombo] hasCapability: Core tile is null at " + pos);
				return super.hasCapability(capability, facing);
			}
		}
		if(inventory && capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY){
			System.out.println("[ProxyCombo] hasCapability: ITEM_HANDLER -> true (inventory=" + inventory + ")");
			return tile.hasCapability(capability, facing);
		}
		if(power && capability == CapabilityEnergy.ENERGY){
			System.out.println("[ProxyCombo] hasCapability: ENERGY -> true (power=" + power + ")");
			return tile.hasCapability(capability, facing);
		}
		if(fluid && capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY){
			System.out.println("[ProxyCombo] hasCapability: FLUID_HANDLER -> " + fluid + " (fluid flag=" + fluid + ")");
			return tile.hasCapability(capability, facing);
		}
		System.out.println("[ProxyCombo] hasCapability: No match, returning false (inv=" + inventory + ", pow=" + power + ", flu=" + fluid + ")");
		return super.hasCapability(capability, facing);
	}

	@Override
	public void setPower(long i) {

		if(!power)
			return;

		if(getTile() instanceof IEnergyUser) {
			((IEnergyUser)getTile()).setPower(i);
		}
	}

	@Override
	public long getPower() {

		if(!power)
			return 0;

		if(getTile() instanceof IEnergyUser) {
			return ((IEnergyUser)getTile()).getPower();
		}

		return 0;
	}

	@Override
	public long getMaxPower() {

		if(!power)
			return 0;

		if(getTile() instanceof IEnergyUser) {
			return ((IEnergyUser)getTile()).getMaxPower();
		}

		return 0;
	}

	@Override
	public void readFromNBT(NBTTagCompound compound) {
		// Only override flags if they exist in NBT
		// This preserves flags set via fluent API in createNewTileEntity()
		if(compound.hasKey("inv")) {
			inventory = compound.getBoolean("inv");
		}
		if(compound.hasKey("flu")) {
			fluid = compound.getBoolean("flu");
		}
		if(compound.hasKey("pow")) {
			power = compound.getBoolean("pow");
		}
		if(compound.hasKey("hea")) {
			heat = compound.getBoolean("hea");
		}

		System.out.println("[ProxyCombo] readFromNBT at " + pos + ": inv=" + inventory + ", flu=" + fluid + ", pow=" + power + ", hea=" + heat);
		super.readFromNBT(compound);
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound compound) {
		compound.setBoolean("inv", inventory);
		compound.setBoolean("flu", fluid);
		compound.setBoolean("pow", power);
		compound.setBoolean("hea", heat);
		System.out.println("[ProxyCombo] writeToNBT at " + pos + ": inv=" + inventory + ", flu=" + fluid + ", pow=" + power + ", hea=" + heat);
		return super.writeToNBT(compound);
	}

	@Override
	public NBTTagCompound getUpdateTag() {
		return writeToNBT(new NBTTagCompound());
	}

	@Override
	public SPacketUpdateTileEntity getUpdatePacket() {
		return new SPacketUpdateTileEntity(pos, 0, getUpdateTag());
	}

	@Override
	public void handleUpdateTag(NBTTagCompound tag) {
		this.readFromNBT(tag);
	}

	@Override
	public int getHeatStored() {
		if (!this.heat) {
			return 0;
		}

		if (getTile() instanceof IHeatSource) {
			return ((IHeatSource) getTile()).getHeatStored();
		}
		return 0;
	}

	@Override
	public void useUpHeat(int heat) {
		if (!this.heat) {
			return;
		}

		if (getTile() instanceof IHeatSource) {
			((IHeatSource) getTile()).useUpHeat(heat);
		}
	}
}
