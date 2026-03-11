package com.hbm.physics;

import com.hbm.items.armor.ArmorPenetrationSystem;
import com.hbm.items.armor.ArmorShieldSystem;
import com.hbm.items.armor.IHeatResistanceArmor;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;

import javax.annotation.Nullable;

/**
 * 熱ダメージシステム - Thermal Damage System
 *
 * 温度(°C)に基づいた熱ダメージを提供するstaticユーティリティクラス。
 * ArmorPenetrationSystem / ArmorShieldSystem と連携して動作します。
 *
 * ================================================================
 * 温度域別の挙動:
 * ================================================================
 *
 *   < 100°C   : ダメージなし
 *
 *   100〜1499°C: 【延焼状態】
 *                乗数 = (temperature - 100) / 100 + 1  (線形スケーリング)
 *                → 100°C: 1倍, 200°C: 2倍, ..., 1400°C: 14倍, 1499°C: ≈15倍
 *                setFire(乗数秒) + BASE_FIRE_DAMAGE × 乗数 のダメージ
 *
 *   >= 1500°C : 【熱気化・即死】
 *                target.setHealth(0.0F) で HP を直接 0 に設定したうえで
 *                target.onDeath(vaporizeSource) を呼び出して即死させます。
 *                ダメージパイプライン・アーマー計算・無敵フレームを一切経由しません。
 *
 * ================================================================
 * 熱耐性による無効化:
 * ================================================================
 *   IHeatResistanceArmor を実装したアーマーを装備中は、
 *   getHeatResistance(stack) >= temperature の場合にダメージを完全無効化。
 *   1500°C以上の即死域でも耐熱値が温度を上回っていれば無効化されます。
 *
 * ================================================================
 * ダメージ量のカスタマイズ:
 * ================================================================
 *   applyThermalDamage() の customDamage パラメータは延焼域(100〜1499°C)専用。
 *   - 延焼域(100〜1499°C): customDamage は無視、温度乗数ベースのダメージを使用
 *   - 即死域 (>=1500°C):   HP を直接 0 化 (customDamage は使用されない)
 *   - 最小値: 任意 (呼び出し元が定義)
 *   - 最大値: Long.MAX_VALUE (applyThermalDamageLong使用時; float変換上限はFloat.MAX_VALUE)
 *
 * @see com.hbm.items.armor.IHeatResistanceArmor
 */
public class ThermalDamageSystem {

    // =========================================================================
    // === 温度定数 ==============================================================
    // =========================================================================

    /** 熱ダメージが発生する最低温度 (°C) */
    public static final double MIN_THERMAL_TEMP = 100.0;

    /** 延焼域の上限 / 超貫通域の境界温度 (°C) */
    public static final double PENETRATION_THRESHOLD = 1500.0;

    /**
     * 即死域での参照用貫通レベル定数 (現在は内部で使用されていません)。
     * HP 直接 0 化方式に移行したため非使用ですが、互換性のために保持します。
     */
    public static final int PENETRATION_LEVEL = Integer.MAX_VALUE;

    /**
     * 基準火ダメージ (100°C = 1倍時のダメージ量)。
     * 延焼域ではこの値に乗数を掛けた分のダメージが与えられます。
     */
    public static final float BASE_FIRE_DAMAGE = 1.0F;

    // =========================================================================
    // === 計算メソッド ==========================================================
    // =========================================================================

    /**
     * 指定温度での延焼ダメージ乗数を返します。
     *
     * 線形スケーリング: 100°Cごとに+1倍
     *   100°C → 1.0倍
     *   200°C → 2.0倍
     *   ...
     *   1499°C → ≈14.99倍
     *   1500°C以上 → 15.0倍を返す (超貫通域ではこの値は使用されないが定義上の最大値)
     *
     * @param temperature 温度 (°C)
     * @return ダメージ乗数 (0未満の温度では0)
     */
    public static float getDamageMultiplier(double temperature) {
        if (temperature < MIN_THERMAL_TEMP) return 0F;
        if (temperature >= PENETRATION_THRESHOLD) return 15.0F;
        return (float) ((temperature - MIN_THERMAL_TEMP) / 100.0 + 1.0);
    }

    /**
     * 指定温度が即死域 (1500°C以上) かどうかを判定します。
     *
     * @param temperature 温度 (°C)
     * @return true = 即死域 (HP を直接 0 に設定して即死させる)
     */
    public static boolean isPenetrationTemperature(double temperature) {
        return temperature >= PENETRATION_THRESHOLD;
    }

    // =========================================================================
    // === 熱耐性チェック ========================================================
    // =========================================================================

