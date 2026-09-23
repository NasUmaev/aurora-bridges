package com.aurora.gtnh;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeHooks;

import com.google.gson.JsonObject;

/** Reads harvest requirements and block drops from the active Forge registries. */
final class ForgeBlockDropReader {

    private static final int MAX_INDEX_BLOCKS = 60000;
    private static final int MAX_TARGETS = 2;
    private static final Set<String> QUERY_WORDS = new LinkedHashSet<>();

    static {
        Collections.addAll(
            QUERY_WORDS,
            "что",
            "как",
            "где",
            "мне",
            "можно",
            "нужно",
            "получить",
            "добыть",
            "добывать",
            "сломать",
            "ломать",
            "блок",
            "дроп",
            "выпадает",
            "падает",
            "инструмент",
            "кирка",
            "the",
            "how",
            "block",
            "drop",
            "harvest");
    }

    private volatile List<IndexedBlock> index;

    String find(String prompt, JsonObject context) {
        if (!looksLikeBlockQuestion(prompt)) return "";
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.theWorld == null || minecraft.thePlayer == null) return "";
        try {
            List<Target> targets = targets(prompt, context, minecraft);
            if (targets.isEmpty()) return "";
            StringBuilder result = new StringBuilder("Данные блоков непосредственно из активной игры:");
            for (Target target : targets) describe(target, minecraft.theWorld, minecraft.thePlayer, result);
            return result.toString();
        } catch (RuntimeException exception) {
            AuroraBridgeMod.LOG.warn("Could not read active block drops", exception);
            return "";
        }
    }

    private List<Target> targets(String prompt, JsonObject context, Minecraft minecraft) {
        String query = normalize(prompt);
        List<ScoredTarget> matches = new ArrayList<>();
        Target lookedAt = lookedAt(context);
        if (lookedAt != null) {
            int score = score(query, lookedAt.display, lookedAt.registry);
            if (referencesTarget(query)) score += 1000;
            if (score >= 12) matches.add(new ScoredTarget(lookedAt, score));
        }
        for (IndexedBlock block : blockIndex()) {
            int score = score(query, block.display, block.registry, block.unlocalized);
            if (score < 12) continue;
            int x = (int) Math.floor(minecraft.thePlayer.posX);
            int y = (int) Math.floor(minecraft.thePlayer.posY);
            int z = (int) Math.floor(minecraft.thePlayer.posZ);
            matches.add(
                new ScoredTarget(
                    new Target(block.block, block.metadata, block.display, block.registry, x, y, z, false),
                    score));
        }
        matches.sort(
            Comparator.comparingInt(ScoredTarget::getScore)
                .reversed());

        List<Target> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (matches.isEmpty()) return result;
        int best = matches.get(0).score;
        for (ScoredTarget match : matches) {
            if (result.size() >= MAX_TARGETS || match.score < best - 8) break;
            String key = match.target.registry + ':' + match.target.metadata;
            if (seen.add(key)) result.add(match.target);
        }
        return result;
    }

    private static void describe(Target target, World world, EntityPlayer player, StringBuilder result) {
        Block block = target.block;
        int metadata = target.metadata;
        String tool = block.getHarvestTool(metadata);
        int level = block.getHarvestLevel(metadata);
        boolean currentToolWorks = ForgeHooks.canHarvestBlock(block, player, metadata);
        result.append("\n- Блок «")
            .append(target.display)
            .append("» (вариант ")
            .append(metadata)
            .append("): ");
        if (tool == null || level < 0) {
            result.append("специальный инструмент Forge не указан");
        } else {
            result.append("нужен инструмент класса ")
                .append(tool)
                .append(" уровня ")
                .append(level)
                .append(" (")
                .append(levelName(level))
                .append(" или лучше)");
        }
        ItemStack held = player.getHeldItem();
        result.append("; текущий предмет ")
            .append(held == null ? "пустая рука" : held.getDisplayName())
            .append(currentToolWorks ? " может добыть блок" : " не может корректно добыть блок");

        if (currentToolWorks) {
            appendDrops(result, "обычный расчёт дропа", safeDrops(block, world, target, 0));
            appendDrops(result, "расчёт с «Удачей III»", safeDrops(block, world, target, 3));
        } else {
            result.append("; полезный дроп текущим предметом не гарантирован");
        }
        try {
            result.append("; «Шёлковое касание» ")
                .append(
                    block.canSilkHarvest(world, player, target.x, target.y, target.z, metadata) ? "поддерживается"
                        : "не поддерживается");
        } catch (RuntimeException exception) {
            result.append("; поддержку «Шёлкового касания» мод определить не дал");
        }
        if (!target.inWorld) {
            result.append(". Блок найден по реестру, поэтому зависящие от координат условия могут отличаться");
        }
        result.append('.');
    }

    private static List<ItemStack> safeDrops(Block block, World world, Target target, int fortune) {
        try {
            return block.getDrops(world, target.x, target.y, target.z, target.metadata, fortune);
        } catch (RuntimeException exception) {
            AuroraBridgeMod.LOG
                .debug("Block {} needs world-specific state for drop calculation", target.registry, exception);
            return Collections.emptyList();
        }
    }

    private static void appendDrops(StringBuilder result, String label, Collection<ItemStack> drops) {
        result.append("; ")
            .append(label)
            .append(": ");
        if (drops == null || drops.isEmpty()) {
            result.append("ничего");
            return;
        }
        List<String> names = new ArrayList<>();
        for (ItemStack stack : drops) {
            if (stack == null) continue;
            String name = stack.getDisplayName();
            if (stack.stackSize > 1) name += " ×" + stack.stackSize;
            names.add(name);
            if (names.size() >= 12) break;
        }
        result.append(names.isEmpty() ? "ничего" : String.join(", ", names));
    }

    private List<IndexedBlock> blockIndex() {
        List<IndexedBlock> current = index;
        if (current != null) return current;
        Map<String, IndexedBlock> unique = new LinkedHashMap<>();
        for (ItemStack stack : neiItems()) {
            if (stack == null || stack.getItem() == null || unique.size() >= MAX_INDEX_BLOCKS) continue;
            Block block = Block.getBlockFromItem(stack.getItem());
            if (block == null || block == Blocks.air) continue;
            addIndexed(unique, block, stack.getItemDamage(), stack.getDisplayName());
        }
        if (unique.isEmpty()) {
            for (Object value : Block.blockRegistry) {
                if (!(value instanceof Block) || unique.size() >= MAX_INDEX_BLOCKS) continue;
                Block block = (Block) value;
                addIndexed(unique, block, 0, block.getLocalizedName());
            }
        }
        current = new ArrayList<>(unique.values());
        index = current;
        AuroraBridgeMod.LOG.info("Aurora indexed {} active block variants for drop lookup", current.size());
        return current;
    }

    private static void addIndexed(Map<String, IndexedBlock> result, Block block, int metadata, String display) {
        Object registryValue = Block.blockRegistry.getNameForObject(block);
        String registry = registryValue == null ? block.getUnlocalizedName() : String.valueOf(registryValue);
        String key = registry + ':' + metadata;
        result.putIfAbsent(key, new IndexedBlock(block, metadata, display, registry, block.getUnlocalizedName()));
    }

    private static List<ItemStack> neiItems() {
        try {
            Class<?> itemList = Class.forName("codechicken.nei.ItemList");
            Field items = itemList.getField("items");
            Object value = items.get(null);
            if (!(value instanceof List)) return Collections.emptyList();
            List<ItemStack> result = new ArrayList<>();
            for (Object entry : (List<?>) value) {
                if (entry instanceof ItemStack) result.add((ItemStack) entry);
            }
            return result;
        } catch (ReflectiveOperationException | LinkageError exception) {
            return Collections.emptyList();
        }
    }

    private static Target lookedAt(JsonObject context) {
        if (!context.has("targetBlock") || !context.get("targetBlock")
            .isJsonObject()) return null;
        JsonObject value = context.getAsJsonObject("targetBlock");
        if (!value.has("id") || !value.has("metadata")) return null;
        Object blockValue = Block.blockRegistry.getObject(
            value.get("id")
                .getAsString());
        if (!(blockValue instanceof Block)) return null;
        return new Target(
            (Block) blockValue,
            value.get("metadata")
                .getAsInt(),
            value.has("name") ? value.get("name")
                .getAsString() : ((Block) blockValue).getLocalizedName(),
            value.get("id")
                .getAsString(),
            value.get("x")
                .getAsInt(),
            value.get("y")
                .getAsInt(),
            value.get("z")
                .getAsInt(),
            true);
    }

    private static int score(String query, String... names) {
        Set<String> queryTokens = tokens(query);
        int result = 0;
        for (String name : names) {
            String normalized = normalize(name);
            if (normalized.isEmpty()) continue;
            if (query.contains(normalized)) result = Math.max(result, 30 + Math.min(normalized.length(), 20));
            for (String token : tokens(normalized)) {
                for (String queryToken : queryTokens) {
                    if (matches(token, queryToken)) result += 8;
                }
            }
        }
        return result;
    }

    private static Set<String> tokens(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (String token : normalize(value).split("[^\\p{L}\\p{N}_:.-]+")) {
            if (token.length() >= 2 && !QUERY_WORDS.contains(token)) result.add(token);
        }
        return result;
    }

    private static boolean matches(String first, String second) {
        if (first.equals(second)) return true;
        int shared = Math.min(Math.min(first.length(), second.length()), 6);
        return shared >= 4 && first.regionMatches(0, second, 0, shared);
    }

    private static boolean referencesTarget(String value) {
        return value.contains("этот") || value.contains("этого")
            || value.contains("этим")
            || value.contains("передо мной")
            || value.contains("под прицелом")
            || value.contains("смотрю");
    }

    private static boolean looksLikeBlockQuestion(String prompt) {
        String value = normalize(prompt);
        return value.contains("дроп") || value.contains("выпад")
            || value.contains("падает")
            || value.contains("добыть")
            || value.contains("добывать")
            || value.contains("сломать")
            || value.contains("ломать")
            || value.contains("инструмент")
            || value.contains("кирк")
            || value.contains("шелков")
            || value.contains("удач")
            || value.contains("block drop")
            || value.contains("harvest");
    }

    private static String levelName(int level) {
        switch (level) {
            case 0:
                return "дерево/золото";
            case 1:
                return "камень";
            case 2:
                return "железо";
            case 3:
                return "алмаз";
            default:
                return "модовый уровень " + level;
        }
    }

    private static String normalize(String value) {
        return value == null ? ""
            : value.replaceAll("§.", "")
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .trim();
    }

    private static final class IndexedBlock {

        private final Block block;
        private final int metadata;
        private final String display;
        private final String registry;
        private final String unlocalized;

        private IndexedBlock(Block block, int metadata, String display, String registry, String unlocalized) {
            this.block = block;
            this.metadata = metadata;
            this.display = display;
            this.registry = registry;
            this.unlocalized = unlocalized;
        }
    }

    private static final class Target {

        private final Block block;
        private final int metadata;
        private final String display;
        private final String registry;
        private final int x;
        private final int y;
        private final int z;
        private final boolean inWorld;

        private Target(Block block, int metadata, String display, String registry, int x, int y, int z,
            boolean inWorld) {
            this.block = block;
            this.metadata = metadata;
            this.display = display;
            this.registry = registry;
            this.x = x;
            this.y = y;
            this.z = z;
            this.inWorld = inWorld;
        }
    }

    private static final class ScoredTarget {

        private final Target target;
        private final int score;

        private ScoredTarget(Target target, int score) {
            this.target = target;
            this.score = score;
        }

        private int getScore() {
            return score;
        }
    }
}
