package com.hbm.entity.projectile;

import java.util.List;

import com.hbm.config.CompatibilityConfig;
import com.hbm.entity.grenade.EntityGrenadeZOMG;
import com.hbm.explosion.ExplosionChaos;
import com.hbm.items.armor.ArmorPenetrationSystem;
import com.hbm.lib.ModDamageSource;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IProjectile;
import net.minecraft.entity.monster.EntityEnderman;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.network.play.server.SPacketChangeGameState;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * EntityRainbow - レベル100装甲貫通弾
 *
 * この発射体は装甲貫通システムを使用し、
 * レベル100の装甲まで貫通可能な最強の攻撃を行います。
 */
public class EntityRainbow extends Entity implements IProjectile {

	// 装甲貫通レベル（1-100）
	public static final int PENETRATION_LEVEL = 100;

	// 貫通ダメージ（setDamageの代わりに使用）
	private float penetrationDamage = 100000F;

	public static final DataParameter<Boolean> CRITICAL = EntityDataManager.createKey(EntityRainbow.class, DataSerializers.BOOLEAN);
	public static final DataParameter<Boolean> RED = EntityDataManager.createKey(EntityRainbow.class, DataSerializers.BOOLEAN);
	public static final DataParameter<Boolean> GREEN = EntityDataManager.createKey(EntityRainbow.class, DataSerializers.BOOLEAN);
	public static final DataParameter<Boolean> BLUE = EntityDataManager.createKey(EntityRainbow.class, DataSerializers.BOOLEAN);

	private int field_145791_d = -1;
	private int field_145792_e = -1;
	private int field_145789_f = -1;
	public double gravity = 0.0D;
	private Block field_145790_g;
	private int inData;
	private boolean inGround;
	public int canBePickedUp;
	public int arrowShake;
	public Entity shootingEntity;
	private int ticksInGround;
	private int ticksInAir;
	private double damage = 2.0D;
	private int knockbackStrength;

	public EntityRainbow(World p_i1753_1_) {
		super(p_i1753_1_);
		if (p_i1753_1_.isRemote)
			setRenderDistanceWeight(10.0);
		this.setSize(0.5F, 0.5F);
	}

	public EntityRainbow(World p_i1754_1_, double p_i1754_2_, double p_i1754_4_, double p_i1754_6_) {
		super(p_i1754_1_);
		if (p_i1754_1_.isRemote)
			setRenderDistanceWeight(10.0);
		this.setSize(0.5F, 0.5F);
		this.setPosition(p_i1754_2_, p_i1754_4_, p_i1754_6_);
	}

	public EntityRainbow(World p_i1755_1_, EntityLivingBase p_i1755_2_, EntityLivingBase p_i1755_3_, float p_i1755_4_, float p_i1755_5_) {
		super(p_i1755_1_);
		if (p_i1755_1_.isRemote)
			setRenderDistanceWeight(10.0);
		this.shootingEntity = p_i1755_2_;

		if (p_i1755_2_ instanceof EntityPlayer) {
			this.canBePickedUp = 1;
		}

		this.posY = p_i1755_2_.posY + p_i1755_2_.getEyeHeight() - 0.10000000149011612D;
		double d0 = p_i1755_3_.posX - p_i1755_2_.posX;
		double d1 = p_i1755_3_.getEntityBoundingBox().minY + p_i1755_3_.height / 3.0F - this.posY;
		double d2 = p_i1755_3_.posZ - p_i1755_2_.posZ;
		double d3 = MathHelper.sqrt(d0 * d0 + d2 * d2);

		if (d3 >= 1.0E-7D) {
			float f2 = (float) (Math.atan2(d2, d0) * 180.0D / Math.PI) - 90.0F;
			float f3 = (float) (-(Math.atan2(d1, d3) * 180.0D / Math.PI));
			double d4 = d0 / d3;
			double d5 = d2 / d3;
			this.setLocationAndAngles(p_i1755_2_.posX + d4, this.posY, p_i1755_2_.posZ + d5, f2, f3);
			float f4 = (float) d3 * 0.2F;
			this.shoot(d0, d1 + f4, d2, p_i1755_4_, p_i1755_5_);
		}
	}

