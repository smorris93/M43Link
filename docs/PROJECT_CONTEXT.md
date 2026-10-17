# LumiLink — Project Context

> Durable context doc. Captures the idea, feasibility findings, protocol details, the
> Android networking approach, architecture direction, and next steps, so work can resume
> even if the chat session is lost. Last updated: 2026-07-11.

## 1. The idea

An Android app to **remote-control and transfer photos from a Panasonic Lumix GX80**
camera over the camera's own WiFi hotspot, from a **Xiaomi 14T Pro** phone.

**Why build it:** the official Panasonic app (Image App / LUMIX Sync) connects unreliably.
Root cause (confirmed during research): modern Android (10+) detects the camera's hotspot
has no internet, routes app traffic back over **cellular**, and aggressively **tears the
WiFi connection down**. So requests never reach the camera, or the link drops. A custom app
that pins its sockets to the camera network fixes exactly this.

## 2. Core requirements (MVP)

1. **Remote shutter control** — trigger capture, autofocus, ideally exposure settings.
2. **Photo transfer** — browse and download stored JPG/RAW files to the phone.

Stretch: live-view viewfinder, video record start/stop, exposure controls (ISO/aperture/shutter).

## 3. Feasibility verdict: CONFIRMED FEASIBLE

Both requirements are achievable, and the connectivity problem has a known, documented fix.

### Camera protocol (reverse-engineered, unencrypted HTTP)
- Camera hotspot address: **`192.168.54.1`**
- Control endpoint: plain HTTP GET to **`http://192.168.54.1/cam.cgi`**
- **Capture:** `?mode=camcmd&value=capture`
- **Autofocus:** `?mode=camcmd&value=oneshot_af`
- **Playback / record mode switch:** `?mode=camcmd&value=playmode` / `recmode`
- **Live view stream:** `?mode=camcmd&value=startstream&value=49152` — camera pushes an
  MJPEG/UDP stream to the specified client port
- **Settings:** ISO / aperture / shutter / focus / metering via `setsetting` commands
- **Info/state:** `?mode=getinfo&type=capability`, `?mode=getstate`
- **Pairing handshake:** short UPnP device-description exchange on **port 60606**, then set
  a device name — required before the camera accepts commands.

### Photo transfer
- Switch camera to playback mode, then browse media over **DLNA/UPnP (ContentDirectory
  service on port 60606)**; the actual image files are served over ordinary **HTTP GET**.
- Existing projects that already do this: lumix-link-desktop, qtpanaremote.

### Caveats
- Protocol is unofficial (Panasonic could change it; GX80 is old/stable, so low risk).
- RAW download is slow over the camera's 2.4GHz WiFi.
- UPnP-based transfer is the fiddliest part to implement.

## 4. The Android networking approach (this is the actual product differentiator)

The whole point is handling the network better than the official app. Pattern:

1. Build a `NetworkRequest` from a **`WifiNetworkSpecifier`** (Android 10+), and
   **remove `NET_CAPABILITY_INTERNET`** so Android stops expecting internet from it.
2. Call **`ConnectivityManager.requestNetwork()`** with it.
3. In the callback, call **`bindProcessToNetwork(network)`** — forces *all* app sockets over
   the camera WiFi regardless of internet status.
4. **Hold the `NetworkRequest` open** to keep the connection alive — Android won't reap a
   network an app is actively requesting. This defeats the "connection quickly discarded"
   problem the user hit.

## 5. Architecture direction (to be finalized in plan mode)

- **Language/UI:** Kotlin + Jetpack Compose + Material 3
- **Networking layer:** ConnectivityManager binding (above) + an HTTP client (OkHttp/Retrofit)
  for `cam.cgi`; a UPnP/DLNA piece for media browse; a UDP receiver for live view.
- **Architecture pattern:** MVVM or MVI (decide in plan mode)
- **Local storage:** where downloaded photos land (MediaStore / app storage)
- **Target:** Xiaomi 14T Pro first; decide min SDK (likely API 29/Android 10 for the WiFi
  specifier APIs).

## 6. Names considered
Chosen: **LumiLink** (Lumix + link). Runners-up: Shutterbind, GXConnect, TetherG, Holdfast.

