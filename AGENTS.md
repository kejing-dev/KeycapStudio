# Keycap Studio 定制键盘工坊 (io.embodit.keycapstudio)

Shared Space, skeleton A: default container = Volumetric window `Studio` (0.8 × 0.5 × 0.8 m, WorldScale.Fixed, Gravity) with
a 1:1 procedural keyboard (ECS); control panel = Planar window `Panel` (560 × 760 dp, `Placement.right(Studio, 56 dp)`,
native TabBar, MainWindowTemplate layout). SDK BOM 6.1.9. Design: `spatial-app-factory/apps/keycap-studio/design-package.json`.

## Build
JDK 21 primer + `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT`, then `./gradlew assembleDebug testDebugUnitTest`.
Release: unsigned `assembleRelease` (no signingConfig).

## Code map
- `domain/` pure Kotlin (unit-tested): KLE layouts 104 / 87 (`assets/layouts/ansi104.json`), groups, 16 colorways
  (`assets/colorways.json`, keysim MIT), `Scheme` (+ JSON), `StudioStore` (process store, undo/redo 50), `KeyboardPose`
  (pivot / extents / chip clearance), `CapGeometry` (OEM / Cherry caps), `Repo` (autosave, schemes, export, import).
- `scene/` KeyboardScene (entities, one frame loop: press travel, fades, ripple, breathing, pose easing, layout slide),
  CaseMesh, Art (legend atlas, board patterns, scheme card), SoundBank (CC0 switch recordings, `res/raw`).
- `ui/` StudioVolume (SpatialView + board-input template + chips), PanelWindow / Pages (6 pages), Widgets.
- `input/BoardInput.kt` factory template (tap / drag / hover); targets `key:<keycode>`, `rim`.

## Debug broadcast (debug builds; same code paths as real input)
`adb -s emulator-5556 shell "am broadcast -a io.embodit.keycapstudio.DEBUG -p io.embodit.keycapstudio --es cmd '<cmds>'"`
Several commands separated by `;`. Full list in `app/src/debug/.../DebugCommandReceiver.kt`. Main ones:
`state S01..S19` · `page colorways|palette|patterns|material|schemes|settings` · `tap <keyId>` (= pointer tap) ·
`press <keyId> [hold]` / `release <keyId>` · `colorway <id>` · `random` · `layout 87|104` · `pose <yaw> <pitch>` ·
`detail on|off` · `rotate` · `undo` / `redo` · `target <alphas|mods|accents|space|arrows|nav|numpad|frow|numrow|case|all|single>` ·
`paint #RRGGBB` · `single paint|accent` · `pattern solid|gradient|themed|side|shine` · `theme <dots|…|image>` · `grad 0-3` ·
`material pbt|abs|pc` · `profile oem|cherry` · `switch linear|tactile|clicky|topre|silent` · `volume 0-100` · `mute on|off` ·
`lang zh|en|system` · `save [name]` · `saveas [name]` · `load <i>` · `delete <i>` · `export card|json` · `import json|image` ·
`typing 0-2|stop` · `coach 0-2|off` · `reopen` · `close` (panel only, = system ×; `state S17` uses it) · `confirm exit|reset|delete|off` · `error <text>` · `reset` · `exit` · `dump`.

Imports: put a jpg / png / json in `/sdcard/Android/data/io.embodit.keycapstudio/files/import/` (adb push), then
[导入图片] / [导入 JSON]. Exports land in `/sdcard/Download/KeycapStudio/`.

## Screenshots
`adb shell screencap` / `pico-cli capture screenshot` tear on this emulator (half black). Use the console:
`adb -s emulator-5556 emu screenrecord screenshot <dir>` (2560 × 2558). The keyboard sits 1.75 m away; crop around
x 1000–1700, y 1300–1600, or `detail on` first. Capture list = design `states[]` S01–S19; evidence in
`spatial-app-factory/apps/keycap-studio/build/` (shots/, first-screen, default-view, settings-firstscreen, newgame-confirm = reset confirm).

## Known emulator facts
- `MeshResource.createWithMeshModel` costs 0.5–1 s per mesh on a memory-starved emulator. KeyboardScene makes meshes on
  `Dispatchers.Default` (SDK accepts it), caches them per profile / side-print variant and only attaches on the main
  thread (≤ 8 ms per frame). Cold start ~100 s there, but the panel stays responsive; side print is pre-built after
  SCENE_READY (`SCENE prewarm`), so switching is a swap (< 1.5 s). Log lines: `SCENE rebuilt … ms= worstKeyMs=`.
- Legend atlas: drawn upside down in `Art.legendAtlas`, sampled with `KeyboardScene.flipV = false` (true mirrors rows).
- A stale "Application Not Responding" system dialog survives app restarts; only an emulator reboot clears it.
