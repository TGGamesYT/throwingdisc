package dev.tggamesyt.throwingdisc;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** A throwing disc. Using it throws it right away; it wears down when it ricochets (see {@link ThrownDisc}). */
public class ThrowingDiscItem extends Item {
    private final float damage;

    public ThrowingDiscItem(float damage, Properties properties) {
        super(properties);
        this.damage = damage;
    }

    public float damage() {
        return damage;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel serverLevel) {
            boolean creative = player.hasInfiniteMaterials();
            ItemStack thrown = creative ? stack.copyWithCount(1) : stack.split(1);
            int slot = hand == InteractionHand.OFF_HAND ? Inventory.SLOT_OFFHAND : player.getInventory().getSelectedSlot();
            ThrownDisc.launch(serverLevel, player, thrown, slot, damage, creative);
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIDENT_THROW, SoundSource.PLAYERS, 1.0F, 1.2F);
        }
        player.awardStat(Stats.ITEM_USED.get(this));
        return InteractionResult.SUCCESS;
    }
}
