package com.hbm.main.tileentity.machine.fusion;

import java.util.Map.Entry;

import com.hbm.main.tileentity.TileEntityLoadedBase;
import com.hbm.uninos.DirPos;
import com.hbm.uninos.GenNode;
import com.hbm.uninos.UniNodespace;
import com.hbm.uninos.networkproviders.KlystronNetwork;
import com.hbm.uninos.networkproviders.KlystronNetworkProvider;
import com.hbm.uninos.networkproviders.PlasmaNetworkProvider;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Fusion Coupler - Converts plasma energy back to klystron energy.
 * Allows recycling excess plasma power back into the reactor.
 *
 * Features:
 * - Receives plasma energy from Plasma network
 * - Converts it 1:1 to klystron energy
 * - Feeds klystron energy back to Torus
 * - No GUI, inventory, or processing
 *
 * @author Adapted for 1.12.2
 */
public class TileEntityFusionCoupler extends TileEntityLoadedBase implements IFusionPowerReceiver, ITickable {

	/** Klystron node (output side - right) */
	protected GenNode<KlystronNetwork> klystronNode;

	/** Plasma node (input side - left) */
	protected GenNode plasmaNode;

	@Override
	public void update() {
		if(!world.isRemote) {
			// Get facing direction
			EnumFacing dir = EnumFacing.byIndex(this.getBlockMetadata() - 10).getOpposite();
			EnumFacing rot = dir.rotateY();

			// Create/update klystron node (right side)
			if(klystronNode == null || klystronNode.expired) {
				int nodeX = pos.getX() + rot.getXOffset();
				int nodeY = pos.getY() + 2;
				int nodeZ = pos.getZ() + rot.getZOffset();

				klystronNode = (GenNode<KlystronNetwork>) UniNodespace.getNode(world, nodeX, nodeY, nodeZ, KlystronNetworkProvider.THE_PROVIDER);

				if(klystronNode == null) {
					BlockPos nodePos = new BlockPos(nodeX, nodeY, nodeZ);
					BlockPos connectionPos = new BlockPos(
						nodeX + rot.getXOffset(),
						nodeY,
						nodeZ + rot.getZOffset()
					);

					klystronNode = (GenNode<KlystronNetwork>) new GenNode<KlystronNetwork>(KlystronNetworkProvider.THE_PROVIDER, nodePos)
						.setConnections(new DirPos(connectionPos, rot));

					UniNodespace.createNode(world, klystronNode);
				}
			}

			// Create/update plasma node (left side)
			if(plasmaNode == null || plasmaNode.expired) {
				int nodeX = pos.getX() - rot.getXOffset();
				int nodeY = pos.getY() + 2;
				int nodeZ = pos.getZ() - rot.getZOffset();

				plasmaNode = UniNodespace.getNode(world, nodeX, nodeY, nodeZ, PlasmaNetworkProvider.THE_PROVIDER);

				if(plasmaNode == null) {
					BlockPos nodePos = new BlockPos(nodeX, nodeY, nodeZ);
					BlockPos connectionPos = new BlockPos(
						nodeX - rot.getXOffset(),
						nodeY,
						nodeZ - rot.getZOffset()
					);

					plasmaNode = new GenNode(PlasmaNetworkProvider.THE_PROVIDER, nodePos)
						.setConnections(new DirPos(connectionPos, rot.getOpposite()));

					UniNodespace.createNode(world, plasmaNode);
				}
			}

			// Register as provider on klystron network
			if(klystronNode.net != null) {
				klystronNode.net.addProvider(this);
			}

			// Register as receiver on plasma network
			if(plasmaNode.net != null) {
				plasmaNode.net.addReceiver(this);
			}
		}
	}

	@Override
	public boolean receivesFusionPower() {
		return true;
	}

	@Override
	public void receiveFusionPower(double thermalPowerWatts, double plasmaTemperatureK, double neutronFlux) {
		// Fusion power is handled by PowerBalance.java as alpha heating
		// Coupler only converts neutron energy to electricity (future implementation)
		// Do not add fusion power to klystronEnergy - that's for external heating only
	}

	@Override
	public void invalidate() {
		super.invalidate();

		if(!world.isRemote) {
			if(this.klystronNode != null) {
				UniNodespace.destroyNode(world, klystronNode);
			}
			if(this.plasmaNode != null) {
				UniNodespace.destroyNode(world, plasmaNode);
			}
		}
	}

	// ===== Rendering =====

	private AxisAlignedBB bb = null;

	@Override
	public AxisAlignedBB getRenderBoundingBox() {
		if(bb == null) {
			bb = new AxisAlignedBB(
				pos.getX() - 1,
				pos.getY(),
				pos.getZ() - 1,
				pos.getX() + 2,
				pos.getY() + 4,
				pos.getZ() + 2
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