## 7. Wireframe (v1)

Low-fidelity clickable wireframe of the three core screens exists at
**`docs/wireframe-v1.html`** (open in a browser) and is published as a Claude Artifact:
https://claude.ai/code/artifact/58f8cf72-f111-464b-8a0a-8237304b3d0e

Design decisions captured there: three-tab bottom nav (**Connect · Control · Photos**);
function-over-form treatment; monospace for technical data (IPs, SSIDs, exposure, filenames)
vs. system sans for UI; amber "camera-dial" accent; light+dark themes.
Screen contents: (1) Connect — pairing checklist, network/keep-alive readout, bind-to-Wi-Fi
callout; (2) Control — live-view placeholder, ISO/aperture/shutter/EV readout, AF + shutter +
REC controls; (3) Photos — selectable thumbnail grid with JPG/RAW badges and a
"N selected · XX MB → Download" action bar. Status: awaiting user feedback / iteration.

## 8. Build status

**MVP1 (Connect + Photo Transfer) is code-complete and builds** (as of 2026-07-09). Awaiting the
first on-device test before starting MVP2.

- Toolchain: Temurin 17 (JDK), Android cmdline-tools SDK (platform-tools/android-34/build-tools 34),
  Gradle 8.9 via wrapper, AGP 8.5.2, Kotlin 2.0.20. No IDE; CLI builds via `./gradlew`.
  Env vars added to `~/.zshrc` (JAVA_HOME, ANDROID_HOME, PATH).
- App: Kotlin + Compose + Material 3, minSdk 29 / target 34, single `:app` module, manual DI.
- Key files: `network/CameraNetworkManager` (WiFi bind), `network/CameraClient` (cam.cgi),
  `network/SsdpDiscovery` + `DeviceDescriptionParser` + `DidlParser`, `data/PhotoRepository`
  (recursive DLNA browse), `data/PhotoDownloader` + `MediaStoreSaver`, `service/CameraConnectionService`
  (foreground), `ui/connect/*`, `ui/gallery/*`. MVP2 adds `ui/control/*` (Control screen + VM),
  `model/CameraStatus` + `network/CameraStatusParser` (getstate), and the bottom-nav in `ui/LumiLinkNavHost`.
- Tests: JVM unit tests for `CamReplyParser` and `DidlParser` (all passing).
- Build: `JAVA_HOME=<temurin17> ./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` (~27 MB).
- Reader's guide for the code: `docs/kotlin-android-for-java-devs.md`.

### Known MVP1 scoping decisions (to revisit)
- Network selection is **manual SSID + password entry** (saved via DataStore), not a WiFi scan —
  the scan/pick UI is a later enhancement.
- Download picks the JPEG original when present; RAW-only shots download as `.rw2`. (RAW+JPEG pairs
  currently save the JPEG.)
- `ktlint` from the plan is not yet wired into Gradle.

### Camera quirks learned on-device (GX80 — important for future work)
- The camera's hotspot can be **open (no password)** if its Wi-Fi password setting is off.
- **Pairing** (`accctrl&type=req_acc`) returns **CSV** (`ok,GX80-…` / `err_user_refused,…`), NOT XML,
  and needs the user to **accept the device on the camera screen** the first time — so we poll ~12 s.
- The camera **sleeps / drops remote mode** when idle; the app now waits/retries and shows a clear
  "re-arm the camera" message.
- **Thumbnail loading was the big saga.** Root causes, all fixed: (1) Android blocked cleartext HTTP;
  (2) OkHttp **keep-alive hangs** with the camera's server → we force `Connection: close`, fresh
  socket per request; (3) camera **chokes on many concurrent connections** → concurrency capped at 3,
  debounced prefetch; (4) camera sends **no HTTP cache headers** so Coil re-downloaded everything →
  `respectCacheHeaders(false)` + disk/memory cache. A single thumbnail is ~4.5 KB / ~50 ms; the whole
  638-thumb set is ~3 MB, so a **background warmer** pre-caches them all in ~30 s (disk cache persists).

## 9. Current status & where to pick up (paused 2026-07-11, night)

