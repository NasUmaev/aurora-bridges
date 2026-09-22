package com.aurora.gtnh.tools;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.minecraft.init.Bootstrap;
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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** Development-only exporter compiled in the test source set, never in the distributable mod JAR. */
public final class VanillaKnowledgeGenerator {

    private static final Gson JSON = new GsonBuilder().setPrettyPrinting()
        .disableHtmlEscaping()
        .create();
    private static final Properties ENGLISH = loadTranslations("en_US");
    private static final Properties RUSSIAN = loadRussianTranslations();

    private VanillaKnowledgeGenerator() {}

    public static void main(String[] args) throws Exception {
        String output = System.getProperty("aurora.knowledge.output", "");
        if (output.isEmpty()) throw new IllegalArgumentException("Missing aurora.knowledge.output");
        File knowledgeRoot = new File(output).getCanonicalFile();
        File crafting = generatedDirectory(knowledgeRoot, "crafting");
        File smelting = generatedDirectory(knowledgeRoot, "smelting");
        recreateDirectory(knowledgeRoot, crafting);
        recreateDirectory(knowledgeRoot, smelting);

        Bootstrap.func_151354_b();
        int craftingCount = exportCrafting(crafting);
        int smeltingCount = exportSmelting(smelting);
        System.out.println("Generated " + craftingCount + " crafting and " + smeltingCount + " smelting articles");
    }

    private static int exportCrafting(File directory) throws Exception {
        Map<String, Article> articles = new LinkedHashMap<>();
        List<IRecipe> recipes = new ArrayList<>();
        for (Object value : CraftingManager.getInstance()
            .getRecipeList()) {
            if (value instanceof IRecipe) recipes.add((IRecipe) value);
        }
        recipes.sort(Comparator.comparing(recipe -> itemKey(recipe.getRecipeOutput())));
        for (IRecipe recipe : recipes) {
            ItemStack output = recipe.getRecipeOutput();
            if (output == null || (!(recipe instanceof ShapedRecipes) && !(recipe instanceof ShapelessRecipes)
                && !(recipe instanceof ShapedOreRecipe)
                && !(recipe instanceof ShapelessOreRecipe))) continue;
            String key = itemKey(output);
            Article article = articles.computeIfAbsent(key, ignored -> articleFor("crafting", output));
            String description;
            if (recipe instanceof ShapedRecipes) {
                description = shaped((ShapedRecipes) recipe);
            } else if (recipe instanceof ShapelessRecipes) {
                description = shapeless((ShapelessRecipes) recipe);
            } else if (recipe instanceof ShapedOreRecipe) {
                description = shapedOre((ShapedOreRecipe) recipe);
            } else {
                description = shapelessOre((ShapelessOreRecipe) recipe);
            }
            article.facts.add(description);
            addIngredientsAsAliases(article, recipe);
        }
        return writeArticles(directory, articles);
    }

    private static int exportSmelting(File directory) throws Exception {
        Map<String, Article> articles = new LinkedHashMap<>();
        List<Map.Entry<ItemStack, ItemStack>> recipes = new ArrayList<>(
            FurnaceRecipes.smelting()
                .getSmeltingList()
                .entrySet());
        recipes.sort(Comparator.comparing(entry -> itemKey(entry.getValue()) + itemKey(entry.getKey())));
        for (Map.Entry<ItemStack, ItemStack> recipe : recipes) {
            ItemStack input = recipe.getKey();
            ItemStack output = recipe.getValue();
            if (input == null || output == null) continue;
            String key = itemKey(output);
            Article article = articles.computeIfAbsent(key, ignored -> articleFor("smelting", output));
            article.aliases.add(russianName(input));
            article.aliases.add(englishName(input));
            article.aliases.add(registryName(input));
            article.facts.add(
                "Помести в верхнюю ячейку печи: " + stackName(input)
                    + ". Результат: "
                    + stackName(output)
                    + ". Опыт: "
                    + formatExperience(
                        FurnaceRecipes.smelting()
                            .func_151398_b(output))
                    + ".");
        }
        return writeArticles(directory, articles);
    }

