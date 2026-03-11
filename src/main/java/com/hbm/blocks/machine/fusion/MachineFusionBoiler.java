package com.hbm.blocks.machine.fusion;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import com.hbm.blocks.BlockDummyable;
import com.hbm.blocks.ILookOverlay;
import com.hbm.blocks.ITooltipProvider;
import com.hbm.lib.ForgeDirection;
import com.hbm.main.tileentity.TileEntityProxyCombo;
import com.hbm.main.tileentity.machine.fusion.TileEntityFusionBoiler;
import com.hbm.util.I18nUtil;

import net.minecraft.block.material.Material;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderGameOverlayEvent.Pre;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Fusion Boiler Block - 7x4x7 structure.
 * @author Adapted for 1.12.2
 */
public class MachineFusionBoiler extends BlockDummyable implements ILookOverlay, ITooltipProvider {

	public MachineFusionBoiler(String s) {
		super(Material.IRON, s);
	}

	@Override
	public TileEntity createNewTileEntity(World world, int meta) {
		// Core block with full functionality
		if(meta >= 12) return new TileEntityFusionBoiler();

		// Extra blocks for fluid connections
		if(meta >= extra) {
			return new TileEntityProxyCombo(false, false, true); // only fluid capability
		}

		return null;
	}

	@Override
	public int[] getDimensions() {
		return new int[] { 3, 0, 4, 4, 1, 1 };
	}

	@Override
	public int getOffset() {
		return 4;
	}

	/**
	 * Fills the multiblock structure and marks special blocks for fluid connections.
	 * Creates extra blocks at the 4 connection points around the boiler.
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

		// Mark extra blocks at the 4 fluid connection points
		// Exact positions from 1.7.10: (x - dir + rot, y, z - dir + rot) etc.
		this.makeExtra(world, x - facing.getXOffset() * 1 + rot.getXOffset(), y, z - facing.getZOffset() * 1 + rot.getZOffset());
		this.makeExtra(world, x - facing.getXOffset() * 1 - rot.getXOffset(), y, z - facing.getZOffset() * 1 - rot.getZOffset());
		this.makeExtra(world, x + facing.getXOffset() * 2 + rot.getXOffset(), y, z + facing.getZOffset() * 2 + rot.getZOffset());
		this.makeExtra(world, x + facing.getXOffset() * 2 - rot.getXOffset(), y, z + facing.getZOffset() * 2 - rot.getZOffset());
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void printHook(Pre event, World world, int x, int y, int z) {
		int[] corePos = this.findCore(world, x, y, z);
		if(corePos == null) return;

		TileEntity te = world.getTileEntity(new BlockPos(corePos[0], corePos[1], corePos[2]));

		if(!(te instanceof TileEntityFusionBoiler)) return;
		TileEntityFusionBoiler boiler = (TileEntityFusionBoiler) te;

		List<String> text = new ArrayList<String>();

		// === PHYSICS-BASED DISPLAY (NO TU) ===

		// Show boiler temperature in Kelvin and Celsius
		double tempK = boiler.boilerTemperature;
		double tempC = tempK - 273.15;
		String tempColor = TextFormatting.WHITE.toString();
		if(tempC >= 600.0) {
			tempColor = TextFormatting.LIGHT_PURPLE.toString(); // UltraSteam temperature
		} else if(tempC >= 450.0) {
			tempColor = TextFormatting.RED.toString(); // SuperHotSteam
		} else if(tempC >= 300.0) {
			tempColor = TextFormatting.GOLD.toString(); // HotSteam
		} else if(tempC >= 100.0) {
			tempColor = TextFormatting.YELLOW.toString(); // Normal steam
		} else if(tempC >= 50.0) {
			tempColor = TextFormatting.GREEN.toString(); // Warm
		}
		text.add(tempColor + "Temperature: " + TextFormatting.RESET + String.format("%.1f K (%.1f°C)", tempK, tempC));

		// Show thermal power in Watts
		double powerW = boiler.thermalPower;
		String powerStr;
		if(powerW >= 1e12) {
			powerStr = String.format("%.2f TW", powerW / 1e12);
		} else if(powerW >= 1e9) {
			powerStr = String.format("%.2f GW", powerW / 1e9);
		} else if(powerW >= 1e6) {
			powerStr = String.format("%.2f MW", powerW / 1e6);
		} else if(powerW >= 1e3) {
			powerStr = String.format("%.2f kW", powerW / 1e3);
		} else {
			powerStr = String.format("%.0f W", powerW);
		}
		String powerColor = powerW > 1e6 ? TextFormatting.GREEN.toString() : TextFormatting.GRAY.toString();
		text.add(powerColor + "Thermal Power: " + TextFormatting.RESET + powerStr);

		// Show tank info
		for(int i = 0; i < boiler.getAllTanks().length; i++) {
			FluidTank tank = boiler.getAllTanks()[i];
			FluidStack fluid = tank.getFluid();
			String fluidName = fluid != null ? fluid.getLocalizedName() : "Empty";
			int amount = fluid != null ? fluid.amount : 0;

			// Green arrow for input (tank 0), red arrow for output (tank 1)
			String arrow = (i == 0 ? (TextFormatting.GREEN + "-> ") : (TextFormatting.RED + "<- "));
			text.add(arrow + TextFormatting.RESET + fluidName + ": " + String.format("%,d", amount) + "/" + String.format("%,d", tank.getCapacity()) + " mB");
		}

		ILookOverlay.printGeneric(event, I18nUtil.resolveKey(getTranslationKey() + ".name"), 0xffff00, 0x404000, text);
	}

	@Override
	public void addInformation(ItemStack stack, @Nullable World player, List<String> tooltip, ITooltipFlag advanced) {
		this.addStandardInfo(tooltip);
	}
}
