package com.hbm.inventory.gui;

import java.util.List;

import org.lwjgl.opengl.GL11;

import com.hbm.lib.RefStrings;
import com.hbm.main.tileentity.network.data.TileEntityFCSConsole.LostContactData;
import com.hbm.main.tileentity.network.data.TileEntityFCSConsole.RadarContactData;
import com.hbm.render.util.Vec2d;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

/**
 * Professional radar display widget for FCS Console
 * Inspired by ASM mod's NRadarGuiElement and real AN/SPY-1 radar displays
 *
 * Features:
 * - Circular radar display (configurable diameter)
 * - Military-style radar overlay texture with range rings and grid
 * - Target icons (diamond shapes, color-coded by signal strength)
 * - Track numbers (T001, T002, etc.)
 * - Mouse hover detection for tooltips
 * - Target selection on click
 * - Coordinate transformation (World → Radar → Screen)
 * - Zoom/scale control
 */
public class RadarDisplayWidget {

	private static final ResourceLocation RADAR_OVERLAY = new ResourceLocation(RefStrings.MODID, "textures/gui/radar_overlay.png");
	private static final ResourceLocation MISSILE_MARKER_TEXTURE = new ResourceLocation(RefStrings.MODID, "textures/gui/radar_missile_marker.png");

	private final int width;   // Display width in pixels
	private final int height;  // Display height in pixels

	// Display settings
	private double scale = 5000.0;  // Default: 5000 blocks (5km) = full display
	private double minScale = 100.0;  // Minimum 100 blocks (0.1km)
	private double maxScale = 500000.0;  // Maximum 500km

	// View panning (offset from radar center)
	private double viewCenterX = 0.0;  // X offset in blocks
	private double viewCenterZ = 0.0;  // Z offset in blocks

	// Display coordinates - tracks what world position is at the center of the display
	// This is used to calculate which elements are visible after panning
	private double displayCenterX = 0.0;  // World X coordinate at display center
	private double displayCenterZ = 0.0;  // World Z coordinate at display center

	// Selection state
	private RadarContactData selectedContact = null;
	private RadarContactData hoveredContact = null;

	// Player position for display
	private BlockPos playerPos = null;

	// Colors
	private static final int COLOR_BACKGROUND = 0xFF0A0F0A;  // Dark green background
	private static final int COLOR_GRID = 0xFF1A3F1A;        // Grid lines
	private static final int COLOR_BORDER = 0xFF2A5F2A;      // Border
	private static final int COLOR_CENTER = 0xFF00FF00;      // Center crosshair
	private static final int COLOR_TEXT = 0xFFFFFFFF;        // White text

	// Signal strength color gradient (red to green)
	private static final int[] SIGNAL_COLORS = {
		0xFFFF0000,  // 0.0-0.2: Red (very weak)
		0xFFFF8800,  // 0.2-0.4: Orange (weak)
		0xFFFFFF00,  // 0.4-0.6: Yellow (medium)
		0xFF88FF00,  // 0.6-0.8: Yellow-green (good)
		0xFF00FF00   // 0.8-1.0: Green (strong)
	};

	public RadarDisplayWidget(int width, int height) {
		this.width = width;
		this.height = height;
	}

	/**
	 * Render the radar display widget
	 * @param x Widget X position (GUI-relative)
	 * @param y Widget Y position (GUI-relative)
	 * @param guiLeft GUI's left position in screen coordinates
	 * @param guiTop GUI's top position in screen coordinates
	 * @param contacts List of radar contacts to display
	 * @param radarPos Position of the radar in the world (for coordinate calculation)
	 */
	public void render(int x, int y, int guiLeft, int guiTop, List<RadarContactData> contacts, BlockPos radarPos) {
		render(x, y, guiLeft, guiTop, contacts, radarPos, null);
	}

	/**
	 * Illumination predicate - checks if a target is being illuminated by SPG-62
	 */
	public interface IlluminationPredicate {
		boolean isIlluminated(int entityId);
	}

	/**
	 * Render the radar display widget with player position
	 * @param x Widget X position (GUI-relative)
	 * @param y Widget Y position (GUI-relative)
	 * @param guiLeft GUI's left position in screen coordinates
	 * @param guiTop GUI's top position in screen coordinates
	 * @param contacts List of radar contacts to display
	 * @param radarPos Position of the radar in the world (for coordinate calculation)
	 * @param playerPos Player position (for debug display)
	 */
	public void render(int x, int y, int guiLeft, int guiTop, List<RadarContactData> contacts, BlockPos radarPos, BlockPos playerPos) {
		render(x, y, guiLeft, guiTop, contacts, radarPos, playerPos, null);
	}

	/**
	 * Render the radar display widget with player position and illumination callback
	 * @param x Widget X position (GUI-relative)
	 * @param y Widget Y position (GUI-relative)
	 * @param guiLeft GUI's left position in screen coordinates
	 * @param guiTop GUI's top position in screen coordinates
	 * @param contacts List of radar contacts to display
	 * @param radarPos Position of the radar in the world (for coordinate calculation)
	 * @param playerPos Player position (for debug display)
	 * @param illuminationCheck Callback to check if a target is illuminated
	 */
	public void render(int x, int y, int guiLeft, int guiTop, List<RadarContactData> contacts, BlockPos radarPos, BlockPos playerPos, IlluminationPredicate illuminationCheck) {
		this.playerPos = playerPos;

		// Update display coordinates (view center in world coordinates)
		// Display center = radar position + view offset
		this.displayCenterX = viewCenterX;
		this.displayCenterZ = viewCenterZ;

		// Save GL state
		GlStateManager.pushMatrix();
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

		// Calculate center position (center of rectangular display)
		int centerX = x + width / 2;
		int centerY = y + height / 2;

		// Render background
		renderBackground(x, y);

		// ===== COORDINATE TRANSFORMATION =====
		// Apply transformations in radar coordinate space
		GlStateManager.pushMatrix();

		// 1. Translate to center of radar display
		GL11.glTranslated(centerX, centerY, 0.0);

		// 2. Apply zoom scale (converts radar coordinates to screen pixels)
		// Use the smaller dimension to ensure everything fits
		double displaySize = Math.min(width, height);
		double zoomScale = displaySize / scale;  // pixels per block
		GL11.glScaled(zoomScale, zoomScale, 1.0);

		// 3. Apply view center offset (pan)
		GL11.glTranslated(-viewCenterX, -viewCenterZ, 0.0);

		// Disable texture for programmatic rendering
		GlStateManager.disableTexture2D();

		// Draw dynamic grid elements in RADAR COORDINATES (zoom-responsive)
		renderDynamicGrid(radarPos);

		// Draw radar position marker
		renderRadarPosition();

		// Draw player position
		if (playerPos != null && radarPos != null) {
			renderPlayerPosition(playerPos, radarPos);
		}

		// Enable texture for contact rendering
		GlStateManager.enableTexture2D();

		// Restore transformation matrix BEFORE rendering contacts
		// This allows contacts to be rendered in SCREEN COORDINATES
		GlStateManager.popMatrix();

		// Render contacts in SCREEN coordinates (after matrix pop)
		// This fixes the issue where extreme zoom levels cause markers to be clipped
		if (contacts != null && radarPos != null) {
			renderContactsInScreenCoords(contacts, radarPos, x, y, illuminationCheck);
		}

		// Render UI elements in screen coordinates (outside transformation)
		GlStateManager.enableTexture2D();
		renderScaleIndicator(x, y);
		renderDebugInfo(x, y, radarPos);

		// Restore GL state
		GlStateManager.popMatrix();
	}

