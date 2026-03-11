package com.hbm.blocks.machine.fusion;

import javax.annotation.Nullable;
import java.util.List;

import com.hbm.blocks.BlockDummyable;
import com.hbm.blocks.ITooltipProvider;
import com.hbm.main.tileentity.machine.fusion.TileEntityFusionCoupler;

import net.minecraft.block.material.Material;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * Fusion Coupler Block - 5x2x3 multiblock structure.
 * Converts plasma energy back to klystron energy.
 *
 * @author Adapted for 1.12.2
 */
public class MachineFusionCoupler extends BlockDummyable implements ITooltipProvider {

	public MachineFusionCoupler(String s) {
		super(Material.IRON, s);
	}

	@Override
	public TileEntity createNewTileEntity(World world, int meta) {
		if(meta >= 12) {
			return new TileEntityFusionCoupler();
		}
		return null;
	}

	/**
	 * Structure dimensions: [right, up, forward, left, down, back]
	 * 5x2x3 structure:
	 * - 3 blocks right, 1 block left (5 wide)
	 * - 0 blocks up, 1 block down (2 tall)
	 * - 1 block forward, 1 block back (3 deep)
	 */
	@Override
	public int[] getDimensions() {
		return new int[] { 3, 0, 1, 1, 1, 1 };
	}

	/**
	 * No offset - core is placed where player clicks.
	 */
	@Override
	public int getOffset() {
		return 0;
	}

	@Override
	public void addInformation(ItemStack stack, @Nullable World player, List<String> tooltip, ITooltipFlag advanced) {
		this.addStandardInfo(tooltip);
	}
}
