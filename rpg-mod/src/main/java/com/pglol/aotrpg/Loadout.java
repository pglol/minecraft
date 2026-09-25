package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.AnimalArmorItem;
import net.minecraft.item.AxeItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.BrushItem;
import net.minecraft.item.BucketItem;
import net.minecraft.item.CompassItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.FireworkRocketItem;
import net.minecraft.item.FishingRodItem;
import net.minecraft.item.FlintAndSteelItem;
import net.minecraft.item.GoatHornItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.LeadItem;
import net.minecraft.item.MaceItem;
import net.minecraft.item.MilkBucketItem;
import net.minecraft.item.MiningToolItem;
import net.minecraft.item.OnAStickItem;
import net.minecraft.item.PotionItem;
import net.minecraft.item.SaddleItem;
import net.minecraft.item.ShearsItem;
import net.minecraft.item.ShieldItem;
import net.minecraft.item.SpyglassItem;
import net.minecraft.item.SwordItem;
import net.minecraft.item.TridentItem;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The combat loadout: every hotbar slot has a purpose.
 *   1 Melee   2 Ranged   3 Sidearm   4 Tool   | 5 Heal |   6 Mount   7 Signal   8 Free   9 Free
 * Items only go where they belong (nothing is equipped for you). ODM grips are a pair: the sheath
 * key puts both on your back, and draws them again into slot 1 and the off hand (the off-hand item
 * is kept aside and comes back when they are sheathed).
 */
public final class Loadout {
    public enum Kind {
        MELEE("Melee", "ODM grips, blades, swords, axes"),
        RANGED("Ranged", "APG gun, bows, crossbows, muskets"),
        SIDEARM("Sidearm", "A second weapon or a shield"),
        TOOL("Tool", "Pickaxes, shovels, fishing rods, shears"),
        HEAL("Heal", "Food, meals and potions. [H] uses it instantly"),
        MOUNT("Mount", "Saddle, lead, horse armor, horse treats"),
        SIGNAL("Signal", "Flare gun and flares, torches, spyglass, maps"),
        FREE("Free", "Anything");

        public final String title, hint;

        Kind(String title, String hint) {
            this.title = title;
            this.hint = hint;
        }
    }

    public static final Kind[] SLOTS = {Kind.MELEE, Kind.RANGED, Kind.SIDEARM, Kind.TOOL, Kind.HEAL,
        Kind.MOUNT, Kind.SIGNAL, Kind.FREE, Kind.FREE};
    public static final int HEAL_SLOT = 4;

    private static final String NS = "dannys-aot";
    private static final Set<String> AOT_MELEE = Set.of("odm_gear", "odm_apg", "blade");
    private static final Set<String> AOT_RANGED = Set.of("apg_gun", "musket", "flinstock", "thunder_spear");
    private static final Set<String> AOT_HEAL = Set.of("syringe", "armor_potion", "canned_herring", "canned_herring_open", "vintage_wine");
    private static final Set<Item> MOUNT_ITEMS = Set.of(Items.HAY_BLOCK, Items.GOLDEN_CARROT, Items.NAME_TAG, Items.APPLE, Items.SUGAR);
    private static final Set<Item> SIGNAL_ITEMS = Set.of(Items.TORCH, Items.SOUL_TORCH, Items.LANTERN, Items.SOUL_LANTERN, Items.CLOCK,
        Items.RECOVERY_COMPASS, Items.MAP);

    Loadout() {}

    private static String aot(ItemStack s) {
        Identifier id = Registries.ITEM.getId(s.getItem());
        return id.getNamespace().equals(NS) || id.getNamespace().equals(AotItems.namespace) ? id.getPath() : null;
    }

    public static boolean isGrip(ItemStack s) {
        String p = s.isEmpty() ? null : aot(s);
        return p != null && (p.equals("odm_gear") || p.equals("odm_apg"));
    }

    public static boolean isMelee(ItemStack s) {
        Item i = s.getItem();
        String p = aot(s);
        return i instanceof SwordItem || i instanceof AxeItem || i instanceof MaceItem || i instanceof TridentItem
            || (p != null && AOT_MELEE.contains(p));
    }

    public static boolean isRanged(ItemStack s) {
        Item i = s.getItem();
        String p = aot(s);
        return i instanceof BowItem || i instanceof CrossbowItem || i instanceof TridentItem || (p != null && AOT_RANGED.contains(p));
    }

    public static boolean isHeal(ItemStack s) {
        String p = aot(s);
        return Provisions.isProvision(s) || s.isOf(Items.GOLDEN_APPLE) || s.isOf(Items.ENCHANTED_GOLDEN_APPLE)
            || s.isOf(Items.GLISTERING_MELON_SLICE) || s.getItem() instanceof PotionItem || s.getItem() instanceof MilkBucketItem
            || (p != null && AOT_HEAL.contains(p));
    }

