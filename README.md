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

An interrupted runtime download resumes on the next attempt. Model download progress is shown inside Minecraft.

## Using the chat

- **T** opens the combined chat on the last selected tab.
- **V** opens the Aurora tab directly.
- **Tab** or a mouse click switches between **Игровой чат** and **Аврора**.
- The game tab sends normal chat and commands.
- The Aurora tab stays local: its messages are never sent to a multiplayer server.
- `/aurora <message>`, `/аврора <message>`, and `/av <message>` remain available as shortcuts.
- `/aurora setup` reopens the installation screen.
- `/aurora memory` shows the latest semantic events remembered for the current world.
- `/aurora knowledge` shows the currently loaded external knowledge profiles.
- `/aurora knowledge reload` reloads profiles after they are installed or edited.

Aurora receives the player's coordinates, health, dimension, held item, block under the crosshair, an optional short recent-chat window, and recent events from the current world's local memory. It observes compact inventory totals once per second but never moves the player, clicks, breaks blocks, or uses the inventory.

Aurora can also notice five important events by herself: death, critical health, catching fire, changing dimension, and breaking an item. These checks compare a few player-state values and do not scan loaded chunks. Proactive replies are rate-limited and briefly appear over the game while Aurora's chat is closed. Events are stored separately for each world in a bounded local journal under `minecraft/aurora/memory`; journal writing runs away from the game thread.

Inventory memory records aggregate gains and losses. Consecutive changes are compacted after one quiet sample, so repeated item use becomes one entry and a quick drop-and-pickup pair cancels out. Moving stacks between inventory slots, equipping armor, or changing a tool's durability does not create an event. At this stage inventory changes are remembered silently; later interpretation can distinguish crafting, placing, consuming, dropping, and container transfers.

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
- `proactive.enabled` — enable autonomous event comments;
- `proactive.cooldownSeconds` — minimum delay between ordinary proactive comments;
- `proactive.criticalHealth` — health threshold for the low-health event.

The internal mod id remains `aurorabridge` for compatibility with existing installations and configuration files.

## Project structure

```text
src/main/java/com/aurora/gtnh/   Forge client, UI, context, Ollama and installer code
src/main/resources/              mod metadata and packaged resources
knowledge-packs/                 external knowledge profiles; never packaged into the mod JAR
gradle/                          Gradle wrapper support
docs/                            design and development notes
```

`build/`, `run/`, and `.gradle/` are generated locally and are not part of the distributable mod. The old standalone Python bridge is intentionally not part of the current architecture.

## Building and verification

Use a full JDK supported by the current GTNH development toolchain:

```sh
./gradlew --no-daemon spotlessApply build
```

The installable artifact is `build/libs/aurorabridge-0.1.0.jar`. The build performs formatting, Checkstyle, compilation, Java 8-compatible reobfuscation, and packaging. Minecraft itself can continue to use the Java version required by the target GTNH package.

## Current scope

This is a macOS client-side MVP with a dedicated two-tab chat and the first persistent per-world event-memory layer. GTNH knowledge retrieval, inventory-difference understanding, places, and long-term summaries remain later stages.

## Primary references

- GTNH 2.8.4 release: <https://github.com/GTNewHorizons/GT-New-Horizons-Modpack/releases/tag/2.8.4>
- GTNH 1.7.10 ExampleMod: <https://github.com/GTNewHorizons/ExampleMod1.7.10>
- Ollama macOS download: <https://ollama.com/download/mac>
- Ollama chat API: <https://docs.ollama.com/api/chat>
- Ollama model pull API: <https://docs.ollama.com/api/pull>
