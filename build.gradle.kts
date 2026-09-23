
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    failOnNoDiscoveredTests.set(false)
}

val validateKnowledge by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Validates external Aurora knowledge profiles"
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.aurora.gtnh.tools.KnowledgeProfileValidator")
    args(layout.projectDirectory.dir("knowledge-packs/vanilla-1.7.10").asFile.absolutePath)
}

tasks.named("check") {
    dependsOn(validateKnowledge)
}

tasks.named<JavaExec>("runClient") {
    if (project.hasProperty("auroraGenerateKnowledge")) {
        dependsOn("testClasses")
        classpath += sourceSets["test"].output
    }
    if (project.hasProperty("auroraGenerateKnowledge")) {
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
