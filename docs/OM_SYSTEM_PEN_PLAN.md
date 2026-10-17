# LumiLink × OM System PEN — Implementation Plan

Branch: `om_system_pen_support`. Written 2026-10-17, before any implementation.
Companion docs: `docs/PROJECT_CONTEXT.md` (GX80 history), research notes in the session
scratchpad (OPC protocol, Profile Library findings, reference OSS projects).

The goal: add the OM System PEN as a fully supported camera alongside the GX80, hitting the
same four milestones the original LumiLink did — Wi-Fi connection, photo view/download,
live view + control, and (new) OM System colour profiles.

**Process rule for this plan: after each milestone is implemented, we STOP. I build, run the
unit tests, install on the phone, and run the on-device checklist; then you look at what's been
done (I'll capture screenshots and summarize) before the next milestone starts. No milestone
stacks on an unverified one.**

---

## What we know about the PEN's protocol (and what we don't)

The PEN (announced 2026-09-09) uses OM Image Share (OI.Share v1.5) and almost certainly speaks
the same OPC protocol as the OM-1/OM-3/OM-5 family, which has been stable since ~2016:

- Camera hotspot at `192.168.0.10`, plain HTTP CGI, User-Agent `OI.Share v2` expected.
- Camera must have Wi-Fi set to **Private** mode for third-party clients.
- Control: `switch_cammode.cgi`, `exec_takemotion.cgi` (shutter/AF), `exec_takemisc.cgi`
  (`startliveview&port=N`), `get/set_camprop.cgi` (ISO, aperture, shutter, EV, WB, …).
- Photos: `get_imglist.cgi` (XML directory listing), `get_thumbnail.cgi`, `get_screennail.cgi`,
  resize previews, plain HTTP GET for originals. RAW = ORF.
- Profiles: distributed as JPG files with the recipe embedded; uploaded to camera via OI.Share's
  Profile Library function. Wire format **unknown** — postdates the leaked OPC 1.0a spec.
  Compatible bodies per OM System: PEN, OM-3, OM-3 ASTRO.

Key unknowns to resolve on hardware (the PEN is new; nobody has published a verification):
1. Does it accept the classic OPC endpoints as-is? (expect yes)
2. Live-view frame format over UDP (single datagram per JPEG like the GX80, or multi-datagram?)
3. Which resize preview sizes it supports (OM-1 supports 1024/1920; wrong sizes fail).
4. Profile Library upload endpoint + payload format (needs an OI.Share traffic capture).
5. Whether it wants any pairing/handshake beyond Private-mode Wi-Fi (older bodies: none).

The plan front-loads on-device probing into Milestones 1 and 3, and does the capture work in
Milestone 4. Reference implementations to crib from: `dpolarov/olympus-view-and-delete`
(transfer, actively maintained), `ccrome/olympus-omd-remote-control` (+ the OPC 1.0a spec and a
full `desclist` dump), `mauriciojost/olympus-photosync`.

---

## Phase 0 — Multi-camera refactor (prerequisite, ~no new features)

Today every class assumes Panasonic: `CameraClient` hardcodes `cam.cgi`/`192.168.54.1`,
`PhotoRepository` is DLNA-specific, mime types are `rw2`-specific. Refactor to a backend seam
**before** adding Olympus code, keeping GX80 behaviour byte-identical.

New seams (interfaces in `com.lumilink.camera`, implementations per brand):

- `CameraType` enum (`LUMIX_GX80`, `OM_SYSTEM_PEN`) — stored with credentials, chosen on the
  Connect tab (segmented control in the existing style).
- `CameraConnection` — handshake after Wi-Fi joins: `suspend fun establishSession()`.
  Lumix impl = current `pair()` + polling. Olympus impl = reachability probe
  (`get_camprop.cgi?com=desc&propname=desclist`, `OI.Share v2` UA) — no pairing expected.
- `PhotoLister` — `suspend fun listPhotos(): List<CameraPhoto>`. Lumix impl = existing
  DLNA/SSDP walk, moved as-is. Olympus impl comes in Milestone 2. `CameraPhoto` gains an
  optional `previewUrl` (Olympus has screennail/resize in addition to thumbnail; Lumix null).
- `CameraControl` — record/live-view-mode switching, capture, AF, video start/stop,
  `fetchStatus()`, `fetchExposure()`; in Milestone 3 it grows `listSettableProps()/getProp/setProp`.
- `LiveViewConfig` — port + frame assembler (Milestone 3 adds an Olympus assembler; Lumix keeps
  the current single-datagram JPEG extraction).
- `DownloadProfile` — mime types per brand (`rw2` vs `orf`), `maxConcurrentDownloads` knob
  (default 1 = today's behaviour), file-extension mapping.

Wiring: `AppContainer` reads the selected `CameraType` from `CameraCredentialsStore`
(extended: ssid, passphrase, cameraType) and constructs the matching backend set. ViewModels
keep talking to interfaces only; **no UI redesign happens here** — the only visible change is
the camera-type selector on the Connect tab.

Already camera-agnostic and left untouched: `CameraNetworkManager` (the specifier/bind core —
works identically for the PEN), `MediaStoreSaver`, the Coil thumbnail pipeline.

**Phase 0 exit gate:** all existing unit tests green, GX80 end-to-end regression on the phone
(connect, browse, download, live view, capture), screenshots for your sign-off. The GX80 must
keep working at every later gate too.

---

## Milestone 1 — Wi-Fi connection to the PEN

*Same bar as MVP1's "Connect": the connection must hold reliably — that's the app's reason to exist.*

Build:
- `OpcCameraConnection` — probe + retry loop (tolerate the camera's HTTP server coming up a few
  seconds after Wi-Fi associates, same pattern as `pair()`'s polling).
- Connect tab: camera type selector; per-type saved credentials; PEN instructions card
  ("On the camera: Setup → Wi-Fi Settings → Private, then launch Wi-Fi and note the SSID/password").
- On-device probe pass: confirm the classic endpoints respond on the PEN; record the desclist
  XML (becomes a test fixture + the basis for Milestone 3 controls); record `get_imglist.cgi`
  output shape (fixture for Milestone 2). Keep `rawQuery`-style escape hatch for probing.

Verify on device:
- Connects first try after the one-time Android prompt; survives 10+ minutes idle, screen off
  and on, app backgrounded/foregrounded; reconnects after camera sleep/wake.
- Solid failure paths: wrong password, camera off, wrong mode (e.g. Wi-Fi in "Smart" mode
  instead of Private) produce the existing friendly error states.

**STOP — verify, screenshots, your review.**

---

## Milestone 2 — View and download photos

Build:
- `OpcPhotoLister` — parse `get_imglist.cgi` (new `ImgListParser`, JVM unit tests against the
  fixture recorded in M1 plus synthetic edge cases: videos, ORF, deep folders, odd names).
  Map to `CameraPhoto` (thumbnail/screennail/original URLs, sizes, ORF detection).
- Thumbnail strategy — port the GX80 lesson, tuned for OPC:
  - Grid cells: Coil `AsyncImage` + the existing paced background warmer (visible-first, then
    batches of ~6 every ~250 ms — this is exactly what made LumiLink beat the official apps).
  - Olympus-specific: request the **resize preview (1024)** for grid sharpness but with a
    fallback chain resize → `get_screennail.cgi` → `get_thumbnail.cgi` (Olympus View learned
    the hard way that unsupported resize sizes fail per-model — probe at runtime, cache the
    working size).
  - Full-screen preview: screennail first (fast), original on demand; RAW previews use
    screennail (can't render ORF).
  - Persistent thumbnail cache (Olympus View keeps ~1000 entries) so revisits are instant —
    evaluate after first on-device run whether it's needed.
- Download engine — make concurrency a first-class, camera-tunable concern:
  - Bounded-parallel downloads (semaphore, default per `DownloadProfile`), keeping the existing
    per-file retry/backoff. Streaming straight to MediaStore as today, `image/x-olympus-orf` mime.
  - **On-device measurement task:** time a 20-file batch (mixed JPG/ORF) at concurrency 1, 2,
    3 against the PEN. Pick the fastest *stable* setting as the PEN default; document results
    in PROJECT_CONTEXT. If errors (resets/503s) appear at 2, default to 1 and keep the knob.
  - Camera-safety rules, applied regardless of concurrency: pause the warmer while downloads
    run (single embedded server + single radio); never issue control commands mid-download;
    global cap on in-flight requests; exponential backoff on failures; abort-all on repeated
    failure (don't hammer a struggling camera); live view and downloads never run together.
    Nothing here can "brick" the camera (read-only + MediaStore writes), but a wedged camera
    server forces a battery pull — the throttling exists to make that impossible to trigger.

Verify on device: listing a large card (500+ files) stays responsive; thumbnails visibly faster
than OI.Share; batch download progress + correctness in gallery app; concurrent download
stability over repeated runs; PEN never wedges across a stress run (continuous browse+download
for ~10 min).

**STOP — verify, screenshots, your review.**

---

## Milestone 3 — Live view and control

Build:
- `OpcCameraControl`:
  - `switch_cammode.cgi?mode=rec&lvqty=…` (also `mode=play` for the Photos tab hand-off;
    the GX80's inter-tab mode juggling carries over 1:1).
  - Shutter: `exec_takemotion.cgi?com=starttake`. Tap-to-focus:
    `com=assignafframe&point=XXXXxYYYY` in the live-view coordinate space (verify the space
    on-device; maps onto the existing tap-to-focus gesture).
  - Video: start/stop verb — confirm on-device (`startmovie`/`stopmovie` expected), wire to the
    existing REC button.
  - `get_camprop.cgi?com=desc&propname=desclist` → build the settable-controls model
    **dynamically from the camera's own desclist** (each property advertises get/set and its
    legal values). The Control tab renders whichever of aperture/shutter/ISO/EV the PEN exposes —
    same readout rows as now (they already light up for non-null aperture/shutter), plus new
    steppers/dials in the existing monospace style for settable values. The GX80 shows exactly
    what it shows today; nothing regresses.
  - Status polling (`getstate`-equivalent: battery/mode via camprops desclist or state endpoint
    — confirm which on-device).
- `OpcLiveViewStreamer` — rework `LiveViewStreamer` to pluggable frame assembly:
  - Unknown #2 from the top: Olympus pushes JPEG frames over UDP, possibly one frame across
    multiple datagrams (RTP-ish). First on-device run logs datagram sizes/headers, then the
    assembler is written to match (Lumix keeps its current single-datagram path untouched).
  - ISO/aperture/shutter readout refresh: GX80 polls curmenu; PEN uses camprops.

Verify on device: live view starts/stops cleanly (incl. after mode switches), frame rate
acceptable, tap-to-focus focuses where tapped, remote shutter fires (photo lands on card and
appears in Photos tab), video record works, ISO/aperture/shutter rows match the camera's own
top-plate/EXIF after changing values from both the app and the camera dials (change detection
is the trickiest part — poll-based, so verify update latency).

**STOP — verify, screenshots, your review.**

---

## Milestone 4 — OM System colour profiles

*The least certain milestone — it depends on capturing OI.Share traffic, so it's last.*

Prerequisite (needs the PEN + a laptop, ~30 min):
1. PEN in Private Wi-Fi mode, laptop joined to its hotspot.
2. Run OI.Share v1.5 on a spare phone (or an emulator + physical Wi-Fi adapter — phone is
   easier), capture with `mitmproxy`/tcpdump on the laptop's Wi-Fi interface (plain HTTP).
3. Perform a Profile Library upload of a profile JPG (e.g. from
   explore.omsystem.com/us/en/creative-profiles) and capture the request sequence: endpoint,
   any handshake, payload encoding of the recipe inside the JPG, confirmation/reply.
4. Repeat for profile export/fetch if OI.Share exposes it. Record everything as fixtures.

Build (assuming the capture succeeds):
- `OpcProfileLibrary`: list profiles on the camera, upload/apply a profile JPG, export the
  active profile. Custom OkHttp + parser, JVM tests against captured fixtures.
- UI: a "Profiles" area for OM cameras (Control-tab sub-screen), same design language:
  - Import: file picker for a JPG already on the phone, plus an optional built-in catalog
    browser for the OM System Creative Profiles page (simple: fetch the page's profile list).
  - List + apply; show the active profile.
- If the capture reveals the flow is gated/complex: fall back to what the protocol does support
  (e.g. applying the underlying colour settings via camprop) and reassess with you.

Verify on device: profile import appears in the camera's Profile Library; applied profile
visibly changes JPEG output (shoot the same scene before/after); export round-trips.

**STOP — verify, screenshots, your review.**

---

## Cross-cutting

- **Fake-camera test server (JVM):** a local HTTP server standing in for the camera
  (serving recorded fixtures for `get_imglist.cgi`, camprops, etc.) so parsers and the
  repository layer get integration tests without hardware — the pattern Olympus View uses.
  Real fixtures come from the M1 probe session.
- **Unit tests** for every new parser (ImgListParser, CamPropParser, frame assembler, profile
  payload codec) — same standard as the existing CamReply/Didl tests. `./gradlew testDebugUnitTest`
  green is part of every stop gate.
- **Dev loop:** CLI build (`./gradlew installDebug`), adb-driven verification (logcat,
  `uiautomator dump`, screencap for your review), pixel-analysis scripts for objective checks
  (e.g. live-view actually rendering frames). gphoto2 USB tether isn't applicable to the PEN;
  GX80 regression runs use Wi-Fi as before.
- **Docs:** update `docs/PROJECT_CONTEXT.md` at each milestone with findings (camera quirks,
  measured numbers, endpoint notes) — that doc is what lets any future session resume cleanly.

## Risks / open questions

| Risk | Mitigation |
|---|---|
| PEN changed/walled the classic OPC protocol | M1 probe catches it immediately; capture OI.Share traffic as fallback |
| Live view spans multiple datagrams | Frame assembler is pluggable; decided from M3 on-device logs |
| Parallel downloads wedging the camera | Concurrency measured on-device, defaults conservative, throttles + abort-all |
| Profile Library format opaque | M4 is capture-first; scope falls back to camprop-level control if needed |
| GX80 regression during refactor | Phase 0 exit gate + GX80 spot-check at every later stop gate |
| Unsupported resize sizes per model | Runtime probe with fallback chain (Olympus View's lesson) |

## Suggested order of work

Phase 0 → M1 → M2 → M3 → M4, with the OI.Share traffic capture slotted at the start of M4
(can be done any time after M1, whenever the camera and a laptop are both at hand).