	/**
	 * Render background (no drawing - GUI texture already has black background)
	 */
	private void renderBackground(int x, int y) {
		// No background rendering needed - the GUI texture already has the black background
		// Drawing anything here would cover the GUI texture
	}

	/**
	 * Render dynamic grid IN RADAR COORDINATES (meters/blocks)
	 * This makes it automatically zoom-responsive through OpenGL transformations
	 * Grid is pan-responsive but clipped to prevent rendering outside display area
	 */
	private void renderDynamicGrid(BlockPos radarPos) {
		GlStateManager.enableBlend();
		GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

		// Draw range rings at 1km and 5km intervals
		// 1km rings: thin, semi-transparent
		// 5km rings: brighter, more visible
		// Maximum: 500km (500000 blocks)

		// Calculate distance from radar (0, 0) to display center (view center)
		// This represents how far we've panned from the radar position
		double distToDisplayCenter = Math.sqrt(
			displayCenterX * displayCenterX +
			displayCenterZ * displayCenterZ
		);

		// Draw range rings up to a reasonable maximum
		// Each circle is individually clipped by drawCircleInRadarCoords
		int maxRange = 500000;  // 500km max

		// Draw 1km interval rings (thin, semi-transparent)
		// All circles centered at radar position (0, 0)
		for (int km = 1; km * 1000 <= maxRange; km++) {
			double rangeBlocks = km * 1000.0;

			// Check if this is a 5km interval
			boolean is5kmInterval = (km % 5 == 0);

			if (is5kmInterval) {
				// 5km rings: bright green, more visible
				GL11.glColor4f(0.0f, 1.0f, 0.0f, 0.6f);
				GL11.glLineWidth(2.0f);
			} else {
				// 1km rings: dim green, semi-transparent
				GL11.glColor4f(0.0f, 0.8f, 0.0f, 0.2f);
				GL11.glLineWidth(1.0f);
			}

			// Draw circle with automatic clipping to display boundary
			drawCircleInRadarCoords(0, 0, rangeBlocks);
		}

		// Draw cardinal direction lines (N, E, S, W) with clipping
		// Lines are clipped to the rectangular display boundary
		double displaySize = Math.min(width, height);
		double zoomScale = displaySize / scale;

		GL11.glColor4f(0.0f, 1.0f, 0.0f, 0.5f);
		GL11.glLineWidth(1.0f);

		// Calculate line endpoints that intersect the display boundary
		// Display boundary in radar coords: circle with radius scale centered at viewCenter
		double displayRadius = scale;

		// North-South line (vertical, Z axis)
		drawClippedLine(0, -displayRadius * 2, 0, displayRadius * 2, zoomScale);

		// East-West line (horizontal, X axis)
		drawClippedLine(-displayRadius * 2, 0, displayRadius * 2, 0, zoomScale);

		// Draw diagonal lines (NE, SE, SW, NW at 45 degrees)
		GL11.glColor4f(0.0f, 1.0f, 0.0f, 0.3f);

		double diagonalLength = displayRadius * 2;

		// NE-SW diagonal
		drawClippedLine(-diagonalLength, -diagonalLength, diagonalLength, diagonalLength, zoomScale);

		// NW-SE diagonal
		drawClippedLine(-diagonalLength, diagonalLength, diagonalLength, -diagonalLength, zoomScale);

		GlStateManager.disableBlend();
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
	}

	/**
	 * Draw a line in radar coordinates with clipping to rectangular display boundary
	 * Line is clipped to only draw the portion inside the rectangular display
	 */
	private void drawClippedLine(double x1, double z1, double x2, double z2, double zoomScale) {
		// Clip the line to the rectangular display boundary
		// Display boundary: rectangle from (-width/2, -height/2) to (+width/2, +height/2)

		// Transform endpoints to screen coordinates
		double sx1 = (x1 - viewCenterX) * zoomScale;
		double sz1 = (z1 - viewCenterZ) * zoomScale;
		double sx2 = (x2 - viewCenterX) * zoomScale;
		double sz2 = (z2 - viewCenterZ) * zoomScale;

		// Calculate rectangular bounds
		double halfWidth = width / 2.0;
		double halfHeight = height / 2.0;

		// Check if endpoints are inside the display rectangle
		boolean inside1 = Math.abs(sx1) <= halfWidth && Math.abs(sz1) <= halfHeight;
		boolean inside2 = Math.abs(sx2) <= halfWidth && Math.abs(sz2) <= halfHeight;

		if (!inside1 && !inside2) {
			// Both endpoints outside - skip for simplicity
			// (a complete implementation would calculate intersection points)
			return;
		}

		// At least one endpoint is inside - draw the line
		GL11.glBegin(GL11.GL_LINES);
		GL11.glVertex2d(x1, z1);
		GL11.glVertex2d(x2, z2);
		GL11.glEnd();
	}

