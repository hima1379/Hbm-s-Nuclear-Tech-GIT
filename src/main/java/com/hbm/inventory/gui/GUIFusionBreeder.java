package com.hbm.inventory.gui;

import com.hbm.inventory.container.ContainerFusionBreeder;
import com.hbm.lib.RefStrings;
import com.hbm.main.tileentity.machine.fusion.TileEntityFusionBreeder;
import com.hbm.render.util.GaugeUtil;
import com.hbm.util.BobMathUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fluids.FluidStack;

/**
 * GUI for Fusion Breeder - Adapted for 1.12.2
 */
public class GUIFusionBreeder extends GuiInfoContainer {

	private static ResourceLocation texture = new ResourceLocation(RefStrings.MODID + ":textures/gui/reactors/gui_fusion_breeder.png");
	public TileEntityFusionBreeder breeder;

	public GUIFusionBreeder(InventoryPlayer invPlayer, TileEntityFusionBreeder breeder) {
		super(new ContainerFusionBreeder(invPlayer, breeder));
		this.breeder = breeder;

		this.xSize = 176;
		this.ySize = 200;
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		this.drawDefaultBackground();
		super.drawScreen(mouseX, mouseY, partialTicks);
		this.renderHoveredToolTip(mouseX, mouseY);

		// Neutron flux tooltip
		if(mouseX >= guiLeft + 79 && mouseX < guiLeft + 79 + 18 && mouseY >= guiTop + 23 && mouseY < guiTop + 23 + 18) {
			this.drawHoveringText(TextFormatting.GREEN + "-> " + TextFormatting.RESET + (int) Math.ceil(breeder.neutronEnergy) + " flux/t", mouseX, mouseY);
		}

		// Progress bar tooltip
		if(mouseX >= guiLeft + 67 && mouseX < guiLeft + 67 + 42 && mouseY >= guiTop + 46 && mouseY < guiTop + 46 + 14) {
			this.drawHoveringText(BobMathUtil.getShortNumber((int) Math.ceil(breeder.progress)) + " / " + BobMathUtil.getShortNumber((int) Math.ceil(breeder.capacity)) + " flux", mouseX, mouseY);
		}

		// Fluid tank tooltips
		renderFluidTankTooltip(breeder.tanks[0], mouseX, mouseY, guiLeft + 26, guiTop + 18, 16, 52);
		renderFluidTankTooltip(breeder.tanks[1], mouseX, mouseY, guiLeft + 134, guiTop + 18, 16, 52);
	}

	@Override
	protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
		String name = I18n.format(breeder.getName());
		this.fontRenderer.drawString(name, this.xSize / 2 - this.fontRenderer.getStringWidth(name) / 2, 6, 4210752);
		this.fontRenderer.drawString(I18n.format("container.inventory"), 35, this.ySize - 93, 4210752);
	}

	@Override
	protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
		Minecraft.getMinecraft().getTextureManager().bindTexture(texture);
		drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, ySize);

		// Progress bar
		int p = (int) Math.ceil(breeder.progress * 42 / breeder.capacity);
		if(p > 0) {
			drawTexturedModalRect(guiLeft + 67, guiTop + 48, 176, 0, p, 10);
		}

		// Neutron flux gauge (exponential scale)
		double gauge = 1D - Math.pow(Math.E, -breeder.neutronEnergy * 10 / breeder.capacity);
		GaugeUtil.drawSmoothGauge(guiLeft + 88, guiTop + 32, this.zLevel, gauge, 5, 2, 1, 0xA00000);

		// Render fluid tanks
		renderFluidTank(breeder.tanks[0], guiLeft + 26, guiTop + 70, 16, 52);
		renderFluidTank(breeder.tanks[1], guiLeft + 134, guiTop + 70, 16, 52);
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
		TextureAtlasSprite sprite = mc.getTextureMapBlocks().getTextureExtry(fluid.getFluid().getStill().toString());
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
				this.drawHoveringText(fluid.getLocalizedName() + ": " + fluid.amount + " / " + tank.getCapacity() + " mB", mouseX, mouseY);
			} else {
				this.drawHoveringText("Empty", mouseX, mouseY);
			}
		}
	}
}
