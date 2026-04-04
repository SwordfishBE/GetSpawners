# GetSpawners

Move mob spawners in survival with Silk Touch.

Ever wanted to move a mob spawner? With GetSpawners, you can now pick up and move monster spawners.
When a player mines a spawner with a Silk Touch pickaxe, the spawner drops as an item and keeps its mob type.

With this mod, you can give yourself a spawner containing any mob that has a spawn egg in Minecraft using a simple command.

The mod has optional permissions support using LuckPerms.

[![GitHub Release](https://img.shields.io/github/v/release/SwordfishBE/GetSpawners?display_name=release&logo=github)](https://github.com/SwordfishBE/GetSpawners/releases)
[![GitHub Downloads](https://img.shields.io/github/downloads/SwordfishBE/GetSpawners/total?logo=github)](https://github.com/SwordfishBE/GetSpawners/releases)
[![Modrinth Downloads](https://img.shields.io/modrinth/dt/L330h09U?logo=modrinth&logoColor=white&label=Modrinth%20downloads)](https://modrinth.com/mod/getspawners)
[![CurseForge Downloads](https://img.shields.io/curseforge/dt/1497073?logo=curseforge&logoColor=white&label=CurseForge%20downloads)](https://www.curseforge.com/minecraft/mc-mods/getspawners)

---

## ✨ Features

- Mine and move spawners.
- Spawner item keeps its mob type.
- `/gs give` gives working spawners with a selected type.
- `/gs types` lists all available types.
- Optional LuckPerms integration (config toggle).

---

## ⚙️ Configuration

Config path:

- `config/getspawners.json`

The file includes inline comments to explain every option.

Options:

- `useLuckPerms` (default: `false`)
- `noSilkTouchSpawners` (default: `false`)

If Mod Menu and Cloth Config are installed on the client, these settings can also be edited through an in-game config screen.

Behavior:

- `useLuckPerms=false`: Everyone can mine/place spawners with Silk Touch.
- `noSilkTouchSpawners=false`: Without Silk Touch, spawners break with normal vanilla behavior (destroyed + XP).
- `noSilkTouchSpawners=true`: Bypass the Silk Touch requirement for everyone.
- `useLuckPerms=true`: Permission nodes are checked with LuckPerms.
  - `getspawners.nosilk` controls the Silk Touch bypass.
- If LuckPerms is not installed, GetSpawners automatically falls back to the non-LuckPerms config behavior and logs a warning.

Example config:

```json
{
  // When true, GetSpawners checks LuckPerms permission nodes if LuckPerms is installed.
  // If false, GetSpawners uses the config-based behavior instead.
  "useLuckPerms": false,

  // When true, everyone can bypass the Silk Touch requirement and still collect spawners.
  // When false, mining a spawner without Silk Touch uses normal vanilla behavior.
  "noSilkTouchSpawners": false
}
```

---

## 🎮 Commands

Main command aliases:

- `/getspawners`
- `/gs`

Subcommands:

- `/gs give <player> <type> [amount]`
- `/gs types`
- `/gs reload`

Notes:

- `amount` defaults to `1`
- max `amount` is `64`
- `type` includes all mobs with a spawn egg

---

## 🔨 Server-side

This mod runs fully server-side. Clients do not need to install the mod.
Also works in single-player (without LuckPerms support).
Without LuckPerms, `/gs give` remains OP-only.

---

## 🔄 LuckPerms permissions

| Node | Description |
|---|---|
| `getspawners.give` | Access to `/gs give` |
| `getspawners.mine` | Mine and place spawners |
| `getspawners.nosilk` | Bypass Silk Touch requirement |
| `getspawners.types` | Access to `/gs types` |
| `getspawners.reload` | Access to `/gs reload` |

---

### 🌍 LuckPerms quick start

If `useLuckPerms` is enabled, assign nodes like this:

```text
/lp user <player> permission set getspawners.mine true
/lp user <player> permission set getspawners.types true
/lp user <player> permission set getspawners.give true
/lp user <player> permission set getspawners.nosilk true
/lp user <player> permission set getspawners.reload true
```

If `useLuckPerms` is disabled, `getspawners.nosilk` is not used and `noSilkTouchSpawners` decides whether Silk Touch is required.

LuckPerms docs:

- Official wiki: [https://luckperms.net/wiki](https://luckperms.net/wiki)
- Command usage: [https://luckperms.net/wiki/Command-Usage](https://luckperms.net/wiki/Command-Usage)
- GitHub wiki mirror: [https://github.com/LuckPerms/LuckPerms/wiki/Command-Usage](https://github.com/LuckPerms/LuckPerms/wiki/Command-Usage)

---

## 📦 Installation

| Platform | Link |
|----------|------|
| GitHub | [Releases](https://github.com/SwordfishBE/GetSpawners/releases) |
| Modrinth | [GetSpawners](https://modrinth.com/mod/getspawners) |
| CurseForge | [GetSpawners](https://www.curseforge.com/minecraft/mc-mods/getspawners) |

1. Download the latest `GetSpawners` JAR from your preferred platform above.
2. Download the latest compatible `Fabric API` version.
3. Place both JARs in your server's `mods/` folder.
4. Start Minecraft.

---

## 🧱 Building from Source

```bash
git clone https://github.com/SwordfishBE/GetSpawners.git
cd GetSpawners
chmod +x gradlew
./gradlew build
# Output: build/libs/getspawners-<version>.jar
```

---

## 📄 License

Released under the [AGPL-3.0 License](LICENSE).
