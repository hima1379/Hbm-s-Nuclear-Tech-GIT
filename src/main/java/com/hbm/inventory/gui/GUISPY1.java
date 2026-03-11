package com.hbm.inventory.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.lwjgl.opengl.GL11;

import com.hbm.entity.missile.EntityMissileSM6;
import com.hbm.inventory.container.ContainerSPY1;
import com.hbm.lib.RefStrings;
import com.hbm.main.tileentity.network.data.TileEntitySPY1;
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
 * SPY-1 Phased Array Radar GUI
 * PPI (Plan Position Indicator) display - circular, 360-degree coverage
 * Ground-based phased array radar with electronic scanning
 */
public class GUISPY1 extends GuiInfoContainer {

	private static final ResourceLocation texture = new ResourceLocation(RefStrings.MODID, "textures/gui/gui_spy1.png");
	private TileEntitySPY1 radar;

	// PPI radar scope dimensions (circular display)
	private static final int SCOPE_CENTER_X = 90;
	private static final int SCOPE_CENTER_Y = 80;
	private static final int SCOPE_RADIUS = 60;

	// GUI dimensions
	private static final int GUI_WIDTH = 256;
	private static final int GUI_HEIGHT = 166;

	public GUISPY1(InventoryPlayer invPlayer, TileEntitySPY1 teRadar) {
		super(new ContainerSPY1(invPlayer, teRadar));
		this.radar = teRadar;
		this.xSize = GUI_WIDTH;
		this.ySize = GUI_HEIGHT;
	}

	@Override
	protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
		// Draw title
		String title = "SPY-1 PHASED ARRAY RADAR";
		int titleWidth = this.fontRenderer.getStringWidth(title);
		this.fontRenderer.drawString(title, (xSize - titleWidth) / 2, 6, 0x00FF00);

		// Draw radar scope
		drawRadarScope();

		// Draw contact list
		drawContactList();

