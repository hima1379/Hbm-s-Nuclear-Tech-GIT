package com.hbm.blocks.machine.fusion;

import java.util.List;

import com.hbm.blocks.BlockDummyable;
import com.hbm.blocks.ITooltipProvider;
import com.hbm.handler.MultiblockHandlerXR;
import com.hbm.main.tileentity.machine.fusion.TileEntityFusionKlystronCreative;

import net.minecraft.block.material.Material;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Creative mode Fusion Klystron - Provides infinite klystron energy without power or air consumption.
 * @author Adapted for 1.12.2
 */
public class MachineFusionKlystronCreative extends BlockDummyable implements ITooltipProvider {

	public MachineFusionKlystronCreative(String s) {
		super(Material.IRON, s);
	}

	@Override
	public TileEntity createNewTileEntity(World world, int meta) {
		if(meta >= 12) return new TileEntityFusionKlystronCreative();
		return null;
	}

	@Override
	public int[] getDimensions() {
		return new int[] { 3, 0, 4, 3, 2, 2 }; // Same as regular klystron: 7x3x7 main
	}

	@Override
	public int getOffset() {
		return 3;
	}

	@Override
	public void addInformation(ItemStack stack, World world, List<String> list, ITooltipFlag flagIn) {
		this.addStandardInfo(list);
		super.addInformation(stack, world, list, flagIn);
	}
}
