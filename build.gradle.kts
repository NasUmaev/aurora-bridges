
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

val knowledgeManifest = groovy.json.JsonSlurper().parse(
    layout.projectDirectory.file("knowledge-packs/vanilla-1.7.10/manifest.json").asFile,
) as Map<*, *>
val knowledgeProfileId = knowledgeManifest["id"] as String
val knowledgeProfileVersion = knowledgeManifest["profileVersion"] as String

val packageKnowledgeProfile by tasks.registering(Zip::class) {
    group = "aurora knowledge"
    description = "Packages the validated external knowledge profile for a GitHub release"
    dependsOn(validateKnowledge)
    archiveFileName.set("$knowledgeProfileId-profile-v$knowledgeProfileVersion.zip")
    destinationDirectory.set(layout.buildDirectory.dir("knowledge-release"))
    from(layout.projectDirectory.dir("knowledge-packs/$knowledgeProfileId")) {
        into(knowledgeProfileId)
    }
}

tasks.register<JavaExec>("prepareKnowledgeRelease") {
    group = "aurora knowledge"
    description = "Packages the profile and writes its SHA-256 checksum"
    dependsOn(packageKnowledgeProfile, "testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.aurora.gtnh.tools.KnowledgeReleaseChecksum")
    val archive = layout.buildDirectory.file(
        "knowledge-release/$knowledgeProfileId-profile-v$knowledgeProfileVersion.zip",
    )
    val checksum = layout.buildDirectory.file(
        "knowledge-release/$knowledgeProfileId-profile-v$knowledgeProfileVersion.zip.sha256",
    )
    inputs.file(archive)
    outputs.file(checksum)
    args(archive.get().asFile.absolutePath, checksum.get().asFile.absolutePath)
}

tasks.named("check") {
    dependsOn(validateKnowledge)
}

tasks.register<JavaExec>("generateVanillaBlockInventory") {
    group = "aurora knowledge"
    description = "Builds the vanilla 1.7.10 block coverage checklist from Minecraft sources"
    dependsOn("testClasses", "decompressDecompiledSources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.aurora.gtnh.tools.VanillaBlockInventoryGenerator")
    args(
        layout.buildDirectory.file("rfg/minecraft-src/java/net/minecraft/block/Block.java").get().asFile.absolutePath,
        layout.projectDirectory.dir("knowledge-packs/vanilla-1.7.10").asFile.absolutePath,
        layout.projectDirectory.file("knowledge-workbench/vanilla-1.7.10/blocks.json").asFile.absolutePath,
    )
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