	/**
	 * Draw a circle in radar coordinates with clipping to rectangular display boundary
	 * Each point is checked to ensure it's within the rectangular display area
	 */
	private void drawCircleInRadarCoords(double centerX, double centerZ, double radiusBlocks) {
		int segments = 128;  // More segments for smoother clipping

		// Rectangular display boundary (after transformation)
		// Display center is at (0, 0) in transformed coordinates
		// Display extends from (-width/2, -height/2) to (+width/2, +height/2)

		// Calculate zoomScale (same as in render method)
		double displaySize = Math.min(width, height);
		double zoomScale = displaySize / scale;

		// Calculate rectangular bounds in transformed space
		double halfWidth = width / 2.0;
		double halfHeight = height / 2.0;

		GL11.glBegin(GL11.GL_LINE_STRIP);
		boolean drawingSegment = false;

		for (int i = 0; i <= segments; i++) {
			double angle = 2.0 * Math.PI * i / segments;
			double x = centerX + radiusBlocks * Math.cos(angle);
			double z = centerZ + radiusBlocks * Math.sin(angle);

			// Transform to screen coordinates (relative to display center)
			double screenX = (x - viewCenterX) * zoomScale;
			double screenZ = (z - viewCenterZ) * zoomScale;

			// Check if this point is inside the rectangular display boundary
			boolean insideDisplay = Math.abs(screenX) <= halfWidth && Math.abs(screenZ) <= halfHeight;

			if (insideDisplay) {
				// Point is inside - draw it
				if (!drawingSegment) {
					// Start a new segment
					GL11.glEnd();
					GL11.glBegin(GL11.GL_LINE_STRIP);
					drawingSegment = true;
				}
				GL11.glVertex2d(x, z);
			} else {
				// Point is outside - skip it
				if (drawingSegment) {
					// End current segment
					GL11.glEnd();
					GL11.glBegin(GL11.GL_LINE_STRIP);
					drawingSegment = false;
				}
			}
		}

		GL11.glEnd();
	}

	/**
	 * Render SPY-1 radar position marker (at radar coords origin 0,0)
	 * Only draws if inside the display boundary
	 */
	private void renderRadarPosition() {
		// Check if radar position is visible (inside rectangular display boundary)
		double displaySize = Math.min(width, height);
		double zoomScale = displaySize / scale;
		double screenX = (0 - viewCenterX) * zoomScale;
		double screenZ = (0 - viewCenterZ) * zoomScale;

		double halfWidth = width / 2.0;
		double halfHeight = height / 2.0;

		if (Math.abs(screenX) > halfWidth || Math.abs(screenZ) > halfHeight) {
			// Radar position is outside display - don't draw
			return;
		}

		GlStateManager.enableBlend();
		GL11.glColor4f(0.0f, 1.0f, 1.0f, 1.0f);  // Cyan color
		GL11.glLineWidth(2.0f);

		// Draw crosshair at radar position (0, 0)
		double size = scale / 100.0;  // Scale-relative size

		// Draw crosshair lines with clipping
		drawClippedLine(0, -size, 0, size, zoomScale);  // Vertical
		drawClippedLine(-size, 0, size, 0, zoomScale);  // Horizontal

		// Draw circle around radar position
		drawCircleInRadarCoords(0, 0, size * 0.7);

		GlStateManager.disableBlend();
	}

	/**
	 * Render player position triangle in radar coordinates
	 * Only draws if inside the display boundary
	 */
	private void renderPlayerPosition(BlockPos playerPos, BlockPos radarPos) {
		// Calculate relative position
		double relX = playerPos.getX() - radarPos.getX();
		double relZ = playerPos.getZ() - radarPos.getZ();

		// Check if player position is visible (inside rectangular display boundary)
		double displaySize = Math.min(width, height);
		double zoomScale = displaySize / scale;
		double screenX = (relX - viewCenterX) * zoomScale;
		double screenZ = (relZ - viewCenterZ) * zoomScale;

		double halfWidth = width / 2.0;
		double halfHeight = height / 2.0;

		if (Math.abs(screenX) > halfWidth || Math.abs(screenZ) > halfHeight) {
			// Player position is outside display - don't draw
			return;
		}

		GlStateManager.pushMatrix();
		GL11.glTranslated(relX, relZ, 0.0);

		// Draw filled triangle (pointing up = North)
		GL11.glColor4f(1.0f, 0.0f, 0.0f, 0.8f);  // Red, semi-transparent

		// CHANGED: Player marker size now zoom-dependent (scales with zoom level)
		// This makes it smaller when zoomed out, larger when zoomed in
		double triSize = scale / 50.0;  // Zoom-dependent size

		GL11.glBegin(GL11.GL_TRIANGLES);
		GL11.glVertex2d(0.0, -triSize);      // Top (North)
		GL11.glVertex2d(-triSize * 0.6, triSize * 0.6);  // Bottom-left
		GL11.glVertex2d(triSize * 0.6, triSize * 0.6);   // Bottom-right
		GL11.glEnd();

		GlStateManager.popMatrix();
	}

	/**
	 * Render contacts in SCREEN coordinates (after matrix transformation)
	 * This fixes issues with extreme zoom levels causing marker clipping
	 */
	private void renderContactsInScreenCoords(List<RadarContactData> contacts, BlockPos radarPos, int guiX, int guiY, IlluminationPredicate illuminationCheck) {
		System.out.println("[RADAR WIDGET] renderContactsInScreenCoords() called with " + contacts.size() + " contacts");

		GlStateManager.disableTexture2D();
		GlStateManager.enableBlend();

		// Calculate display parameters
		double displaySize = Math.min(width, height);
		double zoomScale = displaySize / scale;

		// Calculate display center in screen coordinates
		int centerX = guiX + width / 2;
		int centerY = guiY + height / 2;

		int drawnCount = 0;
		int skippedCount = 0;

		for (RadarContactData contact : contacts) {
			// Calculate relative position (radar coordinates)
			double relX = contact.x - radarPos.getX();
			double relZ = contact.z - radarPos.getZ();

			// Transform to screen coordinates
			double screenX = centerX + (relX - viewCenterX) * zoomScale;
			double screenY = centerY + (relZ - viewCenterZ) * zoomScale;

			System.out.println("[RADAR WIDGET] Contact " + contact.trackNumber +
				" | Radar=(" + String.format("%.1f", relX) + ", " + String.format("%.1f", relZ) + ")" +
				" | Screen=(" + String.format("%.1f", screenX) + ", " + String.format("%.1f", screenY) + ")");

			// Check if contact is within display bounds
			if (screenX < guiX || screenX > guiX + width || screenY < guiY || screenY > guiY + height) {
				System.out.println("[RADAR WIDGET]   ✗ SKIPPED - Outside display bounds");
				skippedCount++;
				continue;
			}

			// Check if target is illuminated
			boolean isIlluminated = (illuminationCheck != null && illuminationCheck.isIlluminated(contact.entityId));

			// Draw marker in screen coordinates
			boolean isSelected = (selectedContact != null && selectedContact.entityId == contact.entityId);
			renderContactMarkerInScreenSpace(screenX, screenY, contact, isSelected, isIlluminated);
			drawnCount++;
		}

		System.out.println("[RADAR WIDGET] Rendered " + drawnCount + " contacts, skipped " + skippedCount);

		GlStateManager.disableBlend();
		GlStateManager.enableTexture2D();
	}

