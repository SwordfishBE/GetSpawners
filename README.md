# GetSpawners

Move mob spawners in survival with Silk Touch.

Ever wanted to move a mob spawner? With GetSpawners, you can now pick up and move monster spawners.
When a player mines a spawner with a Silk Touch pickaxe, the spawner drops as an item and keeps its mob type.

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

Option:

- `useLuckPerms` (default: `false`)

Behavior:

- `useLuckPerms=false`
- Everyone can mine/place spawners with Silk Touch.
- Without Silk Touch, spawners break with normal vanilla behavior (destroyed + XP).
- Admin commands are OP-only.

- `useLuckPerms=true`
- Permission nodes are checked with LuckPerms.
- If LuckPerms is not installed, GetSpawners automatically falls back to non-LuckPerms behavior and logs a warning.

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

LuckPerms docs:

- Official wiki: [https://luckperms.net/wiki](https://luckperms.net/wiki)
- Command usage: [https://luckperms.net/wiki/Command-Usage](https://luckperms.net/wiki/Command-Usage)
- GitHub wiki mirror: [https://github.com/LuckPerms/LuckPerms/wiki/Command-Usage](https://github.com/LuckPerms/LuckPerms/wiki/Command-Usage)

---

## 📦 Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft.
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) and place it in `mods/`.
3. Download `getspawners-<version>.jar` and place it in `mods/`.
4. Launch Minecraft. The config is created automatically on first run.

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


