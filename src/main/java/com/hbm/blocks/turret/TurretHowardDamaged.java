package com.hbm.blocks.turret;

import com.hbm.blocks.BlockDummyable;
import com.hbm.main.tileentity.turret.TileEntityTurretHoward;
import com.hbm.main.tileentity.turret.TileEntityTurretHowardDamaged;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

public class TurretHowardDamaged extends BlockDummyable {

	public TurretHowardDamaged(Material materialIn, String s){
		super(materialIn, s);
	}

	@Override
	public TileEntity createNewTileEntity(World worldIn, int meta){
		if(meta >= 12)
			return new TileEntityTurretHowardDamaged();
		return null;
	}

	@Override
	public int[] getDimensions(){
		return new int[] { 0, 0, 1, 0, 1, 0 };
	}

	@Override
	public int getOffset(){
		return 0;
	}

	@Override
	public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos){
		return new AxisAlignedBB(0.0F, 0.0F, 0.0F, 1.0F, 0.5F, 1.0F);
	}

	/**
	 * ブロック破壊時にアグロシステムのデコイエンティティを確実に削除
	 */
	@Override
	public void breakBlock(World worldIn, BlockPos pos, IBlockState state) {
		// タイルエンティティからアグロシステムを無効化
		TileEntity tileentity = worldIn.getTileEntity(pos);
		if (tileentity instanceof TileEntityTurretHoward) {
			TileEntityTurretHoward turret = (TileEntityTurretHoward) tileentity;
			turret.disableAggroSystem();
			System.out.println("[TurretHowardDamaged Block] Disabled aggro system on block break at " + pos);
		}

		// 親クラスの処理（アイテムドロップなど）
		super.breakBlock(worldIn, pos, state);
	}

}