	/**
	 * Render × "killed" markers and "lost" text for contacts whose entity has died.
	 * Call this after render() to overlay the markers on top of the radar display.
	 *
	 * @param x         Widget X position (GUI-relative)
	 * @param y         Widget Y position (GUI-relative)
	 * @param lostContacts List of killed contacts
	 * @param radarPos  Position of the radar block in world coords
	 */
	public void renderLostContacts(int x, int y, java.util.List<LostContactData> lostContacts, BlockPos radarPos) {
		if (lostContacts == null || lostContacts.isEmpty() || radarPos == null) return;

		double displaySize = Math.min(width, height);
		double zoomScale = displaySize / scale;
		int centerX = x + width / 2;
		int centerY = y + height / 2;

		final double X_SIZE = 8.0;  // half-size of the × mark in pixels

		GlStateManager.disableTexture2D();
		GlStateManager.enableBlend();
		GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

		// Draw × marks first (all in one GL state)
		GL11.glColor4f(1.0f, 0.4f, 0.0f, 1.0f);  // Orange-red — distinct from live red contacts
		GL11.glLineWidth(2.5f);

		for (LostContactData lc : lostContacts) {
			double relX = lc.x - radarPos.getX();
			double relZ = lc.z - radarPos.getZ();
			double screenX = centerX + (relX - viewCenterX) * zoomScale;
			double screenY = centerY + (relZ - viewCenterZ) * zoomScale;

			// Clip to display bounds
			if (screenX < x || screenX > x + width || screenY < y || screenY > y + height) continue;

			// \ diagonal
			GL11.glBegin(GL11.GL_LINES);
			GL11.glVertex2d(screenX - X_SIZE, screenY - X_SIZE);
			GL11.glVertex2d(screenX + X_SIZE, screenY + X_SIZE);
			GL11.glEnd();

			// / diagonal
			GL11.glBegin(GL11.GL_LINES);
			GL11.glVertex2d(screenX + X_SIZE, screenY - X_SIZE);
			GL11.glVertex2d(screenX - X_SIZE, screenY + X_SIZE);
			GL11.glEnd();
		}

		GlStateManager.disableBlend();
		GlStateManager.enableTexture2D();

		// Draw track number + "lost" text (needs texture enabled)
		FontRenderer fontRenderer = Minecraft.getMinecraft().fontRenderer;
		for (LostContactData lc : lostContacts) {
			double relX = lc.x - radarPos.getX();
			double relZ = lc.z - radarPos.getZ();
			double screenX = centerX + (relX - viewCenterX) * zoomScale;
			double screenY = centerY + (relZ - viewCenterZ) * zoomScale;

			if (screenX < x || screenX > x + width || screenY < y || screenY > y + height) continue;

			// Track number below the × mark
			int trackW = fontRenderer.getStringWidth(lc.trackNumber);
			fontRenderer.drawString(lc.trackNumber, (int)(screenX - trackW / 2.0), (int)(screenY + X_SIZE + 2), 0xFF6600);

			// "lost" label below the track number
			int lostW = fontRenderer.getStringWidth("lost");
			fontRenderer.drawString("lost", (int)(screenX - lostW / 2.0), (int)(screenY + X_SIZE + 12), 0xFF6600);
		}
	}

	/**
	 * Render contacts in radar coordinates
	 * Only draws contacts inside the display boundary
	 */
	private void renderContactsInRadarCoords(List<RadarContactData> contacts, BlockPos radarPos) {
		System.out.println("[RADAR WIDGET] renderContactsInRadarCoords() called with " + contacts.size() + " contacts");
		System.out.println("[RADAR WIDGET] Radar position: " + radarPos);
		System.out.println("[RADAR WIDGET] Scale: " + scale + " | View center: (" + viewCenterX + ", " + viewCenterZ + ")");

		FontRenderer fontRenderer = Minecraft.getMinecraft().fontRenderer;
		double displaySize = Math.min(width, height);
		double zoomScale = displaySize / scale;

		double halfWidth = width / 2.0;
		double halfHeight = height / 2.0;

		int drawnCount = 0;
		int skippedCount = 0;

		for (RadarContactData contact : contacts) {
			// Calculate relative position in radar coordinates
			double relX = contact.x - radarPos.getX();
			double relZ = contact.z - radarPos.getZ();

			System.out.println("[RADAR WIDGET] Contact: Track=" + contact.trackNumber +
				" | Pos=(" + String.format("%.1f", contact.x) + ", " + String.format("%.1f", contact.z) + ")" +
				" | RelPos=(" + String.format("%.1f", relX) + ", " + String.format("%.1f", relZ) + ")");

			// Check if contact is visible (inside rectangular display boundary)
			double screenX = (relX - viewCenterX) * zoomScale;
			double screenZ = (relZ - viewCenterZ) * zoomScale;

			System.out.println("[RADAR WIDGET]   Screen=(" + String.format("%.1f", screenX) + ", " + String.format("%.1f", screenZ) + ")" +
				" | Bounds=±(" + String.format("%.1f", halfWidth) + ", " + String.format("%.1f", halfHeight) + ")");

			if (Math.abs(screenX) > halfWidth || Math.abs(screenZ) > halfHeight) {
				// Contact is outside display - don't draw
				System.out.println("[RADAR WIDGET]   ✗ SKIPPED - Outside display bounds");
				skippedCount++;
				continue;
			}

			// Get color based on signal strength
			int color = getSignalColor(contact.signalStrength);

			// Render contact icon with velocity vector
			boolean isSelected = (selectedContact != null &&
			                      selectedContact.entityId == contact.entityId);
			System.out.println("[RADAR WIDGET]   ✓ DRAWING marker at relPos(" + String.format("%.1f", relX) + ", " + String.format("%.1f", relZ) + ")");
			renderContactIconInRadarCoords(relX, relZ, contact, radarPos, color, isSelected);
			drawnCount++;

			// Render track number (needs to be scaled back to screen space for text)
			// Text rendering is complex in transformed space, so we'll skip it for now
			// and render it in a separate pass
		}

		System.out.println("[RADAR WIDGET] Rendering summary: Drew " + drawnCount + " contacts, Skipped " + skippedCount + " contacts");
	}

