package com.aurora.gtnh;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.entity.EntityLiving;
import net.minecraft.item.ItemStack;

import com.google.gson.JsonObject;

/** Reads the optional MobsInfo database that powers mob-drop pages in GTNH's NEI. */
final class MobsInfoDropReader {

    private static final int MAX_DROPS = 14;
    private static final int MAX_REVERSE_MOBS = 6;
    private static final Set<String> QUERY_WORDS = new LinkedHashSet<>();

    static {
        Collections.addAll(
            QUERY_WORDS,
            "что",
            "кто",
            "как",
            "где",
            "мне",
            "можно",
            "получить",
            "добыть",
            "выпадает",
            "падает",
            "дроп",
            "лут",
            "моб",
            "убить",
            "drop",
            "loot",
            "from");
    }

    String find(String prompt, JsonObject context) {
        if (!looksLikeDropQuestion(prompt)) return "";
        try {
            Map<?, ?> recipes = recipes();
            if (recipes == null || recipes.isEmpty()) return "";
            List<Candidate> candidates = candidates(recipes, prompt, context);
            if (candidates.isEmpty()) return "";

            Candidate best = candidates.get(0);
            if (best.mobScore >= 12) return describeMob(best);
            return describeReverse(candidates);
        } catch (ClassNotFoundException | LinkageError ignored) {
            return "";
        } catch (ReflectiveOperationException | RuntimeException exception) {
            AuroraBridgeMod.LOG.warn("Could not read MobsInfo drop database", exception);
            return "";
        }
    }

    private static Map<?, ?> recipes() throws ReflectiveOperationException {
        Class<?> recipeClass = Class.forName("com.kuba6000.mobsinfo.api.MobRecipe");
        Object value = recipeClass.getField("MobNameToRecipeMap")
            .get(null);
        return value instanceof Map ? (Map<?, ?>) value : null;
    }

    private static List<Candidate> candidates(Map<?, ?> recipes, String prompt, JsonObject context)
        throws ReflectiveOperationException {
        String query = normalize(prompt);
        String contextId = contextValue(context, "targetEntity", "id");
        String contextName = contextValue(context, "targetEntity", "name");
        boolean referencesTarget = referencesTarget(query);
        List<Candidate> result = new ArrayList<>();
        for (Map.Entry<?, ?> entry : recipes.entrySet()) {
            Object recipe = entry.getValue();
            if (recipe == null) continue;
            String id = String.valueOf(entry.getKey());
            String entityName = stringField(recipe, "entityName");
            Object entityValue = field(recipe, "entity");
            String display = entityValue instanceof EntityLiving ? ((EntityLiving) entityValue).getCommandSenderName()
                : entityName;
            int mobScore = score(query, id, entityName, display);
            boolean isContextTarget = (!contextId.isEmpty() && sameName(contextId, id))
                || (!contextName.isEmpty() && sameName(contextName, display));
            if (referencesTarget && isContextTarget) mobScore += 1000;

            List<Drop> drops = drops(recipe, query);
            int dropScore = 0;
            for (Drop drop : drops) dropScore = Math.max(dropScore, drop.matchScore);
            if (mobScore >= 12 || dropScore >= 12) {
                result.add(new Candidate(display, drops, mobScore, dropScore));
            }
        }
        result.sort(
            Comparator.comparingInt(Candidate::bestScore)
                .reversed());
        return result;
    }

    private static List<Drop> drops(Object recipe, String query) throws ReflectiveOperationException {
        Object raw = field(recipe, "mOutputs");
        if (!(raw instanceof Iterable)) return Collections.emptyList();
        List<Drop> result = new ArrayList<>();
        for (Object value : (Iterable<?>) raw) {
            if (value == null) continue;
            Object stackValue = field(value, "stack");
            if (!(stackValue instanceof ItemStack)) {
                try {
                    value.getClass()
                        .getMethod("reconstructStack")
                        .invoke(value);
                    stackValue = field(value, "stack");
                } catch (ReflectiveOperationException ignored) {
                    // Some optional drops cannot be reconstructed on this client.
                }
            }
            if (!(stackValue instanceof ItemStack)) continue;
            ItemStack stack = (ItemStack) stackValue;
            result.add(new Drop(value, stack, score(query, stack.getDisplayName(), registryName(stack))));
        }
        return result;
    }

    private static String describeMob(Candidate candidate) throws ReflectiveOperationException {
        StringBuilder result = new StringBuilder("Дроп моба «").append(candidate.display)
            .append("» из активной базы MobsInfo:");
        if (candidate.drops.isEmpty()) {
            return result.append(" зарегистрированных дропов нет.")
                .toString();
        }
        int count = 0;
        for (Drop drop : candidate.drops) {
            if (count++ >= MAX_DROPS) break;
            result.append("\n- ")
                .append(describeDrop(drop));
        }
        if (candidate.drops.size() > MAX_DROPS) {
            result.append("\n- Ещё вариантов: ")
                .append(candidate.drops.size() - MAX_DROPS)
                .append('.');
        }
        return result.toString();
    }

