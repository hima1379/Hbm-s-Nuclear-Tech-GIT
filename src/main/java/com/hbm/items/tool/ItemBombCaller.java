package com.hbm.items.tool;

import java.util.List;

import com.hbm.entity.logic.EntityBomber;
import com.hbm.items.ModItems;
import com.hbm.lib.HBMSoundHandler;
import com.hbm.lib.Library;
import com.hbm.main.MainRegistry;
import com.hbm.util.I18nUtil;

import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.RayTraceResult.Type;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

public class ItemBombCaller extends Item {

	public ItemBombCaller(String s) {
		this.setRegistryName(s);
		this.setTranslationKey(s);
		this.setCreativeTab(MainRegistry.consumableTab);
		this.setHasSubtypes(true);

		ModItems.ALL_ITEMS.add(this);
	}

	@Override
	public void addInformation(ItemStack stack, World worldIn, List<String> list, ITooltipFlag flagIn) {
		list.add(TextFormatting.GRAY + I18nUtil.resolveKey("desc.airstrike"));

		switch (getTypeFromStack(stack)) {
		case CARPET:
			list.add(TextFormatting.GRAY + I18nUtil.resolveKey("desc.type") + " " + TextFormatting.YELLOW + I18nUtil.resolveKey("type.carpet"));
			break;
		case NAPALM:
			list.add(TextFormatting.GRAY + I18nUtil.resolveKey("desc.type") + " " + TextFormatting.GOLD + I18nUtil.resolveKey("type.napalm"));
			break;
		case POISON:
			list.add(TextFormatting.GRAY + I18nUtil.resolveKey("desc.type") + " " + TextFormatting.GREEN + I18nUtil.resolveKey("type.poison"));
			break;
		case ORANGE:
			list.add(TextFormatting.GRAY + I18nUtil.resolveKey("desc.type") + " " + TextFormatting.GOLD + I18nUtil.resolveKey("type.orange"));
			break;
		case ATOMIC:
			list.add(TextFormatting.GRAY + I18nUtil.resolveKey("desc.type") + " " + TextFormatting.DARK_RED + TextFormatting.BOLD + I18nUtil.resolveKey("type.atomic"));
			break;
		case STINGER:
			list.add(TextFormatting.GRAY + I18nUtil.resolveKey("desc.type") + " " + TextFormatting.AQUA + I18nUtil.resolveKey("type.stinger"));
			break;
		case PIP:
			list.add(TextFormatting.GRAY + I18nUtil.resolveKey("desc.type") + " " + TextFormatting.AQUA + I18nUtil.resolveKey("type.pip"));
			break;
		case CLOUD:
			list.add(TextFormatting.GRAY + I18nUtil.resolveKey("desc.type") + " " + TextFormatting.AQUA + I18nUtil.resolveKey("type.cloud"));
			break;
		default:
			break;
		}
	}

