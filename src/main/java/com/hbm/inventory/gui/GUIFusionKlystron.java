package com.hbm.inventory.gui;

import org.apache.commons.lang3.math.NumberUtils;
import org.lwjgl.input.Keyboard;

import com.hbm.inventory.container.ContainerFusionKlystron;
import com.hbm.lib.RefStrings;
import com.hbm.main.tileentity.machine.fusion.TileEntityFusionKlystron;
import com.hbm.main.tileentity.machine.fusion.TileEntityFusionTorus;
import com.hbm.packet.NBTControlPacket;
import com.hbm.packet.PacketDispatcher;
import com.hbm.render.util.GaugeUtil;
import com.hbm.util.BobMathUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextFormatting;

/**
 * GUI for Fusion Klystron - ITER Temperature Control System
 *
 * Features:
 * - Battery power bar display
 * - Target temperature input (K)
 * - Three heating system gauges: NBI, ICRH, ECRH
 * - Status LEDs:
 *   - Yellow: Power supply available
 *   - Green: Target temperature reached
 *   - Red: Torus ignition ready
 *
 * @author Adapted for ITER design
 */
public class GUIFusionKlystron extends GuiInfoContainer {

	private static ResourceLocation texture = new ResourceLocation(RefStrings.MODID + ":textures/gui/reactors/gui_fusion_klystron.png");
	public TileEntityFusionKlystron klystron;
	private GuiTextField field;

	// ITER plasma heat capacity: n × V × k_B = 1.0e20 × 840 × 1.380649e-23 ≈ 1159.345 J/K
	private static final double PLASMA_HEAT_CAPACITY = 1159.345; // J/K

	public GUIFusionKlystron(InventoryPlayer invPlayer, TileEntityFusionKlystron klystron) {
		super(new ContainerFusionKlystron(invPlayer, klystron));
		this.klystron = klystron;

		this.xSize = 194;
		this.ySize = 200; // Original GUI size: 194x200 content in 256x256 canvas
	}

	@Override
	public void initGui() {
		super.initGui();

		Keyboard.enableRepeatEvents(true);

		// Output target text field (green text, no background)
		this.field = new GuiTextField(0, this.fontRenderer, guiLeft + 84, guiTop + 22, 102, 12);
		this.field.setTextColor(0x00FF00);
		this.field.setDisabledTextColour(0x00FF00);
		this.field.setEnableBackgroundDrawing(false);
		this.field.setMaxStringLength(10);
		this.field.setText(klystron.outputTarget + "");
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float interp) {
		super.drawScreen(mouseX, mouseY, interp);

		// Calculate temperatures for tooltips
		double currentTemp = calculateCurrentTemperature();
		double targetTemp = klystron.outputTarget;

		// Power button tooltip (60, 20, 16x16) - actual visual position in PNG
		if(mouseX >= guiLeft + 60 && mouseX < guiLeft + 76 && mouseY >= guiTop + 20 && mouseY < guiTop + 36) {
			java.util.List<String> tooltip = new java.util.ArrayList<>();
			tooltip.add(TextFormatting.GOLD + "Power Button");
			if(klystron.isPoweredOn) {
				tooltip.add(TextFormatting.GREEN + "Status: ON (Heating)");
				tooltip.add(TextFormatting.GRAY + "Click to switch to CHARGE mode");
			} else {
				tooltip.add(TextFormatting.YELLOW + "Status: OFF (Charging)");
				tooltip.add(TextFormatting.GRAY + "Click to switch to HEAT mode");
			}
			this.drawHoveringText(tooltip, mouseX, mouseY);
		}

		// Battery power bar tooltip (left side, 8, 18, 16x52)
		this.drawElectricityInfo(this, mouseX, mouseY, guiLeft + 8, guiTop + 18, 16, 52, klystron.power, klystron.getMaxPower());

		// Gauge tooltips: Always show dedicated information regardless of mode
		// Left gauge: NBI information
		String nbiStatus = klystron.nbiOutput > 0 ? TextFormatting.GREEN + "ACTIVE" : TextFormatting.GRAY + "STANDBY";
		drawCustomInfoStat(mouseX, mouseY, guiLeft + 43, guiTop + 71, 18, 18, mouseX, mouseY, new String[] {
			TextFormatting.GOLD + "NBI (Neutral Beam Injection)" + TextFormatting.RESET,
			TextFormatting.WHITE + "External Heating - Rapid Temperature Increase",
			nbiStatus + " Output: " + formatPowerMW(klystron.nbiOutput) + " / " + formatPowerMW(TileEntityFusionKlystron.NBI_MAX_POWER) + " MW",
			TextFormatting.GRAY + "Efficiency: 34% | Beam Energy: 1 MeV"
		});

		// Middle gauge: RF (ICRH + ECRH) information
		long rfTotal = klystron.icrfOutput + klystron.ecrfOutput;
		String rfStatus = rfTotal > 0 ? TextFormatting.YELLOW + "ACTIVE" : TextFormatting.GRAY + "STANDBY";
		drawCustomInfoStat(mouseX, mouseY, guiLeft + 79, guiTop + 71, 18, 18, mouseX, mouseY, new String[] {
			TextFormatting.GOLD + "RF (Radio Frequency) Heating" + TextFormatting.RESET,
			TextFormatting.WHITE + "High Frequency Microwave Heating",
			rfStatus + " ICRH: " + formatPowerMW(klystron.icrfOutput) + " / " + formatPowerMW(TileEntityFusionKlystron.ICRH_MAX_POWER) + " MW",
			TextFormatting.GRAY + "ECRH: " + formatPowerMW(klystron.ecrfOutput) + " / " + formatPowerMW(TileEntityFusionKlystron.ECRH_MAX_POWER) + " MW",
			TextFormatting.AQUA + "Total RF: " + formatPowerMW(rfTotal) + " MW"
		});

		// Right gauge: Temperature information
		String tempStatus = currentTemp >= 1.0e8 ? TextFormatting.RED + "100 Million K Reached!" : TextFormatting.WHITE + "Current: " + formatTemperature(currentTemp);
		drawCustomInfoStat(mouseX, mouseY, guiLeft + 115, guiTop + 71, 18, 18, mouseX, mouseY, new String[] {
			TextFormatting.GOLD + "Temperature Status" + TextFormatting.RESET,
			tempStatus,
			TextFormatting.GRAY + "Target: " + formatTemperature(klystron.outputTarget),
			TextFormatting.AQUA + "Charged Energy: " + String.format("%.1f%%", (klystron.chargedEnergy * 100.0) / TileEntityFusionKlystron.MAX_CHARGED_ENERGY)
		});

		// Temperature display tooltip (text field area)
		if(mouseX >= guiLeft + 84 && mouseX < guiLeft + 186 && mouseY >= guiTop + 22 && mouseY < guiTop + 34) {
			java.util.List<String> tooltip = new java.util.ArrayList<>();
			tooltip.add(TextFormatting.AQUA + "Target Temperature");
			tooltip.add(TextFormatting.WHITE + "Current: " + formatTemperature(currentTemp));
			tooltip.add(TextFormatting.WHITE + "Target: " + formatTemperature(targetTemp));
			if(currentTemp >= targetTemp && targetTemp > 0) {
				tooltip.add(TextFormatting.GREEN + "Temperature reached!");
			}
			this.drawHoveringText(tooltip, mouseX, mouseY);
		}
	}

