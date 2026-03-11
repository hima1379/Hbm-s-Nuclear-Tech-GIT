package com.hbm.inventory.container;

import com.hbm.main.tileentity.network.data.TileEntityFCSConsole;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;

/**
 * Container for FCS Console
 * Fire Control System console - no inventory needed
 */
public class ContainerFCSConsole extends Container {

	private TileEntityFCSConsole console;

	public ContainerFCSConsole(InventoryPlayer invPlayer, TileEntityFCSConsole console) {
		this.console = console;
		// No inventory slots - this is a pure control interface
	}

	@Override
	public boolean canInteractWith(EntityPlayer player) {
		return console.isUsableByPlayer(player);
	}
}
