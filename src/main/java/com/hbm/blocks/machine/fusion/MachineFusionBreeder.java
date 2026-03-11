package com.hbm.blocks.machine.fusion;

import javax.annotation.Nullable;
import java.util.List;

import com.hbm.blocks.BlockDummyable;
import com.hbm.blocks.ITooltipProvider;
import com.hbm.lib.ForgeDirection;
import com.hbm.main.tileentity.TileEntityProxyCombo;
import com.hbm.main.tileentity.machine.fusion.TileEntityFusionBreeder;

import net.minecraft.block.material.Material;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import com.hbm.blocks.ModBlocks;

/**
 * Fusion Breeder Block - 7x4x7 structure.
 * @author Adapted for 1.12.2
 */
public class MachineFusionBreeder extends BlockDummyable implements ITooltipProvider {

	public MachineFusionBreeder(String s) {
		super(Material.IRON, s);
	}

	@Override
	public TileEntity createNewTileEntity(World world, int meta) {
		// Core block with full functionality
		if(meta >= 12) return new TileEntityFusionBreeder();

		// Extra blocks for inventory and fluid connections
		if(meta >= extra) {
			return new TileEntityProxyCombo(true, false, true); // inventory + fluid capability
		}

		return null;
	}

	@Override
	public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
		return standardOpenBehavior(world, pos.getX(), pos.getY(), pos.getZ(), player, ModBlocks.guiID_fusion_breeder);
	}

	@Override
	public int[] getDimensions() {
		return new int[] { 3, 0, 2, 2, 1, 1 };
	}

	@Override
	public int getOffset() {
		return 2;
	}

	/**
	 * Fills the multiblock structure and marks special blocks for fluid connections.
	 * Creates extra blocks at the 5 connection points around the breeder.
	 * MUST match 1.7.10 version exactly!
	 */
	@Override
	public void fillSpace(World world, int x, int y, int z, ForgeDirection dir, int o) {
		// Fill main structure
		super.fillSpace(world, x, y, z, dir, o);

		// Offset to core position
		x += dir.offsetX * o;
		z += dir.offsetZ * o;

		// Convert ForgeDirection to EnumFacing for calculations
		EnumFacing facing = EnumFacing.byIndex(dir.ordinal());
		EnumFacing rot = facing.rotateY();

		// Mark extra blocks at the 5 fluid connection points
		// Exact positions from 1.7.10:
		this.makeExtra(world, x + rot.getXOffset(), y, z + rot.getZOffset());
		this.makeExtra(world, x - rot.getXOffset(), y, z - rot.getZOffset());
		this.makeExtra(world, x + facing.getXOffset() + rot.getXOffset(), y, z + facing.getZOffset() + rot.getZOffset());
		this.makeExtra(world, x + facing.getXOffset() - rot.getXOffset(), y, z + facing.getZOffset() - rot.getZOffset());
		this.makeExtra(world, x + facing.getXOffset() * 2, y + 2, z + facing.getZOffset() * 2);
	}

	@Override
	public void addInformation(ItemStack stack, @Nullable World player, List<String> tooltip, ITooltipFlag advanced) {
		this.addStandardInfo(tooltip);
	}
}
