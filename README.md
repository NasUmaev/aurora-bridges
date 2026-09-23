# Aurora GTNH Companion

Aurora is a client-side AI companion for **Minecraft 1.7.10 / GT New Horizons 2.8.4**. It talks directly to a local Ollama runtime, so it does not require a Python bridge, a cloud account, an API key, or a server-side mod.

## Player installation

1. Put `aurorabridge-0.1.0.jar` into the instance's `minecraft/mods` directory.
2. Start that instance normally in Prism Launcher.
3. On the first launch, review the in-game download notice and select **Установить Аврору**.
4. Keep Minecraft open while Aurora downloads the official Ollama runtime and the configured model.
5. When setup finishes, enter a world and press **V** to open Aurora directly, or **T** to open the combined chat.

The first-install flow currently supports macOS 14 or newer. It downloads the signed Ollama application from `https://ollama.com`, verifies its Apple code signature, and stores it inside this Minecraft instance:

```text
minecraft/
├── mods/
│   └── aurorabridge-0.1.0.jar
└── aurora/
    ├── downloads/
    ├── runtime/
    ├── models/
    ├── profiles/
    └── logs/
```

Knowledge profiles are deliberately not embedded in the mod JAR or baked into the Ollama model. The mod contains only Aurora's immutable problem-solving rules, profile validation, retrieval, and prompt composition. External profiles live under `minecraft/aurora/profiles/<profile-id>` and can be updated independently. The first-run installer downloads the default vanilla profile as a separate release asset and verifies its pinned SHA-256 checksum before activation.

On later launches Aurora checks the small online catalog in `knowledge-catalog/catalog.json` after the local runtime has started. Only profiles that are already installed are considered for automatic updates. Downloads are restricted to this project's GitHub Releases, verified with the catalog's SHA-256 checksum, unpacked into a staging directory, and activated atomically. If the catalog is unavailable, Aurora silently keeps using the local profile. A cached catalog is stored under `minecraft/aurora/catalog`, while the downloaded profiles remain the offline knowledge cache.

An interrupted runtime download resumes on the next attempt. Model download progress is shown inside Minecraft.

## Using the chat

- **T** opens the combined chat on the last selected tab.
- **V** opens the Aurora tab directly.
- **Tab** or a mouse click switches between **Игровой чат** and **Аврора**.
- The game tab sends normal chat and commands.
- The Aurora tab stays local: its messages are never sent to a multiplayer server.
- `/aurora <message>`, `/аврора <message>`, and `/av <message>` remain available as shortcuts.
- `/aurora setup` reopens the installation screen.
- `/aurora knowledge` shows the currently loaded external knowledge profiles.
- `/aurora knowledge reload` reloads profiles after they are installed or edited.
- `/aurora knowledge update` manually checks the online catalog for profile updates.

Aurora receives the player's coordinates, health, dimension, held item, block under the crosshair, and an optional short recent-chat window only when the player asks a question. It never moves the player, clicks, breaks blocks, reads recipe registries, or uses the inventory.

Reference questions are resolved only against installed external knowledge profiles. The distributable mod does not inspect recipe registries, NEI handlers, mod internals, or drop tables at runtime. Each Minecraft version or modpack receives its own versioned profile archive; Aurora searches that local archive and passes only the most relevant articles to the model.

## Runtime lifecycle and privacy

- Aurora connects only to a loopback address (`127.0.0.1`, `localhost`, or `::1`); remote Ollama endpoints are rejected.
- If a compatible Ollama API is already running, Aurora uses it and does not stop it.
- Otherwise Aurora starts its portable runtime from `minecraft/aurora/runtime`.
- A runtime started by Aurora is stopped when Minecraft exits normally. A JVM shutdown hook covers most forced closes and crashes.
- Only one AI request runs at a time, preventing an accidental queue from consuming memory.
- The log is rotated after 5 MB so it cannot grow forever.

## Configuration

Forge writes `minecraft/config/aurorabridge.cfg`:

- `aurora.enabled` — master switch;
- `ollama.url` — Ollama endpoint, restricted to the local computer;
- `ollama.model` — local model name, `qwen3:8b` by default;
- `privacy.captureIncomingChat` — include recent received chat in local context;
- `chat.replaceVanillaChat` — replace the vanilla chat screen with Aurora's combined chat.

The internal mod id remains `aurorabridge` for compatibility with existing installations and configuration files.

## Project structure

```text
src/main/java/com/aurora/gtnh/   Forge client, UI, context, Ollama and installer code
src/main/resources/              mod metadata and packaged resources
knowledge-packs/                 external knowledge profiles; never packaged into the mod JAR
knowledge-catalog/               online profile catalog published with the repository
knowledge-schema/                formal schemas for curated knowledge articles
gradle/                          Gradle wrapper support
docs/                            design and development notes
```

`build/`, `run/`, and `.gradle/` are generated locally and are not part of the distributable mod. The old standalone Python bridge is intentionally not part of the current architecture.

The current system boundary is documented in `docs/architecture.md`. The latest foundation review and known risks are
recorded in `docs/code-audit-2026-09-23.md`.

## Building and verification

Use a full JDK supported by the current GTNH development toolchain:

```sh
./gradlew --no-daemon spotlessApply build
```

The installable artifact is `build/libs/aurorabridge-0.1.0.jar`. The build performs formatting, Checkstyle, compilation, Java 8-compatible reobfuscation, knowledge-profile validation, and packaging. Minecraft itself can continue to use the Java version required by the target GTNH package.

Curated articles use the structured v2 format documented in `docs/knowledge-schema-v2.md`. Legacy v1 articles remain readable while the profile is migrated in small, reviewable groups. The validator checks both formats and stops the build on malformed data, duplicate identifiers, broken source references, or a stale article count.

The vanilla profile's generated recipe layer can be rebuilt by a development-only catalog generator, including Ore Dictionary alternatives and the official Russian and English language assets:

```sh
./gradlew --no-daemon spotlessApply runClient -PauroraGenerateKnowledge
```

The exporter is a catalog-building tool, not part of Aurora's runtime. It lives in the test source set and is never included in the distributable mod. It writes thematic `crafting/` and `smelting/` packages below `knowledge-packs/vanilla-1.7.10/knowledge`; curated guides remain alongside them.

## Current scope

This is a macOS client-side MVP with a dedicated two-tab chat and an independently updatable vanilla 1.7.10 knowledge profile. The profile currently covers crafting and furnace recipes plus a small curated starter layer. Structured coverage of every vanilla 1.7.10 block, item, mob, and mechanic is the current catalog stage; GTNH knowledge remains a later, separate profile.

## Primary references

- GTNH 2.8.4 release: <https://github.com/GTNewHorizons/GT-New-Horizons-Modpack/releases/tag/2.8.4>
- GTNH 1.7.10 ExampleMod: <https://github.com/GTNewHorizons/ExampleMod1.7.10>
- Ollama macOS download: <https://ollama.com/download/mac>
- Ollama chat API: <https://docs.ollama.com/api/chat>
- Ollama model pull API: <https://docs.ollama.com/api/pull>
