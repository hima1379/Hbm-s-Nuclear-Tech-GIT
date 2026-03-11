package com.hbm.inventory.container;

import com.hbm.main.tileentity.network.data.TileEntitySPY6;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;

/**
 * Container for SPY-6 AESA Radar
 * No inventory slots — pure GUI data synchronization
 */
public class ContainerSPY6 extends Container {

	private TileEntitySPY6 radar;

	public ContainerSPY6(InventoryPlayer invPlayer, TileEntitySPY6 teRadar) {
		this.radar = teRadar;
	}

	@Override
	public boolean canInteractWith(EntityPlayer player) {
		return radar.isUsableByPlayer(player);
	}

	public TileEntitySPY6 getRadar() {
		return radar;
	}
}