	/**
	 * Render contact marker in SCREEN SPACE (red circle + number)
	 * Matches new reference image: red circle outline with detection number
	 * If illuminated, draws × mark on top of the marker
	 */
	private void renderContactMarkerInScreenSpace(double screenX, double screenY, RadarContactData contact, boolean isSelected, boolean isIlluminated) {
		System.out.println("[MARKER RENDER SCREEN] Rendering at screen pos (" + screenX + ", " + screenY + ") | Illuminated: " + isIlluminated);

		// Fixed pixel sizes for screen-space rendering
		final double CIRCLE_RADIUS = 8.0;  // pixels
		final double VELOCITY_LINE_LENGTH = 40.0;  // pixels

		// Calculate velocity direction
		double velocityMagnitude = Math.sqrt(
			contact.velocityX * contact.velocityX +
			contact.velocityZ * contact.velocityZ
		);

		// Disable texture for primitive rendering
		GlStateManager.disableTexture2D();
		GlStateManager.enableBlend();
		GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

		// Draw circle outline: blue for friendly SM-6 missiles, red for enemy contacts
		if (contact.isMissile) {
			GL11.glColor4f(0.2f, 0.5f, 1.0f, 0.9f);  // Blue for friendly missiles
		} else {
			GL11.glColor4f(1.0f, 0.0f, 0.0f, 0.9f);  // Red for enemy targets
		}
		GL11.glLineWidth(2.0f);
		GL11.glBegin(GL11.GL_LINE_LOOP);
		int segments = 20;
		for (int i = 0; i < segments; i++) {
			double angle = 2.0 * Math.PI * i / segments;
			double dx = CIRCLE_RADIUS * Math.cos(angle);
			double dy = CIRCLE_RADIUS * Math.sin(angle);
			GL11.glVertex2d(screenX + dx, screenY + dy);
		}
		GL11.glEnd();

		System.out.println("[MARKER RENDER SCREEN] Drew " + (contact.isMissile ? "blue missile" : "red enemy") + " circle at (" + screenX + ", " + screenY + ") radius=" + CIRCLE_RADIUS);

		// Draw selection indicator if selected
		if (isSelected) {
			GL11.glColor4f(1.0f, 1.0f, 0.0f, 1.0f);  // Yellow
			GL11.glLineWidth(3.0f);
			GL11.glBegin(GL11.GL_LINE_LOOP);
			for (int i = 0; i < segments; i++) {
				double angle = 2.0 * Math.PI * i / segments;
				double dx = (CIRCLE_RADIUS + 2) * Math.cos(angle);
				double dy = (CIRCLE_RADIUS + 2) * Math.sin(angle);
				GL11.glVertex2d(screenX + dx, screenY + dy);
			}
			GL11.glEnd();
		}

		// Draw detection number (01, 02, etc.) in center of circle
		GlStateManager.enableTexture2D();
		FontRenderer fontRenderer = Minecraft.getMinecraft().fontRenderer;

		// Extract number from track number (e.g., "T063" -> "63")
		String trackNum = contact.trackNumber;
		String numberOnly = trackNum.replaceAll("[^0-9]", "");
		if (numberOnly.length() > 0) {
			// Pad with zero if single digit
			int num = Integer.parseInt(numberOnly);
			String displayNum = String.format("%02d", num);

			// Center the text
			int textWidth = fontRenderer.getStringWidth(displayNum);
			int textX = (int)(screenX - textWidth / 2.0);
			int textY = (int)(screenY - fontRenderer.FONT_HEIGHT / 2.0);

			// Draw white text with black shadow for visibility
			fontRenderer.drawString(displayNum, textX, textY, 0xFFFFFF);

			System.out.println("[MARKER RENDER SCREEN] Drew number '" + displayNum + "' at center");
		}

		// Draw velocity vector line (cyan) - from circle center
		GlStateManager.disableTexture2D();
		if (velocityMagnitude > 0.01) {
			// Normalize velocity direction
			double velocityDirX = contact.velocityX / velocityMagnitude;
			double velocityDirZ = contact.velocityZ / velocityMagnitude;

			// Calculate endpoint in screen space (fixed length)
			double velEndX = screenX + velocityDirX * VELOCITY_LINE_LENGTH;
			double velEndY = screenY + velocityDirZ * VELOCITY_LINE_LENGTH;

			GL11.glColor4f(0.0f, 1.0f, 1.0f, 0.8f);  // Cyan
			GL11.glLineWidth(2.0f);
			GL11.glBegin(GL11.GL_LINES);
			GL11.glVertex2d(screenX, screenY);
			GL11.glVertex2d(velEndX, velEndY);
			GL11.glEnd();

			System.out.println("[MARKER RENDER SCREEN] Drew velocity vector from (" + screenX + ", " + screenY + ") to (" + velEndX + ", " + velEndY + ")");
		}

		// Draw × mark if target is illuminated (SPG-62 lock-on)
		if (isIlluminated) {
			GlStateManager.disableTexture2D();
			GL11.glColor4f(0.0f, 1.0f, 0.0f, 1.0f);  // Green × mark (success indicator)
			GL11.glLineWidth(3.0f);

			// Draw × mark centered on the circle
			double xSize = CIRCLE_RADIUS * 0.7;  // Slightly smaller than circle radius

			// Draw \ line (top-left to bottom-right)
			GL11.glBegin(GL11.GL_LINES);
			GL11.glVertex2d(screenX - xSize, screenY - xSize);
			GL11.glVertex2d(screenX + xSize, screenY + xSize);
			GL11.glEnd();

			// Draw / line (top-right to bottom-left)
			GL11.glBegin(GL11.GL_LINES);
			GL11.glVertex2d(screenX + xSize, screenY - xSize);
			GL11.glVertex2d(screenX - xSize, screenY + xSize);
			GL11.glEnd();

			System.out.println("[MARKER RENDER SCREEN] Drew green × mark for illuminated target");
		}

		GlStateManager.disableBlend();
		GlStateManager.enableTexture2D();
		System.out.println("[MARKER RENDER SCREEN] Marker drawn successfully!");
	}