**MVP1 + MVP2 + MVP3 are complete and verified on the real GX80 + Xiaomi 14T Pro.**

- **MVP1 (transfer):** connect (holds), pair, browse all 638 photos, newest/oldest sort, fast cached
  thumbnails, tap-to-preview, long-press multi-select + select-all, single & bulk download.
- **MVP2 (remote shutter):** three-tab bottom nav (Connect · Control · Photos) per the wireframe;
  tabs gate on connection; each tab enters its own camera mode (Control→record, Photos→playback).
  Control screen arms record mode, polls `getstate` (battery / mode / SD strip), and does **shutter
  (`capture`)** and **video record (`video_recstart`/`video_recstop`)**. Disconnect moved to Connect.
- **MVP3 (live view):** **UDP MJPEG live view** in the Control screen — `LiveViewStreamer` opens a
  socket on port 49152, sends `mode=startstream&value=49152`, extracts the JPEG (after a ~178-byte
  header) from each datagram, decodes and renders it (~30fps, smoother than the official app).
  **Tap-to-focus** and the AF button work via **touch AF** (`mode=camctrl&type=touch&value=X/Y`).
  **Live ISO read-out** from the `getinfo&type=curmenu` HTTP query.

Build/run loop unchanged: `JAVA_HOME=<temurin17> ./gradlew installDebug` then relaunch; the phone
connects to the Mac by USB (adb occasionally drops — `adb kill-server && adb start-server` recovers it).

### MVP3 findings learned on-device (GX80 — important for future work)
- **Autofocus is `mode=camctrl&type=touch&value=X/Y` (0..1000 coords), NOT `camcmd&value=oneshot_af`**
  — the latter always returns `err_reject` on the GX80. Touch AF returns `ok`. The AF button focuses
  the centre (500/500); tapping the live view focuses that point. The `capture` full-press also AFs.
- **Exposure read-out: only ISO is available**, and via `getinfo&type=curmenu` HTTP
  (`<item id="menu_item_id_sensitivity" ... value="3200">`), **not** the live-view stream header.
  Verified: the header does NOT encode ISO (stable across an ISO 200→3200 sweep). Aperture & shutter
  are **not exposed** over Wi-Fi in Manual mode (dial-controlled), so the grid shows them as "—".
  curmenu also carries WB / photo-style / AF-mode / metering / focusmode if ever wanted.
- Live view is 4:3 VGA; `startstream` may return `err_reject` for the first second while the camera
  settles into record mode — the streamer retries, so it's transient.

### Deferred / next
1. Aperture & shutter read-out (would need P/A/S modes or deeper stream-header work — uncertain).
2. Wi-Fi scan/pick UI, ktlint in Gradle, RAW+JPEG pair handling, optional MVP1 warmer-pause-on-scroll.

## OM System PEN support (branch `om_system_pen_support`, started 2026-10-17)
Plan with milestones: [`docs/OM_SYSTEM_PEN_PLAN.md`](OM_SYSTEM_PEN_PLAN.md). Protocol research:
OPC (Olympus) HTTP CGIs at `192.168.0.10` (`get_imglist.cgi`, `get/set_camprop.cgi`,
`exec_takemotion.cgi`, `exec_takemisc.cgi`), UDP live view, OIS1/OIS3 QR pairing format; best
reference implementations are `dpolarov/olympus-view-and-delete` (transfer, active) and
`ccrome/olympus-omd-remote-control` (contains the leaked `OPC_Communication_Protocol_EN_1.0a`
spec). PEN protocol not yet verified on hardware — Milestone 1 probes it.

- **Phase 0 (done, verified on-device 2026-10-17):** multi-camera refactor. New `com.lumilink.camera`
  package holds backend seams (`CameraConnection`, `PhotoLister`, `CameraControl`,
  `LiveViewConfig`, `DownloadProfile`, `CameraBackends`); `camera/lumix/LumixBackends.kt` is the
  whole GX80 implementation. ViewModels depend on interfaces only. Credentials are stored per
  camera type (`ssid_<TYPE>` keys; legacy single-camera keys still read as the Lumix's —
  existing installs keep working). Connect tab gained a camera-type selector; types without a
  backend (PEN until M1) have Connect disabled. GX80 regression passed: pair → record mode →
  live view (178B-header frames) → ISO/status read-outs all confirmed on the phone.
  Gallery browse/download is delegation-only and wasn't device-tested (card removed from GX80).
