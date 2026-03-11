package com.hbm.main.tileentity.machine.fusion;

import com.hbm.inventory.recipes.FusionRecipes;
import com.hbm.main.MainRegistry;
import com.hbm.main.tileentity.TileEntityMachineBase;
import com.hbm.sound.AudioWrapper;
import com.hbm.uninos.GenNode;
import com.hbm.uninos.UniNodespace;
import com.hbm.uninos.networkproviders.KlystronNetwork;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Creative Fusion Klystron - Provides infinite klystron energy without power or air requirements.
 *
 * This creative-mode version of the klystron automatically outputs the maximum ignition
 * energy required by any registered fusion recipe, allowing instant ignition of any fuel
 * without resource consumption.
 *
 * Features:
 * - No GUI or inventory
 * - No power or air consumption
 * - Outputs FusionRecipes.maxInput KyU/t (highest ignition temp of all recipes)
 * - Visual feedback with fan animation and sound when connected to Torus
 *
 * @author Adapted for 1.12.2
 */
public class TileEntityFusionKlystronCreative extends TileEntityMachineBase implements ITickable {

	/** Network node for klystron energy distribution */
	protected GenNode<KlystronNetwork> klystronNode;

	/** Fan rotation angle (client-side animation) */
	public float fan;

	/** Previous fan angle for interpolation */
	public float prevFan;

	/** Fan rotation speed */
	public float fanSpeed;

	/** Fan acceleration rate */
	public static final float FAN_ACCELERATION = 0.125F;

	/** Connection status (for visual feedback) */
	public boolean isConnected = false;

	/** Looping sound effect */
	private AudioWrapper audio;

	public TileEntityFusionKlystronCreative() {
		super(0); // No inventory slots (no GUI, no battery)
	}

	@Override
	public void update() {
		if(!world.isRemote) {
			// Server-side logic

			// Handle klystron network node
			this.klystronNode = TileEntityFusionKlystron.handleKNode(klystronNode, this);

			// Provide maximum possible klystron energy (highest ignition temp)
			this.isConnected = TileEntityFusionKlystron.provideKyU(klystronNode, FusionRecipes.INSTANCE.maxInput);

			// Send network sync packet
			NBTTagCompound data = new NBTTagCompound();
			data.setBoolean("isConnected", isConnected);
			this.networkPack(data, 100);

		} else {
			// Client-side logic

			// Fan animation based on connection status
			if(this.isConnected) {
				this.fanSpeed += FAN_ACCELERATION;
			} else {
				this.fanSpeed -= FAN_ACCELERATION;
			}

			this.fanSpeed = MathHelper.clamp(this.fanSpeed, 0F, 5F);

			this.prevFan = this.fan;
			this.fan += this.fanSpeed;

			if(this.fan >= 360F) {
				this.fan -= 360F;
				this.prevFan -= 360F;
			}

			// Sound management
			if(this.fanSpeed > 0 && MainRegistry.proxy.me().getDistanceSq(pos.getX() + 0.5, pos.getY() + 2.5, pos.getZ() + 0.5) < 30 * 30) {
				float speed = this.fanSpeed / 5F;

				if(audio == null) {
					audio = MainRegistry.proxy.getLoopedSound(com.hbm.lib.HBMSoundHandler.fel, net.minecraft.util.SoundCategory.BLOCKS,
						(float)(pos.getX() + 0.5), (float)(pos.getY() + 2.5), (float)(pos.getZ() + 0.5),
						getVolume((int)speed), speed);
					audio.startSound();
				} else {
					audio.updateVolume(getVolume((int)speed));
					audio.updatePitch(speed);
					audio.keepAlive();
				}
			} else {
				if(audio != null) {
					if(audio.isPlaying()) audio.stopSound();
					audio = null;
				}
			}
		}
	}

	@Override
	public void networkUnpack(NBTTagCompound nbt) {
		super.networkUnpack(nbt);
		this.isConnected = nbt.getBoolean("isConnected");
	}

	@Override
	public String getName() {
		return "container.fusionKlystronCreative";
	}

	@Override
	public void onChunkUnload() {
		super.onChunkUnload();

		if(audio != null) {
			audio.stopSound();
			audio = null;
		}
	}

	@Override
	public void invalidate() {
		super.invalidate();

		if(audio != null) {
			audio.stopSound();
			audio = null;
		}

		if(!world.isRemote) {
			if(this.klystronNode != null) {
				UniNodespace.destroyNode(world, klystronNode);
			}
		}
	}

	// ===== Rendering =====

	private AxisAlignedBB bb = null;

	@Override
	public AxisAlignedBB getRenderBoundingBox() {
		if(bb == null) {
			bb = new AxisAlignedBB(
				pos.getX() - 4,
				pos.getY(),
				pos.getZ() - 4,
				pos.getX() + 5,
				pos.getY() + 5,
				pos.getZ() + 5
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