    public static boolean fits(Kind k, ItemStack s) {
        if (s.isEmpty() || k == Kind.FREE) return true;
        Item i = s.getItem();
        String p = aot(s);
        return switch (k) {
            case MELEE -> isMelee(s);
            case RANGED -> isRanged(s);
            case SIDEARM -> isMelee(s) || isRanged(s) || i instanceof ShieldItem;
            case TOOL -> i instanceof MiningToolItem || i instanceof ShearsItem || i instanceof FishingRodItem
                || i instanceof FlintAndSteelItem || i instanceof BrushItem || i instanceof BucketItem;
            case HEAL -> isHeal(s);
            case MOUNT -> i instanceof SaddleItem || i instanceof LeadItem || i instanceof AnimalArmorItem || i instanceof OnAStickItem
                || MOUNT_ITEMS.contains(i);
            case SIGNAL -> i instanceof FireworkRocketItem || i instanceof SpyglassItem || i instanceof CompassItem
                || i instanceof FilledMapItem || i instanceof GoatHornItem || SIGNAL_ITEMS.contains(i)
                || (p != null && (p.equals("flare_gun") || p.endsWith("_flare_cartridge")));
            case FREE -> true;
        };
    }

    public static boolean allows(int hotbarSlot, ItemStack s) {
        return hotbarSlot < 0 || hotbarSlot >= 9 || fits(SLOTS[hotbarSlot], s);
    }

    /** Rules apply to survival players with a character; creative is for building. */
    public static boolean enforced(PlayerEntity p) {
        return p != null && !p.isCreative() && !p.isSpectator();
    }

    // ---------------------------------------------------------------- server side

    private final Map<UUID, Integer> lastSheath = new HashMap<>();

    /** Sheath (slot 0) and stashed off-hand item (slot 1), saved with the satchel. */
    private static SimpleInventory gear(ServerPlayerEntity p) {
        return AotRpg.SATCHEL.gear(p.getUuid());
    }

    /** Every tick for players with a character. */
    public void tick(ServerPlayerEntity p, int ticks) {
        if (!enforced(p)) return;
        boolean calm = p.currentScreenHandler == p.playerScreenHandler && p.currentScreenHandler.getCursorStack().isEmpty();
        if (ticks % 5 == 0) {
            Provisions.convertAll(p.getInventory());
            if (ticks % 20 == 0) Provisions.convertAll(AotRpg.SATCHEL.get(p.getUuid()));
            if (calm) evict(p);
            broadcast(p, false);
        }
    }