    /**
     * 対象エンティティが装備しているアーマーに、指定温度に対する熱耐性があるか確認します。
     *
     * プレイヤーの全アーマースロット (HEAD, CHEST, LEGS, FEET) を走査し、
     * IHeatResistanceArmor を実装したアーマーの耐熱値が temperature 以上であれば
     * 熱耐性ありと判定します (1つでも有効なら全体を無効化)。
     *
     * 非プレイヤーエンティティは現在熱耐性チェック非対応 (常に false を返す)。
     *
     * @param target      対象エンティティ
     * @param temperature 熱ダメージの温度 (°C)
     * @return true = 熱耐性による無効化が有効
     */
    public static boolean isResistantToHeat(EntityLivingBase target, double temperature) {
        if (!(target instanceof EntityPlayer)) return false;

        EntityPlayer player = (EntityPlayer) target;
        for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
            if (slot.getSlotType() != EntityEquipmentSlot.Type.ARMOR) continue;

            ItemStack armorStack = player.getItemStackFromSlot(slot);
            if (armorStack.isEmpty()) continue;
            if (!(armorStack.getItem() instanceof IHeatResistanceArmor)) continue;

            IHeatResistanceArmor heatArmor = (IHeatResistanceArmor) armorStack.getItem();
            if (heatArmor.canResistHeat(armorStack, temperature)) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // === ダメージ適用メソッド ==================================================
    // =========================================================================

    /**
     * 熱ダメージを対象エンティティに適用します。(float版)
     *
     * 処理の流れ:
     * 1. temperature < 100°C → return (何もしない)
     * 2. IHeatResistanceArmor チェック → 耐熱値 >= temperature なら return (全キャンセル)
     * 3. temperature 100〜1499°C → 延焼ダメージ (setFire + 乗数付き火ダメージ)
     * 4. temperature >= 1500°C → HP を直接 0 にセットして onDeath() で即死
     *
     * 注: 延焼域(100〜1499°C)では customDamage は使用されず、
     *     BASE_FIRE_DAMAGE × getDamageMultiplier(temperature) が適用されます。
     *     即死域(>=1500°C)では HP 直接 0 化のため customDamage は使用されません。
     *
     * @param target       対象エンティティ
     * @param temperature  熱ダメージの温度 (°C)
     * @param customDamage 延焼域で参照用に保持 (現在は使用されない)
     * @param source       ダメージ源エンティティ (null可)
     */
    public static void applyThermalDamage(EntityLivingBase target, double temperature,
                                          float customDamage, @Nullable Entity source) {
        if (temperature < MIN_THERMAL_TEMP) return;

        // 熱耐性チェック: 耐熱値 >= 温度 なら即死域でも全ダメージをキャンセル
        if (isResistantToHeat(target, temperature)) return;

        if (isPenetrationTemperature(temperature)) {
            // === 即死域 (>= 1500°C): 熱気化 ===
            // HP を直接 0 に設定し、通常のダメージパイプラインを完全にバイパスして即死させます。
            // アーマー計算・ダメージ軽減・無敵フレームを一切経由しません。
            // ArmorShieldSystem の HP 巻き戻しを防ぐために markPenetrationSuccess を呼び出します。
            if (!target.isDead) {
                target.setHealth(0.0F);
                if (target instanceof EntityPlayer) {
                    ArmorShieldSystem.markPenetrationSuccess(
                            ((EntityPlayer) target).getUniqueID(), 0.0F);
                }
                DamageSource vaporizeSource = new DamageSource("thermalVaporization");
                vaporizeSource.setDamageBypassesArmor();
                vaporizeSource.setDamageIsAbsolute();
                target.onDeath(vaporizeSource);
            }
        } else {
            // === 延焼域 (100〜1499°C) ===
            float multiplier = getDamageMultiplier(temperature);

            // 火の持続時間: 乗数に比例した秒数 (setFireは秒単位)
            int fireSeconds = Math.max(1, (int) multiplier);
            target.setFire(fireSeconds);

            // 火ダメージ: 基準値 × 乗数 (ArmorShieldSystem を完全バイパス)
            ArmorPenetrationSystem.dealAbsoluteDamageBypassAll(target, BASE_FIRE_DAMAGE * multiplier, PENETRATION_LEVEL);
        }
    }

    /**
     * 熱ダメージを対象エンティティに適用します。(ソースエンティティなし版)
     *
     * @param target       対象エンティティ
     * @param temperature  熱ダメージの温度 (°C)
     * @param customDamage 延焼域で参照用に保持 (現在は使用されない)
     */
    public static void applyThermalDamage(EntityLivingBase target, double temperature,
                                          float customDamage) {
        applyThermalDamage(target, temperature, customDamage, null);
    }

    /**
     * 熱ダメージを対象エンティティに適用します。(long値対応版)
     *
     * Long.MAX_VALUE までのダメージ量を指定可能。
     * 内部では float に変換され、Float.MAX_VALUE を上限とします。
     * 即死域(>=1500°C)では HP 直接 0 化のためダメージ量は使用されません。
     *
     * @param target       対象エンティティ
     * @param temperature  熱ダメージの温度 (°C)
     * @param customDamage 延焼域で参照用 (最大値 Long.MAX_VALUE)
     * @param source       ダメージ源エンティティ (null可)
     */
    public static void applyThermalDamageLong(EntityLivingBase target, double temperature,
                                              long customDamage, @Nullable Entity source) {
        float damage = (customDamage >= (long) Float.MAX_VALUE)
                ? Float.MAX_VALUE
                : (float) customDamage;
        applyThermalDamage(target, temperature, damage, source);
    }

    /**
     * 熱ダメージを対象エンティティに適用します。(long値・ソースなし版)
     *
     * @param target       対象エンティティ
     * @param temperature  熱ダメージの温度 (°C)
     * @param customDamage 超貫通域で使用するダメージ量 (最大値 Long.MAX_VALUE)
     */
    public static void applyThermalDamageLong(EntityLivingBase target, double temperature,
                                              long customDamage) {
        applyThermalDamageLong(target, temperature, customDamage, null);
    }
}
