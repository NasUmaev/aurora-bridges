package com.aurora.gtnh;

import com.google.gson.JsonObject;

/** Development-only smoke test running after Forge has registered the active recipes. */
public final class LiveRecipeReaderSmokeTest {

    private LiveRecipeReaderSmokeTest() {}

    public static void main(String[] args) {
        LiveRecipeReader reader = new LiveRecipeReader();
        assertRecipe(reader, "how to make a wooden pickaxe?", "Wooden Pickaxe", "Stick");
        assertRecipe(reader, "torch recipe", "Torch", "Coal");
        assertRecipe(reader, "how to make an iron ingot?", "Iron Ingot", "Iron Ore");
        assertNoRecipe(reader, "как получить алмазы?");
        System.out.println("Aurora LiveRecipeReader smoke test passed");
    }

    private static void assertRecipe(LiveRecipeReader reader, String query, String... expected) {
        String result = reader.find(query, new JsonObject());
        for (String value : expected) {
            if (!result.contains(value)) {
                throw new AssertionError("Query '" + query + "' did not contain '" + value + "': " + result);
            }
        }
    }

    private static void assertNoRecipe(LiveRecipeReader reader, String query) {
        String result = reader.find(query, new JsonObject());
        if (!result.isEmpty()) throw new AssertionError("Expected no recipe for '" + query + "', got: " + result);
    }
}