- **M1 PEN connection (done, verified on-device 2026-10-17):** PEN joins via the same
  `WifiNetworkSpecifier` flow unchanged. `OpcClient` (UA `OI.Share v2`, `192.168.0.10`) + probe
  with retry establish the session; M2/M3 stubs probe first then fail with an explicit message.
  **PEN protocol findings (first body ever to talk to it, presumably):**
  - Cleartext allowlist in `network_security_config.xml` had to add `192.168.0.10` (HTTP 520 →
    "CLEARTEXT not permitted" before that).
  - `get_imglist.cgi?DIR=/DCIM` → **200**, body `VER_100\r\n/DCIM,100OMSYS,0,16,23885,22261`
    (captured as fixture `imglist_dcim.xml` in app `files/opc_fixtures/`) — classic Olympus
    directory-listing format; M2 parses this.
  - `get_camprop.cgi?com=desc&propname=desclist` → **520 + `<errorcode>1001</errorcode>`** from
    a cold camera (Wi-Fi freshly launched, likely playback/standby state). Endpoint understood
    but refuses — M3 starts from `switch_cammode.cgi` mode switching, mirroring the GX80's
    rec/play juggling.
  - Fixtures: `captureFixtures()` in `OpcConnection` writes any 200 responses to
    `files/opc_fixtures/` — pull with `adb shell run-as com.lumilink cat files/opc_fixtures/<f>`.
- **M2 PEN photo browsing + download (done, verified on-device 2026-10-17):** `ImgListParser`
  handles the `VER_100` CSV (`dir,file,size,attrib,dateRaw,timeRaw`; FAT attribute bits, packed
  FAT date/time — fixture decodes to 2026-10-13 10:55:42). `OpcPhotoLister` walks `/DCIM` →
  `100OMSYS` → 204 photos in 2 HTTP calls. URLs: thumbnails `get_thumbnail.cgi?DIR=<path>`,
  previews `get_screennail.cgi?DIR=<path>`, **originals at the plain path**
  `http://192.168.0.10/DCIM/100OMSYS/<file>` (full file, no resize round-trip).
  `switch_cammode.cgi?mode=play` (non-fatal) precedes listing, 500 ms settle.
  **PEN download findings (measured, 10-file/85 MB batch):**
  - Sequential: ~2.85 MB/s effective, ~3 s per 8.5 MB JPG, zero retries. That's the radio +
    server ceiling — parallelism doesn't help (below).
  - **x2 concurrency: NO speed gain (29.9 s vs 29.7 s) AND the camera's file server silently
    crossed response streams between concurrent transfers — saved files contained chunks of
    other photos, camera reported 200s throughout.** PEN downloads are therefore strictly
    sequential (`OpcDownloadProfile.maxConcurrentDownloads = 1`) and the gallery's thumbnail
    warmer pauses during download batches (same stream-crossing risk via thumbnail requests).
    Never raise PEN concurrency without verifying file integrity, not just speed.
  - OI.Share UA is sent on all PEN traffic (Coil ImageLoader, downloads, OpcClient) — older
    bodies reject requests without it.
  - Batch downloader now runs bounded-parallel with per-camera cap + abort-on-3-consecutive-
    failures; per-file and batch timings are logged (`GalleryViewModel` tag) for measurement.
  - Videos on the card are listed-then-skipped (MOV etc.); RAW=ORF handled, though this card
    is JPG-only so ORF is untested on-device.