	/**
	 * Calculate current temperature from charged energy (K)
	 * T = Energy / (n × V × k_B)
	 */
	private double calculateCurrentTemperature() {
		return klystron.chargedEnergy / PLASMA_HEAT_CAPACITY;
	}

	/**
	 * Format temperature in K/kK/MK
	 */
	private String formatTemperature(double tempK) {
		if(tempK >= 1.0e6) {
			return String.format("%.1f MK", tempK / 1.0e6);
		} else if(tempK >= 1.0e3) {
			return String.format("%.1f kK", tempK / 1.0e3);
		} else {
			return String.format("%.0f K", tempK);
		}
	}

	/**
	 * Format power in MW
	 */
	private String formatPowerMW(long watts) {
		return String.valueOf(watts / 1_000_000);
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) throws java.io.IOException {
		super.mouseClicked(mouseX, mouseY, button);

		System.out.println("[GUI] Mouse clicked at: (" + mouseX + ", " + mouseY + "), button=" + button);
		System.out.println("[GUI] guiLeft=" + guiLeft + ", guiTop=" + guiTop);
		System.out.println("[GUI] Power button area: (" + (guiLeft + 60) + ", " + (guiTop + 20) + ") to (" + (guiLeft + 76) + ", " + (guiTop + 36) + ")");

		// Power button click (60, 20, 16x16) - actual visual position in PNG
		if(mouseX >= guiLeft + 60 && mouseX < guiLeft + 76 && mouseY >= guiTop + 20 && mouseY < guiTop + 36) {
			System.out.println("[GUI] Power button CLICKED! Current state: " + klystron.isPoweredOn);
			NBTTagCompound data = new NBTTagCompound();
			data.setBoolean("powerButton", !klystron.isPoweredOn);
			System.out.println("[GUI] Sending packet to server: " + data);
			PacketDispatcher.wrapper.sendToServer(new NBTControlPacket(data, klystron.getPos()));
			return;
		}

		// Handle text field clicks
		this.field.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
		String name = this.klystron.hasCustomInventoryName() ? this.klystron.getInventoryName() : I18n.format(this.klystron.getInventoryName());
		this.fontRenderer.drawString(name, 115 - this.fontRenderer.getStringWidth(name) / 2, 6, 4210752);
		this.fontRenderer.drawString(I18n.format("container.inventory"), 35, this.ySize - 93, 4210752);

		// Temperature target result display
		String result = "= " + formatTemperature(klystron.outputTarget);
		this.fontRenderer.drawString(result, 183 - this.fontRenderer.getStringWidth(result), 40, 0x00FF00);
	}

