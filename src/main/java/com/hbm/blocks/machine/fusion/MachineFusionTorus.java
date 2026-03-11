package com.hbm.blocks.machine.fusion;

import javax.annotation.Nullable;
import java.util.List;

import com.hbm.blocks.BlockDummyable;
import com.hbm.blocks.ITooltipProvider;
import com.hbm.blocks.ModBlocks;
import com.hbm.lib.ForgeDirection;
import com.hbm.main.MainRegistry;
import com.hbm.main.tileentity.TileEntityProxyCombo;
import com.hbm.main.tileentity.machine.fusion.TileEntityFusionTorus;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Fusion Torus Block - 15x5x15 toroidal structure (simplified for 1.12.2).
 *
 * @author Adapted for 1.12.2
 */
public class MachineFusionTorus extends BlockDummyable implements ITooltipProvider {

	public MachineFusionTorus(String s) {
		super(Material.IRON, s);
	}

	@Override
	public TileEntity createNewTileEntity(World world, int meta) {
		// Core block with full functionality
		if(meta >= 12) {
			return new TileEntityFusionTorus();
		}

		// Extra blocks for inventory, power, and fluid connections
		// IMPORTANT: Use constructor with parameters instead of fluent API
		// This ensures flags are preserved after world save/load
		if(meta >= extra) {
			return new TileEntityProxyCombo(true, true, true); // inventory, power, fluid
		}

		return null;
	}

	@Override
	public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
		return standardOpenBehavior(world, pos.getX(), pos.getY(), pos.getZ(), player, ModBlocks.guiID_fusion_torus);
	}

	/**
	 * Main structure dimensions: 15x5x15 toroid
	 * [right, up, forward, left, down, back]
	 */
	@Override
	public int[] getDimensions() {
		return new int[] { 4, 0, 7, 7, 7, 7 };
	}

	@Override
	public int getOffset() {
		return 7;
	}

	/**
	 * Fills the multiblock structure and marks special blocks for fluid/power connections.
	 * Creates extra blocks at strategic positions around the torus for pipe connections.
	 */
	@Override
	public void fillSpace(World world, int x, int y, int z, ForgeDirection dir, int o) {
		// Fill main structure
		super.fillSpace(world, x, y, z, dir, o);

		// Offset to core position
		x += dir.offsetX * o;
		z += dir.offsetZ * o;

		// Mark extra blocks for fluid/power connections around the torus
		// Top center
		this.makeExtra(world, x, y + 4, z);

		// Right side (+X direction) - 6 connection points
		this.makeExtra(world, x + 6, y, z);
		this.makeExtra(world, x + 6, y + 4, z);
		this.makeExtra(world, x + 6, y, z + 2);
		this.makeExtra(world, x + 6, y + 4, z + 2);
		this.makeExtra(world, x + 6, y, z - 2);
		this.makeExtra(world, x + 6, y + 4, z - 2);

		// Left side (-X direction) - 6 connection points
		this.makeExtra(world, x - 6, y, z);
		this.makeExtra(world, x - 6, y + 4, z);
		this.makeExtra(world, x - 6, y, z + 2);
		this.makeExtra(world, x - 6, y + 4, z + 2);
		this.makeExtra(world, x - 6, y, z - 2);
		this.makeExtra(world, x - 6, y + 4, z - 2);

		// Front side (+Z direction) - 6 connection points
		this.makeExtra(world, x, y, z + 6);
		this.makeExtra(world, x, y + 4, z + 6);
		this.makeExtra(world, x + 2, y, z + 6);
		this.makeExtra(world, x + 2, y + 4, z + 6);
		this.makeExtra(world, x - 2, y, z + 6);
		this.makeExtra(world, x - 2, y + 4, z + 6);

		// Back side (-Z direction) - 6 connection points
		this.makeExtra(world, x, y, z - 6);
		this.makeExtra(world, x, y + 4, z - 6);
		this.makeExtra(world, x + 2, y, z - 6);
		this.makeExtra(world, x + 2, y + 4, z - 6);
		this.makeExtra(world, x - 2, y, z - 6);
		this.makeExtra(world, x - 2, y + 4, z - 6);
	}

	@Override
	public void addInformation(ItemStack stack, @Nullable World player, List<String> tooltip, ITooltipFlag advanced) {
		this.addStandardInfo(tooltip);
	}
}
