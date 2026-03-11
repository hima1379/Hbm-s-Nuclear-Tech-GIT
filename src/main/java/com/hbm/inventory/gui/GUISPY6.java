package com.hbm.inventory.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.lwjgl.opengl.GL11;

import com.hbm.entity.missile.EntityMissileSM6;
import com.hbm.inventory.container.ContainerSPY6;
import com.hbm.lib.RefStrings;
import com.hbm.main.tileentity.network.data.TileEntitySPY6;
import com.hbm.radar.PhysicsBasedRadarSystem.RadarContact;
import com.hbm.radar.PhysicsBasedRadarSystem.RadarSpec;
import com.hbm.radar.RadarStealthCapability;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;

/**
 * SPY-6 AESA Radar GUI
 * PPI (Plan Position Indicator) display — cyan color scheme (AESA vs SPY-1 green PESA)
 *
 * Differences from GUISPY1:
 *   - Cyan color scheme (0x00FFFF) — distinguishes AESA from PESA
 *   - 5 range rings (SPY-6 longer range)
 *   - Status line shows "AESA/DBF ACTIVE"
 *   - Max range label updated to 500 km
 */
public class GUISPY6 extends GuiInfoContainer {

	// Reuse the SPY-1 GUI background texture (same layout)
	private static final ResourceLocation texture = new ResourceLocation(RefStrings.MODID, "textures/gui/gui_spy1.png");

	private TileEntitySPY6 radar;

	// PPI radar scope dimensions
	private static final int SCOPE_CENTER_X = 90;
	private static final int SCOPE_CENTER_Y = 80;
	private static final int SCOPE_RADIUS   = 60;

	// GUI dimensions (same as SPY-1)
	private static final int GUI_WIDTH  = 256;
	private static final int GUI_HEIGHT = 166;

	// AESA cyan color
	private static final int COLOR_CYAN       = 0x00FFFF;
	private static final int COLOR_CYAN_DIM   = 0x008888;
	private static final int COLOR_CYAN_GRID  = 0x004444;

	public GUISPY6(InventoryPlayer invPlayer, TileEntitySPY6 teRadar) {
		super(new ContainerSPY6(invPlayer, teRadar));
		this.radar  = teRadar;
		this.xSize  = GUI_WIDTH;
		this.ySize  = GUI_HEIGHT;
	}

	@Override
	protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
		// Title — cyan to distinguish AESA
		String title = "SPY-6 AESA RADAR";
		int titleWidth = this.fontRenderer.getStringWidth(title);
		this.fontRenderer.drawString(title, (xSize - titleWidth) / 2, 6, COLOR_CYAN);