- **M3 PEN live view + control (done, verified on-device 2026-10-17):**
  - **Live view is standard RTP** (RFC 3550, PT 96): 12-byte header per datagram; frame-start
    packets carry an RTP header *extension* (bit X, `00 01 00 3D` = profile 1 + 61 words =
    244 B of frame metadata) and the JPEG SOI lands exactly at offset 12+4+244=260.
    `RtpJpegAssembler` strips RTP headers (incl. extension), feeds the clean payload stream to
    the SOI/EOI framer, and drops loss-torn frames on RTP sequence gaps.
  - **Frame metadata extension contains the JPEG byte size** (verified: 0x1DC6=7622 = emitted
    frame) and, after a tap-to-focus, **AF-frame coords** (`…AE …74 …24 …24` = x174 y116 36x36
    in live-view pixels) — ready-made for an AF-box overlay later.
  - The camera stops pushing after ~1-2 s unless re-requested; the streamer re-requests on a
    1.5 s idle gap (up to 3, then full retry). **Wi-Fi power save was the big stutter cause**:
    without a `WifiLock` (FULL_LOW_LATENCY) + `MulticastLock` (needs
    `CHANGE_WIFI_MULTICAST_STATE`), Android buffers the UDP stream and delivers it in bursts.
    Both locks are now held during streaming for every camera.
  - `stopliveview` returns 520 on the PEN (harmless — the stream stops anyway; the call is
    best-effort).
  - **Exposure:** `desclist` in rec mode gives live ISO/Aperture/Shutter/EV (CamPropParser).
    **`set_camprop.cgi?com=set&propname=X` POSTs `<set><value>V</value></set>` as text/xml —
    verified working.** Steppers step through the camera's own enums; a set is confirmed via a
    ~60 B single-property GET (`get_camprop.cgi?com=get&propname=X` → `<get><value>V</value>`),
    buttons lock until the camera confirms. Mode gating: shutter settable only in S/M, aperture
    only in A/M (a shutter set in A mode returns `errorcode 3001`).
  - Shutter (`exec_takemotion.cgi?com=starttake`) and tap-to-focus (`com=assignafframe&point=
    0160x0120` in live-view pixel coords) both verified. AF coordinate space = live-view pixels
    (320x240 at our lvqty).
  - **Movie recording SOLVED and implemented** (verified on-device): the leaked OPC/AIR
    protocol spec (extracted to text; see the session scratchpad research notes) documents
    `exec_takemotion.cgi?com=newstartmovietake`/`newstopmovietake` (p.111-112). The PEN
    refuses the `new`-prefixed forms (errorcode 1004) but accepts the prefix-stripped pair
    — `com=startmovietake` / `com=stopmovietake` — **only with takemode set to movie** (the
    set of which is verified working). REC switches to movie mode, records, and restores the
    user's takemode on stop; the camera auto-stops recording on disconnect per the spec.
    `startmovie`/`stopmovie` were never real verbs; `starttake` in movie mode hangs. No
    existing open-source implementation ever used the movie verbs — all stills-only.
  - **`get_caminfo.cgi`** = static capability sheet (model, serials, BLE features,
    `GetRawImageSupport`/`GetImageScreennailSupport` — confirms M2 endpoints; fixture
    `caminfo.xml`). **No battery/SD anywhere in the OPC protocol** — which is why OI.Share
    and Olympus View show no battery either. The PEN's status strip now hides unavailable
    fields instead of showing "—" (the GX80 keeps its full strip from `getstate`).
  - **Aperture reading quirk:** the PEN's focalvalue enum has no f/1.7 step (1.6→1.8), so an
    f/1.7 lens wide-open reports f/1.6 — camera-side rounding, not an app bug.
  - **Double window-inset bug fixed** (`consumeWindowInsets` in the nav host): the per-screen
    Scaffolds were re-applying the navigation-bar inset on top of the outer Scaffold's bottom-bar
    padding, double-counting ~200 px and crushing/clipping the bottom of every screen (the
    "white dots" under the shutter button = vertically clipped hint text). Affected the GX80 too.
  - PEN status surface (battery/SD) still unmapped — fetchStatus reports mode only, read-outs
    show "—". Candidates for a future probe: single-property gets.

## Sources
- Lumix GX80 protocol: https://github.com/cleverfox/lumixproto
- libgphoto2 Lumix HTTP/Wifi protocol thread: https://github.com/gphoto/libgphoto2/issues/409
- Android — Connecting your App to a Wi-Fi Device: https://android-developers.googleblog.com/2016/07/connecting-your-app-to-wi-fi-device.html