	/**
	 * Render a contact icon in radar coordinates with velocity vector
	 * Matches reference image: red/pink missile marker + cyan velocity line
	 */
	private void renderContactIconInRadarCoords(double x, double z, RadarContactData contact,
	                                             BlockPos radarPos, int color, boolean isSelected) {
		System.out.println("[MARKER RENDER] renderContactIconInRadarCoords() called for contact at (" + x + ", " + z + ")");

		GlStateManager.disableTexture2D();
		GlStateManager.enableBlend();

		// FIXED: Marker size should be CONSTANT in screen space (pixels), not world space
		// This ensures markers are always visible regardless of zoom level
		double displaySize = Math.min(width, height);
		double zoomScale = displaySize / scale;

		System.out.println("[MARKER RENDER]   displaySize=" + displaySize + " | scale=" + scale + " | zoomScale=" + zoomScale);

		// Define marker size in screen pixels (constant)
		final double MARKER_SCREEN_SIZE = 12.0;  // pixels (increased from 8 for better visibility)
		double markerScreenSize = MARKER_SCREEN_SIZE;
		if (isSelected) markerScreenSize *= 1.5;

		// Convert screen size to radar coordinates: radarSize = screenSize / zoomScale
		double size = markerScreenSize / zoomScale;

		System.out.println("[MARKER RENDER]   markerScreenSize=" + markerScreenSize + " pixels | size in radar coords=" + size);

		// Calculate velocity direction for missile heading
		double velocityMagnitude = Math.sqrt(
			contact.velocityX * contact.velocityX +
			contact.velocityZ * contact.velocityZ
		);

		// Missile heading angle (direction of travel)
		// atan2(velocityZ, velocityX) gives angle in radians
		// We need to convert to screen rotation where 0° = up (North)
		double headingAngle = Math.toDegrees(Math.atan2(contact.velocityX, -contact.velocityZ));

		// Draw missile marker (filled diamond/square - different from player's triangle)
		GL11.glPushMatrix();
		GL11.glTranslated(x, z, 0);

		// Rotate based on missile heading if it's moving
		if (velocityMagnitude > 0.01) {
			GL11.glRotated(headingAngle, 0, 0, 1);
		}

		// Use blue for friendly missiles, red/orange for targets
		if (contact.isMissile) {
			GL11.glColor4f(0.3f, 0.6f, 1.0f, 0.9f);  // Bright blue for missiles
		} else {
			GL11.glColor4f(1.0f, 0.5f, 0.2f, 0.9f);  // Orange/red for targets
		}

		// Draw filled triangle pointing up (missile direction) + center dot
		// Matching reference image: C:\Users\Makiy\OneDrive\ドキュメント\スクリーンショット 2025-12-27 205136.png
		double triangleSize = size * 1.5;

		System.out.println("[MARKER RENDER]   Drawing triangle with size=" + triangleSize + " at world pos (" + x + ", " + z + ")");

		GL11.glBegin(GL11.GL_TRIANGLES);
		GL11.glVertex2d(0, -triangleSize);           // Top (nose)
		GL11.glVertex2d(triangleSize * 0.6, triangleSize * 0.5);  // Bottom right
		GL11.glVertex2d(-triangleSize * 0.6, triangleSize * 0.5); // Bottom left
		GL11.glEnd();

		System.out.println("[MARKER RENDER]   Triangle drawn!");

		// Draw center dot (small filled circle) - match marker color
		if (contact.isMissile) {
			GL11.glColor4f(0.3f, 0.6f, 1.0f, 1.0f);  // Bright blue for missiles
		} else {
			GL11.glColor4f(1.0f, 0.5f, 0.2f, 1.0f);  // Orange/red for targets
		}
		double dotRadius = size * 0.3;
		int dotSegments = 8;
		GL11.glBegin(GL11.GL_TRIANGLE_FAN);
		GL11.glVertex2d(0, 0);  // Center
		for (int i = 0; i <= dotSegments; i++) {
			double angle = 2.0 * Math.PI * i / dotSegments;
			GL11.glVertex2d(dotRadius * Math.cos(angle), dotRadius * Math.sin(angle));
		}
		GL11.glEnd();

		System.out.println("[MARKER RENDER]   Center dot drawn!");

		// Draw outline if selected
		if (isSelected) {
			GL11.glColor4f(1.0f, 1.0f, 0.0f, 1.0f);  // Yellow outline
			GL11.glLineWidth(2.5f);
			GL11.glBegin(GL11.GL_LINE_LOOP);
			GL11.glVertex2d(0, -triangleSize);
			GL11.glVertex2d(triangleSize * 0.6, triangleSize * 0.5);
			GL11.glVertex2d(-triangleSize * 0.6, triangleSize * 0.5);
			GL11.glEnd();
		}

		GL11.glPopMatrix();

		// Draw velocity vector LINE (NO arrowhead - user specifically requested line only)
		// IMPORTANT: Draw velocity line in UNROTATED radar coordinates
		// We're back in the radar coord system (after popMatrix), NOT rotated with heading
		if (velocityMagnitude > 0.01) {  // Only draw if moving
			// FIXED: Velocity vector should be CONSTANT screen length, independent of zoom
			// This prevents the "extending" bug during zoom changes
			// Vector shows direction, with fixed visual length on screen
			final double VELOCITY_LINE_SCREEN_LENGTH = 40.0;  // pixels (constant screen length)

			// Normalize velocity direction
			double velocityDirX = contact.velocityX / velocityMagnitude;
			double velocityDirZ = contact.velocityZ / velocityMagnitude;

			// Convert screen length to radar coordinates
			double velocityLineLength = VELOCITY_LINE_SCREEN_LENGTH / zoomScale;

			// Calculate velocity vector endpoint (fixed screen length)
			double velEndX = x + velocityDirX * velocityLineLength;
			double velEndZ = z + velocityDirZ * velocityLineLength;

			System.out.println("[MARKER RENDER]   Drawing velocity vector from (" + x + ", " + z + ") to (" + velEndX + ", " + velEndZ + ")");
			System.out.println("[MARKER RENDER]   Velocity: (" + contact.velocityX + ", " + contact.velocityZ + ") | magnitude=" + velocityMagnitude);

			// Draw velocity vector line (cyan color matching reference image)
			GL11.glColor4f(0.0f, 1.0f, 1.0f, 0.8f);  // Cyan
			GL11.glLineWidth(2.0f);
			GL11.glBegin(GL11.GL_LINES);
			GL11.glVertex2d(x, z);              // Start: marker position
			GL11.glVertex2d(velEndX, velEndZ);  // End: position + velocity direction
			GL11.glEnd();

			System.out.println("[MARKER RENDER]   Velocity vector drawn!");

			// NO arrowhead - user wants line only!
		}

		GlStateManager.disableBlend();
		GlStateManager.enableTexture2D();
	}