	public EntityRainbow(World p_i1756_1_, EntityLivingBase p_i1756_2_, float p_i1756_3_, int dmgMin, int dmgMax, EntityGrenadeZOMG grenade) {
		super(p_i1756_1_);
		if (p_i1756_1_.isRemote)
			setRenderDistanceWeight(10.0);
		this.shootingEntity = p_i1756_2_;

		this.setSize(0.5F, 0.5F);
		this.setLocationAndAngles(grenade.posX, grenade.posY + grenade.getEyeHeight(), grenade.posZ, grenade.rotationYaw, grenade.rotationPitch);
		this.posX -= MathHelper.cos(this.rotationYaw / 180.0F * (float) Math.PI) * 0.16F;
		this.posY -= 0.10000000149011612D;
		this.posZ -= MathHelper.sin(this.rotationYaw / 180.0F * (float) Math.PI) * 0.16F;
		this.setPosition(this.posX, this.posY, this.posZ);
		this.motionX = -MathHelper.sin(this.rotationYaw / 180.0F * (float) Math.PI) * MathHelper.cos(this.rotationPitch / 180.0F * (float) Math.PI);
		this.motionZ = MathHelper.cos(this.rotationYaw / 180.0F * (float) Math.PI) * MathHelper.cos(this.rotationPitch / 180.0F * (float) Math.PI);
		this.motionY = (-MathHelper.sin(this.rotationPitch / 180.0F * (float) Math.PI));
		this.shoot(this.motionX, this.motionY, this.motionZ, p_i1756_3_ * 1.5F, 1.0F);
	}

	public EntityRainbow(World p_i1756_1_, EntityLivingBase p_i1756_2_, float p_i1756_3_, EnumHand hand) {
		super(p_i1756_1_);
		if (p_i1756_1_.isRemote)
			setRenderDistanceWeight(10.0);
		this.shootingEntity = p_i1756_2_;

		this.setSize(0.5F, 0.5F);
		this.setLocationAndAngles(p_i1756_2_.posX, p_i1756_2_.posY + p_i1756_2_.getEyeHeight(), p_i1756_2_.posZ, p_i1756_2_.rotationYaw, p_i1756_2_.rotationPitch);
		if (hand == EnumHand.MAIN_HAND) {
			this.posX -= MathHelper.cos(this.rotationYaw / 180.0F * (float) Math.PI) * 0.16F;
			this.posY -= 0.10000000149011612D;
			this.posZ -= MathHelper.sin(this.rotationYaw / 180.0F * (float) Math.PI) * 0.16F;
		} else {
			this.posX += MathHelper.cos(this.rotationYaw / 180.0F * (float) Math.PI) * 0.16F;
			this.posY -= 0.10000000149011612D;
			this.posZ += MathHelper.sin(this.rotationYaw / 180.0F * (float) Math.PI) * 0.16F;
		}

		this.setPosition(this.posX, this.posY, this.posZ);
		this.motionX = -MathHelper.sin(this.rotationYaw / 180.0F * (float) Math.PI) * MathHelper.cos(this.rotationPitch / 180.0F * (float) Math.PI);
		this.motionZ = MathHelper.cos(this.rotationYaw / 180.0F * (float) Math.PI) * MathHelper.cos(this.rotationPitch / 180.0F * (float) Math.PI);
		this.motionY = (-MathHelper.sin(this.rotationPitch / 180.0F * (float) Math.PI));
		this.shoot(this.motionX, this.motionY, this.motionZ, p_i1756_3_ * 1.5F, 1.0F);
	}

	public EntityRainbow(World world, int x, int y, int z, double mx, double my, double mz, double grav) {
		super(world);
		this.posX = x + 0.5F;
		this.posY = y + 0.5F;
		this.posZ = z + 0.5F;

		this.motionX = mx;
		this.motionY = my;
		this.motionZ = mz;

		this.gravity = grav;
	}

	@Override
	protected void entityInit() {
		this.getDataManager().register(CRITICAL, false);
		this.getDataManager().register(RED, false);
		this.getDataManager().register(GREEN, false);
		this.getDataManager().register(BLUE, false);
	}