    private static Article articleFor(String kind, ItemStack output) {
        Article article = new Article();
        article.id = kind + "-" + safeId(registryName(output)) + "-" + normalizedDamage(output);
        article.title = ("crafting".equals(kind) ? "Крафт: " : "Переплавка: ") + russianName(output);
        article.aliases.add(russianName(output));
        article.aliases.add(englishName(output));
        article.aliases.add(registryName(output));
        article.aliases.add("как сделать " + russianName(output));
        article.aliases.add("рецепт " + russianName(output));
        article.tags.add("crafting".equals(kind) ? "крафт" : "переплавка");
        article.tags.add("Minecraft 1.7.10");
        return article;
    }

    private static String shaped(ShapedRecipes recipe) {
        StringBuilder text = new StringBuilder();
        text.append("Форменный рецепт ")
            .append(recipe.recipeWidth)
            .append('×')
            .append(recipe.recipeHeight)
            .append(". Результат: ")
            .append(stackName(recipe.getRecipeOutput()))
            .append(". Сетка сверху вниз: ");
        for (int row = 0; row < recipe.recipeHeight; row++) {
            if (row > 0) text.append(" / ");
            text.append('[');
            for (int column = 0; column < recipe.recipeWidth; column++) {
                if (column > 0) text.append(" | ");
                ItemStack ingredient = recipe.recipeItems[row * recipe.recipeWidth + column];
                text.append(ingredient == null ? "пусто" : stackName(ingredient));
            }
            text.append(']');
        }
        return text.append('.')
            .toString();
    }

