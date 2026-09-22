package com.aurora.gtnh;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.oredict.ShapelessOreRecipe;

import com.google.gson.JsonObject;

/** Reads recipes registered in the running client. NEI support is optional and reflection-only. */
final class LiveRecipeReader {

    private static final int MAX_INDEX_ITEMS = 60000;
    private static final int MAX_TARGETS = 3;
    private static final int MAX_RECIPES = 8;
    private static final Set<String> QUERY_WORDS = new LinkedHashSet<>(
        Arrays.asList(
            "как",
            "мне",
            "можно",
            "надо",
            "нужно",
            "сделать",
            "создать",
            "получить",
            "рецепт",
            "крафт",
            "скрафтить",
            "переплавить",
            "the",
            "how",
            "make",
            "craft",
            "recipe"));

    private volatile List<IndexedStack> index;
    private volatile boolean indexedFromNei;

    String find(String prompt, JsonObject context) {
        if (!looksLikeRecipeQuestion(prompt)) return "";
        try {
            List<ItemStack> targets = findTargets(prompt, context);
            if (targets.isEmpty()) return "";
            LinkedHashSet<String> recipes = new LinkedHashSet<>();
            for (ItemStack target : targets) {
                addNeiRecipes(target, recipes);
                addForgeRecipes(target, recipes);
                addFurnaceRecipes(target, recipes);
                if (recipes.size() >= MAX_RECIPES) break;
            }
            if (recipes.isEmpty()) return "";
            StringBuilder result = new StringBuilder("Рецепты из активной запущенной игры:");
            int count = 0;
            for (String recipe : recipes) {
                if (count++ >= MAX_RECIPES) break;
                result.append("\n- ")
                    .append(recipe);
            }
            return result.toString();
        } catch (RuntimeException exception) {
            AuroraBridgeMod.LOG.warn("Could not read active recipes", exception);
            return "";
        }
    }

    private List<ItemStack> findTargets(String prompt, JsonObject context) {
        String normalizedPrompt = normalize(prompt);
        List<ScoredStack> matches = new ArrayList<>();
        for (IndexedStack candidate : itemIndex()) {
            int score = score(candidate, normalizedPrompt);
            if (score >= 12) matches.add(new ScoredStack(candidate.stack, score));
        }
        ItemStack referenced = referencedStack(normalizedPrompt, context);
        if (referenced != null) matches.add(new ScoredStack(referenced, 1000));
        matches.sort(
            Comparator.comparingInt(ScoredStack::getScore)
                .reversed());

        List<ItemStack> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (matches.isEmpty()) return result;
        int best = matches.get(0).score;
        for (ScoredStack match : matches) {
            if (result.size() >= MAX_TARGETS || match.score < best - 8) break;
            String key = stackKey(match.stack);
            if (seen.add(key)) result.add(match.stack.copy());
        }
        return result;
    }

