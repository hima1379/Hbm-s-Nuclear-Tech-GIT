package com.hbm.inventory.gui;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.hbm.inventory.container.ContainerFusionTorus;
import com.hbm.inventory.recipes.FusionRecipe;
import com.hbm.inventory.recipes.FusionRecipes;
import com.hbm.items.machine.ItemFusionTemplate;
import com.hbm.lib.RefStrings;
import com.hbm.main.tileentity.machine.fusion.TileEntityFusionTorus;
import com.hbm.render.util.GaugeUtil;
import com.hbm.util.BobMathUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fluids.FluidStack;

/**
 * GUI for Fusion Torus - 512x512 texture with realistic physics meters
 * Displays: Ti, Te, plasma density, Q-value, fuel rate input
 */
public class GUIFusionTorus extends GuiInfoContainer {

	private static ResourceLocation texture = new ResourceLocation(RefStrings.MODID + ":textures/gui/reactors/gui_fusion_torus_512_final.png");
	private TileEntityFusionTorus torus;
	private final EntityPlayer player;

	// Fuel rate input field
	private GuiTextField fuelRateField;

	public GUIFusionTorus(InventoryPlayer invPlayer, TileEntityFusionTorus torus) {
		super(new ContainerFusionTorus(invPlayer, torus));
		this.torus = torus;
		this.player = invPlayer.player;

		// 512x512 texture displayed at 256x256 GUI size using GL scaling
		this.xSize = 256;
		this.ySize = 256;
	}

	@Override
	public void initGui() {
		super.initGui();

		// Initialize fuel rate input field
		// Position in 512 texture: (425, 311), size: 60x16
		// Convert to 256 GUI coordinates
		int fieldX = guiLeft + (425 * xSize / 512);
		int fieldY = guiTop + (311 * ySize / 512);
		int fieldWidth = (60 * xSize / 512);
		int fieldHeight = (16 * ySize / 512);

		fuelRateField = new GuiTextField(0, fontRenderer, fieldX, fieldY, fieldWidth, fieldHeight);
		fuelRateField.setText(String.valueOf(torus.fuelRate));
		fuelRateField.setMaxStringLength(4); // Max 9998
		fuelRateField.setValidator(this::validateFuelRate);
	}

	/**
	 * Validates fuel rate input (1-9998)
	 */
	private boolean validateFuelRate(String input) {
		if(input.isEmpty()) return true;
		try {
			int value = Integer.parseInt(input);
			return value >= 1 && value <= 9998;
		} catch(NumberFormatException e) {
			return false;
		}
	}

	@Override
	public void onGuiClosed() {
		super.onGuiClosed();
		// Send fuel rate to server
		sendFuelRateToServer();
	}

	@Override
	protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
		if(fuelRateField.isFocused()) {
			if(keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
				sendFuelRateToServer();
				fuelRateField.setFocused(false);
			} else {
				fuelRateField.textboxKeyTyped(typedChar, keyCode);
			}
		} else {
			super.keyTyped(typedChar, keyCode);
		}
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
		super.mouseClicked(mouseX, mouseY, mouseButton);

		// Fuel rate field click
		fuelRateField.mouseClicked(mouseX, mouseY, mouseButton);

