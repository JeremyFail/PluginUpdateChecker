# PluginUpdateChecker

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge)](https://opensource.org/licenses/MIT)

This is a **library** for Minecraft Bukkit / Spigot / Paper server plugins. It is designed to provide easy update checking functionality to any plugin. 

This is **not** a standalone plugin (no `plugin.yml`): you embed the JAR in your plugin, typically with **Maven Shade** and **package relocation**, so multiple plugins can each ship their own copy without classpath conflicts.

The checker runs network work **asynchronously**, then applies results, events, and optional console/player messages on the **main thread**. Built-in sources include **GitHub Releases** (`owner/repo`), a **plain-text URL**, or your own **supplier**. Version comparison uses **Maven-style ordering** (not naive string compare).

| | |
|--|--|
| **Language / build** | Java 17+, Maven |
| **Coordinates** | `com.failprooftech:plugin-update-checker:1.0.0` (see `pom.xml`) |
| **Library version in code** | `PluginUpdateChecker.VERSION` |

## Documentation

Full documentation lives in the **[GitHub Wiki](https://github.com/JeremyFail/PluginUpdateChecker/wiki)**.

## Issues and releases

- **Bug reports and feature requests:** use the **[Issues](https://github.com/JeremyFail/PluginUpdateChecker/issues)** tab.
- **Published builds:** see **[Releases](https://github.com/JeremyFail/PluginUpdateChecker/releases)**.

## Quick peek

```java
@Override
public void onEnable() {
    PluginUpdateChecker checker = new PluginUpdateChecker(this, "Owner/Repo");
    checker.setDownloadUrl("https://example.com/releases");
    checker.checkNow();
    checker.scheduleRepeating(6, TimeUnit.HOURS);
}
```
