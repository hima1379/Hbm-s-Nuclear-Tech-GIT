package com.hbm.inventory.container;

import com.hbm.main.tileentity.machine.TileEntityMachineRadar;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;

public class ContainerMachineRadar extends Container {
	
	public ContainerMachineRadar(InventoryPlayer invPlayer, TileEntityMachineRadar tedf) {
	}

	@Override
	public boolean canInteractWith(EntityPlayer player) {
		return true;
	}
}