		// Recipe selector click (512 texture coordinates)
		if(this.checkClick(mouseX, mouseY, 88, 162, 36, 36)) {
			GUIScreenRecipeSelector.openSelector(torus, torus.fusionModule.recipe, 0, com.hbm.items.machine.ItemBlueprints.grabPool(torus.inventory.getStackInSlot(1)), this);
		}
	}

	/**
	 * Sends fuel rate to server via control packet
	 */
	private void sendFuelRateToServer() {
		try {
			int newFuelRate = Integer.parseInt(fuelRateField.getText());
			if(newFuelRate >= TileEntityFusionTorus.MIN_FUEL_RATE && newFuelRate <= TileEntityFusionTorus.MAX_FUEL_RATE) {
				NBTTagCompound data = new NBTTagCompound();
				data.setInteger("fuelRate", newFuelRate);
				com.hbm.packet.PacketDispatcher.wrapper.sendToServer(new com.hbm.packet.NBTControlPacket(data, torus.getPos()));
			}
		} catch(NumberFormatException e) {
			// Invalid input, reset to current value
			fuelRateField.setText(String.valueOf(torus.fuelRate));
		}
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		this.drawDefaultBackground();
		super.drawScreen(mouseX, mouseY, partialTicks);

		// Draw fuel rate field
		fuelRateField.drawTextBox();

		this.renderHoveredToolTip(mouseX, mouseY);

		// Power tooltip (512 texture coords: 16, 36, size 32x124)
		int powerX = guiLeft + (16 * xSize / 512);
		int powerY = guiTop + (36 * ySize / 512);
		int powerW = 32 * xSize / 512;
		int powerH = 124 * ySize / 512;
		this.drawElectricityInfo(this, mouseX, mouseY, powerX, powerY, powerW, powerH, torus.power, torus.getMaxPower());

		// Tank tooltips (4 fuel tanks) - 512 texture coords
		for(int i = 0; i < 4; i++) {
			int texX = (i < 3 ? 88 + i * 36 : 304);
			int x = guiLeft + (texX * xSize / 512);
			int y = guiTop + (36 * ySize / 512);
			int w = 32 * xSize / 512;
			int h = 104 * ySize / 512;
			renderFluidTankTooltip(torus.tanks[i], mouseX, mouseY, x, y, w, h);
		}

		// Coolant tank tooltips - 512 texture coords
		int coolX1 = guiLeft + (376 * xSize / 512);
		int coolX2 = guiLeft + (412 * xSize / 512);
		int coolY = guiTop + (92 * ySize / 512);
		int coolW = 32 * xSize / 512;
		int coolH = 104 * ySize / 512;
		renderFluidTankTooltip(torus.coolantTanks[0], mouseX, mouseY, coolX1, coolY, coolW, coolH);
		renderFluidTankTooltip(torus.coolantTanks[1], mouseX, mouseY, coolX2, coolY, coolW, coolH);

		// New meters tooltips (Ti, Te, n, Q) - 512 texture coordinates
		renderMeterTooltip("Ion Temperature", formatTemperature(torus.plasmaState.temperatureIon), mouseX, mouseY, 475, 343);
		renderMeterTooltip("Electron Temperature", formatTemperature(torus.plasmaState.temperatureElectron), mouseX, mouseY, 475, 391);
		renderMeterTooltip("Plasma Density", formatDensity(torus.plasmaState.density), mouseX, mouseY, 475, 439);
		renderMeterTooltip("Q-Value (Fusion Gain)", formatQValue(torus.qValue), mouseX, mouseY, 475, 487);

		// Fuel rate field tooltip (512 texture coords: 420-485, 305-325)
		if(isMouseOver(mouseX, mouseY, 420, 305, 70, 25)) {
			List<String> tooltip = new ArrayList<>();
			tooltip.add(TextFormatting.GOLD + "Fuel Rate Multiplier");
			tooltip.add(TextFormatting.GRAY + "Range: 1-9998");
			tooltip.add(TextFormatting.GRAY + "Current: " + TextFormatting.WHITE + torus.fuelRate + "x");
			tooltip.add(TextFormatting.GRAY + "Consumption: " + TextFormatting.WHITE + torus.fuelRate + "x default");
			tooltip.add(TextFormatting.GRAY + "Fusion Power: " + TextFormatting.WHITE + torus.fuelRate + "x default");
			this.drawHoveringText(tooltip, mouseX, mouseY);
		}

		// Gauge tooltips (3 horizontal gauges in center panel) - ITER式温度表示
		FusionRecipe recipe = FusionRecipes.INSTANCE.getRecipe(torus.fusionModule.recipe);

		// Input Temperature gauge tooltip (Klystron加熱による温度) - 512 texture coordinates
		if(this.checkClick(mouseX, mouseY, 104, 240, 36, 36)) {
			List<String> tooltip = new ArrayList<>();
			tooltip.add(TextFormatting.GREEN + "Input Temperature (K)");
			tooltip.add(TextFormatting.GRAY + "Heating from Klystron");
			tooltip.add(TextFormatting.WHITE + formatTemperature(torus.inputTemperatureK));
			this.drawHoveringText(tooltip, mouseX, mouseY);
		}

		// Output Temperature gauge tooltip (核融合反応による温度) - 512 texture coordinates
		if(this.checkClick(mouseX, mouseY, 158, 240, 36, 36)) {
			List<String> tooltip = new ArrayList<>();
			tooltip.add(TextFormatting.RED + "Output Temperature (K)");
			tooltip.add(TextFormatting.GRAY + "Fusion reaction heat");
			tooltip.add(TextFormatting.WHITE + formatTemperature(torus.outputTemperatureK));
			this.drawHoveringText(tooltip, mouseX, mouseY);
		}

		// Current Temperature gauge tooltip (現在のプラズマ温度) - 512 texture coordinates
		if(this.checkClick(mouseX, mouseY, 230, 240, 36, 36)) {
			List<String> tooltip = new ArrayList<>();
			tooltip.add(TextFormatting.AQUA + "Current Temperature (K)");
			tooltip.add(TextFormatting.GRAY + "Current plasma temperature");
			tooltip.add(TextFormatting.WHITE + formatTemperature(torus.currentTemperatureK));
			this.drawHoveringText(tooltip, mouseX, mouseY);
		}

		// Recipe selector tooltip (512 texture coordinates)
		if(this.checkClick(mouseX, mouseY, 88, 162, 36, 36)) {
			renderRecipeTooltip(mouseX, mouseY);
		}
	}

	/**
	 * Renders tooltip for a meter (512 texture coordinates)
	 */
	private void renderMeterTooltip(String label, String value, int mouseX, int mouseY, int centerX, int centerY) {
		// Convert from 512 texture coordinates to GUI coordinates
		int guiX = centerX * xSize / 512;
		int guiY = centerY * ySize / 512;
		int radius = 18 * xSize / 512; // Meter radius in GUI coordinates

		if(isMouseOverCircle(mouseX - guiLeft, mouseY - guiTop, guiX, guiY, radius)) {
			List<String> tooltip = new ArrayList<>();
			tooltip.add(TextFormatting.GOLD + label);
			tooltip.add(TextFormatting.WHITE + value);
			this.drawHoveringText(tooltip, mouseX, mouseY);
		}
	}

	/**
	 * Check if mouse is over a circular area
	 */
	private boolean isMouseOverCircle(int mouseX, int mouseY, int centerX, int centerY, int radius) {
		int dx = mouseX - centerX;
		int dy = mouseY - centerY;
		return (dx * dx + dy * dy) <= (radius * radius);
	}

	/**
	 * Check if mouse is over a rectangular area (512 texture coordinates)
	 */
	private boolean isMouseOver(int mouseX, int mouseY, int x, int y, int width, int height) {
		// Convert from 512 texture coordinates to GUI coordinates
		int guiX = x * xSize / 512;
		int guiY = y * ySize / 512;
		int guiW = width * xSize / 512;
		int guiH = height * ySize / 512;

		return mouseX >= guiLeft + guiX && mouseX < guiLeft + guiX + guiW &&
		       mouseY >= guiTop + guiY && mouseY < guiTop + guiY + guiH;
	}

	/**
	 * Format temperature for display
	 */
	private String formatTemperature(double tempKelvin) {
		if(tempKelvin >= 1.0e9) {
			return String.format("%.2f GK", tempKelvin / 1.0e9);
		} else if(tempKelvin >= 1.0e6) {
			return String.format("%.2f MK", tempKelvin / 1.0e6);
		} else if(tempKelvin >= 1.0e3) {
			return String.format("%.2f kK", tempKelvin / 1.0e3);
		} else {
			return String.format("%.1f K", tempKelvin);
		}
	}

	/**
	 * Format density for display
	 */
	private String formatDensity(double density) {
		if(density >= 1.0e20) {
			return String.format("%.2e /m³", density);
		} else {
			return String.format("%.2e /m³", density);
		}
	}

	/**
	 * Format Q-value for display
	 */
	private String formatQValue(double qValue) {
		if(qValue >= 1000) {
			return String.format("%.1e", qValue);
		} else if(qValue >= 1) {
			return String.format("%.2f", qValue);
		} else {
			return String.format("%.3f", qValue);
		}
	}

	@Override
	protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
		String name = I18n.format(torus.getName());
		// Center text at top
		this.fontRenderer.drawString(name, this.xSize / 2 - this.fontRenderer.getStringWidth(name) / 2, 6, 4210752);
		// Inventory label
		this.fontRenderer.drawString(I18n.format("container.inventory"), 35, this.ySize - 93, 4210752);

		// Temperature display in green frame (right top panel)
		// Green frame is at texture coordinates around (385-460, 70-95)
		// In 256 GUI: x=192-230, y=35-47
		// Target temperature: 123K (-150°C)
		this.fontRenderer.drawString(TextFormatting.AQUA + "/123K", 200, 42, 4210752);

		// Current temperature
		int heat = (int) Math.ceil(torus.temperature);
		String label = (heat > 123 ? TextFormatting.RED : TextFormatting.AQUA) + "" + heat + "K";
		this.fontRenderer.drawString(label, 220 - this.fontRenderer.getStringWidth(label), 36, 4210752);
	}

	@Override
	protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
		Minecraft.getMinecraft().getTextureManager().bindTexture(texture);

		// Draw 512x512 texture scaled to 256x256 GUI with all elements
		GlStateManager.pushMatrix();
		GlStateManager.translate(guiLeft, guiTop, 0);
		GlStateManager.scale(0.5F, 0.5F, 1.0F);

		// Background
		drawModalRectWithCustomSizedTexture(0, 0, 0, 0, 512, 512, 512, 512);

		// Power bar (in 512 texture coordinates)
		if(torus.getMaxPower() > 0) {
			int p = (int) (torus.power * 124 / torus.getMaxPower());
			drawModalRectWithCustomSizedTexture(16, 160 - p, 460, 124 - p, 32, p, 512, 512);
		}

		// Progress bar
		if(torus.fusionModule != null && torus.fusionModule.progress > 0) {
			int prog = (int) Math.ceil(140 * torus.fusionModule.progress);
			drawModalRectWithCustomSizedTexture(196, 162, 0, 488, prog, 12, 512, 512);
		}

		// Bonus bar
		if(torus.fusionModule != null && torus.fusionModule.bonus > 0) {
			int bonus = (int) Math.min(Math.ceil(140 * torus.fusionModule.bonus), 140);
			drawModalRectWithCustomSizedTexture(196, 182, 0, 500, bonus, 12, 512, 512);
		}

		// LED indicators (512 coordinates)
		FusionRecipe recipe = FusionRecipes.INSTANCE.getRecipe(torus.fusionModule.recipe);
		if(recipe != null && torus.power >= 0) drawModalRectWithCustomSizedTexture(320, 230, 492, 28, 16, 16, 512, 512);
		int heat = (int) Math.ceil(torus.temperature);
		if(heat <= 123) drawModalRectWithCustomSizedTexture(340, 230, 492, 28, 16, 16, 512, 512);
		if(torus.didProcess) drawModalRectWithCustomSizedTexture(360, 230, 492, 28, 16, 16, 512, 512);

		// Left/Right LEDs
		if(torus.didProcess) {
			drawModalRectWithCustomSizedTexture(174, 152, 498, 0, 6, 12, 512, 512);
		} else if(recipe != null) {
			drawModalRectWithCustomSizedTexture(174, 152, 492, 0, 6, 12, 512, 512);
		}

		if(torus.didProcess) {
			drawModalRectWithCustomSizedTexture(184, 152, 498, 0, 6, 12, 512, 512);
		} else if(recipe != null && torus.power >= torus.power) {
			drawModalRectWithCustomSizedTexture(184, 152, 492, 0, 6, 12, 512, 512);
		}

		GlStateManager.popMatrix();

		// Render fluid tanks (convert 512 texture coords to 256 GUI coords)
		// Fuel tanks: (88, 36), (124, 36), (160, 36), (304, 36) in 512 texture
		// Height: 104, Width: 32
		for(int i = 0; i < 4; i++) {
			int texX = (i < 3 ? 88 + i * 36 : 304);
			int x = guiLeft + (texX * xSize / 512);
			int y = guiTop + (36 * ySize / 512);
			int w = 32 * xSize / 512;
			int h = 104 * ySize / 512;
			renderFluidTank(torus.tanks[i], x, y, w, h);
		}

		// Coolant tanks: (376, 92), (412, 92) in 512 texture
		int coolX1 = guiLeft + (376 * xSize / 512);
		int coolX2 = guiLeft + (412 * xSize / 512);
		int coolY = guiTop + (92 * ySize / 512);
		int coolW = 32 * xSize / 512;
		int coolH = 104 * ySize / 512;
		renderFluidTank(torus.coolantTanks[0], coolX1, coolY, coolW, coolH);
		renderFluidTank(torus.coolantTanks[1], coolX2, coolY, coolW, coolH);

		// Recipe selector icon (convert 512 texture coords to 256 GUI coords)
		if(torus.fusionModule != null) {
			if(torus.fusionModule.recipe != null && !torus.fusionModule.recipe.isEmpty()) {
				FusionRecipe recipeObj = FusionRecipes.INSTANCE.getRecipe(torus.fusionModule.recipe);
				if(recipeObj != null) {
					int recipeIndex = getRecipeIndex(torus.fusionModule.recipe);
					if(recipeIndex >= 0) {
						ItemStack templateStack = ItemFusionTemplate.getTemplate(recipeIndex);
						int iconX = guiLeft + (88 * xSize / 512);
						int iconY = guiTop + (162 * ySize / 512);
						renderRecipeIcon(templateStack, iconX, iconY);
					}
				}
			}
		}

		// Calculate gauge values - ITER式温度表示 (0-1 GK範囲で正規化)
		double inputTempGauge = getNormalizedTemperature(torus.inputTemperatureK, 0, 1.0e9);
		double outputTempGauge = getNormalizedTemperature(torus.outputTemperatureK, 0, 1.0e9);
		double currentTempGauge = getNormalizedTemperature(torus.currentTemperatureK, 0, 1.0e9);

		// Draw gauges (convert 512 texture coords to 256 GUI coords)
		// Texture positions: (104, 240), (158, 240), (230, 240) in 512 scale
		int gaugeX1 = guiLeft + (104 * xSize / 512);
		int gaugeX2 = guiLeft + (158 * xSize / 512);
		int gaugeX3 = guiLeft + (230 * xSize / 512);
		int gaugeY = guiTop + (240 * ySize / 512);

		GaugeUtil.drawSmoothGauge(gaugeX1, gaugeY, this.zLevel, inputTempGauge, 10, 4, 2, 0x00A000);    // Green for input
		GaugeUtil.drawSmoothGauge(gaugeX2, gaugeY, this.zLevel, outputTempGauge, 10, 4, 2, 0xA00000);  // Red for output
		GaugeUtil.drawSmoothGauge(gaugeX3, gaugeY, this.zLevel, currentTempGauge, 10, 4, 2, 0x00A0A0); // Cyan for current

		// Draw new meters with needles (512 texture coordinates)
		drawMeterNeedle(475, 343, getNormalizedTemperature(torus.plasmaState.temperatureIon, 0, 1.0e9));
		drawMeterNeedle(475, 391, getNormalizedTemperature(torus.plasmaState.temperatureElectron, 0, 1.0e9));
		drawMeterNeedle(475, 439, getNormalizedDensity(torus.plasmaState.density, 1.0e18, 1.0e21));
		drawMeterNeedle(475, 487, getNormalizedQValue(torus.qValue, 0, 10));
	}

	/**
	 * Draws a meter needle (512 texture coordinates)
	 */
	private void drawMeterNeedle(int centerX, int centerY, double value) {
		// Convert from 512 texture coordinates to GUI coordinates
		int guiX = guiLeft + (centerX * xSize / 512);
		int guiY = guiTop + (centerY * ySize / 512);
		int needleLength = 14 * xSize / 512; // Needle length in GUI coordinates

		// Clamp value 0-1
		value = Math.max(0, Math.min(1, value));

		// Angle: -135° (bottom-left) to +135° (bottom-right), 270° total range
		double angleDegrees = -135 + (value * 270);
		double angleRadians = Math.toRadians(angleDegrees);

		// Needle endpoint
		int needleX = guiX + (int) (needleLength * Math.cos(angleRadians));
		int needleY = guiY + (int) (needleLength * Math.sin(angleRadians));

		// Draw needle (golden color)
		GlStateManager.pushMatrix();
		GlStateManager.disableTexture2D();
		GlStateManager.color(1.0F, 0.84F, 0.0F, 1.0F); // Gold

		GL11.glLineWidth(2.0F);
		GL11.glBegin(GL11.GL_LINES);
		GL11.glVertex2i(guiX, guiY);
		GL11.glVertex2i(needleX, needleY);
		GL11.glEnd();

		GlStateManager.enableTexture2D();
		GlStateManager.popMatrix();
	}

	/**
	 * Normalizes temperature to 0-1 range
	 */
	private double getNormalizedTemperature(double temp, double min, double max) {
		return (temp - min) / (max - min);
	}

	/**
	 * Normalizes density to 0-1 range (logarithmic)
	 */
	private double getNormalizedDensity(double density, double min, double max) {
		if(density <= min) return 0.0;
		if(density >= max) return 1.0;
		return (Math.log10(density) - Math.log10(min)) / (Math.log10(max) - Math.log10(min));
	}

	/**
	 * Normalizes Q-value to 0-1 range
	 */
	private double getNormalizedQValue(double qValue, double min, double max) {
		return (qValue - min) / (max - min);
	}

	/**
	 * Draws a scaled textured modal rectangle
	 */
	private void drawScaledTexturedModalRect(int x, int y, int textureX, int textureY, int width, int height, int guiWidth, int guiHeight) {
		// Convert texture coordinates to GUI scale
		int scaledWidth = width * guiWidth / 512;
		int scaledHeight = height * guiHeight / 512;

		double u = textureX / 512.0;
		double v = textureY / 512.0;
		double uWidth = width / 512.0;
		double vHeight = height / 512.0;

		drawModalRectWithCustomSizedTexture(x, y, (float)(u * 512), (float)(v * 512), scaledWidth, scaledHeight, 512, 512);
	}

	/**
	 * Renders a fluid tank using Forge FluidStack rendering
	 */
	private void renderFluidTank(net.minecraftforge.fluids.FluidTank tank, int x, int y, int width, int height) {
		FluidStack fluid = tank.getFluid();
		if(fluid == null || fluid.amount <= 0) return;

		int fluidHeight = (int) ((float) fluid.amount / tank.getCapacity() * height);
		if(fluidHeight <= 0) return;

		Minecraft mc = Minecraft.getMinecraft();
		TextureAtlasSprite sprite = mc.getTextureMapBlocks().getAtlasSprite(fluid.getFluid().getStill().toString());
		if(sprite == null) sprite = mc.getTextureMapBlocks().getMissingSprite();

		mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);

		int color = fluid.getFluid().getColor(fluid);
		float r = ((color >> 16) & 0xFF) / 255.0F;
		float g = ((color >> 8) & 0xFF) / 255.0F;
		float b = (color & 0xFF) / 255.0F;

		GlStateManager.color(r, g, b, 1.0F);

		int yStart = y + height - fluidHeight;
		drawTexturedModalRect(x, yStart, sprite, width, fluidHeight);

		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
	}

	/**
	 * Renders tooltip for a fluid tank
	 */
	private void renderFluidTankTooltip(net.minecraftforge.fluids.FluidTank tank, int mouseX, int mouseY, int x, int y, int width, int height) {
		if(mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height) {
			FluidStack fluid = tank.getFluid();
			if(fluid != null && fluid.amount > 0) {
				List<String> tooltip = new ArrayList<>();
				tooltip.add(fluid.getLocalizedName());
				tooltip.add(TextFormatting.GRAY + "" + fluid.amount + " / " + tank.getCapacity() + " mB");
				this.drawHoveringText(tooltip, mouseX, mouseY);
			} else {
				this.drawHoveringText("Empty", mouseX, mouseY);
			}
		}
	}

	/**
	 * Helper method to check if mouse is within a rectangle (512 texture coordinates)
	 */
	private boolean checkClick(int mouseX, int mouseY, int x, int y, int width, int height) {
		// Convert from 512 texture coordinates to GUI coordinates
		int guiX = x * xSize / 512;
		int guiY = y * ySize / 512;
		int guiW = width * xSize / 512;
		int guiH = height * ySize / 512;

		return mouseX >= guiLeft + guiX && mouseX < guiLeft + guiX + guiW &&
		       mouseY >= guiTop + guiY && mouseY < guiTop + guiY + guiH;
	}

	/**
	 * Gets recipe index from recipe name
	 */
	private int getRecipeIndex(String recipeName) {
		int count = FusionRecipes.INSTANCE.getRecipeCount();
		for(int i = 0; i < count; i++) {
			FusionRecipe recipe = FusionRecipes.INSTANCE.getRecipeByIndex(i);
			if(recipe != null && recipe.getName().equals(recipeName)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Renders a recipe template icon at the specified position
	 */
	private void renderRecipeIcon(ItemStack stack, int x, int y) {
		if(stack.isEmpty()) return;

		RenderHelper.enableGUIStandardItemLighting();
		GlStateManager.pushMatrix();
		GlStateManager.translate(0.0F, 0.0F, 32.0F);
		this.zLevel = 200.0F;
		this.itemRender.zLevel = 200.0F;

		this.itemRender.renderItemAndEffectIntoGUI(player, stack, x, y);

		this.itemRender.zLevel = 0.0F;
		this.zLevel = 0.0F;
		GlStateManager.popMatrix();
		RenderHelper.disableStandardItemLighting();
	}

	/**
	 * Renders detailed recipe tooltip
	 */
	private void renderRecipeTooltip(int mouseX, int mouseY) {
		List<String> tooltip = new ArrayList<>();

		if(torus.fusionModule != null && torus.fusionModule.recipe != null && !torus.fusionModule.recipe.isEmpty()) {
			FusionRecipe recipe = FusionRecipes.INSTANCE.getRecipe(torus.fusionModule.recipe);
			if(recipe != null) {
				// Recipe name
				tooltip.add(TextFormatting.WHITE + I18n.format(recipe.getName()));

				// Duration
				tooltip.add(TextFormatting.RED + "Duration: " + TextFormatting.WHITE + String.format("%.1fs", recipe.duration / 20.0));

				// Klystron Input Energy
				tooltip.add(TextFormatting.LIGHT_PURPLE + "Klystron Input: " + TextFormatting.WHITE + BobMathUtil.getShortNumber(recipe.ignitionTemp) + "KyU/t");

				// Plasma Output Energy
				tooltip.add(TextFormatting.RED + "Plasma Output: " + TextFormatting.WHITE + BobMathUtil.getShortNumber(recipe.outputTemp) + "TU/t");

				// Output Neutron Flux
				tooltip.add(TextFormatting.AQUA + "Neutron Flux: " + TextFormatting.WHITE + String.format("%.1f flux/t", recipe.neutronFlux));
			} else {
				tooltip.add(TextFormatting.RED + "Invalid Recipe");
			}
		} else {
			tooltip.add(TextFormatting.YELLOW + "Click to set recipe");
		}

		this.drawHoveringText(tooltip, mouseX, mouseY);
	}
}
