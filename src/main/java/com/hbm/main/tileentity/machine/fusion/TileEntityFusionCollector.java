package com.hbm.main.tileentity.machine.fusion;

import com.hbm.uninos.DirPos;
import com.hbm.uninos.GenNode;
import com.hbm.uninos.UniNodespace;
import com.hbm.uninos.networkproviders.PlasmaNetworkProvider;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Fusion Collector - Provides a 0.5x bonus multiplier to the Fusion Torus.
 * This is the simplest fusion component with no GUI, inventory, or processing.
 * It simply connects to the Plasma network and registers itself as a receiver.
 *
 * @author Adapted for 1.12.2
 */
public class TileEntityFusionCollector extends TileEntity implements ITickable, IFusionPowerReceiver {

	/** Node for connecting to the Plasma network */
	protected GenNode plasmaNode;

	@Override
	public void update() {
		if(!world.isRemote) {
			// Create or retrieve the plasma node
			if(plasmaNode == null || plasmaNode.expired) {
				createPlasmaNode();
			}

			// Register this collector as a receiver on the network
			// The Torus counts receivers to calculate bonus speed
			if(plasmaNode != null && plasmaNode.hasValidNet()) {
				plasmaNode.net.addReceiver(this);
			}
		}
	}

	/**
	 * Creates the plasma node at the appropriate position.
	 * The node is positioned 2 blocks forward and 2 blocks up from the collector.
	 */
	protected void createPlasmaNode() {
		// Get facing direction from block metadata (subtract offset)
		EnumFacing dir = EnumFacing.byIndex(this.getBlockMetadata() - 10).getOpposite();

		int nodeX = pos.getX() + dir.getXOffset() * 2;
		int nodeY = pos.getY() + 2;
		int nodeZ = pos.getZ() + dir.getZOffset() * 2;

		// Try to get existing node first
		plasmaNode = UniNodespace.getNode(world, nodeX, nodeY, nodeZ, PlasmaNetworkProvider.THE_PROVIDER);

		// Create new node if none exists
		if(plasmaNode == null) {
			BlockPos nodePos = new BlockPos(nodeX, nodeY, nodeZ);

			// Connection point is one block further in the facing direction
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

	@Override
	public void invalidate() {
		super.invalidate();

		// Clean up the plasma node when the tile entity is removed
		if(!world.isRemote) {
			if(this.plasmaNode != null) {
				UniNodespace.destroyNode(world, plasmaNode);
			}
		}
	}

	// ===== IFusionPowerReceiver Implementation =====

	/**
	 * Collector does not receive fusion power, only provides bonus.
	 * It exists purely to be counted by the Torus for bonus calculations.
	 */
	@Override
	public boolean receivesFusionPower() {
		return false;
	}

	/**
	 * No-op since collector doesn't process power.
	 */
	@Override
	public void receiveFusionPower(double thermalPowerWatts, double plasmaTemperatureK, double neutronFlux) {
		// Collector doesn't process power, just provides bonus by being connected
	}

	// ===== Rendering =====

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
