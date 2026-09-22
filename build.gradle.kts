
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    failOnNoDiscoveredTests.set(false)
}

tasks.named<JavaExec>("runClient") {
    if (project.hasProperty("auroraGenerateKnowledge")) {
        dependsOn("testClasses")
        classpath += sourceSets["test"].output
        systemProperty("aurora.generateVanillaKnowledge", "true")
        systemProperty(
            "aurora.knowledge.output",
            layout.projectDirectory.dir("knowledge-packs/vanilla-1.7.10/knowledge").asFile.absolutePath,
        )
        systemProperty(
            "aurora.assets.dir",
            gradle.gradleUserHomeDir.resolve("caches/retro_futura_gradle/assets").absolutePath,
        )
    }
}