		// Draw system status
		drawSystemStatus();
	}

	/**
	 * Draw PPI (Plan Position Indicator) radar display
	 * Circular display with 360-degree coverage
	 */
	private void drawRadarScope() {
		GlStateManager.pushMatrix();
		GlStateManager.disableTexture2D();
		GlStateManager.enableBlend();
		GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

		// Draw circular radar background
		drawCircle(SCOPE_CENTER_X, SCOPE_CENTER_Y, SCOPE_RADIUS, 0x80001400, true);

		// Draw radar grid (PPI style - circular)
		drawRadarGrid();

		// Draw scan line (rotating beam)
		drawScanLine();

		// Draw detected targets
		drawTargets();

		// ★★★ MODIFICATION: Draw friendly missiles as blue markers ★★★
		drawFriendlyMissiles();

		GlStateManager.disableBlend();
		GlStateManager.enableTexture2D();
		GlStateManager.popMatrix();
	}

	/**
	 * Draw PPI radar grid (circular with range rings and azimuth lines)
	 */
	private void drawRadarGrid() {
		GlStateManager.color(0.0F, 1.0F, 0.0F, 0.3F);
		GlStateManager.glLineWidth(1.0F);

		Tessellator tess = Tessellator.getInstance();
		BufferBuilder buf = tess.getBuffer();

		// Draw range rings (concentric circles)
		int numRings = 4;
		for (int i = 1; i <= numRings; i++) {
			int ringRadius = (SCOPE_RADIUS * i) / numRings;
			drawCircle(SCOPE_CENTER_X, SCOPE_CENTER_Y, ringRadius, 0x40008800, false);
		}

		// Draw azimuth lines (radial lines every 45 degrees)
		buf.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION);
		for (int deg = 0; deg < 360; deg += 45) {
			double rad = Math.toRadians(deg);
			double dx = Math.sin(rad) * SCOPE_RADIUS;
			double dy = -Math.cos(rad) * SCOPE_RADIUS;  // Negative because screen Y is inverted

			buf.pos(SCOPE_CENTER_X, SCOPE_CENTER_Y, 0).endVertex();
			buf.pos(SCOPE_CENTER_X + dx, SCOPE_CENTER_Y + dy, 0).endVertex();
		}
		tess.draw();

		// Draw cardinal directions (N, E, S, W)
		GlStateManager.enableTexture2D();
		GlStateManager.color(0.0F, 1.0F, 0.0F, 1.0F);

		// North (0°)
		this.fontRenderer.drawString("N", SCOPE_CENTER_X - 3, SCOPE_CENTER_Y - SCOPE_RADIUS - 10, 0x00FF00);
		// East (90°)
		this.fontRenderer.drawString("E", SCOPE_CENTER_X + SCOPE_RADIUS + 3, SCOPE_CENTER_Y - 3, 0x00FF00);
		// South (180°)
		this.fontRenderer.drawString("S", SCOPE_CENTER_X - 3, SCOPE_CENTER_Y + SCOPE_RADIUS + 3, 0x00FF00);
		// West (270°)
		this.fontRenderer.drawString("W", SCOPE_CENTER_X - SCOPE_RADIUS - 10, SCOPE_CENTER_Y - 3, 0x00FF00);

		GlStateManager.disableTexture2D();
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
	}

	/**
	 * Draw rotating scan line (beam position)
	 */
	private void drawScanLine() {
		if (!radar.isRadarActive()) return;

		double currentAzimuth = radar.getCurrentAzimuth();

		// Convert azimuth to radians (0° = North, clockwise)
		double rad = Math.toRadians(currentAzimuth);
		double dx = Math.sin(rad) * SCOPE_RADIUS;
		double dy = -Math.cos(rad) * SCOPE_RADIUS;

		// Draw scan line from center to edge (yellow, semi-transparent)
		GlStateManager.color(1.0F, 1.0F, 0.0F, 0.6F);
		GlStateManager.glLineWidth(2.0F);

		Tessellator tess = Tessellator.getInstance();
		BufferBuilder buf = tess.getBuffer();
		buf.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION);
		buf.pos(SCOPE_CENTER_X, SCOPE_CENTER_Y, 0).endVertex();
		buf.pos(SCOPE_CENTER_X + dx, SCOPE_CENTER_Y + dy, 0).endVertex();
		tess.draw();

		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
	}

	/**
	 * Draw detected targets on PPI scope
	 */
	private void drawTargets() {
		if (!radar.isRadarActive()) return;

		Map<UUID, RadarContact> contacts = radar.getContacts();
		if (contacts.isEmpty()) return;

		GlStateManager.enableTexture2D();
		GlStateManager.enableBlend();

		RadarSpec spec = radar.getRadarSpec();
		double maxRange = spec.maxRange * 1000.0;  // km to blocks

		for (RadarContact contact : contacts.values()) {
			if (contact.entity == null || contact.entity.isDead) continue;

			// Calculate position on PPI scope
			// Azimuth: angle from north (clockwise)
			// Distance: radius from center
			double rangeNorm = Math.min(1.0, contact.distance / maxRange);
			double radius = rangeNorm * SCOPE_RADIUS;

			double rad = Math.toRadians(contact.azimuth);
			double dx = Math.sin(rad) * radius;
			double dy = -Math.cos(rad) * radius;

			int screenX = (int)(SCOPE_CENTER_X + dx);
			int screenY = (int)(SCOPE_CENTER_Y + dy);

			// Draw target marker (different colors based on type/stealth)
			double stealthFactor = RadarStealthCapability.calculateStealthFactor(contact.entity);
			int color = getTargetColor(stealthFactor);

			// Draw cross marker
			drawCrossMarker(screenX, screenY, 4, color);

			// Draw range text for targets in outer ring
			if (rangeNorm > 0.6) {
				String rangeStr = String.format("%.0f", contact.distance / 1000.0);
				this.fontRenderer.drawString(rangeStr, screenX + 5, screenY - 4, color);
			}
		}

		GlStateManager.disableBlend();
	}

	/**
	 * ★★★ MODIFICATION: Draw friendly missiles (SM-6) as blue markers ★★★
	 * Display missiles from BlueCable-connected launchers regardless of radar detection
	 */
	private void drawFriendlyMissiles() {
		Map<UUID, Integer> activeMissiles = radar.getActiveMissiles();
		if (activeMissiles.isEmpty()) return;

		// Get radar position for distance/azimuth calculation
		double radarX = radar.getPos().getX();
		double radarY = radar.getPos().getY();
		double radarZ = radar.getPos().getZ();

		// Get radar spec for max range
		RadarSpec spec = radar.getRadarSpec();
		double maxRange = spec.maxRange * 1000.0;  // km to blocks

		// Get all SM-6 missiles in the world
		List<EntityMissileSM6> missiles = radar.getWorld().getEntities(EntityMissileSM6.class, entity -> true);

		GlStateManager.enableTexture2D();
		GlStateManager.enableBlend();

		// Draw each active missile as blue marker
		for (UUID missileId : activeMissiles.keySet()) {
			// Find the missile entity
			EntityMissileSM6 missile = null;
			for (EntityMissileSM6 m : missiles) {
				if (m.getDeviceId().equals(missileId)) {
					missile = m;
					break;
				}
			}

			// Skip if missile not found or dead
			if (missile == null || missile.isDead) continue;

			// Calculate range and azimuth from radar to missile
			double dx = missile.posX - radarX;
			double dz = missile.posZ - radarZ;
			double distance = Math.sqrt(dx * dx + dz * dz);

			// Calculate azimuth (0° = North, clockwise)
			// North = -Z direction, East = +X direction
			double azimuth = Math.toDegrees(Math.atan2(dx, -dz));
			if (azimuth < 0) azimuth += 360.0;

			// Calculate position on PPI scope
			double rangeNorm = Math.min(1.0, distance / maxRange);
			double radius = rangeNorm * SCOPE_RADIUS;

			double rad = Math.toRadians(azimuth);
			double scopeDx = Math.sin(rad) * radius;
			double scopeDy = -Math.cos(rad) * radius;

			int screenX = (int)(SCOPE_CENTER_X + scopeDx);
			int screenY = (int)(SCOPE_CENTER_Y + scopeDy);

			// Draw blue cross marker (0x0088FF = bright blue)
			drawCrossMarker(screenX, screenY, 5, 0x0088FF);

			// Draw missile label
			String label = "SM6";
			this.fontRenderer.drawString(label, screenX + 6, screenY - 4, 0x0088FF);
		}

		GlStateManager.disableBlend();
	}

	/**
	 * Draw circle (filled or outline)
	 */
	private void drawCircle(int centerX, int centerY, int radius, int color, boolean filled) {
		float a = ((color >> 24) & 0xFF) / 255.0F;
		float r = ((color >> 16) & 0xFF) / 255.0F;
		float g = ((color >> 8) & 0xFF) / 255.0F;
		float b = (color & 0xFF) / 255.0F;

		GlStateManager.color(r, g, b, a);

		Tessellator tess = Tessellator.getInstance();
		BufferBuilder buf = tess.getBuffer();

		int segments = 64;

		if (filled) {
			buf.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION);
			buf.pos(centerX, centerY, 0).endVertex();
			for (int i = 0; i <= segments; i++) {
				double angle = 2.0 * Math.PI * i / segments;
				double x = centerX + radius * Math.cos(angle);
				double y = centerY + radius * Math.sin(angle);
				buf.pos(x, y, 0).endVertex();
			}
		} else {
			buf.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION);
			for (int i = 0; i < segments; i++) {
				double angle = 2.0 * Math.PI * i / segments;
				double x = centerX + radius * Math.cos(angle);
				double y = centerY + radius * Math.sin(angle);
				buf.pos(x, y, 0).endVertex();
			}
		}

		tess.draw();
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
	}

	/**
	 * Get target marker color based on stealth factor
	 */
	private int getTargetColor(double stealthFactor) {
		if (stealthFactor >= 0.9) {
			return 0xFF8888;  // Red - stealth target (hard to detect)
		} else if (stealthFactor >= 0.5) {
			return 0xFFFF88;  // Yellow - moderate stealth
		} else {
			return 0x88FF88;  // Green - normal target
		}
	}

	/**
	 * Draw cross marker for target
	 */
	private void drawCrossMarker(int x, int y, int size, int color) {
		float r = ((color >> 16) & 0xFF) / 255.0F;
		float g = ((color >> 8) & 0xFF) / 255.0F;
		float b = (color & 0xFF) / 255.0F;

		GlStateManager.disableTexture2D();
		GlStateManager.color(r, g, b, 1.0F);
		GlStateManager.glLineWidth(2.0F);

		Tessellator tess = Tessellator.getInstance();
		BufferBuilder buf = tess.getBuffer();
		buf.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION);

		// Horizontal line
		buf.pos(x - size, y, 0).endVertex();
		buf.pos(x + size, y, 0).endVertex();

		// Vertical line
		buf.pos(x, y - size, 0).endVertex();
		buf.pos(x, y + size, 0).endVertex();

		tess.draw();

		GlStateManager.enableTexture2D();
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
	}

	/**
	 * Draw contact list (right side of scope)
	 */
	private void drawContactList() {
		int listX = SCOPE_CENTER_X + SCOPE_RADIUS + 15;
		int listY = SCOPE_CENTER_Y - SCOPE_RADIUS;

		this.fontRenderer.drawString("CONTACTS", listX, listY, 0x00FF00);

		if (!radar.isRadarActive()) {
			this.fontRenderer.drawString("RADAR OFF", listX, listY + 15, 0xFF0000);
			return;
		}

		Map<UUID, RadarContact> contacts = radar.getContacts();
		if (contacts.isEmpty()) {
			this.fontRenderer.drawString("NO CONTACTS", listX, listY + 15, 0x888888);
			return;
		}

		// Sort contacts by distance
		List<RadarContact> sortedContacts = new ArrayList<>(contacts.values());
		sortedContacts.sort((a, b) -> Double.compare(a.distance, b.distance));

		// Display up to 8 closest contacts
		int maxDisplay = Math.min(8, sortedContacts.size());
		for (int i = 0; i < maxDisplay; i++) {
			RadarContact contact = sortedContacts.get(i);
			if (contact.entity == null) continue;

			int textY = listY + 15 + (i * 10);

			// Draw contact info
			String name = contact.entity.getName();
			if (name.length() > 10) {
				name = name.substring(0, 10);
			}

			String info = String.format("%s %.0fkm", name, contact.distance / 1000.0);

			double stealthFactor = RadarStealthCapability.calculateStealthFactor(contact.entity);
			int color = getTargetColor(stealthFactor);

			this.fontRenderer.drawString(info, listX, textY, color);
		}

		// Show total count if more than displayed
		if (sortedContacts.size() > maxDisplay) {
			this.fontRenderer.drawString("+" + (sortedContacts.size() - maxDisplay) + " more",
					listX, listY + 15 + (maxDisplay * 10), 0x888888);
		}
	}

	/**
	 * Draw system status (bottom area)
	 */
	private void drawSystemStatus() {
		int statusX = SCOPE_CENTER_X - SCOPE_RADIUS;
		int statusY = SCOPE_CENTER_Y + SCOPE_RADIUS + 15;

		// Power status
		String powerStr = String.format("PWR: %d%%", (int)(radar.getPowerScaled(100)));
		int powerColor = radar.getPower() > 10000 ? 0x00FF00 : 0xFF0000;
		this.fontRenderer.drawString(powerStr, statusX, statusY, powerColor);

		// Mode
		String mode = radar.getOperatingMode();
		this.fontRenderer.drawString("MODE: " + mode, statusX + 60, statusY, 0x00FF00);

		// Contact count
		String contacts = String.format("TGT: %d", radar.getContactCount());
		this.fontRenderer.drawString(contacts, statusX + 120, statusY, 0x00FF00);

		// Scan status
		if (radar.isRadarActive()) {
			RadarSpec spec = radar.getRadarSpec();
			String scanInfo = String.format("SCAN: %.0f° BAR:%d/%d",
					radar.getCurrentAzimuth(), radar.getCurrentBar() + 1, spec.scanBars);
			this.fontRenderer.drawString(scanInfo, statusX, statusY + 10, 0x888888);
		} else {
			this.fontRenderer.drawString("RADAR OFFLINE", statusX, statusY + 10, 0xFF0000);
		}
	}

	@Override
	protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
		super.drawDefaultBackground();
		GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
		Minecraft.getMinecraft().getTextureManager().bindTexture(texture);

		// Draw main GUI background
		drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, ySize);
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		super.drawScreen(mouseX, mouseY, partialTicks);
		super.renderHoveredToolTip(mouseX, mouseY);
	}
}
