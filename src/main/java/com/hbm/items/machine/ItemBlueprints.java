package com.hbm.items.machine;

import java.util.List;

import net.minecraft.item.ItemStack;

/**
 * Utility class for blueprint-based recipe filtering.
 * Currently returns null (show all recipes), can be enhanced later.
 */
public class ItemBlueprints {

    /**
     * Gets a list of available recipe names from a blueprint ItemStack.
     * Returns null if no blueprint or to show all recipes.
     *
     * @param stack Blueprint ItemStack (can be empty)
     * @return List of recipe names, or null to show all recipes
     */
    public static List<String> grabPool(ItemStack stack) {
        // For now, return null to show all recipes
        // Future enhancement: Parse blueprint NBT to return filtered recipe list

        if(stack.isEmpty()) {
            return null;
        }

        // TODO: Implement blueprint parsing if needed
        // Example structure:
        // if(stack.getItem() instanceof ItemFusionBlueprint) {
        //     NBTTagCompound nbt = stack.getTagCompound();
        //     if(nbt != null && nbt.hasKey("recipes")) {
        //         List<String> recipes = new ArrayList<>();
        //         NBTTagList list = nbt.getTagList("recipes", 8); // 8 = String
        //         for(int i = 0; i < list.tagCount(); i++) {
        //             recipes.add(list.getStringTagAt(i));
        //         }
        //         return recipes;
        //     }
        // }

        return null;
    }
}
