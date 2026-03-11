package com.hbm.items.machine;

import java.util.List;

import javax.annotation.Nonnull;

import com.hbm.inventory.recipes.FusionRecipe;
import com.hbm.inventory.recipes.FusionRecipes;
import com.hbm.items.ModItems;
import com.hbm.lib.RefStrings;
import com.hbm.main.MainRegistry;
import com.hbm.util.BobMathUtil;
import com.hbm.util.I18nUtil;

import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Fusion reactor template item - stores recipe index in NBT.
 * Adapted from ItemAssemblyTemplate pattern for 1.12.2.
 */
public class ItemFusionTemplate extends Item {

	public static final ModelResourceLocation location = new ModelResourceLocation(RefStrings.MODID + ":fusion_template", "inventory");

	public ItemFusionTemplate(String s) {
		this.setTranslationKey(s);
		this.setRegistryName(s);
		this.setHasSubtypes(true);
		this.setMaxDamage(0);
		this.setCreativeTab(MainRegistry.templateTab);

		ModItems.ALL_ITEMS.add(this);
	}

	@Override
	@SideOnly(Side.CLIENT)
	public String getItemStackDisplayName(ItemStack stack) {
		String s = ("" + I18n.format(this.getTranslationKey() + ".name")).trim();
		int index = getTagWithRecipeNumber(stack).getInteger("type");
		FusionRecipe recipe = FusionRecipes.INSTANCE.getRecipeByIndex(index);

		if (recipe != null) {
			// Try localization first, fall back to recipe name
			String recipeName = I18nUtil.resolveKey(recipe.getName());
			s = s + " " + recipeName;
		} else {
			s = s + " ERROR";
		}

		return s;
	}

	@Override
	public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> list) {
		if (tab == this.getCreativeTab() || tab == CreativeTabs.SEARCH) {
			int count = FusionRecipes.INSTANCE.getRecipeCount();

	    	for(int i = 0; i < count; i++) {
				NBTTagCompound tag = new NBTTagCompound();
				tag.setInteger("type", i);
				ItemStack stack = new ItemStack(this, 1, 0);
				stack.setTagCompound(tag);
				list.add(stack);
			}
		}
	}

	/**
	 * Creates a fusion template ItemStack for the given recipe index.
	 *
	 * @param id Recipe index
	 * @return Template ItemStack
	 */
	public static ItemStack getTemplate(int id){
		NBTTagCompound tag = new NBTTagCompound();
		tag.setInteger("type", id);
		ItemStack stack = new ItemStack(ModItems.fusion_template, 1, 0);
		stack.setTagCompound(tag);
		return stack;
	}

	@Override
	public void addInformation(ItemStack stack, World worldIn, List<String> list, ITooltipFlag flagIn) {
		if (!(stack.getItem() instanceof ItemFusionTemplate))
			return;

		list.add(TextFormatting.GOLD + I18nUtil.resolveKey("info.templatefolder"));
		list.add("");

		int i = getTagWithRecipeNumber(stack).getInteger("type");
		FusionRecipe recipe = FusionRecipes.INSTANCE.getRecipeByIndex(i);

		if(recipe == null) {
    		list.add("I AM ERROR");
    		return;
    	}

		// Recipe name
		list.add(TextFormatting.BOLD + "" + TextFormatting.YELLOW + I18nUtil.resolveKey(recipe.getName()));
		list.add("");

		// Ignition temperature (Klystron energy requirement)
		list.add(TextFormatting.BOLD + "" + TextFormatting.LIGHT_PURPLE + I18nUtil.resolveKey("info.fusion.ignition"));
		list.add(" " + TextFormatting.LIGHT_PURPLE + BobMathUtil.getShortNumber(recipe.ignitionTemp) + "KyU/t");

		// Output temperature (Plasma energy output)
		list.add(TextFormatting.BOLD + "" + TextFormatting.RED + I18nUtil.resolveKey("info.fusion.output"));
		list.add(" " + TextFormatting.RED + BobMathUtil.getShortNumber(recipe.outputTemp) + "TU/t");

		// Neutron flux
		list.add(TextFormatting.BOLD + "" + TextFormatting.AQUA + I18nUtil.resolveKey("info.fusion.flux"));
		list.add(" " + TextFormatting.AQUA + BobMathUtil.getShortNumber((long)recipe.neutronFlux) + " flux/t");

		// Duration
		list.add(TextFormatting.BOLD + "" + TextFormatting.WHITE + I18nUtil.resolveKey("info.template_time"));
    	list.add(" " + TextFormatting.WHITE + Math.floor((float)recipe.duration / 20 * 100) / 100 + " " + I18nUtil.resolveKey("info.template_seconds"));

    	// Plasma color
    	list.add("");
    	list.add(TextFormatting.GRAY + "Plasma RGB: " +
    		String.format("%.2f, %.2f, %.2f", recipe.r, recipe.g, recipe.b));
	}

	/**
	 * Gets the processing time from the recipe.
	 *
	 * @param stack Template ItemStack
	 * @return Duration in ticks
	 */
	public static int getProcessTime(ItemStack stack) {
		if (!(stack.getItem() instanceof ItemFusionTemplate))
			return 100;

		int i = getTagWithRecipeNumber(stack).getInteger("type");
		FusionRecipe recipe = FusionRecipes.INSTANCE.getRecipeByIndex(i);

    	if(recipe != null)
    		return recipe.duration;
    	else
    		return 100;
	}

	/**
	 * Gets the recipe index stored in the template's NBT.
	 *
	 * @param stack Template ItemStack
	 * @return Recipe index
	 */
	public static int getRecipeIndex(ItemStack stack){
		return getTagWithRecipeNumber(stack).getInteger("type");
	}

	/**
	 * Gets or creates the NBT tag containing the recipe number.
	 *
	 * @param stack Template ItemStack
	 * @return NBT tag compound
	 */
	public static NBTTagCompound getTagWithRecipeNumber(@Nonnull ItemStack stack){
		if(!stack.hasTagCompound()){
			stack.setTagCompound(new NBTTagCompound());
			stack.getTagCompound().setInteger("type", 0);
		}
		return stack.getTagCompound();
	}

	/**
	 * Gets the recipe name from a template ItemStack.
	 *
	 * @param stack Template ItemStack
	 * @return Recipe name (e.g. "fus.dd"), or null if invalid
	 */
	public static String getRecipeName(ItemStack stack){
		if (stack.isEmpty() || !(stack.getItem() instanceof ItemFusionTemplate))
			return null;

		int index = getRecipeIndex(stack);
		FusionRecipe recipe = FusionRecipes.INSTANCE.getRecipeByIndex(index);

		return recipe != null ? recipe.getName() : null;
	}
}
