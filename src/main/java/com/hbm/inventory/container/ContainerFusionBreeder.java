package com.hbm.inventory.container;

import com.hbm.inventory.SlotMachineOutput;
import com.hbm.main.tileentity.machine.fusion.TileEntityFusionBreeder;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;

/**
 * Container for Fusion Breeder GUI - Adapted for 1.12.2
 */
public class ContainerFusionBreeder extends Container {

	protected TileEntityFusionBreeder breeder;

	public ContainerFusionBreeder(InventoryPlayer invPlayer, TileEntityFusionBreeder tedf) {
		this.breeder = tedf;

		// Slot 0: Fluid identifier (26, 72)
		this.addSlotToContainer(new SlotItemHandler(breeder.inventory, 0, 26, 72));
		
		// Slot 1: Input item (48, 45)
		this.addSlotToContainer(new SlotItemHandler(breeder.inventory, 1, 48, 45));
		
		// Slot 2: Output item (112, 45) - output only
		this.addSlotToContainer(new SlotMachineOutput(breeder.inventory, 2, 112, 45));

		// Player inventory
		for(int i = 0; i < 3; i++) {
			for(int j = 0; j < 9; j++) {
				this.addSlotToContainer(new Slot(invPlayer, j + i * 9 + 9, 8 + j * 18, 118 + i * 18));
			}
		}

		// Player hotbar
		for(int i = 0; i < 9; i++) {
			this.addSlotToContainer(new Slot(invPlayer, i, 8 + i * 18, 176));
		}
	}

	@Override
	public ItemStack transferStackInSlot(EntityPlayer player, int index) {
		ItemStack result = ItemStack.EMPTY;
		Slot slot = this.inventorySlots.get(index);

		if(slot != null && slot.getHasStack()) {
			ItemStack stack = slot.getStack();
			result = stack.copy();

			if(index <= 2) {
				// From machine to player inventory
				if(!this.mergeItemStack(stack, 3, this.inventorySlots.size(), true)) {
					return ItemStack.EMPTY;
				}
				slot.onSlotChange(stack, result);
			} else {
				// From player inventory to machine
				// Try input slots (0 and 1)
				if(!this.mergeItemStack(stack, 0, 2, false)) {
					return ItemStack.EMPTY;
				}
			}

			if(stack.isEmpty()) {
				slot.putStack(ItemStack.EMPTY);
			} else {
				slot.onSlotChanged();
			}

			if(stack.getCount() == result.getCount()) {
				return ItemStack.EMPTY;
			}

			slot.onTake(player, stack);
		}

		return result;
	}

	@Override
	public boolean canInteractWith(EntityPlayer player) {
		return breeder.isUseableByPlayer(player);
	}
}
