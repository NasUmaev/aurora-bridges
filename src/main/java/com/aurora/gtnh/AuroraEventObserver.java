package com.aurora.gtnh;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.item.ItemStack;

/** Detects meaningful state transitions without scanning the world or running expensive work every tick. */
final class AuroraEventObserver {

    private boolean initialized;
    private boolean wasDead;
    private boolean wasBurning;
    private boolean wasCritical;
    private int dimension;
    private long lastReactionAt;

    AuroraEvent observe(Minecraft minecraft) {
        EntityClientPlayerMP player = minecraft.thePlayer;
        if (player == null || minecraft.theWorld == null) {
            reset();
            return null;
        }

        boolean dead = player.isDead || player.getHealth() <= 0.0F;
        boolean burning = player.isBurning();
        boolean critical = !dead && player.getHealth() > 0.0F && player.getHealth() <= BridgeConfig.criticalHealth;
        int currentDimension = player.dimension;

        if (!initialized) {
            initialized = true;
            wasDead = dead;
            wasBurning = burning;
            wasCritical = critical;
            dimension = currentDimension;
            return null;
        }

        AuroraEvent event = null;
        boolean deathStarted = dead && !wasDead;
        if (deathStarted) {
            event = event("death", "Игрок только что умер.", "critical", true);
        } else if (burning && !wasBurning) {
            event = event(
                "caught_fire",
                "Игрок загорелся. Здоровье: " + player.getHealth() + " из " + player.getMaxHealth() + ".",
                "high",
                false);
        } else if (critical && !wasCritical) {
            event = event(
                "critical_health",
                "Здоровье игрока стало критически низким: " + player.getHealth() + " из " + player.getMaxHealth() + ".",
                "critical",
                false);
        } else if (currentDimension != dimension) {
            event = event(
                "dimension_changed",
                "Игрок сменил измерение с " + dimension + " на " + currentDimension + ".",
                "medium",
                false);
        }

        wasDead = dead;
        wasBurning = burning;
        wasCritical = critical;
        dimension = currentDimension;
        return event;
    }

    AuroraEvent itemDestroyed(ItemStack item) {
        if (!initialized || item == null || !item.isItemStackDamageable()) return null;
        return event(
            "item_broken",
            "У игрока только что сломался предмет: " + item.getDisplayName() + ".",
            "medium",
            false);
    }

    void reset() {
        initialized = false;
        wasDead = false;
        wasBurning = false;
        wasCritical = false;
        dimension = 0;
    }

    private AuroraEvent event(String type, String summary, String importance, boolean urgent) {
        return new AuroraEvent(type, summary, importance, allowReaction(urgent));
    }

    private boolean allowReaction(boolean urgent) {
        if (!BridgeConfig.proactiveComments) return false;
        long now = System.currentTimeMillis();
        long cooldown = BridgeConfig.proactiveCooldownSeconds * 1000L;
        if (!urgent && now - lastReactionAt < cooldown) return false;
        lastReactionAt = now;
        return true;
    }
}
