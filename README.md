# Pokemon Shiny Hunting Bot

A multi method shiny hunting bot built with **Java** and **Python** that can automate resets and encounters until a shiny is found. It supports both **emulator hunting** and **real hardware hunting** using a capture card and a Raspberry Pi controller bridge.

This project is designed to be expandable: you pick a screen capture region, choose a hunting method, and the bot handles the loop. When a shiny is detected, the bot stops and notifies you so you can take over.

## Features

* Multiple hunting methods:
  * Starter resets (Gen 3)
  * Spin method in grass
  * Egg hatching
  * Static encounters in front of you (legendary style)
* Works from a user selected capture region so it is not tied to one specific window layout
* Shiny detection strategies vary by method for reliability
* Supports emulator input and Nintendo Switch input (via Raspberry Pi bridge)

## Current Support

### Platforms

* Emulators
  * mGBA
* Console
  * Nintendo Switch via capture card video on PC plus Raspberry Pi Bluetooth controller bridge

### Generations

* Generation III

### Game Support Notes

* Switch support is currently focused on Gen 3 games (for example FRLG).
* Emulator support is where most testing has been done so far.

## How shiny detection works

Different hunting methods use different signals:

* FRLG starter method: sparkle animation detection (primary)
* RSE starter method: sparkle animation detection (primary)
* Egg hatch method: sprite and image weighting (primary)
* Legendary method: sparkle plus sprite weighting, heavier emphasis on sparkle
* Spin method: sparkle plus sprite weighting, more balanced

Sparkle detection is the most reliable safe detector for several methods, especially starter resets.

## Requirements

### General

* A Windows PC is recommended for easiest setup
* Legal ROMs or game copies are required depending on platform
* This repo does not include any copyrighted game files

### Emulator Hunting

* mGBA installed
* A valid Gen 3 ROM
* Your game configured with predictable controls

### Nintendo Switch Hunting

* Nintendo Switch and legal copy of the game
* USB HDMI capture card
* Video preview on your PC (OBS recommended, but anything that shows the capture feed works)
* Raspberry Pi with Bluetooth
* Two programs:
  * `runMeOnPI-switchBridge` (runs on the Raspberry Pi)
  * `pokemonShinyHunter` (runs on your PC)

You can run both on the Pi, but it has not been fully tested and may introduce lag if the Pi is processing video or doing other heavy work.

## Quick Start (Emulator mGBA)

1. Install and open mGBA.
2. Load your ROM and get to the point where you are ready to start the method.
3. Launch the bot.
4. Select **mGBA** as the platform.
5. Select the game profile.
6. Enter the Pokémon National Dex number you are hunting for.
7. Click **Capture Screen** and draw a small rectangle around the emulator gameplay area.
8. Reset the game to the correct starting state.
9. Start your hunting method.
10. Click back onto the emulator window so the bot sends inputs to the game and not another app.

The bot assumes the game is at a known starting state when the method begins.

## Quick Start (Nintendo Switch)

1. Connect your Switch dock HDMI output to the capture card, then to your PC.
2. Open OBS or another viewer and confirm you can see the game feed.
3. On the Raspberry Pi, start `runMeOnPI-switchBridge`.
4. Put the Switch on the controller pairing and reorder screen, then connect the Raspberry Pi controller as Player 1.
5. Prepare the game at the correct starting state.
   * A reliable flow is to launch the game, then immediately press Home and wait until you are ready.
6. Run `pokemonShinyHunter` on your PC.
7. Select **Switch** as the platform.
8. Enter your Raspberry Pi IP address (you can get it with `hostname -I` on the Pi).
9. Keep the port at `8000` unless you changed it.
10. Click **Capture Screen** and draw a rectangle around the capture feed window.
11. Start the game and start the bot immediately to avoid timing drift.

## Method Notes and Precautions

* Switch methods require both a Raspberry Pi and a capture card.
* Not all Switch methods are fully tested yet.
* Starter method is the most confirmed on Switch.
  * Some starters can be more difficult due to subtle palette changes.
* Egg hatch method on emulator may require a specific setup and has had less testing than other methods.
* The Switch input pipeline currently relies on a bridge that sends controller actions over the network, so there is room to improve timing and reduce overhead.

## What to expect when it runs

* The bot loops the method:
  * performs inputs
  * watches the capture region
  * checks for shiny signals
  * resets or continues when not shiny
* When shiny is detected:
  * the bot stops
  * it notifies you
  * you take over the game

## YouTube Demo

* Coming soon

## References and Inspiration

* AlexSDevDump Switch Shiny Bot concept and approach
  * https://github.com/AlexSDevDump/SwitchShinyBot
* nxbt
  * https://github.com/Brikwerk/nxbt
* PokeAPI sprites
  * https://github.com/PokeAPI/sprites

## Downsides and Future Plans

* Current Switch control path can be inefficient because actions are sent as individual network requests.
* Planned improvements:
  * a lower latency controller interface for Switch
  * more emulator support (including a DS emulator for Gen 4)
  * expand beyond Gen 3
  * next major target: Gen 2, then Gen 4

## Author

Stuarrt Boekelman

## License

No license has been selected yet.