	@Override
	protected void drawGuiContainerBackgroundLayer(float interp, int mouseX, int mouseY) {
		// Draw main GUI texture (194x200 on 256x256 canvas)
		Minecraft.getMinecraft().getTextureManager().bindTexture(texture);
		drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, ySize);

		// Power button state (60, 20, 16x16) - actual visual position
		// OFF = red (default in PNG), ON = green overlay
		if(klystron.isPoweredOn) {
			drawTexturedModalRect(guiLeft + 60, guiTop + 20, 176, 0, 16, 16); // Green button overlay
		}

		// Battery power bar (left side: 8, 18-70, 16x52)
		if(klystron.getMaxPower() > 0) {
			int p = (int)(klystron.power * 52 / klystron.getMaxPower());
			drawTexturedModalRect(guiLeft + 8, guiTop + 70 - p, 194, 52 - p, 16, p);
		}

		// Draw gauges: Charge level (OFF) or Heating outputs (ON)
		if(!klystron.isPoweredOn) {
			// CHARGING MODE: Display chargedEnergy level on all three gauges (yellow)
			double chargeLevel = TileEntityFusionKlystron.MAX_CHARGED_ENERGY > 0
				? (double)klystron.chargedEnergy / TileEntityFusionKlystron.MAX_CHARGED_ENERGY
				: 0;

			GaugeUtil.drawSmoothGauge(guiLeft + 52, guiTop + 80, this.zLevel, chargeLevel, 5, 2, 1, 0xFFFF00);  // Yellow
			GaugeUtil.drawSmoothGauge(guiLeft + 88, guiTop + 80, this.zLevel, chargeLevel, 5, 2, 1, 0xFFFF00);  // Yellow
			GaugeUtil.drawSmoothGauge(guiLeft + 124, guiTop + 80, this.zLevel, chargeLevel, 5, 2, 1, 0xFFFF00); // Yellow
		} else {
			// HEATING MODE: Display individual heating system outputs
			double nbiGauge = klystron.nbiOutput > 0 ? (double)klystron.nbiOutput / TileEntityFusionKlystron.NBI_MAX_POWER : 0;
			double icrfGauge = klystron.icrfOutput > 0 ? (double)klystron.icrfOutput / TileEntityFusionKlystron.ICRH_MAX_POWER : 0;
			double ecrfGauge = klystron.ecrfOutput > 0 ? (double)klystron.ecrfOutput / TileEntityFusionKlystron.ECRH_MAX_POWER : 0;

			GaugeUtil.drawSmoothGauge(guiLeft + 52, guiTop + 80, this.zLevel, nbiGauge, 5, 2, 1, 0x00A000);   // Green: NBI
			GaugeUtil.drawSmoothGauge(guiLeft + 88, guiTop + 80, this.zLevel, icrfGauge, 5, 2, 1, 0xA0A000);  // Yellow: ICRH
			GaugeUtil.drawSmoothGauge(guiLeft + 124, guiTop + 80, this.zLevel, ecrfGauge, 5, 2, 1, 0xA00000); // Red: ECRH
		}

		// Status LEDs at original positions (160, 170, 180 at y=71)
		double currentTemp = calculateCurrentTemperature();
		double targetTemp = klystron.outputTarget;

		// Power Supply LED (left LED at 160, 71) - Yellow when power available
		if(klystron.hasPowerSupply) {
			drawTexturedModalRect(guiLeft + 160, guiTop + 71, 210, 0, 8, 8); // Yellow
		}

		// Temperature Reached LED (middle LED at 170, 71) - Green when target temperature reached
		if(currentTemp >= targetTemp && targetTemp > 0) {
			drawTexturedModalRect(guiLeft + 170, guiTop + 71, 210, 8, 8, 8); // Green
		}

		// Torus Ignition LED (right LED at 180, 71) - Red when torus ready for ignition
		if(klystron.torusReady) {
			drawTexturedModalRect(guiLeft + 180, guiTop + 71, 210, 16, 8, 8); // Red
		}

		// Draw output target text field
		this.field.drawTextBox();
	}

	@Override
	protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
		// Handle text field input
		if(this.field.textboxKeyTyped(typedChar, keyCode)) {
			String text = this.field.getText();

			// Remove leading zeros
			if(text.startsWith("0")) this.field.setText(text.substring(1));
			if(this.field.getText().isEmpty()) this.field.setText("0");

			// Send updated value to server
			if(NumberUtils.isDigits(this.field.getText())) {
				long num = NumberUtils.toLong(this.field.getText());
				NBTTagCompound data = new NBTTagCompound();
				data.setLong("amount", num);
				PacketDispatcher.wrapper.sendToServer(new NBTControlPacket(data, klystron.getPos()));
			}

			return;
		}

		super.keyTyped(typedChar, keyCode);
	}

	@Override
	public void onGuiClosed() {
		super.onGuiClosed();
		Keyboard.enableRepeatEvents(false);
	}
}
