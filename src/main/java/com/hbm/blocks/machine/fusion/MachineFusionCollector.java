package com.hbm.blocks.machine.fusion;

import javax.annotation.Nullable;
import java.util.List;

import com.hbm.blocks.BlockDummyable;
import com.hbm.blocks.ITooltipProvider;
import com.hbm.main.tileentity.machine.fusion.TileEntityFusionCollector;

import net.minecraft.block.material.Material;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * Fusion Collector Block - 5x4x5 multiblock structure.
 * Provides a 0.5x bonus multiplier to the Fusion Torus when connected to the Plasma network.
 * This is the simplest fusion component with no GUI or inventory.
 *
 * @author Adapted for 1.12.2
 */
public class MachineFusionCollector extends BlockDummyable implements ITooltipProvider {

	public MachineFusionCollector(String s) {
		super(Material.IRON, s);
	}

	@Override
	public TileEntity createNewTileEntity(World world, int meta) {
		// Only the core block (meta >= 12) has a tile entity
		if(meta >= 12) {
			return new TileEntityFusionCollector();
		}
		return null;
	}

	/**
	 * Defines the multiblock structure dimensions.
	 * Format: [right, up, forward, left, down, back] relative to facing direction
	 *
	 * Structure is 5x4x5:
	 * - 3 blocks right, 2 blocks left (5 wide)
	 * - 0 blocks up, 2 blocks down (4 tall from top, core is offset 1 down)
	 * - 1 block forward, 2 blocks back (5 deep)
	 */
	@Override
	public int[] getDimensions() {
		return new int[] { 3, 0, 2, 1, 2, 2 };
	}

	/**
	 * Returns the metadata offset for dummy blocks.
	 * Offset of 1 means the core is placed slightly offset from where player clicks.
	 */
	@Override
	public int getOffset() {
		return 1;
	}

	/**
	 * Adds tooltip information when hovering over the item in inventory.
	 * Displays localized description text when holding LSHIFT.
	 */
	@Override
	public void addInformation(ItemStack stack, @Nullable World player, List<String> tooltip, ITooltipFlag advanced) {
		this.addStandardInfo(tooltip);
	}
}