		drawRadarScope();
		drawContactList();
		drawSystemStatus();
	}

	private void drawRadarScope() {
		GlStateManager.pushMatrix();
		GlStateManager.disableTexture2D();
		GlStateManager.enableBlend();
		GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

		// Dark cyan background
		drawCircle(SCOPE_CENTER_X, SCOPE_CENTER_Y, SCOPE_RADIUS, 0x80001414, true);

		drawRadarGrid();
		drawScanLine();
		drawTargets();
		drawFriendlyMissiles();

		GlStateManager.disableBlend();
		GlStateManager.enableTexture2D();
		GlStateManager.popMatrix();
	}

	private void drawRadarGrid() {
		GlStateManager.color(0.0F, 1.0F, 1.0F, 0.3F);
		GlStateManager.glLineWidth(1.0F);

		Tessellator tess = Tessellator.getInstance();
		BufferBuilder buf = tess.getBuffer();

		// 5 range rings (SPY-6 longer range — one more ring than SPY-1's 4)
		int numRings = 5;
		for (int i = 1; i <= numRings; i++) {
			int ringRadius = (SCOPE_RADIUS * i) / numRings;
			drawCircle(SCOPE_CENTER_X, SCOPE_CENTER_Y, ringRadius, 0x40008888, false);
		}

		// Azimuth lines every 45°
		buf.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION);
		for (int deg = 0; deg < 360; deg += 45) {
			double rad = Math.toRadians(deg);
			double dx  = Math.sin(rad) * SCOPE_RADIUS;
			double dy  = -Math.cos(rad) * SCOPE_RADIUS;
			buf.pos(SCOPE_CENTER_X,      SCOPE_CENTER_Y,      0).endVertex();
			buf.pos(SCOPE_CENTER_X + dx, SCOPE_CENTER_Y + dy, 0).endVertex();
		}
		tess.draw();

		// Cardinal direction labels (cyan)
		GlStateManager.enableTexture2D();
		GlStateManager.color(0.0F, 1.0F, 1.0F, 1.0F);
		this.fontRenderer.drawString("N", SCOPE_CENTER_X - 3,                    SCOPE_CENTER_Y - SCOPE_RADIUS - 10, COLOR_CYAN);
		this.fontRenderer.drawString("E", SCOPE_CENTER_X + SCOPE_RADIUS + 3,     SCOPE_CENTER_Y - 3,                 COLOR_CYAN);
		this.fontRenderer.drawString("S", SCOPE_CENTER_X - 3,                    SCOPE_CENTER_Y + SCOPE_RADIUS + 3,  COLOR_CYAN);
		this.fontRenderer.drawString("W", SCOPE_CENTER_X - SCOPE_RADIUS - 10,    SCOPE_CENTER_Y - 3,                 COLOR_CYAN);
		GlStateManager.disableTexture2D();
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
	}

	private void drawScanLine() {
		if (!radar.isRadarActive()) return;

		double currentAzimuth = radar.getCurrentAzimuth();
		double rad = Math.toRadians(currentAzimuth);
		double dx  = Math.sin(rad) * SCOPE_RADIUS;
		double dy  = -Math.cos(rad) * SCOPE_RADIUS;

		// Cyan scan line (vs SPY-1 yellow)
		GlStateManager.color(0.0F, 1.0F, 1.0F, 0.6F);
		GlStateManager.glLineWidth(2.0F);

		Tessellator tess = Tessellator.getInstance();
		BufferBuilder buf = tess.getBuffer();
		buf.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION);
		buf.pos(SCOPE_CENTER_X,      SCOPE_CENTER_Y,      0).endVertex();
		buf.pos(SCOPE_CENTER_X + dx, SCOPE_CENTER_Y + dy, 0).endVertex();
		tess.draw();

		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
	}

	private void drawTargets() {
		if (!radar.isRadarActive()) return;

		Map<UUID, RadarContact> contacts = radar.getContacts();
		if (contacts.isEmpty()) return;

		GlStateManager.enableTexture2D();
		GlStateManager.enableBlend();

		RadarSpec spec    = radar.getRadarSpec();
		double maxRange   = spec.maxRange * 1000.0;  // km → blocks

		for (RadarContact contact : contacts.values()) {
			if (contact.entity == null || contact.entity.isDead) continue;

			double rangeNorm = Math.min(1.0, contact.distance / maxRange);
			double radius    = rangeNorm * SCOPE_RADIUS;
			double rad       = Math.toRadians(contact.azimuth);
			double dx        = Math.sin(rad) * radius;
			double dy        = -Math.cos(rad) * radius;

			int screenX = (int)(SCOPE_CENTER_X + dx);
			int screenY = (int)(SCOPE_CENTER_Y + dy);

			double stealthFactor = RadarStealthCapability.calculateStealthFactor(contact.entity);
			int color = getTargetColor(stealthFactor);

			drawCrossMarker(screenX, screenY, 4, color);

			if (rangeNorm > 0.6) {
				String rangeStr = String.format("%.0f", contact.distance / 1000.0);
				this.fontRenderer.drawString(rangeStr, screenX + 5, screenY - 4, color);
			}
		}

		GlStateManager.disableBlend();
	}

	private void drawFriendlyMissiles() {
		Map<UUID, Integer> activeMissiles = radar.getActiveMissiles();
		if (activeMissiles.isEmpty()) return;

		double radarX = radar.getPos().getX();
		double radarY = radar.getPos().getY();
		double radarZ = radar.getPos().getZ();

		RadarSpec spec  = radar.getRadarSpec();
		double maxRange = spec.maxRange * 1000.0;

		List<EntityMissileSM6> missiles = radar.getWorld().getEntities(EntityMissileSM6.class, entity -> true);

		GlStateManager.enableTexture2D();
		GlStateManager.enableBlend();

		for (UUID missileId : activeMissiles.keySet()) {
			EntityMissileSM6 missile = null;
			for (EntityMissileSM6 m : missiles) {
				if (m.getDeviceId().equals(missileId)) { missile = m; break; }
			}
			if (missile == null || missile.isDead) continue;

			double dx       = missile.posX - radarX;
			double dz       = missile.posZ - radarZ;
			double distance = Math.sqrt(dx * dx + dz * dz);
			double azimuth  = Math.toDegrees(Math.atan2(dx, -dz));
			if (azimuth < 0) azimuth += 360.0;

			double rangeNorm = Math.min(1.0, distance / maxRange);
			double radius    = rangeNorm * SCOPE_RADIUS;
			double rad       = Math.toRadians(azimuth);
			double scopeDx   = Math.sin(rad) * radius;
			double scopeDy   = -Math.cos(rad) * radius;

			int screenX = (int)(SCOPE_CENTER_X + scopeDx);
			int screenY = (int)(SCOPE_CENTER_Y + scopeDy);

			drawCrossMarker(screenX, screenY, 5, 0x0088FF);
			this.fontRenderer.drawString("SM6", screenX + 6, screenY - 4, 0x0088FF);
		}

		GlStateManager.disableBlend();
	}

	private void drawCircle(int centerX, int centerY, int radius, int color, boolean filled) {
		float a = ((color >> 24) & 0xFF) / 255.0F;
		float r = ((color >> 16) & 0xFF) / 255.0F;
		float g = ((color >>  8) & 0xFF) / 255.0F;
		float b = ( color        & 0xFF) / 255.0F;

		GlStateManager.color(r, g, b, a);

		Tessellator tess = Tessellator.getInstance();
		BufferBuilder buf = tess.getBuffer();
		int segments = 64;

		if (filled) {
			buf.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION);
			buf.pos(centerX, centerY, 0).endVertex();
			for (int i = 0; i <= segments; i++) {
				double angle = 2.0 * Math.PI * i / segments;
				buf.pos(centerX + radius * Math.cos(angle), centerY + radius * Math.sin(angle), 0).endVertex();
			}
		} else {
			buf.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION);
			for (int i = 0; i < segments; i++) {
				double angle = 2.0 * Math.PI * i / segments;
				buf.pos(centerX + radius * Math.cos(angle), centerY + radius * Math.sin(angle), 0).endVertex();
			}
		}
		tess.draw();
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
	}

	private int getTargetColor(double stealthFactor) {
		if      (stealthFactor >= 0.9) return 0xFF8888;  // Red — stealth
		else if (stealthFactor >= 0.5) return 0xFFFF88;  // Yellow — moderate stealth
		else                           return 0x88FFFF;  // Cyan — normal (AESA color scheme)
	}

	private void drawCrossMarker(int x, int y, int size, int color) {
		float r = ((color >> 16) & 0xFF) / 255.0F;
		float g = ((color >>  8) & 0xFF) / 255.0F;
		float b = ( color        & 0xFF) / 255.0F;

		GlStateManager.disableTexture2D();
		GlStateManager.color(r, g, b, 1.0F);
		GlStateManager.glLineWidth(2.0F);

		Tessellator tess = Tessellator.getInstance();
		BufferBuilder buf = tess.getBuffer();
		buf.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION);
		buf.pos(x - size, y,      0).endVertex();
		buf.pos(x + size, y,      0).endVertex();
		buf.pos(x,        y - size, 0).endVertex();
		buf.pos(x,        y + size, 0).endVertex();
		tess.draw();

		GlStateManager.enableTexture2D();
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
	}

	private void drawContactList() {
		int listX = SCOPE_CENTER_X + SCOPE_RADIUS + 15;
		int listY = SCOPE_CENTER_Y - SCOPE_RADIUS;

		this.fontRenderer.drawString("CONTACTS", listX, listY, COLOR_CYAN);

		if (!radar.isRadarActive()) {
			this.fontRenderer.drawString("RADAR OFF", listX, listY + 15, 0xFF0000);
			return;
		}

		Map<UUID, RadarContact> contacts = radar.getContacts();
		if (contacts.isEmpty()) {
			this.fontRenderer.drawString("NO CONTACTS", listX, listY + 15, COLOR_CYAN_DIM);
			return;
		}

		List<RadarContact> sorted = new ArrayList<>(contacts.values());
		sorted.sort((a, b) -> Double.compare(a.distance, b.distance));

		int maxDisplay = Math.min(8, sorted.size());
		for (int i = 0; i < maxDisplay; i++) {
			RadarContact contact = sorted.get(i);
			if (contact.entity == null) continue;

			String name = contact.entity.getName();
			if (name.length() > 10) name = name.substring(0, 10);

			String info = String.format("%s %.0fkm", name, contact.distance / 1000.0);

			double stealthFactor = RadarStealthCapability.calculateStealthFactor(contact.entity);
			this.fontRenderer.drawString(info, listX, listY + 15 + (i * 10), getTargetColor(stealthFactor));
		}

		if (sorted.size() > maxDisplay) {
			this.fontRenderer.drawString("+" + (sorted.size() - maxDisplay) + " more",
				listX, listY + 15 + (maxDisplay * 10), COLOR_CYAN_DIM);
		}
	}

	private void drawSystemStatus() {
		int statusX = SCOPE_CENTER_X - SCOPE_RADIUS;
		int statusY = SCOPE_CENTER_Y + SCOPE_RADIUS + 15;

		String powerStr  = String.format("PWR: %d%%", (int)(radar.getPowerScaled(100)));
		int    powerColor = radar.getPower() > 10000 ? COLOR_CYAN : 0xFF0000;
		this.fontRenderer.drawString(powerStr, statusX, statusY, powerColor);

		this.fontRenderer.drawString("MODE: " + radar.getOperatingMode(), statusX + 60, statusY, COLOR_CYAN);

		String contacts = String.format("TGT: %d", radar.getContactCount());
		this.fontRenderer.drawString(contacts, statusX + 120, statusY, COLOR_CYAN);

		if (radar.isRadarActive()) {
			// Show AESA/DBF status on second line
			this.fontRenderer.drawString("AESA/DBF ACTIVE", statusX, statusY + 10, COLOR_CYAN_DIM);
		} else {
			this.fontRenderer.drawString("RADAR OFFLINE", statusX, statusY + 10, 0xFF0000);
		}
	}

	@Override
	protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
		super.drawDefaultBackground();
		GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
		Minecraft.getMinecraft().getTextureManager().bindTexture(texture);
		drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, ySize);
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		super.drawScreen(mouseX, mouseY, partialTicks);
		super.renderHoveredToolTip(mouseX, mouseY);
	}
}