    /** Items that do not belong in a hotbar slot move to the backpack. Nothing is equipped for you. */
    private void evict(ServerPlayerEntity p) {
        PlayerInventory inv = p.getInventory();
        boolean changed = false;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.main.get(i);
            if (s.isEmpty() || allows(i, s)) continue;
            int to = freeBackpack(inv, s);
            if (to < 0) continue;
            if (inv.main.get(to).isEmpty()) inv.main.set(to, s);
            else inv.main.get(to).increment(s.getCount());
            inv.main.set(i, ItemStack.EMPTY);
            changed = true;
        }
        if (changed) inv.markDirty();
    }

    /** A backpack slot (9-35) that can take all of the stack, or -1. */
    private static int freeBackpack(PlayerInventory inv, ItemStack s) {
        for (int j = 9; j < 36; j++) {
            ItemStack m = inv.main.get(j);
            if (!m.isEmpty() && ItemStack.areItemsAndComponentsEqual(m, s) && m.getCount() + s.getCount() <= m.getMaxCount()) return j;
        }
        for (int j = 9; j < 36; j++) if (inv.main.get(j).isEmpty()) return j;
        return -1;
    }

    private static final int SHEATH_A = 0, STASH = 1, SHEATH_B = 2;

    private static boolean sheathed(SimpleInventory g) {
        return isGrip(g.getStack(SHEATH_A)) || isGrip(g.getStack(SHEATH_B));
    }

    /** Puts a grip straight into the sheath (starter kit); false if the sheath is full. */
    public boolean sheathe(ServerPlayerEntity p, ItemStack grip) {
        SimpleInventory g = gear(p);
        int slot = g.getStack(SHEATH_A).isEmpty() ? SHEATH_A : g.getStack(SHEATH_B).isEmpty() ? SHEATH_B : -1;
        if (slot < 0) return false;
        g.setStack(slot, grip);
        g.markDirty();
        broadcast(p, true);
        return true;
    }

    /** The sheath key: draw both grips (slot 1 and the off hand) or put them on your back. */
    public void toggle(ServerPlayerEntity p) {
        if (!enforced(p)) return;
        SimpleInventory g = gear(p);
        if (sheathed(g)) draw(p, g);
        else sheatheHeld(p, g);
        broadcast(p, true);
    }

    private void draw(ServerPlayerEntity p, SimpleInventory g) {
        PlayerInventory inv = p.getInventory();
        ItemStack a = g.getStack(SHEATH_A), b = g.getStack(SHEATH_B);
        int aSlot = SHEATH_A, bSlot = SHEATH_B;
        if (!isGrip(a)) {
            a = b;
            aSlot = SHEATH_B;
            b = ItemStack.EMPTY;
        }
        // Slot 1 makes room: whatever is there goes to the backpack.
        ItemStack cur = inv.main.get(0);
        if (!cur.isEmpty()) {
            int to = freeBackpack(inv, cur);
            if (to < 0) {
                p.sendMessage(Text.literal("No room in your backpack to draw your grips.").formatted(Formatting.RED), true);
                return;
            }
            if (inv.main.get(to).isEmpty()) inv.main.set(to, cur);
            else inv.main.get(to).increment(cur.getCount());
        }
        inv.main.set(0, a);
        g.setStack(aSlot, ItemStack.EMPTY);
        if (isGrip(b)) {
            ItemStack off = inv.offHand.get(0);
            boolean room = true;
            if (!off.isEmpty()) {
                if (g.getStack(STASH).isEmpty()) g.setStack(STASH, off);
                else {
                    int to = freeBackpack(inv, off);
                    if (to < 0) room = false;
                    else if (inv.main.get(to).isEmpty()) inv.main.set(to, off);
                    else inv.main.get(to).increment(off.getCount());
                }
            }
            if (room) {
                inv.offHand.set(0, b);
                g.setStack(bSlot, ItemStack.EMPTY);
            }
        }
        inv.selectedSlot = 0;
        p.networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(0));
        inv.markDirty();
        g.markDirty();
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.ITEM_ARMOR_EQUIP_IRON.value(), SoundCategory.PLAYERS, 0.8f, 1.2f);
        p.sendMessage(Text.literal("Grips drawn").formatted(Formatting.GOLD), true);
    }

    private void sheatheHeld(ServerPlayerEntity p, SimpleInventory g) {
        PlayerInventory inv = p.getInventory();
        int n = 0;
        // The grip in your hand, or the one in slot 1.
        int mainSlot = isGrip(inv.main.get(inv.selectedSlot)) ? inv.selectedSlot : isGrip(inv.main.get(0)) ? 0 : -1;
        if (mainSlot >= 0) {
            g.setStack(SHEATH_A, inv.main.get(mainSlot));
            inv.main.set(mainSlot, ItemStack.EMPTY);
            n++;
        }
        ItemStack off = inv.offHand.get(0);
        if (isGrip(off)) {
            g.setStack(n == 0 ? SHEATH_A : SHEATH_B, off);
            inv.offHand.set(0, g.getStack(STASH));
            g.setStack(STASH, ItemStack.EMPTY);
            n++;
        }
        if (n == 0) {
            p.sendMessage(Text.literal("No ODM grips in hand or in slot 1 to sheathe.").formatted(Formatting.GRAY), true);
            return;
        }
        inv.markDirty();
        g.markDirty();
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.ITEM_ARMOR_EQUIP_LEATHER.value(), SoundCategory.PLAYERS, 0.8f, 1.0f);
        p.sendMessage(Text.literal("Grips sheathed").formatted(Formatting.GRAY), true);
    }

    /** Everything in the sheath and stash back to the inventory (death, reset). */
    public void unsheathAll(ServerPlayerEntity p) {
        SimpleInventory g = gear(p);
        for (int i = 0; i < g.size(); i++) {
            ItemStack s = g.removeStack(i);
            if (!s.isEmpty()) p.getInventory().offerOrDrop(s);
        }
    }

    /** How many grips show on this player's back: 0, 1 or 2. */
    private static int onBack(ServerPlayerEntity p) {
        SimpleInventory g = gear(p);
        return (isGrip(g.getStack(SHEATH_A)) ? 1 : 0) + (isGrip(g.getStack(SHEATH_B)) ? 1 : 0);
    }

    private static String sheathItem(ServerPlayerEntity p) {
        SimpleInventory g = gear(p);
        ItemStack s = isGrip(g.getStack(SHEATH_A)) ? g.getStack(SHEATH_A) : g.getStack(SHEATH_B);
        return s.isEmpty() ? "" : Registries.ITEM.getId(s.getItem()).toString();
    }

    public void broadcast(ServerPlayerEntity p, boolean force) {
        int n = onBack(p);
        String item = sheathItem(p);
        int key = n * 31 + item.hashCode();
        Integer old = lastSheath.put(p.getUuid(), key);
        if (!force && old != null && old == key) return;
        Net.SheathState msg = new Net.SheathState(p.getUuid(), item, n);
        for (ServerPlayerEntity o : p.getServer().getPlayerManager().getPlayerList()) {
            if (ServerPlayNetworking.canSend(o, Net.SheathState.ID)) ServerPlayNetworking.send(o, msg);
        }
    }

    /** A joining player learns everyone's sheath. */
    public void sendAll(ServerPlayerEntity to) {
        if (!ServerPlayNetworking.canSend(to, Net.SheathState.ID)) return;
        for (ServerPlayerEntity o : to.getServer().getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(to, new Net.SheathState(o.getUuid(), sheathItem(o), onBack(o)));
        }
    }

    public void forget(ServerPlayerEntity p) {
        lastSheath.remove(p.getUuid());
    }
}
