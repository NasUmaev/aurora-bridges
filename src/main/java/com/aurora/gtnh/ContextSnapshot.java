package com.aurora.gtnh;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

public final class ContextSnapshot {

    private ContextSnapshot() {}

    public static JsonObject capture() {
        Minecraft minecraft = Minecraft.getMinecraft();
        JsonObject result = new JsonObject();
        if (minecraft.thePlayer == null || minecraft.theWorld == null) return result;

        result.addProperty("player", minecraft.thePlayer.getCommandSenderName());
        result.addProperty("x", round(minecraft.thePlayer.posX));
        result.addProperty("y", round(minecraft.thePlayer.posY));
        result.addProperty("z", round(minecraft.thePlayer.posZ));
        result.addProperty("health", minecraft.thePlayer.getHealth());
        result.addProperty("maxHealth", minecraft.thePlayer.getMaxHealth());
        result.addProperty("dimensionId", minecraft.theWorld.provider.dimensionId);
        result.addProperty("dimension", minecraft.theWorld.provider.getDimensionName());

        ItemStack held = minecraft.thePlayer.getHeldItem();
        if (held == null) {
            result.add("heldItem", JsonNull.INSTANCE);
        } else {
            JsonObject item = new JsonObject();
            Item itemType = held.getItem();
            Object registryName = Item.itemRegistry.getNameForObject(itemType);
            item.addProperty("id", registryName == null ? "unknown" : registryName.toString());
            item.addProperty("name", held.getDisplayName());
            item.addProperty("count", held.stackSize);
            item.addProperty("damage", held.getItemDamage());
            result.add("heldItem", item);
        }

        MovingObjectPosition hit = minecraft.objectMouseOver;
        if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            Block block = minecraft.theWorld.getBlock(hit.blockX, hit.blockY, hit.blockZ);
            JsonObject target = new JsonObject();
            Object registryName = Block.blockRegistry.getNameForObject(block);
            target.addProperty("id", registryName == null ? "unknown" : registryName.toString());
            target.addProperty("name", block.getLocalizedName());
            target.addProperty("metadata", minecraft.theWorld.getBlockMetadata(hit.blockX, hit.blockY, hit.blockZ));
            target.addProperty("x", hit.blockX);
            target.addProperty("y", hit.blockY);
            target.addProperty("z", hit.blockZ);
            result.add("targetBlock", target);
        } else {
            result.add("targetBlock", JsonNull.INSTANCE);
        }
        if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
            && hit.entityHit instanceof EntityLivingBase) {
            EntityLivingBase entity = (EntityLivingBase) hit.entityHit;
            JsonObject target = new JsonObject();
            String id = EntityList.getEntityString(entity);
            target.addProperty(
                "id",
                id == null ? entity.getClass()
                    .getName() : id);
            target.addProperty("name", entity.getCommandSenderName());
            target.addProperty("health", entity.getHealth());
            target.addProperty("maxHealth", entity.getMaxHealth());
            result.add("targetEntity", target);
        } else {
            result.add("targetEntity", JsonNull.INSTANCE);
        }
        return result;
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}