    private static String describeReverse(List<Candidate> candidates) throws ReflectiveOperationException {
        StringBuilder result = new StringBuilder("Источники предмета среди дропов мобов активной базы MobsInfo:");
        int mobs = 0;
        for (Candidate candidate : candidates) {
            if (candidate.dropScore < 12 || mobs++ >= MAX_REVERSE_MOBS) continue;
            int written = 0;
            for (Drop drop : candidate.drops) {
                if (drop.matchScore != candidate.dropScore || written++ >= 2) continue;
                result.append("\n- ")
                    .append(candidate.display)
                    .append(": ")
                    .append(describeDrop(drop));
            }
        }
        return mobs == 0 ? "" : result.toString();
    }

    private static String describeDrop(Drop drop) throws ReflectiveOperationException {
        Object data = drop.data;
        int chance = intField(data, "chance");
        boolean variable = booleanField(data, "variableChance");
        StringBuilder result = new StringBuilder(drop.stack.getDisplayName());
        if (drop.stack.stackSize > 1) result.append(" ×")
            .append(drop.stack.stackSize);
        result.append(variable ? ", базовый шанс " : ", шанс ")
            .append(chance(chance));

        List<String> conditions = new ArrayList<>();
        if (booleanField(data, "lootable")) conditions.add("влияет зачарование «Добыча»");
        if (booleanField(data, "playerOnly")) conditions.add("только при убийстве игроком");
        addChanceModifiers(data, conditions);
        addStrings(field(data, "additionalInfo"), conditions);
        if (!conditions.isEmpty()) result.append("; ")
            .append(String.join("; ", conditions));
        return clean(result.toString());
    }

    private static void addChanceModifiers(Object data, List<String> result) throws ReflectiveOperationException {
        Object modifiers = field(data, "chanceModifiers");
        if (!(modifiers instanceof Iterable)) return;
        for (Object modifier : (Iterable<?>) modifiers) {
            if (modifier == null) continue;
            try {
                Method description = modifier.getClass()
                    .getMethod("getDescription");
                Object value = description.invoke(modifier);
                if (value != null && !String.valueOf(value)
                    .trim()
                    .isEmpty()) result.add(String.valueOf(value));
            } catch (ReflectiveOperationException ignored) {
                // Unknown third-party modifiers remain optional.
            }
        }
    }

    private static void addStrings(Object values, List<String> result) {
        if (!(values instanceof Iterable)) return;
        for (Object value : (Iterable<?>) values) {
            if (value != null && !String.valueOf(value)
                .trim()
                .isEmpty()) result.add(String.valueOf(value));
        }
    }

    private static String chance(int value) {
        if (value <= 0) return "<0,01%";
        double percent = value / 100.0D;
        if (percent == Math.rint(percent)) return String.format(Locale.ROOT, "%.0f%%", percent);
        return String.format(Locale.ROOT, "%.2f%%", percent)
            .replaceAll("0+%$", "%")
            .replace(".%", "%")
            .replace('.', ',');
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

    private static boolean sameName(String first, String second) {
        String left = normalize(first);
        String right = normalize(second);
        return left.equals(right) || left.endsWith(":" + right) || right.endsWith(":" + left);
    }

    private static boolean referencesTarget(String query) {
        return query.contains("этот") || query.contains("этого")
            || query.contains("этим")
            || query.contains("него")
            || query.contains("неё")
            || query.contains("нее")
            || query.contains("передо мной")
            || query.contains("под прицелом");
    }

    private static boolean looksLikeDropQuestion(String prompt) {
        String value = normalize(prompt);
        return value.contains("дроп") || value.contains("лут")
            || value.contains("выпад")
            || value.contains("падает")
            || value.contains("получить")
            || value.contains("добыть")
            || value.contains("что дает")
            || value.contains("что даёт")
            || value.contains("drop")
            || value.contains("loot");
    }

    private static String contextValue(JsonObject context, String objectName, String property) {
        if (!context.has(objectName) || !context.get(objectName)
            .isJsonObject()) return "";
        JsonObject object = context.getAsJsonObject(objectName);
        return object.has(property) ? object.get(property)
            .getAsString() : "";
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass()
            .getField(name);
        return field.get(target);
    }

    private static String stringField(Object target, String name) throws ReflectiveOperationException {
        Object value = field(target, name);
        return value == null ? "" : String.valueOf(value);
    }

    private static int intField(Object target, String name) throws ReflectiveOperationException {
        return ((Number) field(target, name)).intValue();
    }

    private static boolean booleanField(Object target, String name) throws ReflectiveOperationException {
        Object value = field(target, name);
        return value instanceof Boolean && (Boolean) value;
    }

    private static String registryName(ItemStack stack) {
        Object name = net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem());
        return name == null ? stack.getUnlocalizedName() : String.valueOf(name);
    }

    private static String clean(String value) {
        return value.replaceAll("§.", "")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim();
    }

    private static String normalize(String value) {
        return value == null ? ""
            : clean(value).toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .trim();
    }

    private static final class Drop {

        private final Object data;
        private final ItemStack stack;
        private final int matchScore;

        private Drop(Object data, ItemStack stack, int matchScore) {
            this.data = data;
            this.stack = stack;
            this.matchScore = matchScore;
        }
    }

    private static final class Candidate {

        private final String display;
        private final List<Drop> drops;
        private final int mobScore;
        private final int dropScore;

        private Candidate(String display, List<Drop> drops, int mobScore, int dropScore) {
            this.display = display;
            this.drops = drops;
            this.mobScore = mobScore;
            this.dropScore = dropScore;
        }

        private int bestScore() {
            return Math.max(mobScore, dropScore);
        }
    }
}