	@Override
	public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer playerIn, EnumHand handIn) {
		RayTraceResult trace = Library.rayTrace(playerIn, 500, 1);
		ItemStack stack = playerIn.getHeldItem(handIn);
		boolean b = false;
		if (trace.typeOfHit != Type.MISS && !world.isRemote) {

			int x = trace.getBlockPos().getX();
			int y = trace.getBlockPos().getY();
			int z = trace.getBlockPos().getZ();

			EntityBomber bomber = null;

			switch (getTypeFromStack(stack)) {
			case CARPET:
				bomber = EntityBomber.statFacCarpet(world, x, y, z);
				break;
			case NAPALM:
				bomber = EntityBomber.statFacNapalm(world, x, y, z);
				break;
			case POISON:
				bomber = EntityBomber.statFacChlorine(world, x, y, z);
				break;
			case ORANGE:
				bomber = EntityBomber.statFacOrange(world, x, y, z);
				break;
			case ATOMIC:
				bomber = EntityBomber.statFacABomb(world, x, y, z);
				break;
			case STINGER:
				bomber = EntityBomber.statFacStinger(world, x, y, z);
				break;
			case PIP:
				bomber = EntityBomber.statFacBoxcar(world, x, y, z);
				break;
			case CLOUD:
				bomber = EntityBomber.statFacPC(world, x, y, z);
				break;
			default:
				break;
			}

			if (bomber != null) {
				// Set calling player UUID for countdown system
				bomber.setCallingPlayer(playerIn.getUniqueID());

				// === FORMATION SYSTEM ===
				com.hbm.entity.logic.EntityBomberFormationManager formationManager =
					com.hbm.entity.logic.EntityBomberFormationManager.getInstance();

				// Get bombing type for formation grouping
				int bombingType = getTypeFromStack(stack).ordinal();

				// Check if there's an active formation accepting members
				com.hbm.entity.logic.EntityBomberFormation formation =
					formationManager.findActiveFormation(world, bombingType);

				boolean isNewFormation = false;
				if (formation == null) {
					// Create new formation
					formation = formationManager.createFormation(world, x, y, z, bombingType);
					isNewFormation = true;
					MainRegistry.logger.info("Created new formation: " + formation.getFormationId());
				}

				// Add bomber to formation
				int[] position = formation.addBomber(bomber.getUniqueID());
				if (position != null) {
					int row = position[0];
					int column = position[1];
					boolean isLeader = (formation.getBomberCount() == 1);

					// Re-initialize bomber with formation positioning
					// This recalculates spawn position using formation's approach vector and position offsets
					bomber.fac(world, x, y, z, formation, row, column);

					// Set formation data
					bomber.setFormation(formation.getFormationId(), row, column, isLeader);

					MainRegistry.logger.info("Added bomber to formation at row=" + row + ", column=" + column +
						" (Total: " + formation.getBomberCount() + " bombers)");
				}

				// Spawn the bomber
				boolean spawned = world.spawnEntity(bomber);

				if (spawned) {
					b = true;

					// Send formation messages
					if (isNewFormation) {
						// First bomber in new formation
						playerIn.sendMessage(new net.minecraft.util.text.TextComponentString(
							TextFormatting.AQUA + "B-29 編隊を要請しました"
						));
						playerIn.sendMessage(new net.minecraft.util.text.TextComponentString(
							TextFormatting.YELLOW + "編隊機数: " + TextFormatting.GOLD + "1機"
						));
					} else {
						// Added to existing formation
						playerIn.sendMessage(new net.minecraft.util.text.TextComponentString(
							TextFormatting.GREEN + "編隊追加！"
						));
						playerIn.sendMessage(new net.minecraft.util.text.TextComponentString(
							TextFormatting.YELLOW + "現在の編隊: " + TextFormatting.GOLD + formation.getBomberCount() + "機" +
							TextFormatting.GRAY + " / " + com.hbm.entity.logic.EntityBomberFormation.MAX_BOMBERS_PER_FORMATION + "機"
						));

						// Show formation status
						int row0 = Math.min(formation.getBomberCount(), 10);
						int row1 = Math.max(0, Math.min(formation.getBomberCount() - 10, 10));
						int row2 = Math.max(0, formation.getBomberCount() - 20);

						if (row2 > 0) {
							playerIn.sendMessage(new net.minecraft.util.text.TextComponentString(
								TextFormatting.GRAY + "配置: 第3列" + row2 + "機 | 第1列" + row0 + "機 | 第2列" + row1 + "機"
							));
						} else if (row1 > 0) {
							playerIn.sendMessage(new net.minecraft.util.text.TextComponentString(
								TextFormatting.GRAY + "配置: 第1列" + row0 + "機 | 第2列" + row1 + "機"
							));
						}
					}

					if (!playerIn.capabilities.isCreativeMode)
						stack.shrink(1);
				} else {
					MainRegistry.logger.error("Failed to spawn B-29! Check world height limits and chunk loading.");
					playerIn.sendMessage(new net.minecraft.util.text.TextComponentString(
						TextFormatting.RED + "エラー: B-29のスポーンに失敗しました"
					));
				}
			}
			world.playSound(playerIn.posX, playerIn.posY, playerIn.posZ, HBMSoundHandler.techBoop, SoundCategory.PLAYERS, 1.0F, 1.0F, true);

		}
		return new ActionResult<ItemStack>(b ? EnumActionResult.SUCCESS : EnumActionResult.FAIL, stack.copy());
	}

	@Override
	public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> items) {
		if (tab == this.getCreativeTab() || tab == CreativeTabs.SEARCH)
			for (int i = 0; i < EnumCallerType.values().length - 4; i++) {
				ItemStack stack = new ItemStack(this, 1, 0);
				setCallerType(stack, EnumCallerType.values()[i]);
				items.add(stack);
			}
	}

	@Override
	public boolean hasEffect(ItemStack stack) {
		return getTypeFromStack(stack).ordinal() >= 4;
	}

	public static enum EnumCallerType {
		CARPET, NAPALM, POISON, ORANGE, ATOMIC, STINGER, PIP, CLOUD, NONE
	}

	public static EnumCallerType getTypeFromStack(ItemStack stack) {
		if (stack == null || stack.getItem() != ModItems.bomb_caller) {
			return EnumCallerType.NONE;
		}
		if (!stack.hasTagCompound()) {
			NBTTagCompound tag = new NBTTagCompound();
			tag.setInteger("callerType", EnumCallerType.CARPET.ordinal());
			stack.setTagCompound(tag);
		}
		int i = stack.getTagCompound().getInteger("callerType");
		if (i < 0 || i > EnumCallerType.values().length - 2) {
			return EnumCallerType.NONE;
		}
		return EnumCallerType.values()[i];
	}

	public static void setCallerType(ItemStack stack, EnumCallerType type) {
		if (!stack.hasTagCompound()) {
			stack.setTagCompound(new NBTTagCompound());
		}
		stack.getTagCompound().setInteger("callerType", type.ordinal());
	}
	
	public static ItemStack getStack(EnumCallerType type){
		ItemStack stack = new ItemStack(ModItems.bomb_caller, 1, 0);
		setCallerType(stack, type);
		return stack;
	}
}
