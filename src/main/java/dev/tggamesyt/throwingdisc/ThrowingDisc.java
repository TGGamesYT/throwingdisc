package dev.tggamesyt.throwingdisc;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.TicketType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Throwing discs: copper, iron, golden, diamond and netherite. Using one throws it straight ahead (no gravity, no
 * charging). It cuts through every mob in its way, ricochets off hard surfaces (flying further and faster after each
 * bounce) and, after a while, comes back to whoever threw it, hitting things on the way back too. It goes back into
 * the slot it was thrown from (also the offhand).
 */
public class ThrowingDisc implements ModInitializer {
    public static final String MOD_ID = "throwingdisc";

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    /**
     * Blocks a disc does not ricochet off: it turns back instead. Empty, since the announcement shows it bouncing off
     * grass, logs and leaves alike; a data pack can add blocks to it.
     */
    public static final TagKey<Block> SOFT_SURFACES = TagKey.create(Registries.BLOCK, id("soft_surfaces"));

    private static Item disc(String material, float damage, int durability, int enchantability, Item repairItem,
                             UnaryOperator<Item.Properties> extra) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(material + "_throwing_disc"));
        Item.Properties properties = new Item.Properties().setId(key).durability(durability).enchantable(enchantability).repairable(repairItem);
        return Registry.register(BuiltInRegistries.ITEM, key, new ThrowingDiscItem(damage, extra.apply(properties)));
    }

    // Only the iron disc's damage (7) is known so far; the others follow the usual tool tiers.
    public static final Item COPPER_THROWING_DISC = disc("copper", 6, 190, 13, Items.COPPER_INGOT, p -> p);
    public static final Item IRON_THROWING_DISC = disc("iron", 7, 250, 14, Items.IRON_INGOT, p -> p);
    public static final Item GOLDEN_THROWING_DISC = disc("golden", 5, 32, 22, Items.GOLD_INGOT, p -> p);
    public static final Item DIAMOND_THROWING_DISC = disc("diamond", 8, 1561, 10, Items.DIAMOND, p -> p);
    public static final Item NETHERITE_THROWING_DISC = disc("netherite", 9, 2031, 15, Items.NETHERITE_INGOT, Item.Properties::fireResistant);

    public static final List<Item> DISCS = List.of(COPPER_THROWING_DISC, IRON_THROWING_DISC, GOLDEN_THROWING_DISC,
            DIAMOND_THROWING_DISC, NETHERITE_THROWING_DISC);

    /** Keeps the chunks around a flying disc loaded (and the dimension running), like an ender pearl. */
    public static final TicketType FLIGHT_TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE, id("disc_flight"),
            new TicketType(20L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));

    private static final ResourceKey<EntityType<?>> THROWN_DISC_KEY = ResourceKey.create(Registries.ENTITY_TYPE, id("throwing_disc"));
    public static final EntityType<ThrownDisc> THROWN_DISC = Registry.register(BuiltInRegistries.ENTITY_TYPE, THROWN_DISC_KEY,
            EntityType.Builder.<ThrownDisc>of(ThrownDisc::new, MobCategory.MISC)
                    .sized(0.5F, 0.25F).clientTrackingRange(4).updateInterval(1)
                    .build(THROWN_DISC_KEY));

    @Override
    public void onInitialize() {
        // Discs in flight go back to throwers who leave or are still on when the server stops.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ThrownDisc.recoverFor(handler.player));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> server.getPlayerList().getPlayers().forEach(ThrownDisc::recoverFor));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ThrownDisc.forgetAll());
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.COMBAT).register(output -> {
            for (Item disc : DISCS) {
                output.accept(disc);
            }
        });
    }
}