	/**
	 * Transform world coordinates to screen coordinates
	 * @return Screen position, or null if outside rectangular display area
	 */
	private Vec2d worldToScreen(RadarContactData contact, BlockPos radarPos, int centerX, int centerY) {
		// Calculate relative position to radar, accounting for view center offset
		double dx = contact.x - radarPos.getX() - viewCenterX;
		double dz = contact.z - radarPos.getZ() - viewCenterZ;

		// Scale to screen coordinates
		double displaySize = Math.min(width, height);
		double zoomScale = displaySize / scale;

		double screenX = centerX + dx * zoomScale;
		double screenY = centerY + dz * zoomScale;

		// Check if inside rectangular display bounds
		if (screenX < 0 || screenX >= width || screenY < 0 || screenY >= height) {
			return null;
		}

		return new Vec2d(screenX, screenY);
	}

	/**
	 * Render a contact icon (diamond shape)
	 */
	private void renderContactIcon(double x, double y, int color, boolean isSelected) {
		GlStateManager.disableTexture2D();
		GlStateManager.enableBlend();

		// Extract RGB from color
		float r = ((color >> 16) & 0xFF) / 255.0f;
		float g = ((color >> 8) & 0xFF) / 255.0f;
		float b = (color & 0xFF) / 255.0f;

		int size = isSelected ? 5 : 3;  // Larger if selected

		// Draw filled diamond
		GL11.glColor4f(r, g, b, 0.8f);
		GL11.glBegin(GL11.GL_QUADS);
		GL11.glVertex2d(x, y - size);      // Top
		GL11.glVertex2d(x + size, y);      // Right
		GL11.glVertex2d(x, y + size);      // Bottom
		GL11.glVertex2d(x - size, y);      // Left
		GL11.glEnd();

		// Draw outline if selected
		if (isSelected) {
			GL11.glColor4f(1.0f, 1.0f, 0.0f, 1.0f);  // Yellow outline
			GL11.glBegin(GL11.GL_LINE_LOOP);
			GL11.glVertex2d(x, y - size);
			GL11.glVertex2d(x + size, y);
			GL11.glVertex2d(x, y + size);
			GL11.glVertex2d(x - size, y);
			GL11.glEnd();
		}

		GlStateManager.disableBlend();
	}

	/**
	 * Render scale indicator
	 */
	private void renderScaleIndicator(int widgetX, int widgetY) {
		FontRenderer fontRenderer = Minecraft.getMinecraft().fontRenderer;
		GlStateManager.enableTexture2D();

		String scaleText = formatDistance(scale);
		fontRenderer.drawString(scaleText, widgetX + 5, widgetY + height - 10, COLOR_TEXT);
	}

	/**
	 * Render debug info (PlayerPos, DisplayCenter, ViewCenter, Scale) like reference image
	 * Positioned in TOP-RIGHT corner
	 */
	private void renderDebugInfo(int widgetX, int widgetY, BlockPos radarPos) {
		FontRenderer fontRenderer = Minecraft.getMinecraft().fontRenderer;
		GlStateManager.enableTexture2D();

		int infoY = widgetY + 5;
		int lineHeight = 10;

		// Player position (relative to radar if available, otherwise show as unavailable)
		String playerPosText;
		if (playerPos != null && radarPos != null) {
			int relX = playerPos.getX() - radarPos.getX();
			int relY = playerPos.getY() - radarPos.getY();
			int relZ = playerPos.getZ() - radarPos.getZ();
			playerPosText = String.format("PlayerPos:[%d, %d, %d, 0]", relX, relY, relZ);
		} else {
			playerPosText = "PlayerPos:[N/A]";
		}

		// Display center (world coordinates at display center)
		String displayCenterText = String.format("DisplayCenter:[%.0f,%.0f]", displayCenterX, displayCenterZ);

		// View center (same as display center, but kept for compatibility)
		String viewCenterText = String.format("ViewCenter:[%.0f,%.0f]", viewCenterX, viewCenterZ);

		// Scale
		String scaleText = String.format("Scale:%.1f", scale);

		// Right-align text (calculate X position based on text width)
		int playerPosX = widgetX + width - fontRenderer.getStringWidth(playerPosText) - 5;
		int displayCenterX_ui = widgetX + width - fontRenderer.getStringWidth(displayCenterText) - 5;
		int viewCenterX_ui = widgetX + width - fontRenderer.getStringWidth(viewCenterText) - 5;
		int scaleX = widgetX + width - fontRenderer.getStringWidth(scaleText) - 5;

		// Draw in top-right corner
		fontRenderer.drawString(playerPosText, playerPosX, infoY, 0xFFFFFF);
		fontRenderer.drawString(displayCenterText, displayCenterX_ui, infoY + lineHeight, 0x00FF00);  // Green for display center
		fontRenderer.drawString(viewCenterText, viewCenterX_ui, infoY + lineHeight * 2, 0xFFFF00);  // Yellow for view center
		fontRenderer.drawString(scaleText, scaleX, infoY + lineHeight * 3, 0xFFFFFF);
	}

	/**
	 * Format distance for display
	 */
	private String formatDistance(double blocks) {
		if (blocks >= 1000) {
			return String.format("%.1fkm", blocks / 1000.0);
		} else {
			return String.format("%.0fm", blocks);
		}
	}

	/**
	 * Get color based on signal strength (0.0 to 1.0)
	 */
	private int getSignalColor(double signalStrength) {
		// Clamp to 0.0 - 1.0
		signalStrength = Math.max(0.0, Math.min(1.0, signalStrength));

		// Map to color array index
		int index = (int)(signalStrength * (SIGNAL_COLORS.length - 1));
		index = Math.max(0, Math.min(SIGNAL_COLORS.length - 1, index));

		return SIGNAL_COLORS[index];
	}

	/**
	 * Render a circle (filled or outline)
	 */
	private void renderCircle(int centerX, int centerY, int radius, int color, boolean filled) {
		float r = ((color >> 16) & 0xFF) / 255.0f;
		float g = ((color >> 8) & 0xFF) / 255.0f;
		float b = (color & 0xFF) / 255.0f;
		float a = ((color >> 24) & 0xFF) / 255.0f;

		GL11.glColor4f(r, g, b, a);
		GL11.glBegin(filled ? GL11.GL_TRIANGLE_FAN : GL11.GL_LINE_LOOP);

		if (filled) {
			GL11.glVertex2f(centerX, centerY);  // Center point for triangle fan
		}

		int segments = 64;  // Smooth circle
		for (int i = 0; i <= segments; i++) {
			double angle = 2.0 * Math.PI * i / segments;
			double x = centerX + radius * Math.cos(angle);
			double y = centerY + radius * Math.sin(angle);
			GL11.glVertex2d(x, y);
		}

		GL11.glEnd();
	}

