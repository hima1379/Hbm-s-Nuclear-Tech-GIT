package com.hbm.blocks.machine.fusion;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import com.hbm.blocks.BlockDummyable;
import com.hbm.blocks.ILookOverlay;
import com.hbm.blocks.ITooltipProvider;
import com.hbm.main.tileentity.machine.fusion.TileEntityFusionMHDT;
import com.hbm.util.I18nUtil;

import net.minecraft.block.material.Material;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderGameOverlayEvent.Pre;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Fusion MHDT Block - 7x4x7 structure.
 * @author Adapted for 1.12.2
 */
public class MachineFusionMHDT extends BlockDummyable implements ILookOverlay, ITooltipProvider {

	public MachineFusionMHDT(String s) {
		super(Material.IRON, s);
	}

	@Override
	public TileEntity createNewTileEntity(World world, int meta) {
		if(meta >= 12) return new TileEntityFusionMHDT();
		if(meta >= 6) return new com.hbm.main.tileentity.TileEntityProxyCombo().power();  // Power connection ports
		return null;
	}

	@Override
	public int[] getDimensions() {
		return new int[] { 2, 0, 6, 7, 2, 2 };  // Original: 6x4x3 structure
	}

	@Override
	public int getOffset() {
		return 7;
	}

	@Override
	protected void fillSpace(World world, int x, int y, int z, com.hbm.lib.ForgeDirection dir, int o) {
		// Fill main structure
		super.fillSpace(world, x, y, z, dir, o);

		// Adjust position to core location
		x += dir.offsetX * o;
		z += dir.offsetZ * o;

		// Create power connection ports (extra blocks)
		// MUST MATCH getConPos() positions from 1.7.10 TileEntityFusionMHDT
		com.hbm.lib.ForgeDirection rot = dir.getRotation(com.hbm.lib.ForgeDirection.UP);

		// Side ports (left and right relative to facing direction)
		// Position: dir * 4 + rot * 4 (NOT rot * 3!)
		this.makeExtra(world, x + dir.offsetX * 4 + rot.offsetX * 4, y, z + dir.offsetZ * 4 + rot.offsetZ * 4);
		this.makeExtra(world, x + dir.offsetX * 4 - rot.offsetX * 4, y, z + dir.offsetZ * 4 - rot.offsetZ * 4);

		// Front port: dir * 8, y + 1 (NOT dir * 7!)
		this.makeExtra(world, x + dir.offsetX * 8, y + 1, z + dir.offsetZ * 8);
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void printHook(Pre event, World world, int x, int y, int z) {
		int[] corePos = this.findCore(world, x, y, z);
		if(corePos == null) return;

		TileEntity te = world.getTileEntity(new BlockPos(corePos[0], corePos[1], corePos[2]));

		if(!(te instanceof TileEntityFusionMHDT)) return;
		TileEntityFusionMHDT mhdt = (TileEntityFusionMHDT) te;

		List<String> text = new ArrayList<String>();

		// === MHD GENERATOR STATUS DISPLAY ===

		// 1. Fusion Power Input (MW)
		double fusionPowerMW = mhdt.fusionPowerReceived / 1.0e6;
		String powerInputColor = TextFormatting.AQUA.toString();
		if(fusionPowerMW > 1000.0) {
			powerInputColor = TextFormatting.LIGHT_PURPLE.toString(); // GW range
		} else if(fusionPowerMW > 100.0) {
			powerInputColor = TextFormatting.GREEN.toString();        // 100+ MW
		} else if(fusionPowerMW > 10.0) {
			powerInputColor = TextFormatting.YELLOW.toString();       // 10-100 MW
		} else if(fusionPowerMW > 0.0) {
			powerInputColor = TextFormatting.GRAY.toString();         // <10 MW
		} else {
			powerInputColor = TextFormatting.DARK_GRAY.toString();    // Offline
		}

		String powerInputStr;
		if(mhdt.fusionPowerReceived >= 1e12) {
			powerInputStr = String.format("%.2f TW", mhdt.fusionPowerReceived / 1e12);
		} else if(mhdt.fusionPowerReceived >= 1e9) {
			powerInputStr = String.format("%.2f GW", mhdt.fusionPowerReceived / 1e9);
		} else if(mhdt.fusionPowerReceived >= 1e6) {
			powerInputStr = String.format("%.2f MW", mhdt.fusionPowerReceived / 1e6);
		} else if(mhdt.fusionPowerReceived >= 1e3) {
			powerInputStr = String.format("%.2f kW", mhdt.fusionPowerReceived / 1e3);
		} else {
			powerInputStr = String.format("%.0f W", mhdt.fusionPowerReceived);
		}
		text.add(powerInputColor + "Fusion Power Input: " + TextFormatting.RESET + powerInputStr);

		// 2. MHD Exhaust Temperature (K - Realistic MHD Scale)
		double tempK = mhdt.plasmaTemperature;
		String tempColor = TextFormatting.WHITE.toString();
		if(tempK >= 3000.0) {
			tempColor = TextFormatting.LIGHT_PURPLE.toString(); // Optimal MHD: 3000+ K
		} else if(tempK >= 2500.0) {
			tempColor = TextFormatting.GOLD.toString();         // Good: 2500-3000 K
		} else if(tempK >= 2000.0) {
			tempColor = TextFormatting.YELLOW.toString();       // Minimum: 2000-2500 K
		} else {
			tempColor = TextFormatting.GRAY.toString();         // Too cold: <2000 K
		}
		text.add(tempColor + "Exhaust Temperature: " + TextFormatting.RESET + String.format("%.0f K", tempK));

		// 3. MHD Efficiency (%) - Exhaust Temperature Range
		double efficiencyPercent = calculateMHDEfficiency(tempK) * 100.0;
		String effColor = TextFormatting.GREEN.toString();
		if(efficiencyPercent >= 55.0) {
			effColor = TextFormatting.LIGHT_PURPLE.toString(); // Peak: 55% (isentropic limit)
		} else if(efficiencyPercent >= 50.0) {
			effColor = TextFormatting.GREEN.toString();        // Good: 50-55%
		} else if(efficiencyPercent >= 45.0) {
			effColor = TextFormatting.YELLOW.toString();       // Fair: 45-50%
		} else {
			effColor = TextFormatting.RED.toString();          // Poor: <45%
		}
		text.add(effColor + "MHD Efficiency: " + TextFormatting.RESET + String.format("%.1f%%", efficiencyPercent) +
				TextFormatting.DARK_GRAY + " (max 55%)");

		// 4. Electrical Power Generated (HE/tick and MW)
		long hePerTick = mhdt.electricalPowerGenerated;
		double electricalMW = (hePerTick / 50_000.0);  // Convert HE/tick to MW
		String genColor = TextFormatting.GREEN.toString();
		if(electricalMW > 1_000_000_000.0) {  // Exawatt range
			genColor = TextFormatting.LIGHT_PURPLE.toString();
		} else if(electricalMW > 1_000_000.0) {  // Petawatt range
			genColor = TextFormatting.AQUA.toString();
		} else if(electricalMW > 1000.0) {  // Gigawatt range
			genColor = TextFormatting.GREEN.toString();
		} else if(electricalMW > 10.0) {
			genColor = TextFormatting.YELLOW.toString();
		}

		// Extended units for fusion-scale power generation
		String heStr;
		if(hePerTick >= 1_000_000_000_000_000_000L) {  // 10^18 (Exa)
			heStr = String.format("%.2f EHE/tick", hePerTick / 1_000_000_000_000_000_000.0);
		} else if(hePerTick >= 1_000_000_000_000_000L) {  // 10^15 (Peta)
			heStr = String.format("%.2f PHE/tick", hePerTick / 1_000_000_000_000_000.0);
		} else if(hePerTick >= 1_000_000_000_000L) {  // 10^12 (Tera)
			heStr = String.format("%.2f THE/tick", hePerTick / 1_000_000_000_000.0);
		} else if(hePerTick >= 1_000_000_000) {  // 10^9 (Giga)
			heStr = String.format("%.2f GHE/tick", hePerTick / 1_000_000_000.0);
		} else {
			heStr = String.format("%,d HE/tick", hePerTick);
		}

		String elecStr;
		if(electricalMW >= 1_000_000_000.0) {  // Exawatt
			elecStr = String.format("%.2f EW", electricalMW / 1_000_000_000.0);
		} else if(electricalMW >= 1_000_000.0) {  // Petawatt
			elecStr = String.format("%.2f PW", electricalMW / 1_000_000.0);
		} else if(electricalMW >= 1_000.0) {  // Terawatt
			elecStr = String.format("%.2f TW", electricalMW / 1_000.0);
		} else if(electricalMW >= 1.0) {  // Gigawatt
			elecStr = String.format("%.2f GW", electricalMW);
		} else if(electricalMW >= 0.001) {  // Megawatt
			elecStr = String.format("%.2f MW", electricalMW * 1000.0);
		} else {
			elecStr = String.format("%.2f kW", electricalMW * 1_000_000.0);
		}
		text.add(genColor + "Power Generated: " + TextFormatting.RESET + heStr +
				TextFormatting.DARK_GRAY + " (" + elecStr + ")");

		// 5. Stored Power (HE and %)
		long storedPower = mhdt.power;
		long maxPower = mhdt.MAX_POWER;
		double storagePercent = (double)storedPower / (double)maxPower * 100.0;
		String storageColor = TextFormatting.AQUA.toString();
		if(storagePercent >= 90.0) {
			storageColor = TextFormatting.RED.toString();          // Nearly full
		} else if(storagePercent >= 50.0) {
			storageColor = TextFormatting.GREEN.toString();        // Good storage
		} else if(storagePercent >= 20.0) {
			storageColor = TextFormatting.YELLOW.toString();       // Low storage
		} else {
			storageColor = TextFormatting.DARK_GRAY.toString();    // Very low
		}

		String storedStr;
		// Extended units for fusion-scale power storage (up to Exa scale)
		if(storedPower >= 1_000_000_000_000_000_000L) {  // 10^18 (Exa)
			storedStr = String.format("%.2f EHE", storedPower / 1_000_000_000_000_000_000.0);
		} else if(storedPower >= 1_000_000_000_000_000L) {  // 10^15 (Peta)
			storedStr = String.format("%.2f PHE", storedPower / 1_000_000_000_000_000.0);
		} else if(storedPower >= 1_000_000_000_000L) {  // 10^12 (Tera)
			storedStr = String.format("%.2f THE", storedPower / 1_000_000_000_000.0);
		} else if(storedPower >= 1_000_000_000) {  // 10^9 (Giga)
			storedStr = String.format("%.2f GHE", storedPower / 1_000_000_000.0);
		} else if(storedPower >= 1_000_000) {  // 10^6 (Mega)
			storedStr = String.format("%.2f MHE", storedPower / 1_000_000.0);
		} else if(storedPower >= 1_000) {  // 10^3 (Kilo)
			storedStr = String.format("%.2f kHE", storedPower / 1_000.0);
		} else {
			storedStr = String.format("%d HE", storedPower);
		}

		// Only show percentage if it's meaningful (< 99.9%)
		if(storagePercent < 99.9) {
			text.add(storageColor + "Stored Power: " + TextFormatting.RESET + storedStr +
					TextFormatting.DARK_GRAY + " (" + String.format("%.1f%%", storagePercent) + ")");
		} else {
			text.add(storageColor + "Stored Power: " + TextFormatting.RESET + storedStr +
					TextFormatting.DARK_GRAY + " (Max: " + String.format("%.2f EHE", maxPower / 1_000_000_000_000_000_000.0) + ")");
		}

		// 6. MHD Advantage Info (only show if generating power)
		if(hePerTick > 0) {
			double turbineEfficiency = 0.40;  // 40%
			long turbineEquivalent = (long)(hePerTick / efficiencyPercent * 100.0 * turbineEfficiency);
			double advantage = efficiencyPercent / (turbineEfficiency * 100.0);
			text.add(TextFormatting.GOLD + "⚡ Direct Conversion: " + TextFormatting.RESET +
					String.format("%.1fx vs Turbine", advantage) +
					TextFormatting.DARK_GRAY + " (No coolant!)");
		}

		ILookOverlay.printGeneric(event, I18nUtil.resolveKey(getTranslationKey() + ".name"), 0x00ffff, 0x004040, text);
	}

	/**
	 * Calculate MHD efficiency based on exhaust temperature (matches TileEntity).
	 * Temperature range: 2-10 kK (realistic MHD exhaust scale)
	 * Efficiency range: 40% - 55% (isentropic limit)
	 */
	private double calculateMHDEfficiency(double exhaustTemp) {
		final double MHD_MIN_TEMP = 2000.0;           // 2 kK
		final double MHD_OPTIMAL_TEMP = 3000.0;       // 3 kK (realistic MHD optimal)
		final double ISENTROPIC_EFFICIENCY = 0.55;    // 55% theoretical maximum

		// Below minimum temperature: quadratic drop-off
		if(exhaustTemp < MHD_MIN_TEMP) {
			double ratio = exhaustTemp / MHD_MIN_TEMP;
			return ISENTROPIC_EFFICIENCY * ratio * ratio;
		}

		// Above optimal temperature: maximum efficiency
		if(exhaustTemp >= MHD_OPTIMAL_TEMP) {
			return ISENTROPIC_EFFICIENCY; // 55%
		}

		// Between 2 kK and 3 kK: linear interpolation 40% → 55%
		double minEfficiency = 0.40;
		double tempRange = MHD_OPTIMAL_TEMP - MHD_MIN_TEMP;
		double tempDelta = exhaustTemp - MHD_MIN_TEMP;
		double efficiencyRange = ISENTROPIC_EFFICIENCY - minEfficiency;

		return minEfficiency + (efficiencyRange * tempDelta / tempRange);
	}

	@Override
	public void addInformation(ItemStack stack, @Nullable World player, List<String> tooltip, ITooltipFlag advanced) {
		this.addStandardInfo(tooltip);

		// Add tooltip information about MHD technology
		tooltip.add("");
		tooltip.add(TextFormatting.GOLD + "Magnetohydrodynamic Generator");
		tooltip.add(TextFormatting.GRAY + "Direct energy conversion from fusion exhaust");
		tooltip.add(TextFormatting.GRAY + "No moving parts, no coolant required");
		tooltip.add("");
		tooltip.add(TextFormatting.GREEN + "Efficiency: 40-55% " + TextFormatting.DARK_GRAY + "(isentropic limit)");
		tooltip.add(TextFormatting.GREEN + "Optimal Temp: 3000 K " + TextFormatting.DARK_GRAY + "(exhaust plasma)");
		tooltip.add(TextFormatting.AQUA + "Operating range: 2-10 kK");
		tooltip.add(TextFormatting.YELLOW + "Scales with Torus fuel rate");
	}
}
