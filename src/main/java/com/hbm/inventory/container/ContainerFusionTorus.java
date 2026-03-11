package com.hbm.inventory.container;

import com.hbm.main.tileentity.machine.fusion.TileEntityFusionTorus;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;

/**
 * Container for Fusion Torus GUI - Adapted for 1.12.2 with correct positions
 */
public class ContainerFusionTorus extends Container {

	protected TileEntityFusionTorus torus;

	public ContainerFusionTorus(InventoryPlayer invPlayer, TileEntityFusionTorus torus) {
		this.torus = torus;

		// Slot 0: Battery (8, 82)
		this.addSlotToContainer(new SlotItemHandler(torus.inventory, 0, 8, 82));

		// Slot 1: Blueprint (71, 81)
		this.addSlotToContainer(new SlotItemHandler(torus.inventory, 1, 71, 81));

		// Slot 2: Output (130, 36)
		this.addSlotToContainer(new SlotItemHandler(torus.inventory, 2, 130, 36));

		// Player inventory (35, 162)
		for(int i = 0; i < 3; i++) {
			for(int j = 0; j < 9; j++) {
				this.addSlotToContainer(new Slot(invPlayer, j + i * 9 + 9, 35 + j * 18, 162 + i * 18));
			}
		}

		// Player hotbar (35, 220)
		for(int i = 0; i < 9; i++) {
			this.addSlotToContainer(new Slot(invPlayer, i, 35 + i * 18, 220));
		}
	}

	@Override
	public ItemStack transferStackInSlot(EntityPlayer player, int index) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean canInteractWith(EntityPlayer player) {
		return torus.isUseableByPlayer(player);
	}
}
