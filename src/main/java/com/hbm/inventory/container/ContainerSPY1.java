package com.hbm.inventory.container;

import com.hbm.main.tileentity.network.data.TileEntitySPY1;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;

/**
 * Container for SPY-1 Radar
 * No inventory slots - just for GUI data synchronization
 */
public class ContainerSPY1 extends Container {

	private TileEntitySPY1 radar;

	public ContainerSPY1(InventoryPlayer invPlayer, TileEntitySPY1 teRadar) {
		this.radar = teRadar;
	}

	@Override
	public boolean canInteractWith(EntityPlayer player) {
		return radar.isUsableByPlayer(player);
	}

	public TileEntitySPY1 getRadar() {
		return radar;
	}
}
