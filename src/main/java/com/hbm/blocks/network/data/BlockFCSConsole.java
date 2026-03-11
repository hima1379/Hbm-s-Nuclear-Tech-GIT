package com.hbm.blocks.network.data;

import com.hbm.blocks.BlockDummyable;
import com.hbm.blocks.ModBlocks;
import com.hbm.handler.MultiblockHandlerXR;
import com.hbm.lib.ForgeDirection;
import com.hbm.main.MainRegistry;
import com.hbm.main.tileentity.network.data.TileEntityFCSConsole;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * FCS Console Block - Fire Control System command center
 * EXACT SAME structure as RBMK Console:
 * - Back: 5 wide × 4 tall × 1 deep
 * - Front: 5 wide × 1 tall × 1 deep
 * Connects to radar/missile network via Blue Cable (DataNet)
 */
public class BlockFCSConsole extends BlockDummyable {

	public BlockFCSConsole(Material materialIn, String s) {
		super(materialIn, s);
		this.setCreativeTab(MainRegistry.machineTab);
		this.setHardness(5.0F);
		this.setResistance(10.0F);
	}

	@Override
	public TileEntity createNewTileEntity(World worldIn, int meta) {
		// Only create TileEntity for the core block (meta >= offset)
		if(meta >= offset)
			return new TileEntityFCSConsole();
		return null;
	}

	@Override
	public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player, EnumHand hand,
			EnumFacing facing, float hitX, float hitY, float hitZ) {
		if (world.isRemote) {
			return true;
		}

		if (!player.isSneaking()) {
			// Find the core block
			int[] corePos = this.findCore(world, pos.getX(), pos.getY(), pos.getZ());
			if (corePos == null)
				return false;

			TileEntity te = world.getTileEntity(new BlockPos(corePos[0], corePos[1], corePos[2]));
			if (!(te instanceof TileEntityFCSConsole))
				return false;

			player.openGui(MainRegistry.instance, ModBlocks.guiID_fcs_console, world, corePos[0], corePos[1], corePos[2]);
			return true;
		}

		return false;
	}

	@Override
	public EnumBlockRenderType getRenderType(IBlockState state) {
		// Use TESR for 3D OBJ model rendering (same as RBMK Console)
		return EnumBlockRenderType.ENTITYBLOCK_ANIMATED;
	}

	@Override
	public boolean isOpaqueCube(IBlockState state) {
		return false;
	}

	@Override
	public boolean isBlockNormalCube(IBlockState state) {
		return false;
	}

	@Override
	public boolean isNormalCube(IBlockState state) {
		return false;
	}

	@Override
	public boolean shouldSideBeRendered(IBlockState blockState, IBlockAccess blockAccess, BlockPos pos, EnumFacing side) {
		return false;
	}

	// EXACT SAME as RBMK Console
	@Override
	public int[] getDimensions() {
		// [Up, Down, North, South, West, East]
		// Up: 3, West: 2, East: 2 = 5 wide × 4 tall
		return new int[] {3, 0, 0, 0, 2, 2};
	}

	// EXACT SAME as RBMK Console
	@Override
	public int getOffset() {
		return 1;  // RBMK Console uses offset=1, not default 10
	}

	// EXACT SAME as RBMK Console
	@Override
	public void fillSpace(World world, int x, int y, int z, ForgeDirection dir, int o) {
		// Back section: 5 wide × 4 tall
		super.fillSpace(world, x, y, z, dir, o);

		// Front section: 5 wide × 1 tall (ground level only)
		// Place at (x + dir.offsetX * o, y, z + dir.offsetZ * o)
		MultiblockHandlerXR.fillSpace(world, x + dir.offsetX * o , y, z + dir.offsetZ * o,
		                               new int[] {0, 0, 0, 1, 2, 2}, this, dir);
	}

	// EXACT SAME as RBMK Console
	@Override
	protected boolean checkRequirement(World world, int x, int y, int z, ForgeDirection dir, int o) {
		// Check front section space
		if(!MultiblockHandlerXR.checkSpace(world, x + dir.offsetX * o , y + dir.offsetY * o, z + dir.offsetZ * o,
		                                    new int[] {0, 0, 0, 1, 2, 2}, x, y, z, dir))
			return false;

		// Check back section space
		return super.checkRequirement(world, x, y, z, dir, o);
	}
}
