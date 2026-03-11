package com.hbm.blocks.machine.fusion;

import javax.annotation.Nullable;
import java.util.List;

import com.hbm.blocks.BlockDummyable;
import com.hbm.blocks.ITooltipProvider;
import com.hbm.blocks.ModBlocks;
import com.hbm.handler.MultiblockHandlerXR;
import com.hbm.lib.ForgeDirection;
import com.hbm.main.MainRegistry;
import com.hbm.main.tileentity.TileEntityProxyCombo;
import com.hbm.main.tileentity.machine.fusion.TileEntityFusionKlystron;

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
 * Fusion Klystron Block - Complex multiblock structure.
 *
 * Structure:
 * - Main body: 7x3x7 blocks
 * - Extension below: 8x4x8 blocks (additional height layer)
 * - Total dimensions accommodate the klystron's large cylindrical design
 * - Extra blocks for energy/fluid connections on sides and front
 *
 * Features:
 * - Opens GUI on right-click
 * - Creates TileEntityProxyCombo for extra connection blocks (power/fluid)
 * - Offset of 3 means core is placed 3 blocks forward from placement position
 *
 * @author Adapted for 1.12.2
 */
public class MachineFusionKlystron extends BlockDummyable implements ITooltipProvider {

	public MachineFusionKlystron(String s) {
		super(Material.IRON, s);
	}

	@Override
	public TileEntity createNewTileEntity(World world, int meta) {
		// Core block with full functionality
		if(meta >= 12) {
			return new TileEntityFusionKlystron();
		}

		// Extra blocks for power and fluid connections
		if(meta >= extra) {
			return new TileEntityProxyCombo().inventory().power().fluid();
		}

		return null;
	}

	@Override
	public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
		return standardOpenBehavior(world, pos.getX(), pos.getY(), pos.getZ(), player, ModBlocks.guiID_fusion_klystron);
	}

	/**
	 * Main structure dimensions: [right, up, forward, left, down, back]
	 * Creates a 7x3x7 structure:
	 * - 3 blocks right, 3 blocks left (7 wide)
	 * - 0 blocks up, 2 blocks down (3 tall)
	 * - 4 blocks forward, 2 blocks back (7 deep)
	 */
	@Override
	public int[] getDimensions() {
		return new int[] { 3, 0, 4, 3, 2, 2 };
	}

	/**
	 * Offset of 3 means the core is placed 3 blocks forward from where player clicks.
	 */
	@Override
	public int getOffset() {
		return 3;
	}

	/**
	 * Additional space check for the extended structure below the main body.
	 * This creates the tall cylindrical appearance of the klystron.
	 */
	@Override
	public boolean checkRequirement(World world, int x, int y, int z, ForgeDirection dir, int o) {
		// Check main structure
		if(!super.checkRequirement(world, x, y, z, dir, o)) {
			return false;
		}

		// Check extended lower section: 8x4x8 centered below
		// [right, up, forward, left, down, back]
		return MultiblockHandlerXR.checkSpace(world,
			x + dir.offsetX * o, y + dir.offsetY * o, z + dir.offsetZ * o,
			new int[] { 4, -3, 4, 3, 1, 1 },
			x, y, z, dir);
	}

	/**
	 * Fills the multiblock structure and marks special blocks for connections.
	 */
	@Override
	public void fillSpace(World world, int x, int y, int z, ForgeDirection dir, int o) {
		// Fill main structure
		super.fillSpace(world, x, y, z, dir, o);

		// Fill extended lower section
		MultiblockHandlerXR.fillSpace(world,
			x + dir.offsetX * o, y, z + dir.offsetZ * o,
			new int[] { 4, -3, 4, 3, 1, 1 },
			this, dir);

		// Offset to core position
		x += dir.offsetX * o;
		z += dir.offsetZ * o;

		// Mark extra blocks for energy/fluid connections
		ForgeDirection rot = dir.getRotation(ForgeDirection.UP);

		// Front connection (klystron output)
		this.makeExtra(world, x + dir.offsetX * 3, y + 2, z + dir.offsetZ * 3);

		// Right side connection
		this.makeExtra(world, x + rot.offsetX * 2, y, z + rot.offsetZ * 2);

		// Left side connection
		this.makeExtra(world, x - rot.offsetX * 2, y, z - rot.offsetZ * 2);
	}

	/**
	 * Adds tooltip information when hovering over the item in inventory.
	 */
	@Override
	public void addInformation(ItemStack stack, @Nullable World player, List<String> tooltip, ITooltipFlag advanced) {
		this.addStandardInfo(tooltip);
	}
}
