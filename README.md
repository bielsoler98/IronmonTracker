# IronmonTracker

An Android app that bundles a GBA emulator (built on [mGBA](https://mgba.io/)) with a
live, in-app port of [Ironmon-Tracker](https://github.com/besteon/Ironmon-Tracker), for
playing Pokémon Ironmon/Kaizo randomizer challenges on a handheld Android device (built
for and tested on an Ayaneo Pocket with a physical gamepad).

It plays a Game Boy Advance ROM you supply and shows a real-time tracker panel next to
it: your party, the enemy's revealed info, type matchups, catch rates, a bag/heals
screen, badge progress, and more — all read directly from emulated game memory, with
the same "don't reveal more than a legitimate blind playthrough would know" rules the
original Ironmon-Tracker follows.

## Supported ROMs

Ruby, Sapphire, Emerald, FireRed and LeafGreen — the Lua tracker engine detects which
one is loaded from the cartridge's own game code, no configuration needed. The
following specific releases are recognized (from `GameAddresses/` in the vendored
tracker):

- FireRed v1.0, v1.1, J (Japanese); Feuerrote (DE), Rojo Fuego (ES), Rosso Fuoco (IT),
  Rouge Feu (FR)
- LeafGreen v1.0, v1.1
- Ruby v1.0, v1.1, v1.2
- Sapphire v1.0, v1.1, v1.2
- Emerald

No checksum is needed for the app to work (it reads the ROM's own internal game code,
not a hash) — this list is just so you know ahead of time whether your dump is one of
the recognized revisions. FireRed/LeafGreen is this project's primary target and gets
the most real play-testing; Ruby/Sapphire/Emerald are supported by the same code paths
but see less testing.

## Randomizer settings

This app does not ship with, or generate, any randomizer ruleset. You create your own
`.rnqs` settings file with [Universal Pokemon Randomizer ZX](https://github.com/Ajarmar/universal-pokemon-randomizer-zx)
(desktop) or its [Android port](https://github.com/bielsoler98/PokemonRandomizerZX),
then import it from this app's home screen ("Import randomizer settings"). From then
on, every reset re-randomizes your ROM with that same ruleset.

## What this is not

- It does **not** include any Pokémon ROM, BIOS file, or save file. You provide your
  own legally obtained ROM; the app never ships one.
- It does **not** include the sprite/trainer-portrait/box-art image assets that the
  vendored Ironmon-Tracker normally ships with (see below) — those are Nintendo/Game
  Freak/Creatures-owned artwork, not something this repository redistributes.
- Pokémon, and all associated names, are trademarks of Nintendo, Creatures Inc., and
  Game Freak Inc. This is an unofficial, non-commercial fan project with no
  affiliation to any of them.

## Setting up a fresh clone

The `app/src/main/assets/ironmon_tracker/ironmon_tracker/images/` folder is
intentionally untracked (see `.gitignore`) since it's bundled game artwork, not this
project's own code. To get a working build:

1. Download the matching [Ironmon-Tracker release](https://github.com/besteon/Ironmon-Tracker/releases).
2. Copy its `images/` folder into `app/src/main/assets/ironmon_tracker/ironmon_tracker/images/`.
3. Supply your own legally dumped GBA ROM at runtime — the app loads it from your
   device, it's never part of the repo.

## Credits / third-party components

- **[Ironmon-Tracker](https://github.com/besteon/Ironmon-Tracker)** by Besteon and
  contributors — the Lua tracker this app embeds and adapts for mobile, vendored under
  `app/src/main/assets/ironmon_tracker/`, MIT licensed (see the `LICENSE.txt` in that
  folder).
- **[Universal Pokemon Randomizer ZX](https://github.com/Ajarmar/universal-pokemon-randomizer-zx)**
  by dabomstew and contributors — the randomizer engine vendored under
  `app/src/main/java/com/dabomstew/pkrandom/`, GPLv3-or-later licensed (see the header
  comment in each source file).
- **[mGBA](https://mgba.io/)** by endrift and contributors — the emulator core. Not
  vendored; `app/src/main/cpp/CMakeLists.txt` fetches it at build time from its own
  repository. MPL-2.0 licensed.
- **[Lua](https://www.lua.org/)** — the scripting runtime Ironmon-Tracker runs on. Not
  vendored; fetched at build time the same way as mGBA. MIT licensed.

## License

This project is licensed under the GNU General Public License v3.0 (or later) — see
[`LICENSE`](./LICENSE). It bundles the GPLv3-licensed Universal Pokemon Randomizer ZX
source, so the combined work is distributed under those terms.
