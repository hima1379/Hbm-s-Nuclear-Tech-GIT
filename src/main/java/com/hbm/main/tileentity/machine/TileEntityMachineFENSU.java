package com.hbm.main.tileentity.machine;

import com.hbm.lib.ForgeDirection;

import api.hbm.energy.EnergyValue;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;

public class TileEntityMachineFENSU extends TileEntityMachineBattery {

	public EnumDyeColor color = EnumDyeColor.LIGHT_BLUE;

	public static final long maxTransfer = 10_000_000_000_000_000L; //10E
	//									9,223,372,036,854,775,807 is long max
	
	public float prevRotation = 0F;
	public float rotation = 0F;

	/**
	 * FENSU constructor - Initialize with BigInteger mode for power storage
	 */
	public TileEntityMachineFENSU() {
		super();
		// Force BigInteger mode even at zero, ensuring FENSU can accumulate beyond Long.MAX_VALUE
		this.setPowerEV(EnergyValue.zeroBigInteger());
	}

	@Override
	public void update() {
		if(!world.isRemote) {
			// FENSU special handling for battery item charging to support BigInteger
			// We need to override the parent's update() to handle charging beyond Long.MAX_VALUE

			// Clamp power to max capacity
			if(this.getPowerEV().isGreaterThan(this.getMaxPowerEV())) {
				this.setPowerEV(this.getMaxPowerEV());
			}
			EnergyValue prevPower = this.getPowerEV();

			if(inventory.getSlots() < 3){
				inventory = this.getNewInventory(4, 64);
			}

			// FENSU SPECIAL: Battery item charging with BigInteger support
			// Strategy: Always pretend we have charging headroom, then add the delta
			long fakeCurrentPower = Math.min(this.getPowerEV().toLongClamped(), Long.MAX_VALUE - maxTransfer);
			long fakeMaxPower = Long.MAX_VALUE;
			long newPowerFromItems = com.hbm.lib.Library.chargeTEFromItems(inventory, 0, fakeCurrentPower, fakeMaxPower);
			long chargedAmount = newPowerFromItems - fakeCurrentPower;
			if(chargedAmount > 0) {
				this.setPowerEV(this.getPowerEV().add(EnergyValue.of(chargedAmount)));
				// Clamp to max capacity
				if(this.getPowerEV().isGreaterThan(this.getMaxPowerEV())) {
					this.setPowerEV(this.getMaxPowerEV());
				}
				System.out.println("[FENSU] Charged " + chargedAmount + " HE from battery item, total: " + this.getPowerEV());
			}

			//////////////////////////////////////////////////////////////////////
			this.transmitPowerFairly();
			//////////////////////////////////////////////////////////////////////

			// FENSU SPECIAL: Battery item discharging with BigInteger support
			long fakeCurrentPowerDischarge = Math.min(this.getPowerEV().toLongClamped(), Long.MAX_VALUE);
			long newPowerAfterDischarge = com.hbm.lib.Library.chargeItemsFromTE(inventory, 2, fakeCurrentPowerDischarge, Long.MAX_VALUE);
			long dischargedAmount = fakeCurrentPowerDischarge - newPowerAfterDischarge;
			if(dischargedAmount > 0) {
				this.setPowerEV(this.getPowerEV().subtract(EnergyValue.of(dischargedAmount)));
				System.out.println("[FENSU] Discharged " + dischargedAmount + " HE to battery item, total: " + this.getPowerEV());
			}

			byte comp = this.getComparatorPower();
			if(comp != this.lastRedstone)
				this.markDirty();
			this.lastRedstone = comp;

			tryMoveItems();

			// Calculate power delta using the same logic as parent class
			// Use average of current and previous power for smooth delta tracking
			long currentPowerClamped = this.getPowerEV().toLongClamped();
			long prevPowerClamped = prevPower.toLongClamped();
			long avg = (currentPowerClamped >> 1) + (prevPowerClamped >> 1);

			this.powerDeltaEV = EnergyValue.of(avg - this.log[0]);
			for(int i = 1; i < this.log.length; i++) {
				this.log[i - 1] = this.log[i];
			}
			this.log[this.log.length-1] = avg;

			this.networkPack(packNBT(), 20);
		} else {
			// Client side: rotation animation
			this.prevRotation = this.rotation;
			this.rotation += this.getSpeed();

			if(rotation >= 360) {
				rotation -= 360;
				prevRotation -= 360;
			}
		}
	}

	public static ForgeDirection[] getSendDirections(){
		return new ForgeDirection[]{ForgeDirection.DOWN};
	}

	@Override
	public NBTTagCompound packNBT(){
		NBTTagCompound nbt = super.packNBT();
		nbt.setByte("color", (byte) this.color.getMetadata());
		return nbt;
	}

	@Override
	public void networkUnpack(NBTTagCompound nbt) {
		super.networkUnpack(nbt);
		this.color = EnumDyeColor.byMetadata(nbt.getByte("color"));
	}

	@Override
	public long getPowerRemainingScaled(long i) {
		// Use parent's implementation which handles EnergyValue correctly
		return super.getPowerRemainingScaled(i);
	}

	@Override
	public long getTransferWeight() {
		return Math.min(super.getTransferWeight(), maxTransfer);
	}

	public float getSpeed() {
		return (float) Math.pow(Math.log(getPower() * 0.75 + 1) * 0.05F, 5);
	}

	@Override
	public AxisAlignedBB getRenderBoundingBox() {
		return TileEntity.INFINITE_EXTENT_AABB;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public double getMaxRenderDistanceSquared() {
		return 65536.0D;
	}

	@Override
	public void readFromNBT(NBTTagCompound compound) {
		this.color = EnumDyeColor.byMetadata(compound.getByte("color"));
		super.readFromNBT(compound);
	}
	
	@Override
	public @NotNull NBTTagCompound writeToNBT(NBTTagCompound compound) {
		compound.setByte("color", (byte) this.color.getMetadata());
		return super.writeToNBT(compound);
	}

	@Override
	public long getMaxPower() {
		// Return Long.MAX_VALUE for backward compatibility with long-based systems
		// The real capacity is 10^100, accessible via getMaxPowerEV()
		return Long.MAX_VALUE;
	}

	@Override
	public EnergyValue getMaxPowerEV() {
		// FENSU true capacity: 10^100 HE (1 googol HE)
		// This allows FENSU to store energy far beyond long range
		return EnergyValue.of(new BigInteger("10").pow(100));
	}
}