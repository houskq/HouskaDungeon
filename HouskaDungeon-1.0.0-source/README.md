# HouskaDungeon — Paper 1.21.11

## Build
Use Java 21 and Gradle:
`gradlew build`

JAR is generated in:
`build/libs/HouskaDungeon-1.0.0.jar`

## Required
- Paper 1.21.11
- Java 21
- Vault + an economy provider if you want money rewards

ExcellentCrates integration is command-based. Set the exact key-give command in config.yml.

## Commands
/dungeon wand
/dungeon create <id>
/dungeon setspawn <id>
/dungeon setexit <id>
/dungeon info <id>
/dungeon list
/dungeon delete <id>
/dungeon reload
/spawndinfo <id>
/spawndinfo remove

## Flow
Player enters a created region -> dungeon becomes occupied -> 5/4/3/2/1 countdown -> waves 1-4 -> 19 mobs of final wave -> boss -> $2000 + key -> full-screen completion -> 5 second teleport countdown -> exit/crate location -> 1 hour per-player cooldown.

Mob spawn positions are RNG inside the selected cuboid; no manual mob spawn points are used.
Flying mobs are not used.
