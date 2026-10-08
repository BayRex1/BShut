# BGame

Native Android 3D game prototype by BayRex.

## Build
GitHub Actions builds a debug APK on every push to `main`. The APK is published as the **BGame-debug** workflow artifact.

## Current vertical slice
- Landscape mobile presentation
- OpenGL ES procedural gameplay scene without Unity/Godot
- Google Filament GLB/glTF rendering layer
- Animated CC0 Soldier GLB hero model in the main menu
- Brawl Stars-inspired main-menu composition
- Play button and loading screen
- Third-person forest driving sequence
- Smooth transition to the vehicle interior
- Driver and civilian passenger dialogue
- Non-graphic collision transition
- Black-screen aftermath and return to the main menu

## 3D asset credits
- **Character_Soldier.glb** — Quaternius Toon Shooter Kit, delivered as a single GLB through Cinevva's CC0 asset library.
- License: **CC0 1.0**.
- Source: https://app.cinevva.com/game-assets/free-3d-character-models
- The model has 21,469 vertices and 17 animation clips according to the asset listing.

The procedural gameplay geometry remains original/generated. External 3D assets are only added when their license permits redistribution.
