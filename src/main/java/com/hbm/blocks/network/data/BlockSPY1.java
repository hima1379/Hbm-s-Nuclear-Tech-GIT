package com.hbm.blocks.network.data;

import com.hbm.blocks.BlockDummyable;
import com.hbm.blocks.ModBlocks;
import com.hbm.handler.MultiblockHandlerXR;
import com.hbm.lib.ForgeDirection;
import com.hbm.main.MainRegistry;
import com.hbm.main.tileentity.network.data.TileEntitySPY1;
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
 * SPY-1 Phased Array Radar Block
 *
 * Multiblock Structure: 12 wide × 7 tall × 12 deep
 * - Based on real AN/SPY-1 phased array radar (12.2m × 12.2m array)
 * - Realistic scale: each face is 12 blocks × 12 blocks
 * - Height: 7 blocks (array mount + pedestal + top)
 *
 * Connection:
 * - CableBlue (DataNet) connection on BOTTOM face only
 * - Connects to FCS Console for radar data transmission
 * - Power cable connection on BOTTOM face only
 * - Only bottom blocks of multiblock can connect to cables
 *
 * Features:
 * - PESA (Passive Electronically Scanned Array) radar
 * - S-band operation (3.1-3.5 GHz)
 * - 360-degree coverage with electronic scanning
 * - No GUI on right-click (data displayed via FCS Console)
 */
public class BlockSPY1 extends BlockDummyable implements IEnergyConnectorBlock, IDataConnectorBlock {

	public BlockSPY1(Material materialIn, String s) {
		super(materialIn, s);
		this.setCreativeTab(MainRegistry.machineTab);
		this.setHardness(10.0F);
		this.setResistance(50.0F);  // High resistance (military grade equipment)
	}

	@Override
	public TileEntity createNewTileEntity(World worldIn, int meta) {
		// Core block: full TileEntity with radar logic
		if(meta >= offset)
			return new TileEntitySPY1();
		// Dummy blocks: proxy TileEntity for power routing
		// Enable power routing to allow cables to connect to any bottom block
		return new TileEntityProxyCombo(false, true, false);  // inventory=false, power=true, fluid=false
	}

	@Override
	public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player, EnumHand hand,
			EnumFacing facing, float hitX, float hitY, float hitZ) {
		// SPY-1 has no direct GUI - it's accessed through FCS Console
		// Right-clicking does nothing
		return false;
	}

	@Override
	public EnumBlockRenderType getRenderType(IBlockState state) {
		// Use TESR for 3D OBJ model rendering
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
	 * Multiblock dimensions: 12 wide × 7 tall × 12 deep
	 * [Up, Down, North, South, West, East]
	 *
	 * Structure layout (top view, realistic SPY-1 scale):
	 *   W E S T                                    E A S T
	 *     -5  -4  -3  -2  -1   0  +1  +2  +3  +4  +5  +6
	 * N  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]
	 * O  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]
	 * R  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]
	 * T  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]
	 * H  [ ]  [ ]  [ ]  [ ]  [ ]  [C]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]
	 *    [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]
	 *    [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]
	 *    [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]
	 *    [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]
	 *    [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]
	 *    [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]
	 *    [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]
	 *
	 * C = core block at (0, 0, 0)
	 * Height: 0 (ground), +1, +2, +3, +4, +5, +6 (top)
	 */
	@Override
	public int[] getDimensions() {
		// [Up, Down, North, South, West, East]
		// Up: 6 (7 blocks tall: 0, +1, +2, +3, +4, +5, +6)
		// North: 3, South: 8 = 12 blocks north-south (center shifted back)
		// West: 5, East: 6 = 12 blocks west-east
		return new int[] {6, 0, 3, 8, 5, 6};
	}

	/**
	 * Offset for core block positioning
	 * Using offset=1 (same as RBMK Console and FCS Console)
	 */
	@Override
	public int getOffset() {
		return 1;
	}

	/**
	 * Fill the multiblock structure
	 * Creates a 12×7×12 array (realistic SPY-1 scale)
	 */
	@Override
	public void fillSpace(World world, int x, int y, int z, ForgeDirection dir, int o) {
		// Main structure: 12 wide × 7 tall × 12 deep
		super.fillSpace(world, x, y, z, dir, o);
	}

	/**
	 * Check if there's enough space for the multiblock
	 */
	@Override
	protected boolean checkRequirement(World world, int x, int y, int z, ForgeDirection dir, int o) {
		// Check main structure space
		return super.checkRequirement(world, x, y, z, dir, o);
	}

	/**
	 * IEnergyConnectorBlock implementation
	 * Allow power cable connections only on bottom blocks, from DOWN direction
	 */
	@Override
	public boolean canConnect(IBlockAccess world, BlockPos pos, ForgeDirection dir) {
		// Only allow connections from DOWN direction
		if (dir != ForgeDirection.DOWN) {
			return false;
		}

		// Find the core block position
		int[] corePos = this.findCore(world, pos.getX(), pos.getY(), pos.getZ());
		if (corePos == null) {
			return false;
		}

		// Check if this block is at the bottom of the multiblock (Y = coreY)
		// Core is at Y=0, so all blocks at Y=0 are bottom blocks
		boolean isBottomBlock = (pos.getY() == corePos[1]);

		return isBottomBlock;
	}

	/**
	 * IDataConnectorBlock implementation
	 * Allow CableBlue (data cable) connections only on bottom blocks, from DOWN direction
	 */
	@Override
	public boolean canConnect(EnumFacing dir) {
		// Only allow connections from DOWN direction
		// Note: This method doesn't have position context, so we allow DOWN
		// The actual validation happens in the TileEntity's canConnect method
		return dir == EnumFacing.DOWN;
	}
}
