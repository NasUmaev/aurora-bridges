package com.aurora.gtnh;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Compares compact inventory totals once per second; slot movement and durability changes are ignored. */
final class AuroraInventoryObserver {

    private static final int SAMPLE_INTERVAL_TICKS = 20;
    private static final int SUMMARY_ITEM_LIMIT = 4;

    private final Map<String, PendingChange> pending = new HashMap<>();
    private Map<String, ItemTotal> previous;
    private int ticksUntilSample;

    AuroraEvent observe(Minecraft minecraft) {
        if (minecraft.thePlayer == null || minecraft.theWorld == null) {
            reset();
            return null;
        }
        if (ticksUntilSample-- > 0) return null;
        ticksUntilSample = SAMPLE_INTERVAL_TICKS - 1;

        Map<String, ItemTotal> current = snapshot(minecraft);
        if (previous == null) {
            previous = current;
            return null;
        }

        boolean changed = false;
        Set<String> keys = new TreeSet<>(previous.keySet());
        keys.addAll(current.keySet());
        for (String key : keys) {
            ItemTotal before = previous.get(key);
            ItemTotal after = current.get(key);
            int oldCount = before == null ? 0 : before.count;
            int newCount = after == null ? 0 : after.count;
            int difference = newCount - oldCount;
            if (difference == 0) continue;
            changed = true;
            ItemTotal details = after == null ? before : after;
            PendingChange accumulated = pending.get(key);
            if (accumulated == null) {
                accumulated = new PendingChange(details.id, details.name);
                pending.put(key, accumulated);
            }
            accumulated.difference += difference;
            if (accumulated.difference == 0) pending.remove(key);
        }
        previous = current;
        if (changed || pending.isEmpty()) return null;

        List<ItemChange> gained = new ArrayList<>();
        List<ItemChange> lost = new ArrayList<>();
        for (String key : new TreeSet<>(pending.keySet())) {
            PendingChange change = pending.get(key);
            ItemChange result = new ItemChange(change.id, change.name, Math.abs(change.difference));
            if (change.difference > 0) gained.add(result);
            else lost.add(result);
        }
        pending.clear();
        if (gained.isEmpty() && lost.isEmpty()) return null;

        JsonObject data = new JsonObject();
        data.add("gained", toJson(gained));
        data.add("lost", toJson(lost));
        return new AuroraEvent("inventory_changed", summary(gained, lost), "info", false, data);
    }

    void reset() {
        previous = null;
        pending.clear();
        ticksUntilSample = 0;
    }

    private static Map<String, ItemTotal> snapshot(Minecraft minecraft) {
        Map<String, ItemTotal> result = new HashMap<>();
        addAll(result, minecraft.thePlayer.inventory.mainInventory);
        addAll(result, minecraft.thePlayer.inventory.armorInventory);
        return result;
    }

    private static void addAll(Map<String, ItemTotal> result, ItemStack[] inventory) {
        for (ItemStack stack : inventory) {
            if (stack == null || stack.getItem() == null || stack.stackSize <= 0) continue;
            Item item = stack.getItem();
            Object registered = Item.itemRegistry.getNameForObject(item);
            String id = registered == null ? "item:" + Item.getIdFromItem(item) : registered.toString();
            String key = item.isDamageable() ? id : id + "@" + stack.getItemDamage();
            ItemTotal total = result.get(key);
            if (total == null) {
                result.put(key, new ItemTotal(id, stack.getDisplayName(), stack.stackSize));
            } else {
                total.count += stack.stackSize;
            }
        }
    }

    private static JsonArray toJson(List<ItemChange> changes) {
        JsonArray result = new JsonArray();
        for (ItemChange change : changes) {
            JsonObject item = new JsonObject();
            item.addProperty("id", change.id);
            item.addProperty("name", change.name);
            item.addProperty("count", change.count);
            result.add(item);
        }
        return result;
    }

    private static String summary(List<ItemChange> gained, List<ItemChange> lost) {
        List<String> parts = new ArrayList<>();
        if (!gained.isEmpty()) parts.add("Появилось в инвентаре: " + describe(gained));
        if (!lost.isEmpty()) parts.add("Исчезло из инвентаря: " + describe(lost));
        return String.join(". ", parts) + ".";
    }

    private static String describe(List<ItemChange> changes) {
        List<String> descriptions = new ArrayList<>();
        int shown = Math.min(changes.size(), SUMMARY_ITEM_LIMIT);
        for (int index = 0; index < shown; index++) {
            ItemChange change = changes.get(index);
            descriptions.add(change.name + " ×" + change.count);
        }
        if (changes.size() > shown) descriptions.add("и ещё " + (changes.size() - shown) + " вида");
        return String.join(", ", descriptions);
    }

    private static final class ItemTotal {

        private final String id;
        private final String name;
        private int count;

        private ItemTotal(String id, String name, int count) {
            this.id = id;
            this.name = name;
            this.count = count;
        }
    }

    private static final class ItemChange {

        private final String id;
        private final String name;
        private final int count;

        private ItemChange(String id, String name, int count) {
            this.id = id;
            this.name = name;
            this.count = count;
        }
    }

    private static final class PendingChange {

        private final String id;
        private final String name;
        private int difference;

        private PendingChange(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
