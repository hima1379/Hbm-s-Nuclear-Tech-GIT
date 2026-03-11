package com.hbm.blocks.machine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.hbm.lib.Library;
import com.hbm.lib.InventoryHelper;
import com.hbm.blocks.ILookOverlay;
import com.hbm.blocks.BlockDummyableMBB;
import com.hbm.blocks.ModBlocks;
import com.hbm.main.MainRegistry;
import com.hbm.main.tileentity.machine.TileEntityMachineFENSU;

import api.hbm.energy.EnergyValue;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.client.event.RenderGameOverlayEvent.Pre;
import net.minecraftforge.fml.common.network.internal.FMLNetworkHandler;

public class MachineFENSU extends BlockDummyableMBB implements ILookOverlay {

	public MachineFENSU(Material materialIn, String s) {
		super(materialIn, s);
	}

	@Override
	public TileEntity createNewTileEntity(World worldIn, int meta) {
		if(meta >= 12)
			return new TileEntityMachineFENSU();
		return null;
	}

	@Override
	public int[] getDimensions() {
		return new int[] {4, 0, 1, 1, 2, 2};
	}

	@Override
	public int getOffset() {
		return 1;
	}

	@Override
	public Item getItemDropped(IBlockState state, Random rand, int fortune) {
		return null;
	}

	@Override
	public boolean removedByPlayer(IBlockState state, World world, BlockPos pos, EntityPlayer player, boolean willHarvest){
		if(!player.capabilities.isCreativeMode && !world.isRemote && willHarvest) {
			
			ItemStack drop = new ItemStack(this);
			int[] posCore = this.findCore(world, pos.getX(), pos.getY(), pos.getZ());
			TileEntity te;
			if(posCore == null){
				te = world.getTileEntity(pos);
			}else{
				te = world.getTileEntity(new BlockPos(posCore[0], posCore[1], posCore[2]));
			}

			if (te instanceof TileEntityMachineFENSU) {
				TileEntityMachineFENSU battery = (TileEntityMachineFENSU) te;
			
				NBTTagCompound nbt = new NBTTagCompound();
				battery.writeNBT(nbt);

				if(!nbt.isEmpty()) {
					drop.setTagCompound(nbt);
				}
			}

			InventoryHelper.spawnItemStack(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, drop);
		}
		return world.setBlockToAir(pos);
	}
	
	@Override
	public boolean onBlockActivated(World world, BlockPos pos1, IBlockState state, EntityPlayer player, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
		if(world.isRemote){
			return true;
		} else if(!player.isSneaking()){
			int[] pos = this.findCore(world, pos1.getX(), pos1.getY(), pos1.getZ());

			if(pos == null)
				return false;

			BlockPos corePos = new BlockPos(pos[0], pos[1], pos[2]);
			TileEntityMachineFENSU entity = (TileEntityMachineFENSU) world.getTileEntity(corePos);
			if(entity != null) {
				if(!player.getHeldItem(hand).isEmpty()){

					int[] ores = OreDictionary.getOreIDs(player.getHeldItem(hand));
					for(int ore : ores){
						String name = OreDictionary.getOreName(ore);
						//Why are these ones named differently
						if(name.equals("dyeLightBlue"))
							name = "dyeLight_Blue";
						if(name.equals("dyeLightGray"))
							name = "dyeSilver";
						if(name.length() > 3 && name.startsWith("dye")){
							try {
								EnumDyeColor color = EnumDyeColor.valueOf(name.substring(3, name.length()).toUpperCase());
								entity.color = color;
								entity.markDirty();
								world.notifyBlockUpdate(corePos, state, state, 2 | 4);
								if(!player.isCreative())
									player.getHeldItem(hand).shrink(1);
								return true;
							} catch(IllegalArgumentException e){}
						}
					}
				}
				FMLNetworkHandler.openGui(player, MainRegistry.instance, ModBlocks.guiID_machine_battery, world, pos[0], pos[1], pos[2]);
			}
			return true;
		} else {
			return false;
		}
	}