	/**
	 * Handle mouse click on the widget
	 * @param mouseX Mouse X relative to widget top-left
	 * @param mouseY Mouse Y relative to widget top-left
	 * @param contacts List of radar contacts
	 * @param radarPos Radar position in world
	 */
	public void handleClick(int mouseX, int mouseY, List<RadarContactData> contacts, BlockPos radarPos) {
		if (contacts == null || radarPos == null) return;

		int centerX = width / 2;
		int centerY = height / 2;

		// Check if click is within rectangular display area
		if (mouseX < 0 || mouseX >= width || mouseY < 0 || mouseY >= height) {
			// Click outside display, deselect
			selectedContact = null;
			return;
		}

		// Find closest contact to click position
		RadarContactData closest = null;
		double closestDist = Double.MAX_VALUE;

		for (RadarContactData contact : contacts) {
			Vec2d screenPos = worldToScreen(contact, radarPos, centerX, centerY);
			if (screenPos == null) continue;

			double dist = Math.sqrt(
				Math.pow(mouseX - screenPos.x, 2) +
				Math.pow(mouseY - screenPos.y, 2)
			);

			if (dist < closestDist && dist < 10) {  // Within 10 pixels
				closest = contact;
				closestDist = dist;
			}
		}

		selectedContact = closest;
	}

	/**
	 * Handle mouse wheel scroll for zoom
	 * @param delta Scroll delta (positive = zoom in, negative = zoom out)
	 */
	public void handleScroll(int delta) {
		if (delta > 0) {
			// Zoom in (decrease scale)
			scale = Math.max(minScale, scale * 0.8);
		} else if (delta < 0) {
			// Zoom out (increase scale)
			scale = Math.min(maxScale, scale * 1.25);
		}
	}

	/**
	 * Get the currently selected contact
	 */
	public RadarContactData getSelectedContact() {
		return selectedContact;
	}

	/**
	 * Set the scale (max range in blocks)
	 */
	public void setScale(double scale) {
		this.scale = Math.max(minScale, Math.min(maxScale, scale));
	}

	/**
	 * Get current scale
	 */
	public double getScale() {
		return scale;
	}

	/**
	 * Set view center (for panning)
	 */
	public void setViewCenter(double x, double z) {
		this.viewCenterX = x;
		this.viewCenterZ = z;

		// Update display coordinates to match view center
		this.displayCenterX = x;
		this.displayCenterZ = z;
	}

	/**
	 * Pan view by delta
	 */
	public void panView(double deltaX, double deltaZ) {
		this.viewCenterX += deltaX;
		this.viewCenterZ += deltaZ;

		// Update display coordinates to match view center
		this.displayCenterX = this.viewCenterX;
		this.displayCenterZ = this.viewCenterZ;
	}

	/**
	 * Reset view to center
	 */
	public void resetView() {
		this.viewCenterX = 0.0;
		this.viewCenterZ = 0.0;

		// Reset display coordinates as well
		this.displayCenterX = 0.0;
		this.displayCenterZ = 0.0;
	}

	/**
	 * Get view center X
	 */
	public double getViewCenterX() {
		return viewCenterX;
	}

	/**
	 * Get view center Z
	 */
	public double getViewCenterZ() {
		return viewCenterZ;
	}

	/**
	 * Get display center X (world coordinate at display center)
	 */
	public double getDisplayCenterX() {
		return displayCenterX;
	}

	/**
	 * Get display center Z (world coordinate at display center)
	 */
	public double getDisplayCenterZ() {
		return displayCenterZ;
	}

	/**
	 * Render tooltip for hovered contact
	 * @param mouseX Mouse X position on screen
	 * @param mouseY Mouse Y position on screen
	 */
	public void renderTooltip(int mouseX, int mouseY, RadarContactData contact) {
		if (contact == null) return;

		FontRenderer fontRenderer = Minecraft.getMinecraft().fontRenderer;
		GlStateManager.pushMatrix();
		GlStateManager.enableTexture2D();

		// Calculate actual speed from velocity vector (already in m/s)
		double speed = Math.sqrt(
			contact.velocityX * contact.velocityX +
			contact.velocityY * contact.velocityY +
			contact.velocityZ * contact.velocityZ
		);

		// DEBUG: Log velocity values to verify what we're receiving
		System.out.println("[GUI DEBUG] Contact " + contact.trackNumber +
			" | VelX=" + String.format("%.1f", contact.velocityX) +
			" | VelY=" + String.format("%.1f", contact.velocityY) +
			" | VelZ=" + String.format("%.1f", contact.velocityZ) +
			" | Calculated Speed=" + String.format("%.1f", speed) + " m/s");

		// Build tooltip text
		String[] lines = {
			contact.trackNumber,
			String.format("RNG: %.0fm", contact.distance),
			String.format("AZ: %.1f°", contact.azimuth),
			String.format("EL: %.1f°", contact.elevation),
			String.format("SIG: %.0f%%", contact.signalStrength * 100),
			String.format("SPD: %.1fm/s", speed)  // Display actual velocity magnitude
		};

		// Calculate tooltip dimensions
		int maxWidth = 0;
		for (String line : lines) {
			maxWidth = Math.max(maxWidth, fontRenderer.getStringWidth(line));
		}

		int tooltipX = mouseX + 10;
		int tooltipY = mouseY - 10;
		int tooltipWidth = maxWidth + 6;
		int tooltipHeight = lines.length * 10 + 4;

		// Render background
		GlStateManager.disableTexture2D();
		drawRect(tooltipX, tooltipY, tooltipX + tooltipWidth, tooltipY + tooltipHeight, 0xD0000000);
		GlStateManager.enableTexture2D();

		// Render text
		for (int i = 0; i < lines.length; i++) {
			fontRenderer.drawString(lines[i], tooltipX + 3, tooltipY + 3 + i * 10, 0xFFFFFF);
		}

		GlStateManager.popMatrix();
	}

	/**
	 * Draw a filled rectangle (helper method)
	 */
	private void drawRect(int left, int top, int right, int bottom, int color) {
		float a = ((color >> 24) & 0xFF) / 255.0f;
		float r = ((color >> 16) & 0xFF) / 255.0f;
		float g = ((color >> 8) & 0xFF) / 255.0f;
		float b = (color & 0xFF) / 255.0f;

		GL11.glColor4f(r, g, b, a);
		GL11.glBegin(GL11.GL_QUADS);
		GL11.glVertex2f(left, bottom);
		GL11.glVertex2f(right, bottom);
		GL11.glVertex2f(right, top);
		GL11.glVertex2f(left, top);
		GL11.glEnd();
	}
}
