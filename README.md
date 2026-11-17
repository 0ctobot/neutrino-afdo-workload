# Neutrino AutoFDO workload

An account-free Android app that gives the kernel AutoFDO profiling suite
(`tools/afdo/suite` in the Neutrino kernel tree) controlled, verifiable work:
scrolling and rendering, web, GPU, video, camera, audio, storage, network and
memory.  Each case reports what it actually did, and the suite refuses to
train on a case that didn't do its work.

The kernel tree pins one release of this app (`tools/afdo/suite/workload.pin`:
tag, commit, APK hash, protocol version); `run-host.sh fetch` downloads it.

## Protocol (version 1)

The runner (root or shell; the activity and receiver require
`android.permission.DUMP`) starts a case:

    am start -n org.neutrino.afdo.workload/.Run --es run RUN --es scenario SCENARIO \
        --es case CASE --ei rep N --ei attempt N --ei seconds S --el seed SEED --ei protocol 1 \
        [--es peer IP:PORT --es token TOKEN]

The case reports in `files/cases/RUN/SCENARIO.CASE.REP.ATTEMPT/`.  `state` holds
one word: PREPARING, READY, RUNNING, DONE, VALIDATED, FAILED or SAFETY.
`result.json` (status ok, fail or safety, the requested and completed work,
evidence and problems) is written before VALIDATED, FAILED or SAFETY.

- Preparation leaves the app quiet: READY means set up, not working (a
  bounded warmup at most, such as the GPU case's first frames).
- On READY, the runner arms the trace and sends RUN:
  `am broadcast -n org.neutrino.afdo.workload/.Ctl -a org.neutrino.afdo.workload.RUN --es run RUN --es key KEY`.
- The case works only between RUN and DONE, and records its own monotonic
  start and end (CLOCK_BOOTTIME).  Its work (threads, animations,
  rendering, decoders, streams) has stopped before DONE is written.
- After DONE, the runner stops the trace and sends VALIDATE. Checks and
  cleanup run then, untraced.
- ABORT stops a case. A timeout is the runner's: it force-stops the package.
- The end is decided once: SAFETY wins over FAILED, which wins over
  VALIDATED, until the terminal state is written; then nothing changes it.
  A safety stop (the case's own memory or storage limit) also writes the
  file `safety` at once, before cleanup: the runner stops the run on it.
- Resources are released only after the threads that use them have stopped
  (bounded waits); if one doesn't stop, nothing is released, the attempt
  fails, and the runner's force-stop ends the process.

Input is self-driven, from inside the app's own process: nothing is injected
into the system.  Everything the app writes stays in its private storage,
and it is never backed up.

## Building

    ./assets.sh                  # app/assets: images, page, media (ffmpeg; MANIFEST records versions and hashes)
    ./build.sh                   # out/workload.apk, out/build-info.txt
    DEBUGGABLE=1 ./build.sh      # for a test phone without root (run-as reads the results)

No Gradle and no libraries: framework APIs only, compiled with JDK 21
against Android's own Java API surface (a jlink image of the platform's
core library), so using a host-only JDK API fails the build.  Pinned:
build-tools 36.0.0, platform android-35, NDK 28.2 (the one native library,
`native/afdomem.c`, is built with 16 KiB page alignment and stored uncompressed).

`tools/drive.sh SCENARIO [SECONDS]` runs one case on a test phone over adb
(`SCREEN_OFF=1` turns the screen off between READY and RUN).

`peer/peer.py` is the LAN peer of the network and streaming cases (see its
header): token-gated and bound to one run (`--run`), on the host's LAN
address, with per-request and concurrency limits and one byte budget for
everything it moves (UDP included), reserved before each transfer.

`tests/run.sh` runs the host tests: the case protocol (`Case.java` compiled
against small stubs) and the write budget, and the peer on loopback.

## Scenarios

| scenario | work | evidence |
|---|---|---|
| `ui-list` | endless list of images (decoded off the main thread through a small cache) and text; in-process drags and flings | gestures, items scrolled each way, decodes during the interval, frames (with lost frame reports), longest gap between frames |
| `ui-anim` | 48 shadowed, overlapping views under property animations; one view resized every frame | animation ticks, frames, longest gap between frames |
| `ui-web` | WebView on a long local page (only the APK's assets can load), in-process flings and drags; the direction turns every quarter of the interval and near either end | page loaded, gestures, travel each way, 5 s windows with movement, unreadable positions (never guessed), host frames and gaps, WebView version |
| `gpu` | GLES 3: fragment-heavy procedural background, 3000 instanced animated quads, a 512x512 texture uploaded every frame; idle after a 10-frame warmup until RUN | frames drawn on the GL thread, renderer |
| `video-local` | HEVC 1080p60 from the APK, hardware decoder, real time to a SurfaceView, looped | decoder (hardware), frames rendered, media time played |
| `video-stream` | AV1 1080p30 fetched from the peer in 512 KiB ranges, at most a few ahead of playback (the moov chunk pinned, the media data a rolling window) | as above, plus bytes, requests, bytes fetched again, 5 s windows with a transfer, Wi-Fi stability |
| `camera` | back camera: preview + YUV stream (luma sampled) + a JPEG still every 4 s, then video-only HEVC recording; case `main` needs preview+YUV+JPEG, case `preview-jpeg` is the explicit variant without YUV | YUV images and timestamps, stills, the recording parsed (track, duration, frames) |
| `audio-off` | looping AAC at low player volume in a mediaPlayback foreground service, screen off | position played, routed device (must not change during the case), focus losses, errors |
| `net-rpc` | paced HTTP request/responses (new and keep-alive connections, 256 B to 64 KiB) and UDP request/responses with IDs and one retry | answered requests, CRC per response, UDP echoes compared byte for byte, losses, late and duplicate echoes, the peer's own counts for the attempt |
| `net-bulk` | 4 paced TCP streams of whole downloads and uploads | bytes, CRCs against the peer's record and reply, errors |
| `mem-fixture` | bounded anonymous memory (JNI mmap; nearly empty, text-like and random pages) plus shmem and Java-heap churn; allocator-style release (madvise) and remap, rewrites and re-reads; live MemAvailable floors (soft: stop growing and free, hard: SAFETY) | regions, pages written, released and verified, stamps intact after the trace; swap/refault counters as outcomes (allocation and churn: reaching reclaim is not required) |
| `ipc` (diagnostic) | raw Binder transactions to a second process, 64 B to 64 KiB, with a pipe fd every 16th | transactions, reply CRCs, fd byte counts |
| `storage` | fixed windows: sequential write + fsync, one page-cache read-back, random 4 KiB with fdatasync (writes paced), mmap + force(), metadata storm, SQLite WAL; a write budget per subcase and for preparation (2016 MiB in all), checked before every write; SAFETY below 6 GiB free | units, bytes and budget used per subcase, checksums, SQLite integrity and row count, minimum free space, /proc/self/io (physical writes) |
