# Windows 10 / RTX 5080 / OBS Game Capture acceptance

## Status and evidence boundaries (2026-10-15)

Reviewed PR #2 at `efc9a4dc0e125e6cdedd1836a7008f65a1201478` (still Draft).
[PR CI run 37218121341](https://github.com/half-drop/obs-overlay/actions/runs/37218121341)
completed successfully: Windows/Linux native compile, native load/exports/ABI,
and Fabric/NeoForge packaging. It contains **no real OBS recording test**.
The [2026-10-04 Linux results](VULKAN.md#validation) are historical development
results, including the 18/18 lavapipe probe and Fabric integration. They have
not been rerun on Windows by this change.

**All target-machine cases below are NOT RUN.** The collector's portable unit
tests exercise evidence handling and verdict aggregation only. A hosted Windows
runner is also insufficient to validate Windows 10 + RTX 5080 + OBS.

`destroySwapResources` in `native/vulkan-overlay/src/layer.cpp` still waits with
`vkQueueWaitIdle` and destroys presentation semaphores. Successful stress tests
cannot establish a strict presentation-resource retirement guarantee. A
KHR/EXT swapchain-maintenance present-fence solution remains a separate code
requirement. Keep PR #2 Draft while platform acceptance and that limitation
remain unresolved. This change does not modify compositor synchronization.

## Prerequisites

- Use a disposable **local** world, Minecraft 26.3, Java 25 x64, and a packaged
  Fabric beta JAR from the exact tested commit. Test NeoForge separately with
  a fresh report if support is claimed. Remove duplicate versions. Save loader,
  Java, Fabric API and mod dependency versions in `setup.txt`.
- Confirm Windows 10 build, RTX 5080 and NVIDIA driver version from collected
  inventory. Record OBS exact version, canvas/output resolution, frame rate,
  encoder, scaling, HDR state, GPU selection, fullscreen resolution/refresh rate,
  and third-party overlays. Start with SDR and other overlays disabled; repeat
  relevant cases with the intended daily configuration afterward.
- Select **Vulkan** in Minecraft and restart the game. `latest.log` must include
  `OBS Overlay Vulkan HUD backend initialized`; save renderer/device information
  too. OpenGL or a missing backend initialization is blocked acceptance.
- Make a dedicated OBS scene with a single **Game Capture** source, capture the
  specific Java game window, and record its properties (including capture
  third-party overlays and hook compatibility options). Remove desktop/window
  capture fallbacks. Use local MKV recording, no streaming required. Check the
  OBS log confirms the game process and Vulkan hook; save the relevant lines.
- In the mod configuration enable **Show test icon**. The implementation draws
  a private 16x16 GUI-unit checkmark at top left; UI scaling changes its physical
  size. Use a public hotbar/crosshair and continuous camera movement as controls.
  Also select a component such as chat as private and display dummy text. Do not
  use real secrets to test privacy.
- Use Python 3.10+ (`py -3`), no pip packages or administrator privileges needed.
  Locate the exact game PID in Task Manager Details. Match the game window to
  that PID; never choose a launcher or another Java application by name alone.

## Start a repeatable session

Run from the repository root in PowerShell. Replace paths and SHA with the
actual packaged build source, which may differ from this documentation's
reviewed baseline. Each run uses a new directory; reports must not be reused
across JARs, drivers, loaders or OBS configurations.

```powershell
py -3 tools/windows-validation/validate.py init --jar 'C:\mods\obs-overlay-fabric.jar' --commit '<40-character-build-SHA>' --out 'C:\obs-validation\run-01'
py -3 tools/windows-validation/validate.py snapshot --out 'C:\obs-validation\run-01' --game-pid 12345 --game-dir 'C:\Minecraft\instance' --obs-log "$env:APPDATA\obs-studio\logs\2026-10-15 17-00-00.txt"
```

`init` hashes the JAR and bundled Windows DLL (and checks its PE signature),
collects OS/GPU/driver inventory and `nvidia-smi` output, and creates a report
with every case pending. This is a preflight record, not DLL loading, ABI or
capture verification. Command failures are retained in JSON; resolve them or
explicitly record the associated checks as blocked.

`snapshot` collects the **exact PID** start time, responding flag, handles,
working/private memory, total GPU memory usage, copies current game/config/OBS
logs, and lists crash files. Take snapshots before/after each lifecycle group,
after exit with the old PID, and after relaunch with the new PID. Choose the
new OBS log after each OBS restart. Compare process start times to detect PID
reuse. GPU memory is device-wide and includes OBS/other apps; it is a diagnostic
signal, not a per-process leak detector. Crash paths are listed only: copy any
new crash files as case evidence. Missing files are recorded, not silently passed.

The tool never terminates processes, sends keys, changes OBS settings, edits the
registry, installs global Vulkan layers, or deletes native caches. Operations
requiring control of the live game remain explicit operator steps.

## Execute and judge

Record continuously during each scenario, with a spoken/visible case and cycle
number. Keep OBS logs with hook/reconnect events. For every transition inspect
both the player display and **saved recording**, including frame-by-frame near
the transition. OBS preview alone is insufficient. Use a phone camera or an
independent desktop recording for player-side evidence; keep that capture out
of the OBS test scene. Minecraft F2 screenshots can show the clean main target
and cannot prove that the player saw the private HUD. Synchronize both views
with a public camera movement or menu action.

| Case ID | Procedure | Pass criteria |
| --- | --- | --- |
| `isolation` | Record 30s; test icon OFF → ON → OFF, private chat dummy text; move camera throughout. | Player sees private content when enabled; no private pixels in any inspected recording frame. Public hotbar/world remain visible and moving in both views. Icon disabled leaves no stale pixels. |
| `resize` | 1280×720 → 960×600 → native monitor size, 10 cycles during capture. | Each transition settles within 5s; live capture, correct private HUD scale/position, no leakage, corruption, stale image, hang or crash. Record actual client sizes. |
| `fullscreen` | F11 10 round trips; minimize 5s/restore 5 times. | Within 5s after each return, both views update and isolation holds; no corruption/crash. Log whether fullscreen is exclusive or borderless. Black frames while minimized are allowed; persistent black frames after restore fail. |
| `reconnect` | Deactivate/reactivate Game Capture 5 times (use source properties, not only visibility); restart OBS twice with game running; restart game twice with OBS running. | Within 10s of reactivation or gameplay, world is live and private content remains isolated. No source-method fallback. Inspect first recovered frames too. |
| `normal-exit` | Quit normally 5 times, including directly after resize/F11. | Exact game PID gone within 15s; no new crash, device-lost or validation errors; relaunch recovers capture and isolation. Snapshot before/after. |
| `abnormal-exit` | End only the confirmed game PID in Task Manager, 3 times, including after resize. | PID gone within 15s; OBS stays usable; fresh game captures with isolation within 10s of gameplay. Record PID/start time for each trial. Forced termination does not exercise orderly destructor completion. |
| `soak` | Record 10min, then 20 resize/F11 cycles; return to same scene/size and wait 60s. | No leakage, driver reset, hang, new validation/device-lost errors. Compare memory after initial warmup and each settled cycle group; sustained monotonic growth needs investigation and cannot be marked pass until explained. Attach raw readings and rationale. |

The timings are acceptance thresholds for this procedure, not implementation
guarantees. Any private HUD leak is an immediate failure, even if capture later
recovers. Record black/frozen/corrupt frames and exact timestamps. Review the
whole clip at normal speed plus frame-step all transitions; document sampling
limits. Human review cannot prove absence of a one-frame leak in uninspected
frames. For stronger privacy assurance inspect every frame with a fixed visual
fixture in a separate instrumented test; this collector does not implement a
pixel oracle for variable world backgrounds or compressed video.

For cleanup, compare new crash reports, `hs_err_pid*.log`, OBS hook errors,
Windows Reliability Monitor/Event Viewer display-driver reset entries, and
process/GPU memory observations. Optional Vulkan SDK validation with
synchronization validation can supply additional evidence: preserve loader
settings and logs proving the layers were active. An empty validation log with
no evidence of activation is inconclusive. Logs without errors and recovered
memory do not prove Vulkan WSI resource lifetime correctness.

The bootstrap intentionally retains its content-addressed DLL/manifest under
`java.io.tmpdir\obs-overlay-vulkan\<digest>\...`; the library remains loaded
while Vulkan may dispatch through it. Cache files surviving exit are expected.
Do not delete them while the game is running. Forced exit relies on OS/driver
process cleanup; successful relaunch is behavioral evidence only.

## Record evidence and results

After observing each case, write a short note containing cycle counts, times,
actual result, recovery delay, and any error. Attach player-side evidence,
recorded MKV, setup/settings and relevant logs (multiple files accepted):

```powershell
py -3 tools/windows-validation/validate.py record --out 'C:\obs-validation\run-01' --case isolation --status pass --note '30s, OFF/ON/OFF; player checkmark visible; all frames reviewed; recording has moving public hotbar only; see clip timestamps' --evidence 'C:\evidence\player.mp4' 'C:\evidence\obs.mkv' 'C:\evidence\setup.txt'
```

Use `fail` for observed faults and `blocked` for missing evidence, inactive
Vulkan, wrong capture method or an unexecuted prerequisite. Evidence is copied
and SHA-256 hashed (large recordings need enough free disk space). Results are
**operator assertions**. A case is never automatically passed by log scanning
or successful commands. Retests append history; the newest case result controls
the summary, so explain any earlier failure and why it is resolved. Missing or
blocked cases keep the report incomplete. All seven passes yield only
`manual-acceptance-pass; presentation-retirement-guarantee-still-unresolved`.

Keep the complete report directory and original recordings. Before uploading,
review copies for usernames, chat, paths and tokens. Submit a redacted evidence
bundle with a new hash inventory if necessary. Report the build SHA, JAR hash,
Windows build, GPU/driver, OBS/loader versions, case outcomes and remaining
limitations in PR #2. Do not relabel CI or Linux results as Windows acceptance.

## Tool checks

```powershell
py -3 -m unittest discover -s tools/windows-validation -v
```

These tests cover verdict gating and evidence integrity; target Windows
inventory, actual game/OBS recordings and lifecycle behavior remain local
execution requirements.
