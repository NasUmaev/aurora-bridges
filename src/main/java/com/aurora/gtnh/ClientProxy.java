package com.aurora.gtnh;

public final class ClientProxy extends CommonProxy {

    @Override
    public void init() {
        if (Boolean.getBoolean("aurora.generateVanillaKnowledge")) {
            try {
                Class<?> generator = Class.forName("com.aurora.gtnh.tools.VanillaKnowledgeGenerator");
                generator.getMethod("main", String[].class)
                    .invoke(null, (Object) new String[0]);
            } catch (Exception exception) {
                throw new IllegalStateException("Could not generate the vanilla knowledge profile", exception);
            }
            cpw.mods.fml.common.FMLCommonHandler.instance()
                .exitJava(0, false);
            return;
        }
        AuroraClient.start();
    }
}
