package com.hbm.items.armor;

import net.minecraft.item.ItemStack;

/**
 * 熱耐性アーマーインターフェース - Heat Resistance Armor Interface
 *
 * このインターフェースを実装したアーマーは熱ダメージへの耐性を持ちます。
 * 耐熱値(°C)を定義し、その温度以下の熱ダメージを完全に無効化します。
 *
 * 熱耐性の仕様:
 *   - 熱ダメージの温度 <= 耐熱値 の場合: ダメージを完全無効化
 *   - 1500°C以上の超貫通域(ArmorPenetrationSystem相当)でも耐熱値が上回れば無効化
 *   - 最大耐熱値: 1億°C (MAX_HEAT_RESISTANCE)
 *
 * 実装例:
 *   public int getHeatResistance(ItemStack stack) { return 10_000_000; } // 1000万°C耐性
 *
 * @see com.hbm.physics.ThermalDamageSystem
 */
public interface IHeatResistanceArmor {

    /**
     * 耐熱値の最大値: 1億°C
     */
    int MAX_HEAT_RESISTANCE = 100_000_000;

    /**
     * このアーマーの耐熱値を返します (°C単位)。
     *
     * 熱ダメージの温度がこの値以下の場合、ダメージは完全に無効化されます。
     * 値は 0 〜 MAX_HEAT_RESISTANCE の範囲で指定してください。
     *
     * @param stack アーマーのItemStack
     * @return 耐熱値 (0°C〜MAX_HEAT_RESISTANCE°C)
     */
    int getHeatResistance(ItemStack stack);

    /**
     * 指定された温度の熱ダメージをこのアーマーが無効化できるか判定します。
     *
     * デフォルト実装: 耐熱値 >= 温度 なら無効化可能。
     *
     * @param stack       アーマーのItemStack
     * @param temperature 熱ダメージの温度 (°C)
     * @return true = 無効化可能 (ダメージをブロック), false = ダメージ通過
     */
    default boolean canResistHeat(ItemStack stack, double temperature) {
        return getHeatResistance(stack) >= temperature;
    }
}