    private List<IndexedStack> itemIndex() {
        List<IndexedStack> current = index;
        List<ItemStack> stacks = neiItemList();
        if (current != null && (indexedFromNei || stacks.isEmpty())) return current;
        boolean fromNei = !stacks.isEmpty();
        if (!fromNei) stacks = forgeItemList();
        LinkedHashMap<String, IndexedStack> unique = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.getItem() == null || unique.size() >= MAX_INDEX_ITEMS) continue;
            try {
                String display = normalize(stack.getDisplayName());
                String registry = normalize(registryName(stack));
                String unlocalized = normalize(stack.getUnlocalizedName());
                if (display.isEmpty()) continue;
                unique.putIfAbsent(stackKey(stack), new IndexedStack(stack.copy(), display, registry, unlocalized));
            } catch (RuntimeException ignored) {
                // A broken third-party item must not disable Aurora's whole recipe reader.
            }
        }
        current = new ArrayList<>(unique.values());
        index = current;
        indexedFromNei = fromNei;
        AuroraBridgeMod.LOG
            .info("Aurora indexed {} active item variants for recipe lookup (NEI: {})", current.size(), fromNei);
        return current;
    }

    private static List<ItemStack> neiItemList() {
        try {
            Class<?> itemList = Class.forName("codechicken.nei.ItemList");
            Field items = itemList.getField("items");
            Object value = items.get(null);
            if (!(value instanceof List)) return new ArrayList<>();
            List<ItemStack> result = new ArrayList<>();
            for (Object entry : new ArrayList<>((List<?>) value)) {
                if (entry instanceof ItemStack) result.add((ItemStack) entry);
            }
            return result;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            return new ArrayList<>();
        }
    }

    private static List<ItemStack> forgeItemList() {
        List<ItemStack> result = new ArrayList<>();
        for (Object value : Item.itemRegistry) {
            if (!(value instanceof Item)) continue;
            Item item = (Item) value;
            result.add(new ItemStack(item));
            CreativeTabs tab = item.getCreativeTab();
            if (tab == null) continue;
            try {
                List<ItemStack> variants = new ArrayList<>();
                item.getSubItems(item, tab, variants);
                result.addAll(variants);
            } catch (RuntimeException ignored) {
                // Some legacy mod items assume a fully initialized creative screen.
            }
        }
        return result;
    }

    private static int score(IndexedStack candidate, String prompt) {
        int score = phraseScore(candidate.display, prompt, 70);
        score = Math.max(score, phraseScore(candidate.registry, prompt, 55));
        score = Math.max(score, phraseScore(candidate.unlocalized, prompt, 35));
        int tokenScore = 0;
        Set<String> promptTokens = tokens(prompt);
        for (String token : tokens(candidate.display)) {
            if (!QUERY_WORDS.contains(token) && promptTokens.contains(token))
                tokenScore += token.length() >= 5 ? 12 : 7;
        }
        return Math.max(score, tokenScore);
    }

    private static int phraseScore(String name, String prompt, int base) {
        return name.length() >= 3 && prompt.contains(name) ? base + Math.min(name.length(), 25) : 0;
    }

    private static ItemStack referencedStack(String prompt, JsonObject context) {
        if (!(prompt.contains("это") || prompt.contains("этот") || prompt.contains("рук") || prompt.contains("держу")))
            return null;
        if (!context.has("heldItem") || !context.get("heldItem")
            .isJsonObject()) return null;
        JsonObject held = context.getAsJsonObject("heldItem");
        if (!held.has("id")) return null;
        Object item = Item.itemRegistry.getObject(
            held.get("id")
                .getAsString());
        if (!(item instanceof Item)) return null;
        int damage = held.has("damage") ? held.get("damage")
            .getAsInt() : 0;
        return new ItemStack((Item) item, 1, damage);
    }

    private static void addForgeRecipes(ItemStack target, Collection<String> result) {
        for (Object value : CraftingManager.getInstance()
            .getRecipeList()) {
            if (!(value instanceof IRecipe)) continue;
            IRecipe recipe = (IRecipe) value;
            if (!sameItem(target, recipe.getRecipeOutput())) continue;
            String rendered = renderForgeRecipe(recipe);
            if (!rendered.isEmpty()) result.add(rendered);
            if (result.size() >= MAX_RECIPES) return;
        }
    }

    private static void addFurnaceRecipes(ItemStack target, Collection<String> result) {
        for (Map.Entry<ItemStack, ItemStack> entry : FurnaceRecipes.smelting()
            .getSmeltingList()
            .entrySet()) {
            if (!sameItem(target, entry.getValue())) continue;
            result.add("Печь: " + stackName(entry.getKey()) + " → " + stackName(entry.getValue()) + ".");
            if (result.size() >= MAX_RECIPES) return;
        }
    }

    private static String renderForgeRecipe(IRecipe recipe) {
        if (recipe instanceof ShapedRecipes) {
            ShapedRecipes shaped = (ShapedRecipes) recipe;
            return shapedRecipe(
                "Верстак",
                shaped.recipeWidth,
                shaped.recipeHeight,
                shaped.recipeItems,
                shaped.getRecipeOutput());
        }
        if (recipe instanceof ShapelessRecipes) {
            return shapelessRecipe("Верстак", ((ShapelessRecipes) recipe).recipeItems, recipe.getRecipeOutput());
        }
        if (recipe instanceof ShapedOreRecipe) {
            ShapedOreRecipe shaped = (ShapedOreRecipe) recipe;
            return shapedRecipe(
                "Верстак",
                integerField(shaped, "width"),
                integerField(shaped, "height"),
                shaped.getInput(),
                shaped.getRecipeOutput());
        }
        if (recipe instanceof ShapelessOreRecipe) {
            return shapelessRecipe(
                "Верстак",
                Arrays.asList(((ShapelessOreRecipe) recipe).getInput()),
                recipe.getRecipeOutput());
        }
        return "Крафт: результат " + stackName(recipe.getRecipeOutput()) + ".";
    }

    private static String shapedRecipe(String type, int width, int height, Object[] ingredients, ItemStack output) {
        StringBuilder result = new StringBuilder(type).append(' ')
            .append(width)
            .append('×')
            .append(height)
            .append(": ");
        for (int row = 0; row < height; row++) {
            if (row > 0) result.append(" / ");
            result.append('[');
            for (int column = 0; column < width; column++) {
                if (column > 0) result.append(" | ");
                result.append(ingredientName(ingredients[row * width + column]));
            }
            result.append(']');
        }
        return result.append(" → ")
            .append(stackName(output))
            .append('.')
            .toString();
    }

    private static String shapelessRecipe(String type, Iterable<?> ingredients, ItemStack output) {
        List<String> names = new ArrayList<>();
        for (Object ingredient : ingredients) names.add(ingredientName(ingredient));
        return type + ", бесформенный: " + String.join(", ", names) + " → " + stackName(output) + ".";
    }

    private static String ingredientName(Object ingredient) {
        if (ingredient == null) return "пусто";
        if (ingredient instanceof ItemStack) return stackAlternatives((ItemStack) ingredient);
        if (ingredient instanceof List) {
            LinkedHashSet<String> alternatives = new LinkedHashSet<>();
            for (Object value : (List<?>) ingredient) {
                if (value instanceof ItemStack) alternatives.add(stackName((ItemStack) value));
                if (alternatives.size() >= 6) break;
            }
            if (alternatives.isEmpty()) return "неизвестный ингредиент";
            if (alternatives.size() == 1) return alternatives.iterator()
                .next();
            return "любой из: " + String.join(" / ", alternatives);
        }
        return "неизвестный ингредиент";
    }

    private static String stackAlternatives(ItemStack stack) {
        if (stack.getItemDamage() != OreDictionary.WILDCARD_VALUE) return stackName(stack);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (int metadata = 0; metadata < 16 && names.size() < 6; metadata++) {
            names.add(stackName(new ItemStack(stack.getItem(), stack.stackSize, metadata)));
        }
        return names.size() == 1 ? names.iterator()
            .next() : "любой из: " + String.join(" / ", names);
    }

    private static void addNeiRecipes(ItemStack target, Collection<String> result) {
        try {
            Class<?> gui = Class.forName("codechicken.nei.recipe.GuiCraftingRecipe");
            Method lookup = gui.getMethod("getCraftingHandlers", String.class, Object[].class);
            Object found = lookup.invoke(null, "item", new Object[] { target.copy() });
            if (!(found instanceof Iterable)) return;
            for (Object handler : (Iterable<?>) found) {
                addNeiHandler(handler, target, result);
                if (result.size() >= MAX_RECIPES) return;
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            AuroraBridgeMod.LOG.debug("NEI recipe integration is unavailable", exception);
        }
    }

    private static void addNeiHandler(Object handler, ItemStack target, Collection<String> result)
        throws ReflectiveOperationException {
        Class<?> recipeHandler = Class.forName("codechicken.nei.recipe.IRecipeHandler");
        Method nameMethod = recipeHandler.getMethod("getRecipeName");
        Method countMethod = recipeHandler.getMethod("numRecipes");
        Method ingredientsMethod = recipeHandler.getMethod("getIngredientStacks", int.class);
        Method resultMethod = recipeHandler.getMethod("getResultStack", int.class);
        String handlerName = String.valueOf(nameMethod.invoke(handler));
        int count = Math.min(((Number) countMethod.invoke(handler)).intValue(), MAX_RECIPES);
        for (int recipe = 0; recipe < count; recipe++) {
            ItemStack output = firstStack(resultMethod.invoke(handler, recipe));
            if (output != null && !sameItem(target, output)) continue;
            Object ingredients = ingredientsMethod.invoke(handler, recipe);
            List<String> names = new ArrayList<>();
            if (ingredients instanceof Iterable) {
                for (Object positioned : (Iterable<?>) ingredients) {
                    String value = positionedStackName(positioned);
                    if (!value.isEmpty()) names.add(value);
                }
            }
            if (names.isEmpty()) continue;
            result.add(
                handlerName + ": "
                    + String.join(", ", names)
                    + " → "
                    + stackName(output == null ? target : output)
                    + ".");
            if (result.size() >= MAX_RECIPES) return;
        }
    }

    private static String positionedStackName(Object positioned) throws ReflectiveOperationException {
        if (positioned == null) return "";
        Field items = positioned.getClass()
            .getField("items");
        Object value = items.get(positioned);
        if (!(value instanceof ItemStack[])) return "";
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (ItemStack stack : (ItemStack[]) value) {
            if (stack != null) names.add(stackName(stack));
            if (names.size() >= 6) break;
        }
        if (names.isEmpty()) return "";
        if (names.size() == 1) return names.iterator()
            .next();
        return "любой из: " + String.join(" / ", names);
    }

    private static ItemStack firstStack(Object positioned) throws ReflectiveOperationException {
        if (positioned == null) return null;
        Field item = positioned.getClass()
            .getField("item");
        Object value = item.get(positioned);
        return value instanceof ItemStack ? (ItemStack) value : null;
    }

    private static int integerField(Object target, String name) {
        try {
            Field field = target.getClass()
                .getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not read recipe dimensions", exception);
        }
    }

    private static boolean sameItem(ItemStack first, ItemStack second) {
        if (first == null || second == null || first.getItem() != second.getItem()) return false;
        return first.getItemDamage() == second.getItemDamage() || first.getItemDamage() == OreDictionary.WILDCARD_VALUE
            || second.getItemDamage() == OreDictionary.WILDCARD_VALUE;
    }

    private static String stackName(ItemStack stack) {
        if (stack == null) return "неизвестный результат";
        String name;
        try {
            name = stack.getDisplayName();
        } catch (RuntimeException exception) {
            name = registryName(stack);
        }
        return name + (stack.stackSize > 1 ? " ×" + stack.stackSize : "");
    }

    private static String registryName(ItemStack stack) {
        Object name = Item.itemRegistry.getNameForObject(stack.getItem());
        return name == null ? stack.getUnlocalizedName() : name.toString();
    }

    private static String stackKey(ItemStack stack) {
        return registryName(stack) + ':' + stack.getItemDamage() + ':' + String.valueOf(stack.getTagCompound());
    }

    private static boolean looksLikeRecipeQuestion(String prompt) {
        String value = normalize(prompt);
        return value.contains("рецепт") || value.contains("крафт")
            || value.contains("скрафт")
            || value.contains("как сделать")
            || value.contains("как создать")
            || value.contains("как получить")
            || value.contains("переплав")
            || value.contains("recipe")
            || value.contains("how to craft")
            || value.contains("how to make");
    }

    private static Set<String> tokens(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : normalize(value).split("[^\\p{L}\\p{N}_]+")) {
            if (token.length() >= 2) result.add(stem(token));
        }
        return result;
    }

    private static String stem(String token) {
        if (!token.matches("[а-яё]+") || token.length() <= 4) return token;
        String[] endings = { "иями", "ями", "ами", "ого", "ему", "ому", "ыми", "ими", "ую", "юю", "ая", "яя", "ое",
            "ее", "ий", "ый", "ой", "ам", "ям", "ах", "ях", "ов", "ев", "а", "я", "у", "ю", "ы", "и", "е", "о" };
        for (String ending : endings) {
            if (token.endsWith(ending) && token.length() - ending.length() >= 3) {
                return token.substring(0, token.length() - ending.length());
            }
        }
        return token;
    }

    private static String normalize(String value) {
        return value == null ? ""
            : value.replaceAll("§.", "")
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .trim();
    }

    private static final class IndexedStack {

        private final ItemStack stack;
        private final String display;
        private final String registry;
        private final String unlocalized;

        private IndexedStack(ItemStack stack, String display, String registry, String unlocalized) {
            this.stack = stack;
            this.display = display;
            this.registry = registry;
            this.unlocalized = unlocalized;
        }
    }

    private static final class ScoredStack {

        private final ItemStack stack;
        private final int score;

        private ScoredStack(ItemStack stack, int score) {
            this.stack = stack;
            this.score = score;
        }

        private int getScore() {
            return score;
        }
    }
}