    private static String shapeless(ShapelessRecipes recipe) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack ingredient : recipe.recipeItems) {
            String name = stackName(ingredient);
            counts.put(name, counts.getOrDefault(name, 0) + 1);
        }
        List<String> ingredients = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            ingredients.add(entry.getKey() + (entry.getValue() > 1 ? " ×" + entry.getValue() : ""));
        }
        return "Бесформенный рецепт. Соедини: " + String.join(", ", ingredients)
            + ". Результат: "
            + stackName(recipe.getRecipeOutput())
            + ".";
    }

    private static String shapedOre(ShapedOreRecipe recipe) {
        int width = integerField(recipe, "width");
        int height = integerField(recipe, "height");
        Object[] ingredients = recipe.getInput();
        StringBuilder text = new StringBuilder();
        text.append("Форменный рецепт ")
            .append(width)
            .append('×')
            .append(height)
            .append(". Результат: ")
            .append(stackName(recipe.getRecipeOutput()))
            .append(". Сетка сверху вниз: ");
        for (int row = 0; row < height; row++) {
            if (row > 0) text.append(" / ");
            text.append('[');
            for (int column = 0; column < width; column++) {
                if (column > 0) text.append(" | ");
                text.append(ingredientName(ingredients[row * width + column]));
            }
            text.append(']');
        }
        return text.append('.')
            .toString();
    }

    private static String shapelessOre(ShapelessOreRecipe recipe) {
        List<String> ingredients = new ArrayList<>();
        for (Object ingredient : recipe.getInput()) ingredients.add(ingredientName(ingredient));
        return "Бесформенный рецепт. Соедини: " + String.join(", ", ingredients)
            + ". Результат: "
            + stackName(recipe.getRecipeOutput())
            + ".";
    }

    private static String ingredientName(Object ingredient) {
        if (ingredient == null) return "пусто";
        if (ingredient instanceof ItemStack) return formatAlternatives(ingredientAlternatives((ItemStack) ingredient));
        if (ingredient instanceof List) {
            LinkedHashSet<String> alternatives = new LinkedHashSet<>();
            for (Object value : (List<?>) ingredient) {
                if (value instanceof ItemStack) alternatives.addAll(ingredientAlternatives((ItemStack) value));
                if (alternatives.size() >= 8) break;
            }
            return alternatives.isEmpty() ? "неизвестная группа" : formatAlternatives(alternatives);
        }
        return "неизвестный ингредиент";
    }

    private static Set<String> ingredientAlternatives(ItemStack stack) {
        LinkedHashSet<String> alternatives = new LinkedHashSet<>();
        if (stack.getItemDamage() != OreDictionary.WILDCARD_VALUE) {
            alternatives.add(stackName(stack));
            return alternatives;
        }
        for (int metadata = 0; metadata < 16 && alternatives.size() < 8; metadata++) {
            ItemStack variant = new ItemStack(stack.getItem(), stack.stackSize, metadata);
            alternatives.add(stackName(variant));
        }
        return alternatives;
    }

    private static String formatAlternatives(Set<String> alternatives) {
        if (alternatives.size() == 1) return alternatives.iterator()
            .next();
        return "один из вариантов: " + String.join(" или ", alternatives);
    }

    private static int integerField(Object target, String name) {
        try {
            Field field = target.getClass()
                .getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not read recipe " + name, exception);
        }
    }

    private static void addIngredientsAsAliases(Article article, IRecipe recipe) {
        Iterable<ItemStack> ingredients;
        if (recipe instanceof ShapedRecipes) {
            ingredients = Arrays.asList(((ShapedRecipes) recipe).recipeItems);
        } else if (recipe instanceof ShapelessRecipes) {
            ingredients = ((ShapelessRecipes) recipe).recipeItems;
        } else if (recipe instanceof ShapedOreRecipe) {
            addOreIngredientsAsTags(article, Arrays.asList(((ShapedOreRecipe) recipe).getInput()));
            return;
        } else {
            addOreIngredientsAsTags(article, ((ShapelessOreRecipe) recipe).getInput());
            return;
        }
        for (ItemStack ingredient : ingredients) {
            if (ingredient != null) article.tags.add(russianName(ingredient));
        }
    }

    private static void addOreIngredientsAsTags(Article article, Iterable<?> ingredients) {
        for (Object ingredient : ingredients) {
            if (ingredient instanceof ItemStack) {
                article.tags.addAll(ingredientAlternatives((ItemStack) ingredient));
            } else if (ingredient instanceof List) {
                for (Object alternative : (List<?>) ingredient) {
                    if (alternative instanceof ItemStack)
                        article.tags.addAll(ingredientAlternatives((ItemStack) alternative));
                }
            }
        }
    }

    private static int writeArticles(File directory, Map<String, Article> articles) throws Exception {
        int count = 0;
        for (Article article : articles.values()) {
            JsonObject json = new JsonObject();
            json.addProperty("id", article.id);
            json.addProperty("title", article.title);
            json.add("aliases", strings(article.aliases));
            json.add("tags", strings(article.tags));
            json.addProperty("body", String.join(" ", article.facts));
            json.addProperty("sourceLabel", "Minecraft 1.7.10 — игровые реестры");
            json.addProperty("sourceUrl", "");
            File target = new File(directory, article.id + ".json");
            try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8))) {
                JSON.toJson(json, writer);
                writer.write('\n');
            }
            count++;
        }
        return count;
    }

    private static JsonArray strings(Set<String> values) {
        JsonArray result = new JsonArray();
        for (String value : values) {
            if (value != null && !value.trim()
                .isEmpty()) result.add(new JsonPrimitive(value));
        }
        return result;
    }

    private static String stackName(ItemStack stack) {
        String name = russianName(stack);
        return name + (stack.stackSize > 1 ? " ×" + stack.stackSize : "");
    }

    private static String russianName(ItemStack stack) {
        return translatedName(stack, RUSSIAN);
    }

    private static String englishName(ItemStack stack) {
        return translatedName(stack, ENGLISH);
    }

    private static String translatedName(ItemStack original, Properties translations) {
        ItemStack stack = original.copy();
        if (stack.getItemDamage() == 32767) stack.setItemDamage(0);
        String key = stack.getUnlocalizedName() + ".name";
        String translated = translations.getProperty(key);
        if (translated == null || translated.trim()
            .isEmpty()) translated = stack.getUnlocalizedName();
        return translated.replaceAll("%[0-9$]*[dfs]", "")
            .trim();
    }

    private static String registryName(ItemStack stack) {
        Object name = Item.itemRegistry.getNameForObject(stack.getItem());
        return name == null ? stack.getUnlocalizedName() : name.toString();
    }

    private static String itemKey(ItemStack stack) {
        return stack == null ? "" : registryName(stack) + ":" + normalizedDamage(stack);
    }

    private static int normalizedDamage(ItemStack stack) {
        return stack.getItemDamage() == 32767 ? 0 : stack.getItemDamage();
    }

    private static String safeId(String value) {
        return value.toLowerCase(Locale.ROOT)
            .replace(':', '-')
            .replaceAll("[^a-z0-9._-]+", "-");
    }

    private static String formatExperience(float value) {
        return String.format(Locale.ROOT, "%.2f", value)
            .replaceAll("0+$", "")
            .replaceAll("\\.$", "");
    }

    private static Properties loadRussianTranslations() {
        Properties translations = loadTranslations("ru_RU");
        return translations.isEmpty() ? ENGLISH : translations;
    }

    private static Properties loadTranslations(String language) {
        Properties result = new Properties();
        String resource = "assets/minecraft/lang/" + language + ".lang";
        try (InputStream input = openResource(resource)) {
            if (input == null) return result;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int separator = line.indexOf('=');
                    if (separator > 0) result.setProperty(line.substring(0, separator), line.substring(separator + 1));
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not load " + resource, exception);
        }
        return result;
    }

    private static InputStream openResource(String resource) throws Exception {
        InputStream bundled = VanillaKnowledgeGenerator.class.getClassLoader()
            .getResourceAsStream(resource);
        if (bundled != null) return bundled;
        String assetsPath = System.getProperty("aurora.assets.dir", "");
        if (assetsPath.isEmpty()) return null;
        File assets = new File(assetsPath).getCanonicalFile();
        File index = new File(new File(assets, "indexes"), "1.7.10.json");
        if (!index.isFile()) return null;
        JsonObject root;
        try (InputStream input = new FileInputStream(index);
            InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            root = new JsonParser().parse(reader)
                .getAsJsonObject();
        }
        String assetName = resource.startsWith("assets/") ? resource.substring("assets/".length()) : resource;
        JsonObject objects = root.getAsJsonObject("objects");
        if (objects == null || !objects.has(assetName)) return null;
        String hash = objects.getAsJsonObject(assetName)
            .get("hash")
            .getAsString();
        if (!hash.matches("[0-9a-f]{40}")) throw new SecurityException("Invalid asset hash");
        File object = new File(new File(new File(assets, "objects"), hash.substring(0, 2)), hash);
        String objectRoot = new File(assets, "objects").getCanonicalPath() + File.separator;
        if (!object.getCanonicalPath()
            .startsWith(objectRoot) || !object.isFile()) return null;
        return new FileInputStream(object);
    }

    private static File generatedDirectory(File root, String name) throws Exception {
        File directory = new File(root, name).getCanonicalFile();
        String prefix = root.getCanonicalPath() + File.separator;
        if (!directory.getPath()
            .startsWith(prefix)) throw new SecurityException("Generated directory escaped the profile root");
        return directory;
    }

    private static void recreateDirectory(File root, File directory) throws Exception {
        if (directory.exists()) deleteGenerated(root, directory);
        if (!directory.mkdirs()) throw new IllegalStateException("Could not create " + directory);
    }

    private static void deleteGenerated(File root, File target) throws Exception {
        String prefix = root.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath()
            .startsWith(prefix)) throw new SecurityException("Refusing to clean outside the profile root");
        if (Files.isSymbolicLink(target.toPath())) throw new SecurityException("Refusing to follow a symbolic link");
        File[] children = target.listFiles();
        if (children != null) {
            for (File child : children) deleteGenerated(root, child);
        }
        if (!target.delete()) throw new IllegalStateException("Could not delete " + target);
    }

    private static final class Article {

        private String id;
        private String title;
        private final Set<String> aliases = new LinkedHashSet<>();
        private final Set<String> tags = new LinkedHashSet<>();
        private final List<String> facts = new ArrayList<>();
    }
}
