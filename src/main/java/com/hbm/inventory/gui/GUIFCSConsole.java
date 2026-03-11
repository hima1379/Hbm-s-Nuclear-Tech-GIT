package com.hbm.inventory.gui;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.lwjgl.input.Mouse;

import com.hbm.inventory.container.ContainerFCSConsole;
import com.hbm.lib.RefStrings;
import com.hbm.main.tileentity.network.data.TileEntityFCSConsole;
import com.hbm.packet.FCSTargetDesignationPacket;
import com.hbm.packet.MissileLaunchPacket;
import com.hbm.packet.PacketDispatcher;

import api.hbm.data.DataDeviceType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;

/**
 * Simplified FCS Console GUI - Single Large Radar Display
 *
 * Features:
 * - One large main screen (230x218px black area)
 * - Auto-detects connected SPY-1 radar via DataNet
 * - Auto-displays radar screen when connected
 * - No selection needed - plug and play
 * - GUI coordinate system for all rendering
 */
public class GUIFCSConsole extends GuiContainer {

	private static final ResourceLocation TEXTURE = new ResourceLocation(RefStrings.MODID, "textures/gui/fcs_console.png");
	private TileEntityFCSConsole console;

	// Radar display widget (200x200 for the main screen)
	private RadarDisplayWidget radarWidget;

	// Mouse state for panning
	private boolean rightMouseDown = false;
	private int lastMouseX = 0;
	private int lastMouseY = 0;

	// Designated target (for two-click behavior)
	private Integer designatedEntityId = null;

	// Tab system
	private enum TabType {
		RADAR,    // Radar display (existing functionality)
		MISSILES  // Missile control (new)
	}
	private TabType currentTab = TabType.RADAR;

	// GUI layout constants
	private static final int GUI_WIDTH = 256;
	private static final int GUI_HEIGHT = 256;

	// Screen area (black display area in GUI)
	// Position: x=13, y=25 (in texture coordinates)
	// Size: 230x218px
	private static final int SCREEN_X = 13;
	private static final int SCREEN_Y = 25;
	private static final int SCREEN_WIDTH = 230;
	private static final int SCREEN_HEIGHT = 218;

	// Tab layout constants
	private static final int TAB_WIDTH = 13;
	private static final int TAB_HEIGHT = 30;
	private static final int TAB_X = 0;
	private static final int TAB_RADAR_Y = 25;
	private static final int TAB_MISSILES_Y = 55;

	public GUIFCSConsole(InventoryPlayer invPlayer, TileEntityFCSConsole console) {
		super(new ContainerFCSConsole(invPlayer, console));
		this.console = console;

		// Set GUI size
		this.xSize = GUI_WIDTH;
		this.ySize = GUI_HEIGHT;

		// Initialize radar display widget with exact screen dimensions
		this.radarWidget = new RadarDisplayWidget(SCREEN_WIDTH, SCREEN_HEIGHT);
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		this.drawDefaultBackground();
		super.drawScreen(mouseX, mouseY, partialTicks);
		this.renderHoveredToolTip(mouseX, mouseY);
	}

	@Override
	protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
		// Render GUI background texture
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
		this.mc.getTextureManager().bindTexture(TEXTURE);

