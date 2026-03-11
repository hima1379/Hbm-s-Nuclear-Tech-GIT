package com.hbm.inventory.container;

import com.hbm.items.ModItems;
import com.hbm.main.tileentity.machine.fusion.TileEntityFusionKlystron;

import api.hbm.energy.IBatteryItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;

/**
 * Container for Fusion Klystron GUI.
 * Provides access to battery slot and player inventory.
 *
 * @author Adapted for 1.12.2
 */
public class ContainerFusionKlystron extends Container {

	protected TileEntityFusionKlystron klystron;

	public ContainerFusionKlystron(InventoryPlayer invPlayer, TileEntityFusionKlystron klystron) {
		this.klystron = klystron;

		// Battery slot (slot 0) - original position from sample code
		this.addSlotToContainer(new SlotItemHandler(klystron.inventory, 0, 8, 72));

		// Player inventory (3 rows x 9 slots) - original position
		for(int i = 0; i < 3; i++) {
			for(int j = 0; j < 9; j++) {
				this.addSlotToContainer(new Slot(invPlayer, j + i * 9 + 9, 17 + j * 18, 118 + i * 18));
			}
		}

		// Player hotbar (9 slots) - original position
		for(int i = 0; i < 9; i++) {
			this.addSlotToContainer(new Slot(invPlayer, i, 17 + i * 18, 176));
		}
	}

	/**
	 * Handles shift-clicking items between player inventory and machine slots.
	 */
	@Override
	public ItemStack transferStackInSlot(EntityPlayer player, int index) {
		ItemStack copy = ItemStack.EMPTY;
		Slot slot = (Slot) this.inventorySlots.get(index);

		if(slot != null && slot.getHasStack()) {
			ItemStack stack = slot.getStack();
			copy = stack.copy();

			// If clicking on battery slot (index 0), move to player inventory
			if(index == 0) {
				if(!this.mergeItemStack(stack, 1, this.inventorySlots.size(), true)) {
					return ItemStack.EMPTY;
				}
			} else {
				// If clicking from player inventory, try to put in battery slot
				if(copy.getItem() instanceof IBatteryItem || copy.getItem() == ModItems.battery_creative) {
					if(!this.mergeItemStack(stack, 0, 1, false)) {
						return ItemStack.EMPTY;
					}
				} else {
					return ItemStack.EMPTY;
				}
			}

			if(stack.isEmpty()) {
				slot.putStack(ItemStack.EMPTY);
			} else {
				slot.onSlotChanged();
			}
		}

		return copy;
	}

	@Override
	public boolean canInteractWith(EntityPlayer player) {
		return klystron.isUseableByPlayer(player);
	}
}
