# Vulkan compositor integration test

This test runs the **production Vulkan layer** against a real Vulkan device,
Xlib surface, swapchain, command submissions, and presentation. Mesa lavapipe
can supply a software Vulkan device when a hardware GPU is unavailable.

The probe supplies known game pixels and an asymmetric, partially transparent
HUD. It reads the clean swapchain before the production layer runs. The separate
observer layer then reads the actual swapchain after production composition.
The observer contains no shaders, graphics pipelines, or alpha-blending
implementation. `XGetImage` additionally checks the image that X11 presents.

## Dependencies and build

On a normal Ubuntu/Debian development machine, install a C/C++ compiler, CMake,
Vulkan and X11 development files, glslang, a Vulkan driver, Khronos validation
layers, Xvfb, and xauth. Typical package names are:

```sh
sudo apt install build-essential cmake glslang-tools libvulkan-dev libx11-dev \
    mesa-vulkan-drivers vulkan-validationlayers xvfb xauth
```

From the repository root, build the production layer and the test outside the
source tree:

```sh
export OBSVK_TEST_ROOT="$(mktemp -d /tmp/obs-overlay-vulkan-test.XXXXXX)"

cmake -S native/vulkan-overlay -B "$OBSVK_TEST_ROOT/layer" \
    -DCMAKE_BUILD_TYPE=RelWithDebInfo \
    -DVULKAN_HEADERS_DIR=/usr/include \
    -DGLSLANG_VALIDATOR="$(command -v glslangValidator)"
cmake --build "$OBSVK_TEST_ROOT/layer" --parallel

sh native/vulkan-overlay/tests/build.sh "$OBSVK_TEST_ROOT/probe"
```

The test build script uses the system Vulkan and X11 libraries by default. It
also accepts `VULKAN_HEADERS_DIR`, `PROBE_CFLAGS`, `PROBE_LDFLAGS`, and `PROBE_CC`
(or `CC`) for a separately extracted toolchain. Its output consists of `probe`,
`libobserver.so`, and `observer.json` in the chosen build directory.

## Run

Use absolute paths for both shared libraries. The probe creates its own test
layer manifests and explicitly enables the production layer, observer, and
Khronos validation layer in that order. It preserves existing layer search
paths. No global Vulkan layer installation is required.

```sh
xvfb-run -a -s '-screen 0 1280x720x24' \
    "$OBSVK_TEST_ROOT/probe/probe" \
    --layer "$OBSVK_TEST_ROOT/layer/libobs_overlay_vulkan.so" \
    --observer "$OBSVK_TEST_ROOT/probe/libobserver.so" \
    --output "$OBSVK_TEST_ROOT/results" \
    --frames 18 --resize-frame 9
```

An existing X11 display can also be used. In an environment where local Unix
sockets are unavailable, use an authenticated Xvfb server configured for an
allowed TCP display instead of `xvfb-run`; the test itself uses the normal
`DISPLAY` and `XAUTHORITY` variables.

To select lavapipe explicitly, set `VK_DRIVER_FILES` to its ICD manifest before
running the test. A typical x86-64 Ubuntu path is
`/usr/share/vulkan/icd.d/lvp_icd.x86_64.json`; use the path supplied by the
installed driver package. The probe prints the selected Vulkan device.

Validation, including Khronos synchronization validation, is enabled by
default. A successful default run exits with code zero and ends with:

```text
RESULT PASS mode=production-layer frames=18 verified=18 active=13 dirty_off=5 generations=2 validation_errors=0
```

The number of swapchain images may depend on the driver. Each tested swapchain
generation must actually present at least two different images. The probe
prefetches an acquired image to exercise that rotation.

## What the test verifies

Each frame follows this submission sequence:

1. The application submits the game image and HUD upload and signals its normal
   presentation semaphore.
2. A **separate submission on the same queue** reads the clean swapchain image.
   This capture submission has no wait or signal semaphores. It does not consume
   the application's presentation semaphore, matching the relevant OBS capture
   ordering that the compositor must support.
3. The application calls `vkQueuePresentKHR` through the production layer. The
   production implementation must synchronize and composite the private HUD.
4. The downstream observer consumes the forwarded presentation waits, reads
   back the real swapchain image, waits for that copy to complete, and forwards
   presentation with those waits already satisfied.

Assertions cover:

- Exact clean-frame bytes: private HUD pixels must never enter the capture.
- Correct premultiplied alpha composition inside the expected HUD regions.
- Different top and bottom HUD patterns, checking Minecraft's required Y flip.
- Unchanged pixels outside the HUD, including an opaque upstream marker.
- Frames that withdraw a previously published HUD and a fresh swapchain that
  has no HUD publication.
- HUD image waits and release, swapchain destruction/recreation, and subsequent
  rendering at the new size.
- Actual presentation of multiple swapchain images in each generation.
- Full-image agreement between the post-composition GPU readback and X11's
  displayed window at the end of each generation.
- Vulkan validation and synchronization errors, including errors reported
  during resource destruction.

`clean-NNN.ppm`, `player-NNN.ppm`, and `window-NNN.ppm` are diagnostic output.
They are not source files and should remain in the chosen results directory.

To check the test device, presentation, and observer independently of the
production layer, run a baseline first:

```sh
xvfb-run -a -s '-screen 0 1280x720x24' \
    "$OBSVK_TEST_ROOT/probe/probe" --baseline \
    --observer "$OBSVK_TEST_ROOT/probe/libobserver.so" \
    --output "$OBSVK_TEST_ROOT/baseline" \
    --frames 8 --resize-frame 4
```

In this baseline, clean, player, and presented images must agree. The full run
requires both shared libraries and fails if either layer is missing or inactive.

## Scope

This is a rendering and synchronization correctness test. The observer's
deliberate per-frame readback serializes work to make its pixel comparisons
deterministic, so its frame rate is not a compositor performance measurement.
Lavapipe results do not establish hardware GPU performance.

The Linux test checks the same C++ compositor and ABI used by the packaged
Windows layer. It simulates the relevant OBS capture submission sequence; it
does not run Windows OBS, exercise the Windows loader/installer, or establish
compatibility with arbitrary third-party layers. Minecraft integration and
Windows/OBS testing are separate validation steps.
