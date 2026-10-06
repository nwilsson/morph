# Morphed

A Fabric mod for Minecraft 26.3. Kill a mob to unlock it, then morph into it. You take its body, its health, its abilities and its weaknesses.

## Features

- **Unlock by killing.** Every mob you kill becomes a morph. Variants count separately, so a red sheep, a baby zombie or a charged creeper each get their own entry.
- **Take the mob's body.** Your hitbox, max health and model all change to the mob's. Other players see the mob too, holding whatever you hold, with its walk, attack and idle animations.
- **Abilities.** Parrots, bats, bees, ghasts and blazes can fly. Phantoms glide like they have an elytra and flap to gain height. Spiders climb walls. Fish and squid breathe and swim fast underwater. Striders walk on lava. Rabbits and horses run fast and jump high. Nether mobs are fire immune.
- **Powers.** Many mobs come with an active power on a cooldown. Blazes shoot fireballs, endermen teleport, creepers explode (you survive it), wardens use a sonic boom, skeletons shoot arrows, evokers summon fangs, goats ram, foxes pounce, sniffers dig up seeds, and dozens more.
- **Weaknesses.** Zombies and skeletons burn in daylight. Fish dry out on land. Water hurts blazes and endermen. Healing potions hurt you as an undead mob.
- **Mob relations.** Monsters treat you like the mob you look like. A zombie ignores you as a cow or another zombie but still attacks you as a villager. Creepers run from you as a cat, and village iron golems go after you as a monster. Hit a monster and it fights back no matter what you are.
- **Morph sidebar.** Press `M` to open a panel with 3D previews of every unlocked morph, then click one to morph into it.

## Controls

| Key | Action |
| --- | --- |
| `M` | Open the morph sidebar |
| `R` | Use the current morph's power |

You can rebind both under *Options → Controls → Morphed*.

## Commands

| Command | Description |
| --- | --- |
| `/morph` | List your unlocked morphs |
| `/morph <mob> [nbt]` | Morph into an unlocked mob, optionally a specific variant |
| `/unmorph` | Return to your own body |
| `/morph unlock <mob> [nbt]` | Unlock a morph without killing the mob (operators only) |

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) 0.19.5 or newer for Minecraft 26.3.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) in your `mods` folder.
3. Download the Morphed jar from the [releases page](../../releases), or build it yourself (see below), and put it in `mods` too.

Install the mod on both the client and the server.

## Building

You need JDK 25.

```sh
./gradlew build
```

The jar ends up in `build/libs/`. To launch a development client, run `./gradlew runClient`. To run the automated client tests, run `./gradlew runClientGameTest`.

## Credits

Morphed is inspired by [Morph](https://www.curseforge.com/minecraft/mc-mods/morph) by iChun, the original "kill a mob, become it" mod. Morphed is an independent reimplementation for modern Fabric and doesn't use any of its code.

## License

[MIT](LICENSE)