	@Override
	public void shoot(double p_70186_1_, double p_70186_3_, double p_70186_5_, float p_70186_7_, float p_70186_8_) {
		float f2 = MathHelper.sqrt(p_70186_1_ * p_70186_1_ + p_70186_3_ * p_70186_3_ + p_70186_5_ * p_70186_5_);
		p_70186_1_ /= f2;
		p_70186_3_ /= f2;
		p_70186_5_ /= f2;
		p_70186_1_ += this.rand.nextGaussian() * (this.rand.nextBoolean() ? -1 : 1) * 0.054499999832361937D * p_70186_8_;
		p_70186_3_ += this.rand.nextGaussian() * (this.rand.nextBoolean() ? -1 : 1) * 0.054499999832361937D * p_70186_8_;
		p_70186_5_ += this.rand.nextGaussian() * (this.rand.nextBoolean() ? -1 : 1) * 0.054499999832361937D * p_70186_8_;
		p_70186_1_ *= p_70186_7_;
		p_70186_3_ *= p_70186_7_;
		p_70186_5_ *= p_70186_7_;
		this.motionX = p_70186_1_;
		this.motionY = p_70186_3_;
		this.motionZ = p_70186_5_;
		float f3 = MathHelper.sqrt(p_70186_1_ * p_70186_1_ + p_70186_5_ * p_70186_5_);
		this.prevRotationYaw = this.rotationYaw = (float) (Math.atan2(p_70186_1_, p_70186_5_) * 180.0D / Math.PI);
		this.prevRotationPitch = this.rotationPitch = (float) (Math.atan2(p_70186_3_, f3) * 180.0D / Math.PI);
		this.ticksInGround = 0;
		this.randomizeColor();
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void setPositionAndRotationDirect(double p_70056_1_, double p_70056_3_, double p_70056_5_, float p_70056_7_, float p_70056_8_, int p_70056_9_, boolean b) {
		this.setPosition(p_70056_1_, p_70056_3_, p_70056_5_);
		this.setRotation(p_70056_7_, p_70056_8_);
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void setVelocity(double p_70016_1_, double p_70016_3_, double p_70016_5_) {
		this.motionX = p_70016_1_;
		this.motionY = p_70016_3_;
		this.motionZ = p_70016_5_;

		if (this.prevRotationPitch == 0.0F && this.prevRotationYaw == 0.0F) {
			float f = MathHelper.sqrt(p_70016_1_ * p_70016_1_ + p_70016_5_ * p_70016_5_);
			this.prevRotationYaw = this.rotationYaw = (float) (Math.atan2(p_70016_1_, p_70016_5_) * 180.0D / Math.PI);
			this.prevRotationPitch = this.rotationPitch = (float) (Math.atan2(p_70016_3_, f) * 180.0D / Math.PI);
			this.prevRotationPitch = this.rotationPitch;
			this.prevRotationYaw = this.rotationYaw;
			this.setLocationAndAngles(this.posX, this.posY, this.posZ, this.rotationYaw, this.rotationPitch);
			this.ticksInGround = 0;
		}
	}

	@Override
	public void onUpdate() {
		super.onUpdate();

		if (this.ticksExisted > 100)
			this.setDead();

		if (this.prevRotationPitch == 0.0F && this.prevRotationYaw == 0.0F) {
			this.prevRotationYaw = this.rotationYaw = (float) (Math.atan2(this.motionX, this.motionZ) * 180.0D / Math.PI);
		}

		BlockPos pos = new BlockPos(this.field_145791_d, this.field_145792_e, this.field_145789_f);
		IBlockState blockstate = world.getBlockState(pos);

		if (blockstate.getMaterial() != Material.AIR) {
			if (!world.isRemote)
				ExplosionChaos.explodeZOMG(this.world, (int) this.posX, (int) this.posY, (int) this.posZ, 5);
		}

		if (this.arrowShake > 0) {
			--this.arrowShake;
		} else {
			++this.ticksInAir;
			Vec3d vec31 = new Vec3d(this.posX, this.posY, this.posZ);
			Vec3d vec3 = new Vec3d(this.posX + this.motionX, this.posY + this.motionY, this.posZ + this.motionZ);
			RayTraceResult movingobjectposition = this.world.rayTraceBlocks(vec31, vec3, false, true, false);
			vec31 = new Vec3d(this.posX, this.posY, this.posZ);
			vec3 = new Vec3d(this.posX + this.motionX, this.posY + this.motionY, this.posZ + this.motionZ);

			if (movingobjectposition != null) {
				vec3 = new Vec3d(movingobjectposition.hitVec.x, movingobjectposition.hitVec.y, movingobjectposition.hitVec.z);
			}

			Entity entity = null;
			List<Entity> list = this.world.getEntitiesWithinAABBExcludingEntity(this, this.getEntityBoundingBox().grow(this.motionX, this.motionY, this.motionZ).grow(1.0D));
			double d0 = 0.0D;
			int i;
			float f1;

			for (i = 0; i < list.size(); ++i) {
				Entity entity1 = (Entity) list.get(i);

				if (entity1.canBeCollidedWith() && (entity1 != this.shootingEntity || this.ticksInAir >= 5)) {
					f1 = 0.3F;
					AxisAlignedBB axisalignedbb1 = entity1.getEntityBoundingBox().grow(f1);
					RayTraceResult movingobjectposition1 = axisalignedbb1.calculateIntercept(vec31, vec3);

					if (movingobjectposition1 != null) {
						double d1 = vec31.distanceTo(movingobjectposition1.hitVec);

						if (d1 < d0 || d0 == 0.0D) {
							entity = entity1;
							d0 = d1;
						}
					}
				}
			}

			if (entity != null) {
				movingobjectposition = new RayTraceResult(entity);
			}

			if (movingobjectposition != null && movingobjectposition.entityHit != null && movingobjectposition.entityHit instanceof EntityPlayer) {
				EntityPlayer entityplayer = (EntityPlayer) movingobjectposition.entityHit;

				if (entityplayer.capabilities.disableDamage || this.shootingEntity instanceof EntityPlayer && !((EntityPlayer) this.shootingEntity).canAttackPlayer(entityplayer)) {
					movingobjectposition = null;
				}
			}

			float f2;
			float f4;

			if (movingobjectposition != null && CompatibilityConfig.isWarDim(world)) {
				if (movingobjectposition.entityHit != null) {
					f2 = MathHelper.sqrt(this.motionX * this.motionX + this.motionY * this.motionY + this.motionZ * this.motionZ);
					int k = MathHelper.ceil(f2 * this.damage);

					if (this.getIsCritical()) {
						k += this.rand.nextInt(k / 2 + 2);
					}

					// ★★★ 強化された装甲貫通システムを使用 ★★★
					if (movingobjectposition.entityHit instanceof EntityLivingBase) {
						EntityLivingBase target = (EntityLivingBase) movingobjectposition.entityHit;

						// ★★★ デバッグログ - EntityRainbow ヒット時 ★★★
						System.out.println("[EntityRainbow] ===== PROJECTILE HIT =====");
						System.out.println("[EntityRainbow] Hit Target: " + target.getName());
						System.out.println("[EntityRainbow] Target Class: " + target.getClass().getName());
						System.out.println("[EntityRainbow] Penetration Damage: " + this.penetrationDamage);
						System.out.println("[EntityRainbow] Penetration Level: " + PENETRATION_LEVEL);
						System.out.println("[EntityRainbow] Shooter: " + (this.shootingEntity != null ? this.shootingEntity.getName() : "null"));

						// レベル100貫通ダメージを与える（通常のダメージソースを一切使用しない）
						// この方法は寄生虫modのダメージキャップを完全に無視します
						boolean success = ArmorPenetrationSystem.dealPenetrationDamage(
								target,
								this.shootingEntity,
								PENETRATION_LEVEL,      // レベル100
								this.penetrationDamage  // 設定された貫通ダメージ
						);

						System.out.println("[EntityRainbow] Damage dealt: " + success);
						System.out.println("[EntityRainbow] ===== PROJECTILE HIT END =====");
						System.out.println("");

						if (success) {
							// 追加効果（ノックバック、エンチャント等）
							if (this.knockbackStrength > 0) {
								f4 = MathHelper.sqrt(this.motionX * this.motionX + this.motionZ * this.motionZ);

								if (f4 > 0.0F) {
									target.addVelocity(
											this.motionX * this.knockbackStrength * 0.6000000238418579D / f4,
											0.1D,
											this.motionZ * this.knockbackStrength * 0.6000000238418579D / f4
									);
								}
							}

							if (this.shootingEntity != null && this.shootingEntity instanceof EntityLivingBase) {
								EnchantmentHelper.applyThornEnchantments(target, this.shootingEntity);
								EnchantmentHelper.applyArthropodEnchantments((EntityLivingBase) this.shootingEntity, target);
							}

							if (this.shootingEntity != null && target instanceof EntityPlayer && this.shootingEntity instanceof EntityPlayerMP) {
								((EntityPlayerMP) this.shootingEntity).connection.sendPacket(new SPacketChangeGameState(6, 0.0F));
							}
						}

						// 爆発エフェクト
						if (!world.isRemote) {
							ExplosionChaos.explodeZOMG(this.world, (int) this.posX, (int) this.posY, (int) this.posZ, 5);
						}
					}
				} else {
					// ブロックに当たった場合
					BlockPos newPos = movingobjectposition.getBlockPos();
					IBlockState newState = world.getBlockState(newPos);
					this.field_145791_d = movingobjectposition.getBlockPos().getX();
					this.field_145792_e = movingobjectposition.getBlockPos().getY();
					this.field_145789_f = movingobjectposition.getBlockPos().getZ();
					this.field_145790_g = newState.getBlock();
					this.inData = newState.getBlock().getMetaFromState(newState);
				}
			}

			this.posX += this.motionX;
			this.posY += this.motionY;
			this.posZ += this.motionZ;
			f2 = MathHelper.sqrt(this.motionX * this.motionX + this.motionZ * this.motionZ);
			this.rotationYaw = (float) (Math.atan2(this.motionX, this.motionZ) * 180.0D / Math.PI);

			f1 = 0.05F;

			if (this.isInWater()) {
				for (int l = 0; l < 4; ++l) {
					f4 = 0.25F;
					this.world.spawnParticle(EnumParticleTypes.WATER_BUBBLE, this.posX - this.motionX * f4, this.posY - this.motionY * f4, this.posZ - this.motionZ * f4, this.motionX, this.motionY, this.motionZ);
				}
			}

			if (this.isWet()) {
				this.extinguish();
			}

			this.setPosition(this.posX, this.posY, this.posZ);
			this.doBlockCollisions();
		}
	}

	@Override
	public void writeEntityToNBT(NBTTagCompound p_70014_1_) {
		p_70014_1_.setShort("xTile", (short) this.field_145791_d);
		p_70014_1_.setShort("yTile", (short) this.field_145792_e);
		p_70014_1_.setShort("zTile", (short) this.field_145789_f);
		p_70014_1_.setShort("life", (short) this.ticksInGround);
		p_70014_1_.setByte("inTile", (byte) Block.getIdFromBlock(this.field_145790_g));
		p_70014_1_.setByte("inData", (byte) this.inData);
		p_70014_1_.setByte("shake", (byte) this.arrowShake);
		p_70014_1_.setByte("inGround", (byte) (this.inGround ? 1 : 0));
		p_70014_1_.setByte("pickup", (byte) this.canBePickedUp);
		p_70014_1_.setDouble("damage", this.damage);
		p_70014_1_.setFloat("penetrationDamage", this.penetrationDamage);
	}

	@Override
	public void readEntityFromNBT(NBTTagCompound p_70037_1_) {
		this.field_145791_d = p_70037_1_.getShort("xTile");
		this.field_145792_e = p_70037_1_.getShort("yTile");
		this.field_145789_f = p_70037_1_.getShort("zTile");
		this.ticksInGround = p_70037_1_.getShort("life");
		this.field_145790_g = Block.getBlockById(p_70037_1_.getByte("inTile") & 255);
		this.inData = p_70037_1_.getByte("inData") & 255;
		this.arrowShake = p_70037_1_.getByte("shake") & 255;
		this.inGround = p_70037_1_.getByte("inGround") == 1;

		if (p_70037_1_.hasKey("damage", 99)) {
			this.damage = p_70037_1_.getDouble("damage");
		}

		if (p_70037_1_.hasKey("penetrationDamage", 99)) {
			this.penetrationDamage = p_70037_1_.getFloat("penetrationDamage");
		}

		if (p_70037_1_.hasKey("pickup", 99)) {
			this.canBePickedUp = p_70037_1_.getByte("pickup");
		} else if (p_70037_1_.hasKey("player", 99)) {
			this.canBePickedUp = p_70037_1_.getBoolean("player") ? 1 : 0;
		}

		this.randomizeColor();
	}

	@Override
	protected boolean canTriggerWalking() {
		return false;
	}

	public void setDamage(double p_70239_1_) {
		this.damage = p_70239_1_;
	}

	public double getDamage() {
		return this.damage;
	}

	/**
	 * 装甲貫通ダメージを設定
	 * このメソッドを使用することで、寄生虫modのダメージキャップを完全に無視できます
	 */
	public void setPenetrationDamage(float damage) {
		this.penetrationDamage = damage;
	}

	/**
	 * 装甲貫通ダメージを取得
	 */
	public float getPenetrationDamage() {
		return this.penetrationDamage;
	}

	public void setKnockbackStrength(int p_70240_1_) {
		this.knockbackStrength = p_70240_1_;
	}

	@Override
	public boolean canBeAttackedWithItem() {
		return false;
	}

	public void setIsCritical(boolean crit) {
		this.getDataManager().set(CRITICAL, crit);
	}

	public boolean getIsCritical() {
		return this.getDataManager().get(CRITICAL);
	}

	public void randomizeColor() {
		this.getDataManager().set(RED, rand.nextInt(2) == 1 ? true : false);
		this.getDataManager().set(GREEN, rand.nextInt(2) == 1 ? true : false);
		this.getDataManager().set(BLUE, rand.nextInt(2) == 1 ? true : false);
	}
}