	@Override
	public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase placer, ItemStack stack) {
		super.onBlockPlacedBy(world, pos, state, placer, stack);
		int[] posCore = this.findCore(world, pos.getX(), pos.getY(), pos.getZ());
		TileEntity te;
		if(posCore == null){
			te = world.getTileEntity(pos);
		}else{
			te = world.getTileEntity(new BlockPos(posCore[0], posCore[1], posCore[2]));
		}
		if(stack.hasTagCompound()){
			if (te instanceof TileEntityMachineFENSU) {
				TileEntityMachineFENSU battery = (TileEntityMachineFENSU) te;
				if(stack.hasDisplayName()) {
					battery.setCustomName(stack.getDisplayName());
				}
				
				try {
					NBTTagCompound stackNBT = stack.getTagCompound();
					if(stackNBT.hasKey("NBT_PERSISTENT_KEY")){
						battery.readNBT(stackNBT);
					}
				} catch(Exception ex) {
					ex.printStackTrace();
				}
			}
		}
	}

	@Override
	public void breakBlock(World worldIn, BlockPos pos, IBlockState state) {
		TileEntity tileentity = worldIn.getTileEntity(pos);
		if (tileentity instanceof TileEntityMachineFENSU) {
			InventoryHelper.dropInventoryItems(worldIn, pos, (TileEntityMachineFENSU) tileentity);
			worldIn.updateComparatorOutputLevel(pos, this);
		}

		super.breakBlock(worldIn, pos, state);
	}

	@Override
	public boolean hasComparatorInputOverride(IBlockState state){
		return true;
	}

	@Override
	public int getComparatorInputOverride(IBlockState blockState, World worldIn, BlockPos pos){
		
		TileEntity te = worldIn.getTileEntity(pos);
		
		if(!(te instanceof TileEntityMachineFENSU))
			return 0;
		
		TileEntityMachineFENSU battery = (TileEntityMachineFENSU) te;
		return (int)battery.getPowerRemainingScaled(15L);
	}

	@Override
	public void addInformation(ItemStack stack, World worldIn, List<String> list, ITooltipFlag flagIn) {
		super.addInformation(stack, worldIn, list, flagIn);
		list.add("Change color using dyes");

		// Read power as EnergyValue to support BigInteger (beyond Long.MAX_VALUE)
		EnergyValue charge = EnergyValue.ZERO;
		if(stack.hasTagCompound()){
			NBTTagCompound nbt = stack.getTagCompound();
			if(nbt.hasKey("NBT_PERSISTENT_KEY")){
				charge = EnergyValue.readFromNBT(nbt.getCompoundTag("NBT_PERSISTENT_KEY"), "power");
			}
		}

		// Max capacity: 10^100 HE
		EnergyValue maxCapacity = EnergyValue.of(new BigInteger("10").pow(100));

		if(charge.isZero()){
			list.add("§c0§4/10^100HE §c(0.0%)§r");
		} else {
			// Calculate percentage using BigDecimal for precision
			BigDecimal chargeDecimal = charge.toBigDecimal();
			BigDecimal maxDecimal = maxCapacity.toBigDecimal();
			double percent = chargeDecimal.divide(maxDecimal, 10, BigDecimal.ROUND_HALF_UP)
			                            .multiply(new BigDecimal("100"))
			                            .doubleValue();

			String color = "§e";
			String color2 = "§6";
			if(percent < 25){
				color = "§c";
				color2 = "§4";
			}else if(percent >= 75){
				color = "§a";
				color2 = "§2";
			}

			list.add(color + Library.getShortNumber(chargeDecimal) + color2 + "/10^100HE " +
			         color + "(" + String.format("%.1f", percent) + "%)§r");
		}
	}

	@Override
	public void printHook(Pre event, World world, int x, int y, int z) {
			
		TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
		
		if(!(te instanceof TileEntityMachineFENSU))
			return;

		TileEntityMachineFENSU battery = (TileEntityMachineFENSU) te;
		List<String> text = new ArrayList();
		text.add("§6<> §rStored Energy: " + Library.getShortNumber(battery.getPowerEV().toBigDecimal()) + "/" + Library.getShortNumber(battery.getMaxPowerEV().toBigDecimal()) + "HE");
		if(battery.getPowerDelta() == 0)
			text.add("§e-- §r0HE/s");
		else if(battery.getPowerDelta() > 0)
			text.add("§a-> §r" + Library.getShortNumber(battery.getPowerDelta()) + "HE/s");
		else
			text.add("§c<- §r" + Library.getShortNumber(-battery.getPowerDelta()) + "HE/s");
		text.add("&["+Library.getColorProgress(battery.getPowerEV().toBigDecimal().doubleValue()/battery.getMaxPowerEV().toBigDecimal().doubleValue())+"&]    "+Library.getPercentage(battery.getPowerEV().toBigDecimal().doubleValue()/battery.getMaxPowerEV().toBigDecimal().doubleValue())+"%");
		ILookOverlay.printGeneric(event, getLocalizedName(), 0xffff00, 0x404000, text);
	}
}
