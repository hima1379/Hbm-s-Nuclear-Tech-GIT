package com.hbm.blocks.network.data;

import com.hbm.blocks.BlockDummyable;
import com.hbm.blocks.ModBlocks;
import com.hbm.handler.MultiblockHandlerXR;
import com.hbm.lib.ForgeDirection;
import com.hbm.main.MainRegistry;
import com.hbm.main.tileentity.network.data.TileEntitySPY6;
import com.hbm.main.tileentity.TileEntityProxyCombo;

import api.hbm.energy.IEnergyConnectorBlock;
import api.hbm.data.IDataConnectorBlock;
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
 * SPY-6 AESA Radar Block
 *
 * Multiblock Structure: 12 wide x 7 tall x 12 deep (identical footprint to SPY-1)
 * - AESA (Active Electronically Scanned Array) — 4 fixed faces, no mechanical rotation
 * - GaN T/R modules: +15 dB gain over SPY-1 PESA
 * - 500 km detection range for ballistic missile-sized targets
 *
 * Connection:
 * - CableBlue (DataNet) on BOTTOM face only
 * - Power cable on BOTTOM face only
 */
public class BlockSPY6 extends BlockDummyable implements IEnergyConnectorBlock, IDataConnectorBlock {

	public BlockSPY6(Material materialIn, String s) {
		super(materialIn, s);
		this.setCreativeTab(MainRegistry.machineTab);
		this.setHardness(10.0F);
		this.setResistance(50.0F);
	}

	@Override
	public TileEntity createNewTileEntity(World worldIn, int meta) {
		if (meta >= offset)
			return new TileEntitySPY6();
		return new TileEntityProxyCombo(false, true, false);
	}

	@Override
	public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player, EnumHand hand,
			EnumFacing facing, float hitX, float hitY, float hitZ) {
		// SPY-6 is accessed through FCS Console — no direct GUI
		return false;
	}

	@Override
	public EnumBlockRenderType getRenderType(IBlockState state) {
		return EnumBlockRenderType.ENTITYBLOCK_ANIMATED;
	}

	@Override
	public boolean isOpaqueCube(IBlockState state) { return false; }

	@Override
	public boolean isBlockNormalCube(IBlockState state) { return false; }

	@Override
	public boolean isNormalCube(IBlockState state) { return false; }

	@Override
	public boolean shouldSideBeRendered(IBlockState blockState, IBlockAccess blockAccess, BlockPos pos, EnumFacing side) {
		return false;
	}

	/**
	 * 12x7x12 footprint: [Up:6, Down:0, North:5, South:6, West:5, East:6]
	 * Core is near-center: 5+1+6=12 in both X and Z axes.
	 * Previously North=3/South=8 caused a 5-block hitbox overhang in -Z when
	 * placed facing NORTH (rotate() swaps N↔S, putting South=8 on the -Z side
	 * while the OBJ model only extends 3 blocks there).
	 */
	@Override
	public int[] getDimensions() {
		return new int[] {6, 0, 5, 6, 5, 6};
	}

	@Override
	public int getOffset() {
		return 1;
	}

	@Override
	public void fillSpace(World world, int x, int y, int z, ForgeDirection dir, int o) {
		super.fillSpace(world, x, y, z, dir, o);
	}

	@Override
	protected boolean checkRequirement(World world, int x, int y, int z, ForgeDirection dir, int o) {
		return super.checkRequirement(world, x, y, z, dir, o);
	}

	@Override
	public boolean canConnect(IBlockAccess world, BlockPos pos, ForgeDirection dir) {
		if (dir != ForgeDirection.DOWN) return false;
		int[] corePos = this.findCore(world, pos.getX(), pos.getY(), pos.getZ());
		if (corePos == null) return false;
		return pos.getY() == corePos[1];
	}

	@Override
	public boolean canConnect(EnumFacing dir) {
		return dir == EnumFacing.DOWN;
	}
}
