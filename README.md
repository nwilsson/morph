# Morphed

A Fabric mod for Minecraft 26.3. Kill a mob to unlock it, then morph into it. You take its body, its health, its abilities and its weaknesses.

## Features

- **Unlock by killing.** Every mob you kill becomes a morph. Variants count separately, so a red sheep, a baby zombie or a charged creeper each get their own entry.
- **Take the mob's body.** Your hitbox, max health and model all change to the mob's. Other players see the mob too, holding whatever you hold, with its walk, attack and idle animations.
- **Abilities.** Parrots, bats, bees, ghasts and blazes can fly. Phantoms glide like they have an elytra and flap to gain height. Spiders climb walls. Fish and squid breathe and swim fast underwater. Striders walk on lava. Rabbits and horses run fast and jump high. Nether mobs are fire immune.
- **Powers.** Many mobs come with an active power on a cooldown. Blazes shoot fireballs, endermen teleport, creepers explode (you survive it), wardens use a sonic boom, skeletons shoot arrows, evokers summon fangs, goats ram, foxes pounce, sniffers dig up seeds, and dozens more.
- **Weaknesses.** Zombies and skeletons burn in daylight. Fish dry out on land. Water hurts blazes and endermen. Healing potions hurt you as an undead mob.
- **Mob relations.** Monsters treat you like the mob you look like. A zombie ignores you as a cow or another zombie but still attacks you as a villager. Creepers run from you as a cat, and village iron golems go after you as a monster. Hit a monster and it fights back no matter what you are.
- **Morph sidebar.** Press `M` to open a panel with 3D previews of every unlocked morph, then click one to morph into it. Type in the search box to find a mob, and press Enter to morph into the first match.
- **Favourites.** In the sidebar, press `F` or middle-click a morph to star it. Starred morphs go to the top of the list. Hold the backtick key to get a ring of them around the cursor, point at one and let go.
- **Quick toggle.** Press `B` to go back to yourself, and again to become the last mob you were.
- **Server settings.** Gamerules for how many kills unlock a morph, losing morphs on death, morphing on unlock, flight and how monsters treat you. Datapack tags to block mobs or their powers.

## Controls

| Key | Action |
| --- | --- |
| `M` | Open the morph sidebar |
| `R` | Use the current morph's power |
| `B` | Toggle between yourself and your last morph |
| `` ` `` (hold) | Ring of starred morphs: point at one and let go |
| `F` or middle click | Star or unstar a morph (in the sidebar) |

You can rebind `M`, `R`, `B` and the backtick under *Options → Controls → Morphed*.

## Commands

| Command | Description |
| --- | --- |
| `/morph` | List your unlocked morphs |
| `/morph <mob> [nbt]` | Morph into an unlocked mob, optionally a specific variant |
| `/unmorph` | Return to your own body |
| `/morph unlock <mob> [nbt]` | Unlock a morph without killing the mob (operators only) |

## Gamerules

Set these with `/gamerule`, or under *More World Options → Game Rules* when creating a world.

| Gamerule | Default | Description |
| --- | --- | --- |
| `morph:kills_to_unlock` | `1` | Kills of a mob needed to unlock it. Other looks of a mob you already have unlock on one kill. |
| `morph:keep_morphs_on_death` | `true` | When `false`, dying forgets every unlocked morph, starred morph and kill count. |
| `morph:morph_on_unlock` | `false` | Turn into a mob as soon as you unlock it. |
| `morph:allow_flight` | `true` | When `false`, flying mobs (parrots, blazes, ghasts...) get slow falling instead of flight. |
| `morph:flight_needs_advancement` | `false` | When `true`, flying mobs only flutter down until you've been to the End. See [Earning flight](#earning-flight). |
| `morph:monster_behavior` | `DISGUISE` | `DISGUISE`: monsters treat you like the mob you look like. `MONSTERS_ONLY`: monsters leave you alone only while you're a monster. `SHORT_RANGE`: monsters `DISGUISE` would fool still attack, but only notice you from half as far. `OFF`: morphing doesn't change how mobs treat you. |

## Datapack tags

Both entity type tags are empty by default. Add mobs to them in a datapack, with `"replace": false`.

| Tag | Effect |
| --- | --- |
| `#morph:blocked` | These mobs can't be unlocked or morphed into. Anyone who is one goes back to their own body. |
| `#morph:powerless` | Morphing into these mobs works, but their active power is turned off. |

For example, `data/morph/tags/entity_type/blocked.json`:

```json
{
	"replace": false,
	"values": ["minecraft:wither", "minecraft:ender_dragon"]
}
```

## Earning flight

With `morph:flight_needs_advancement` on, flying morphs only fly once the player has the hidden `morph:flight` advancement. By default it's granted to anyone who has the vanilla *The End?* advancement, including players who reached the End before the rule was turned on. A datapack can replace `data/morph/advancement/flight.json` to require something else, for example killing the dragon:

```json
{
	"criteria": {
		"killed_dragon": {
			"trigger": "minecraft:tick",
			"conditions": {
				"player": {
					"type": "minecraft:entity_properties",
					"entity": "this",
					"predicate": {
						"minecraft:type_specific/player": {
							"advancements": {"minecraft:end/kill_dragon": true}
						}
					}
				}
			}
		}
	}
}
```

`/advancement grant <player> only morph:flight` lets one player fly straight away.

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
