package com.hbm.blocks.network.data;

import com.hbm.blocks.BlockDummyable;
import com.hbm.blocks.ModBlocks;
import com.hbm.main.MainRegistry;
import com.hbm.main.tileentity.network.data.TileEntitySPG62;
import com.hbm.main.tileentity.TileEntityProxyCombo;

import api.hbm.data.IDataConnectorBlock;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * SPG-62 Fire Control Radar Block
 *
 * Multiblock Structure: 2 wide × 1 tall × 2 deep (same as turrets)
 * - Based on real AN/SPG-62 fire control radar
 * - I/J-Band operation (8-20 GHz)
 * - Peak power: 10 kW
 * - Target illumination for semi-active missiles
 *
 * Connection:
 * - CableBlue (DataNet) connection on all faces
 * - Receives RADAR_SCAN_DATA from SPY-1
 * - Sends ILLUMINATION_DATA to missile launchers
 *
 * Features:
 * - Mechanical rotation (azimuth and elevation)
 * - Tracks targets from SPY-1 radar
 * - Intermittent illumination pattern (10 ticks on, 10 ticks off)
 * - No GUI (temporary implementation)
 */
public class BlockSPG62 extends BlockDummyable implements IDataConnectorBlock {

	public BlockSPG62(Material materialIn, String s) {
		super(materialIn, s);
		this.setCreativeTab(MainRegistry.machineTab);
		this.setHardness(5.0F);
		this.setResistance(20.0F);  // Military equipment
	}

	@Override
	public TileEntity createNewTileEntity(World worldIn, int meta) {
		// Core block: full TileEntity with radar logic
		if(meta >= offset)
			return new TileEntitySPG62();
		// Dummy blocks: proxy TileEntity for data routing
		return new TileEntityProxyCombo(false, false, true);  // inventory=false, power=false, data=true
	}

	@Override
	public boolean onBlockActivated(World world, BlockPos bpos, IBlockState state, EntityPlayer player, EnumHand hand,
			EnumFacing facing, float hitX, float hitY, float hitZ) {
		// No GUI on right-click (temporary implementation)
		// In the future, could open a targeting GUI
		return false;
	}

	@Override
	public EnumBlockRenderType getRenderType(IBlockState state) {
		// Use TESR for 3D OBJ model rendering with rotation
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

	/**
	 * Multiblock dimensions: 2 wide × 1 tall × 2 deep (same as TurretBaseNT)
	 * [Up, Down, North, South, West, East]
	 *
	 * Structure layout (top view):
	 *   W    E
	 *   [ ]  [ ]  (North: -1)
	 *   [C]  [ ]  (South: 0, core at west)
	 *
	 * C = core block at (0, 0, 0)
	 */
	@Override
	public int[] getDimensions() {
		// [Up, Down, North, South, West, East]
		// Same as TurretBaseNT: {0, 0, 1, 0, 1, 0}
		// 0 up, 0 down, 1 north, 0 south, 1 west, 0 east
		// Results in 2x1x2 multiblock
		return new int[] {0, 0, 1, 0, 1, 0};
	}

	/**
	 * Offset for core block positioning
	 * Same as TurretBaseNT
	 */
	@Override
	public int getOffset() {
		return 0;
	}

	/**
	 * Bounding box for collision (lower profile like turret base)
	 */
	@Override
	public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
		// Lower profile (0.5 blocks tall) like turret base
		return new AxisAlignedBB(0.0F, 0.0F, 0.0F, 1.0F, 0.5F, 1.0F);
	}

	/**
	 * IDataConnectorBlock implementation
	 * Allow CableBlue (data cable) connections from all directions
	 */
	@Override
	public boolean canConnect(EnumFacing dir) {
		// Allow connections from all directions
		return true;
	}
}