		// Draw the GUI background
		this.drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, ySize);
	}

	@Override
	protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
		// Draw power bar (simple text for now)
		String powerText = String.format("PWR: %d/%d HE", console.power, TileEntityFCSConsole.maxPower);
		this.fontRenderer.drawString(powerText, 8, 250, 0x00FF00);

		// Draw tabs
		renderTabs();

		// Check if FCS Console has power
		if (console.power < TileEntityFCSConsole.powerConsumption) {
			// No power - black screen, no display, no operations
			return;
		}

		// Render content based on current tab
		switch (currentTab) {
			case RADAR:
				renderRadarTab();
				break;
			case MISSILES:
				renderMissileTab();
				break;
		}
	}

	/**
	 * Render left-side tabs
	 * Tabs are positioned to the LEFT of the radar screen (SCREEN_X)
	 */
	private void renderTabs() {
		// Tabs positioned on the LEFT edge of GUI (x=0)
		// They will be 13 pixels wide, positioned before the radar screen
		int tabX = 0;  // Left edge of GUI

		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

		// RADAR tab background (GUI-relative coordinates)
		int radarBgColor = (currentTab == TabType.RADAR) ? 0xFF00FF00 : 0xFF404040;
		drawRect(tabX, TAB_RADAR_Y,
		         tabX + TAB_WIDTH, TAB_RADAR_Y + TAB_HEIGHT, radarBgColor);

		// MISSILES tab background (GUI-relative coordinates)
		int missileBgColor = (currentTab == TabType.MISSILES) ? 0xFF00FF00 : 0xFF404040;
		drawRect(tabX, TAB_MISSILES_Y,
		         tabX + TAB_WIDTH, TAB_MISSILES_Y + TAB_HEIGHT, missileBgColor);

		// Tab labels (GUI-relative coordinates - match background)
		int radarColor = (currentTab == TabType.RADAR) ? 0x000000 : 0xFFFFFF;
		this.fontRenderer.drawString("R", tabX + 3, TAB_RADAR_Y + 11, radarColor);

		// MISSILES tab label (GUI-relative coordinates)
		int missileColor = (currentTab == TabType.MISSILES) ? 0x000000 : 0xFFFFFF;
		this.fontRenderer.drawString("M", tabX + 3, TAB_MISSILES_Y + 11, missileColor);
	}

	/**
	 * Render radar tab content (existing functionality)
	 */
	private void renderRadarTab() {
		// Auto-detect and display radar if connected
		UUID radarId = getConnectedRadar();

		if (radarId != null) {
			// Check if radar is online
			TileEntityFCSConsole.DeviceStatusData status = console.getDeviceStatus(radarId);

			if (status != null && status.online) {
				// Render radar display automatically
				renderRadarScreen(radarId);
			} else {
				// Radar connected but offline (no power)
				renderOfflineScreen();
			}
		} else {
			// No radar connected
			renderNoRadarScreen();
		}
	}

	// Selected launch pad for missile control (legacy - will be removed)
	private UUID selectedLaunchPad = null;
	private boolean sarhMode = true;  // true = SARH, false = ARH

	// New missile control tab state
	private String selectedMissileType = null;  // Selected missile type (e.g., "hbm:missile_sm6")
	private Integer selectedTargetEntityId = null;  // Selected target entity ID
	private int targetScrollOffset = 0;  // Scroll position for target list
	private static final int MAX_VISIBLE_TARGETS = 8;  // Maximum targets visible without scrolling

	/**
	 * Render missile control tab (redesigned)
	 * Layout: Left = Missile Inventory, Right = Target List, Bottom = FIRE Button
	 */
	private void renderMissileTab() {
		// Get connected launch pads to count missiles
		List<TileEntityFCSConsole.LaunchPadInfo> pads = console.getConnectedLaunchPads();

		// Layout constants
		int leftPanelX = SCREEN_X + 5;
		int leftPanelY = SCREEN_Y + 5;
		int leftPanelWidth = 110;

		int rightPanelX = SCREEN_X + 120;
		int rightPanelY = SCREEN_Y + 5;
		int rightPanelWidth = 105;

		// === LEFT PANEL: MISSILE INVENTORY ===
		this.fontRenderer.drawString("MISSILES", leftPanelX, leftPanelY, 0x00FFFF);

		// Count SM-6 missiles
		int sm6Count = 0;
		for (TileEntityFCSConsole.LaunchPadInfo pad : pads) {
			if (pad.missileType != null && (pad.missileType.toLowerCase().contains("sm6") || pad.missileType.toLowerCase().contains("sm_6"))) {
				sm6Count++;
			}
		}

		// Display SM-6 as selectable item
		int missileY = leftPanelY + 15;
		boolean sm6Selected = "hbm:missile_sm6".equals(selectedMissileType);

		// Draw selection box if selected (white border)
		if (sm6Selected) {
			drawRect(leftPanelX - 2, missileY - 2, leftPanelX + leftPanelWidth + 2, missileY + 12, 0xFFFFFFFF);
			drawRect(leftPanelX, missileY, leftPanelX + leftPanelWidth, missileY + 10, 0xFF000000);
		}

		// Draw SM-6 entry
		int sm6Color = sm6Count > 0 ? 0x00FF00 : 0x808080;
		this.fontRenderer.drawString("SM-6: " + sm6Count, leftPanelX + 2, missileY + 1, sm6Color);

		// === RIGHT PANEL: TARGET LIST ===
		this.fontRenderer.drawString("TARGETS", rightPanelX, rightPanelY, 0x00FFFF);

		// Get radar contacts from connected radar
		UUID radarId = getConnectedRadar();
		List<TileEntityFCSConsole.RadarContactData> contacts = null;
		if (radarId != null) {
			contacts = console.getRadarContacts(radarId);
		}

		if (contacts == null || contacts.isEmpty()) {
			this.fontRenderer.drawString("No targets", rightPanelX, rightPanelY + 15, 0x808080);
		} else {
			// Display targets with scrolling
			int targetY = rightPanelY + 15;
			int visibleCount = Math.min(MAX_VISIBLE_TARGETS, contacts.size());
			int targetLineHeight = 15;

			for (int i = targetScrollOffset; i < Math.min(targetScrollOffset + visibleCount, contacts.size()); i++) {
				TileEntityFCSConsole.RadarContactData contact = contacts.get(i);
				boolean isSelected = (selectedTargetEntityId != null && selectedTargetEntityId.equals(contact.entityId));

				// Draw selection box if selected
				if (isSelected) {
					drawRect(rightPanelX - 2, targetY - 2, rightPanelX + rightPanelWidth + 2, targetY + targetLineHeight - 3, 0xFFFFFFFF);
					drawRect(rightPanelX, targetY, rightPanelX + rightPanelWidth, targetY + targetLineHeight - 5, 0xFF000000);
				}

				// Draw target info
				String targetText = contact.trackNumber + " " + String.format("%.0fm", contact.distance);
				this.fontRenderer.drawString(targetText, rightPanelX + 2, targetY + 1, 0xFFFFFF);

				targetY += targetLineHeight;
			}

			// Draw scroll bar if needed
			if (contacts.size() > MAX_VISIBLE_TARGETS) {
				int scrollBarX = rightPanelX + rightPanelWidth + 3;
				int scrollBarY = rightPanelY + 15;
				int scrollBarHeight = MAX_VISIBLE_TARGETS * targetLineHeight - 5;

				// Scroll bar track (gray)
				drawRect(scrollBarX, scrollBarY, scrollBarX + 3, scrollBarY + scrollBarHeight, 0xFF404040);

				// Scroll bar thumb (white)
				int thumbHeight = Math.max(10, scrollBarHeight * MAX_VISIBLE_TARGETS / contacts.size());
				int thumbY = scrollBarY + (scrollBarHeight - thumbHeight) * targetScrollOffset / Math.max(1, contacts.size() - MAX_VISIBLE_TARGETS);
				drawRect(scrollBarX, thumbY, scrollBarX + 3, thumbY + thumbHeight, 0xFFFFFFFF);
			}
		}

		// === FIRE BUTTON (Bottom Center) ===
		int fireButtonX = SCREEN_X + SCREEN_WIDTH / 2 - 25;
		int fireButtonY = SCREEN_Y + SCREEN_HEIGHT - 20;
		int fireButtonWidth = 50;
		int fireButtonHeight = 15;

		// Check if we have a ready SM-6 launch pad available
		boolean hasReadyPad = false;
		for (TileEntityFCSConsole.LaunchPadInfo pad : pads) {
			if (pad.missileType != null &&
			    (pad.missileType.toLowerCase().contains("sm6") || pad.missileType.toLowerCase().contains("sm_6")) &&
			    pad.readyToFire && pad.power >= 75000) {
				hasReadyPad = true;
				break;
			}
		}

		// Check if both missile and target are selected and we have a ready pad
		boolean canFire = (selectedMissileType != null && selectedTargetEntityId != null && hasReadyPad);

		// Draw button background (black or gray)
		int buttonBgColor = canFire ? 0xFF808080 : 0xFF000000;
		drawRect(fireButtonX, fireButtonY, fireButtonX + fireButtonWidth, fireButtonY + fireButtonHeight, buttonBgColor);

		// Draw button border (white)
		drawRect(fireButtonX, fireButtonY, fireButtonX + fireButtonWidth, fireButtonY + 1, 0xFFFFFFFF);
		drawRect(fireButtonX, fireButtonY + fireButtonHeight - 1, fireButtonX + fireButtonWidth, fireButtonY + fireButtonHeight, 0xFFFFFFFF);
		drawRect(fireButtonX, fireButtonY, fireButtonX + 1, fireButtonY + fireButtonHeight, 0xFFFFFFFF);
		drawRect(fireButtonX + fireButtonWidth - 1, fireButtonY, fireButtonX + fireButtonWidth, fireButtonY + fireButtonHeight, 0xFFFFFFFF);

		// Draw button text
		int fireTextColor = canFire ? 0x00FF00 : 0x606060;
		this.fontRenderer.drawString("FIRE", fireButtonX + 15, fireButtonY + 4, fireTextColor);
	}

	/**
	 * Get the first connected radar (auto-detection)
	 */
	private UUID getConnectedRadar() {
		List<UUID> radars = console.getConnectedRadarIds();
		return radars.isEmpty() ? null : radars.get(0);
	}

	/**
	 * Render radar screen with RadarDisplayWidget
	 */
	private void renderRadarScreen(UUID radarId) {
		// Get radar contacts from console.
		// This includes both SPY-1 detected contacts and server-tracked SM-6 missiles
		// (synced via TileEntityFCSConsole.updateTrackedMissiles() every 5 ticks).
		List<TileEntityFCSConsole.RadarContactData> contacts = console.getRadarContacts(radarId);

		// Widget position - exact match with screen area (230x218)
		// No centering needed since widget size matches screen size exactly
		int widgetX = SCREEN_X;
		int widgetY = SCREEN_Y;

		// Save state
		GlStateManager.pushMatrix();
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

		// Get player position for debug display
		net.minecraft.util.math.BlockPos playerPos = this.mc.player.getPosition();

		// Render widget (GUI coordinates) with player position
		// Widget fills the entire screen area (230x218 pixels)
		// Pass illumination callback to widget
		radarWidget.render(widgetX, widgetY, guiLeft, guiTop, contacts, console.getBlockPos(), playerPos,
			entityId -> console.isTargetIlluminated(entityId));

		// Render × "lost" markers for killed non-missile contacts
		java.util.List<TileEntityFCSConsole.LostContactData> lostContacts = console.getLostContacts();
		if (!lostContacts.isEmpty()) {
			radarWidget.renderLostContacts(widgetX, widgetY, lostContacts, console.getBlockPos());
		}

		// Render radar info overlay
		String deviceName = console.getDeviceName(radarId);
		this.fontRenderer.drawString(deviceName, SCREEN_X + 5, SCREEN_Y + 5, 0x00FF00);

		// Contact count - only count hostile (non-missile) contacts
		long hostileCount = 0;
		for (TileEntityFCSConsole.RadarContactData c : contacts) {
			if (!c.isMissile) hostileCount++;
		}
		String contactStr = "TGT: " + hostileCount;
		this.fontRenderer.drawString(contactStr, SCREEN_X + 5, SCREEN_Y + 15, 0xFFFF00);

		// Render selected target info if any
		TileEntityFCSConsole.RadarContactData selected = radarWidget.getSelectedContact();
		if (selected != null) {
			int infoX = SCREEN_X + 5;
			int infoY = SCREEN_Y + SCREEN_HEIGHT - 50;

			this.fontRenderer.drawString(">> " + selected.trackNumber, infoX, infoY, 0x00FFFF);
			this.fontRenderer.drawString(String.format("RNG: %.0fm", selected.distance), infoX, infoY + 10, 0xFFFFFF);
			this.fontRenderer.drawString(String.format("AZ: %.1f°", selected.azimuth), infoX, infoY + 20, 0xFFFFFF);
			this.fontRenderer.drawString(String.format("SPD: %.1fm/s", selected.closureRate * 20), infoX, infoY + 30, 0xFFFFFF);
		}

		GlStateManager.popMatrix();
	}

	/**
	 * Render screen when radar is connected but offline
	 */
	private void renderOfflineScreen() {
		int centerX = SCREEN_X + SCREEN_WIDTH / 2;
		int centerY = SCREEN_Y + SCREEN_HEIGHT / 2;

		this.fontRenderer.drawString("Radar off-line", centerX - 40, centerY - 10, 0xFF0000);
		this.fontRenderer.drawString("Check power supply", centerX - 50, centerY + 5, 0x808080);
	}

	/**
	 * Render screen when no radar is connected
	 */
	private void renderNoRadarScreen() {
		int centerX = SCREEN_X + SCREEN_WIDTH / 2;
		int centerY = SCREEN_Y + SCREEN_HEIGHT / 2;

		this.fontRenderer.drawString("NO RADAR DETECTED", centerX - 50, centerY - 10, 0x808080);
		this.fontRenderer.drawString("Connect SPY-1 via Blue Cable", centerX - 70, centerY + 5, 0x606060);
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
		super.mouseClicked(mouseX, mouseY, mouseButton);

		// Convert to GUI coordinates
		int guiMouseX = mouseX - guiLeft;
		int guiMouseY = mouseY - guiTop;

		// Check for tab clicks
		if (mouseButton == 0) {  // Left click only
			if (isPointInTab(guiMouseX, guiMouseY, TabType.RADAR)) {
				currentTab = TabType.RADAR;
				return;
			} else if (isPointInTab(guiMouseX, guiMouseY, TabType.MISSILES)) {
				currentTab = TabType.MISSILES;
				return;
			}
		}

		// Handle missile tab clicks
		if (currentTab == TabType.MISSILES && mouseButton == 0 && isPointInScreen(guiMouseX, guiMouseY)) {
			handleMissileTabClick(guiMouseX, guiMouseY);
			return;
		}

		// Check if click is on radar screen area
		if (currentTab == TabType.RADAR && isPointInScreen(guiMouseX, guiMouseY)) {
			UUID radarId = getConnectedRadar();
			if (radarId != null) {
				TileEntityFCSConsole.DeviceStatusData status = console.getDeviceStatus(radarId);
				if (status != null && status.online) {
					if (mouseButton == 1) {  // Right click
						// Start panning
						rightMouseDown = true;
						lastMouseX = mouseX;
						lastMouseY = mouseY;
					} else if (mouseButton == 0) {  // Left click
						// Forward click to radar widget for target selection
						List<TileEntityFCSConsole.RadarContactData> contacts = console.getRadarContacts(radarId);

						// Widget fills entire screen area (230x218) - same as in renderRadarScreen()
						int widgetX = SCREEN_X;
						int widgetY = SCREEN_Y;

						radarWidget.handleClick(guiMouseX - widgetX, guiMouseY - widgetY, contacts, console.getBlockPos());

						// Two-click behavior:
						// 1st click: Select target (yellow circle)
						// 2nd click on SAME target: Designate to SPG-62 (send packet)
						TileEntityFCSConsole.RadarContactData clickedContact = radarWidget.getSelectedContact();
						if (clickedContact != null) {
							if (designatedEntityId != null && designatedEntityId == clickedContact.entityId) {
								// Second click on same target - send designation to SPG-62
								PacketDispatcher.wrapper.sendToServer(
									new FCSTargetDesignationPacket(console.getBlockPos(), clickedContact.entityId));
								System.out.println("[FCS GUI CLIENT] ✓ DESIGNATED " + clickedContact.trackNumber +
									" (ID: " + clickedContact.entityId + ") to SPG-62 for illumination");
							} else {
								// First click or different target - just select (yellow circle)
								designatedEntityId = clickedContact.entityId;
								System.out.println("[FCS GUI CLIENT] Selected " + clickedContact.trackNumber +
									" (ID: " + clickedContact.entityId + ") - click again to designate to SPG-62");
							}
						}
					}
				}
			}
		}
	}

	@Override
	protected void mouseReleased(int mouseX, int mouseY, int mouseButton) {
		super.mouseReleased(mouseX, mouseY, mouseButton);

		if (mouseButton == 1) {  // Right mouse button released
			rightMouseDown = false;
		}
	}

	@Override
	protected void mouseClickMove(int mouseX, int mouseY, int mouseButton, long timeSinceLastClick) {
		super.mouseClickMove(mouseX, mouseY, mouseButton, timeSinceLastClick);

		// Handle right-click drag for panning
		if (rightMouseDown && mouseButton == 1) {
			UUID radarId = getConnectedRadar();
			if (radarId != null) {
				TileEntityFCSConsole.DeviceStatusData status = console.getDeviceStatus(radarId);
				if (status != null && status.online) {
					// Calculate mouse delta
					int deltaX = mouseX - lastMouseX;
					int deltaY = mouseY - lastMouseY;

					// Convert screen delta to radar coordinate delta
					// The radar widget uses: zoomScale = radius / scale
					// So to convert screen pixels to radar blocks:
					double radarScale = radarWidget.getScale();
					int widgetRadius = 100;  // 200px diameter / 2
					double pixelsPerBlock = (double)widgetRadius / radarScale;

					double radarDeltaX = -deltaX / pixelsPerBlock;  // Negative for natural pan direction
					double radarDeltaZ = -deltaY / pixelsPerBlock;  // Negative for natural pan direction

					// Update view center
					radarWidget.panView(radarDeltaX, radarDeltaZ);

					// Update last mouse position
					lastMouseX = mouseX;
					lastMouseY = mouseY;
				}
			}
		}
	}

	@Override
	public void handleMouseInput() throws IOException {
		super.handleMouseInput();

		// Only handle mouse wheel if radar is connected and online
		UUID radarId = getConnectedRadar();
		if (radarId == null) return;  // No radar connected - disable all mouse interaction

		TileEntityFCSConsole.DeviceStatusData status = console.getDeviceStatus(radarId);
		if (status == null || !status.online) return;  // Radar offline - disable all mouse interaction

		// Handle radar zoom with mouse wheel
		int wheel = Mouse.getEventDWheel();
		if (wheel != 0) {
			int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
			int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;

			// Convert to GUI coordinates
			int guiMouseX = mouseX - guiLeft;
			int guiMouseY = mouseY - guiTop;

			// Check if mouse is over screen area
			if (isPointInScreen(guiMouseX, guiMouseY)) {
				radarWidget.handleScroll(wheel);
			}
		}
	}

	/**
	 * Handle clicks within the missile tab
	 */
	private void handleMissileTabClick(int guiMouseX, int guiMouseY) {
		// Layout constants (must match renderMissileTab())
		int leftPanelX = SCREEN_X + 5;
		int leftPanelY = SCREEN_Y + 5;
		int leftPanelWidth = 110;

		int rightPanelX = SCREEN_X + 120;
		int rightPanelY = SCREEN_Y + 5;
		int rightPanelWidth = 105;

		// === 1. CHECK SM-6 MISSILE SELECTION (Left Panel) ===
		int missileY = leftPanelY + 15;
		int missileHeight = 10;

		if (guiMouseX >= leftPanelX && guiMouseX < leftPanelX + leftPanelWidth &&
		    guiMouseY >= missileY && guiMouseY < missileY + missileHeight) {
			// Toggle SM-6 selection
			if ("hbm:missile_sm6".equals(selectedMissileType)) {
				selectedMissileType = null;  // Deselect
				System.out.println("[FCS GUI] Deselected SM-6");
			} else {
				selectedMissileType = "hbm:missile_sm6";  // Select
				System.out.println("[FCS GUI] Selected SM-6");
			}
			return;
		}

		// === 2. CHECK TARGET SELECTION (Right Panel) ===
		UUID radarId = getConnectedRadar();
		List<TileEntityFCSConsole.RadarContactData> contacts = null;
		if (radarId != null) {
			contacts = console.getRadarContacts(radarId);
		}

		if (contacts != null && !contacts.isEmpty()) {
			int targetY = rightPanelY + 15;
			int targetLineHeight = 15;
			int visibleCount = Math.min(MAX_VISIBLE_TARGETS, contacts.size());

			// Check click on target entries
			for (int i = targetScrollOffset; i < Math.min(targetScrollOffset + visibleCount, contacts.size()); i++) {
				TileEntityFCSConsole.RadarContactData contact = contacts.get(i);
				int thisTargetY = targetY + (i - targetScrollOffset) * targetLineHeight;

				if (guiMouseX >= rightPanelX && guiMouseX < rightPanelX + rightPanelWidth &&
				    guiMouseY >= thisTargetY && guiMouseY < thisTargetY + (targetLineHeight - 5)) {
					// Toggle target selection
					if (selectedTargetEntityId != null && selectedTargetEntityId.equals(contact.entityId)) {
						selectedTargetEntityId = null;  // Deselect
						System.out.println("[FCS GUI] Deselected target " + contact.trackNumber);
					} else {
						selectedTargetEntityId = contact.entityId;  // Select
						System.out.println("[FCS GUI] Selected target " + contact.trackNumber + " (ID: " + contact.entityId + ")");
					}
					return;
				}
			}

			// === 3. CHECK SCROLL BAR CLICK ===
			if (contacts.size() > MAX_VISIBLE_TARGETS) {
				int scrollBarX = rightPanelX + rightPanelWidth + 3;
				int scrollBarY = rightPanelY + 15;
				int scrollBarHeight = MAX_VISIBLE_TARGETS * targetLineHeight - 5;

				if (guiMouseX >= scrollBarX && guiMouseX < scrollBarX + 3 &&
				    guiMouseY >= scrollBarY && guiMouseY < scrollBarY + scrollBarHeight) {
					// Calculate new scroll position based on click position
					int clickOffset = guiMouseY - scrollBarY;
					int maxScroll = contacts.size() - MAX_VISIBLE_TARGETS;
					targetScrollOffset = Math.min(maxScroll, (clickOffset * contacts.size()) / scrollBarHeight);
					targetScrollOffset = Math.max(0, targetScrollOffset);
					System.out.println("[FCS GUI] Scrolled to offset " + targetScrollOffset);
					return;
				}
			}
		}

		// === 4. CHECK FIRE BUTTON CLICK ===
		int fireButtonX = SCREEN_X + SCREEN_WIDTH / 2 - 25;
		int fireButtonY = SCREEN_Y + SCREEN_HEIGHT - 20;
		int fireButtonWidth = 50;
		int fireButtonHeight = 15;

		// Check if FIRE button was clicked (regardless of canFire state for debugging)
		if (guiMouseX >= fireButtonX && guiMouseX < fireButtonX + fireButtonWidth &&
		    guiMouseY >= fireButtonY && guiMouseY < fireButtonY + fireButtonHeight) {

			System.out.println("[FCS GUI] FIRE BUTTON CLICKED");
			System.out.println("  Selected Missile: " + selectedMissileType);
			System.out.println("  Selected Target: " + selectedTargetEntityId);

			// Find available SM-6 missiles and select first ready launch pad
			List<TileEntityFCSConsole.LaunchPadInfo> pads = console.getConnectedLaunchPads();
			System.out.println("  Total LaunchPads: " + pads.size());

			TileEntityFCSConsole.LaunchPadInfo availablePad = null;
			for (TileEntityFCSConsole.LaunchPadInfo pad : pads) {
				System.out.println("  Checking pad: " + pad.padName +
				                   ", Type: " + pad.missileType +
				                   ", Ready: " + pad.readyToFire +
				                   ", Power: " + pad.power);

				if (pad.missileType != null &&
				    (pad.missileType.toLowerCase().contains("sm6") || pad.missileType.toLowerCase().contains("sm_6"))) {
					System.out.println("    -> Has SM-6 missile");
					if (pad.readyToFire && pad.power >= 75000) {
						availablePad = pad;
						System.out.println("    -> SELECTED for launch!");
						break;
					} else {
						System.out.println("    -> NOT ready: readyToFire=" + pad.readyToFire + ", power=" + pad.power);
					}
				}
			}

			if (availablePad == null) {
				System.out.println("  ERROR: No available SM-6 launch pad found!");
				return;
			}

			System.out.println("  Launch Pad: " + availablePad.padName + " (" + availablePad.padId + ")");
			System.out.println("  Sending MissileLaunchPacket to server...");

			// Send missile launch packet to server (ARH mode = fire-and-forget)
			PacketDispatcher.wrapper.sendToServer(
			    new MissileLaunchPacket(console.getBlockPos(), availablePad.padId, selectedTargetEntityId, false));

			System.out.println("  Packet sent successfully!");
			return;
		}
	}

	/**
	 * Check if a point (in GUI coordinates) is within the screen area
	 */
	private boolean isPointInScreen(int x, int y) {
		return x >= SCREEN_X && x < SCREEN_X + SCREEN_WIDTH &&
		       y >= SCREEN_Y && y < SCREEN_Y + SCREEN_HEIGHT;
	}

	/**
	 * Check if point is within a tab area
	 * Point should be in GUI-relative coordinates
	 */
	private boolean isPointInTab(int x, int y, TabType tab) {
		// Tab X position (same as in renderTabs)
		int tabX = 0;  // Left edge of GUI

		int tabY;
		switch (tab) {
			case RADAR:
				tabY = TAB_RADAR_Y;
				break;
			case MISSILES:
				tabY = TAB_MISSILES_Y;
				break;
			default:
				return false;
		}
		return x >= tabX && x < tabX + TAB_WIDTH &&
		       y >= tabY && y < tabY + TAB_HEIGHT;
	}
}
