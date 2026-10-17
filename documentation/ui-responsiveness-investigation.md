# UI responsiveness investigation

Baseline: production `f562476` (v1.0.24 / versionCode 33), compared with `d16c71e`
(v1.0.23). Working branch: `feature/2-Ui-improvement`.

## Evidence and limits

Only four Android source files changed between these releases: `MainActivity.kt`,
`HomeAnnouncementViewModel.kt`, `data/HomeAnnouncementModels.kt`, and
`data/HomeAnnouncementRepository.kt`. The diff introduces root-level Home/Easy
ViewModel creation and full-state collection, and foreground announcement refresh.
It removes the announcement badge's continuous animation. No navigation click
handler, pointer interceptor, full-screen touch overlay, or scroll modifier was
added by that diff.

Source-confirmed main-thread paths in the production baseline:

- Root composition constructs both location ViewModels. Each constructs a
  `PackStore`, whose constructor calls `mkdirs()`. Telephony/location service
  initialization is also eager. These constructors are now reached even while
  displaying More. Before v1.0.24 each screen obtained its own ViewModel on entry.
- `HomeViewModel.refresh()` launches on Main, reads `LocalityCache`, loads/parses
  installed-pack preferences and checks installed database files synchronously.
  It calls `CellReader`, whose subscription lookup, subscription manager creation,
  update request and cached `allCellInfo` fallback include synchronous Android
  calls. These paths predate v1.0.24.
- `CellReader` uses a direct executor; conversion and fallback reads run on
  whichever thread executes the callback, without an off-main guarantee.
- Easy constructs `TextToSpeech` during composition and calls `shutdown()` during
  disposal, so switching into/out of Easy can involve speech-service work on
  Main. This predates v1.0.24.
- GNSS provider checks, last-known lookup and listener registration execute on
  the caller's dispatcher. Successful callback cleanup calls `removeUpdates()`
  from the main looper. These Binder calls predate v1.0.24.
- The new root observes complete Home/Easy states even though announcements only
  need a readiness Boolean. Home loading/diagnostic and Easy error updates can
  consequently invalidate root composition unnecessarily.

Announcement networking/parsing already runs on IO, is bounded to 64 KiB and has
10-second connection/read timeouts. The foreground loop suspends for 30 seconds
between local checks and refreshes at the existing throttle intervals; it is not
a per-frame busy loop. Reassigning an equal data-class value to a StateFlow does
not emit a new value. Neither the loop nor root recomposition is established as
the cause of stalled taps.

These are source findings, **not a captured stall trace**. No ADB/Android SDK,
emulator, Gradle executable or Gradle wrapper was available in this environment.
The attempted `adb devices -l` and `gradle lint testDebugUnitTest` commands failed
because the executables were absent. No FPS, dropped-frame, main-thread trace,
ANR or device before/after results were obtained. The production stall's root
cause and its resolution remain unverified.

## Changes

- Home refresh/approximate/assisted work runs on IO, including cache, preferences,
  installed-file checks, connectivity and telephony. Explicit requests cancel and
  replace the previous request, waiting for its cleanup under a serial operation
  lock before starting the replacement. Refresh taps are not discarded by an
  active-job guard. Approximate/assisted choices use the same runner, including
  when the producer has just published a pending choice but is still finishing.
  Cancellation propagates; resolver exceptions become a Home error for all paths.
- CellReader's entry points dispatch to IO. Its callbacks use the shared IO
  dispatcher executor, with a one-completion guard for callback/failure/cancel
  races. Each API 29+ callback wait is bounded to five seconds, then uses cached
  cells. Errors also use the cache; permission/service failures yield an empty
  cache result. Late callbacks do not read cache or convert cells. External
  cancellation does not trigger fallback. Permission rechecks and
  registered/neighbour handling are retained.
- PackStore defers directory creation until the downloader needs it. Pack/cache
  preferences and telephony/location clients are initialized lazily.
- GNSS provider/last-known/registration work runs on IO. Callbacks retain their
  explicit main looper and perform only classification/copy/resume. Listener
  removal runs in a non-cancellable IO `finally`, including cancellation during
  registration. `RegisteredLocationRequest` owns synchronous registration and
  cleanup in one coroutine; callbacks only deliver a result. Cleanup cannot run
  until registration has returned, even for a callback during registration or a
  concurrent timeout/cancellation. Cleanup failures are surfaced rather than
  silently swallowed. Accuracy thresholds, the 45-second acquisition wait,
  cached-fix policy and number of requests are unchanged. Assisted request setup
  and Task callbacks also run on IO.
- Root collection projects Home/Easy to distinct readiness Booleans before
  combining them. Any Home Ready (even no locality) and either Easy result still
  suppress announcements for the session. Navigation handlers still just assign
  tab/destination state.
- Easy owns a cheap `EasySpeech` controller. The first explicit Read aloud tap
  initializes TTS on IO, waits for initialization and speaks the same digits.
  Cancellation/disposal cleans up the connection off main. Disposal wakes a
  pending initialization immediately and late onInit callbacks are ignored.
  Shutdown shares the operation lock with initialization/speech; an already
  in-flight Binder operation finishes before shutdown, without blocking UI
  disposal. No engine is created simply by entering Easy.
- Announcement dismissal updates session state immediately, with serialized
  max-revision persistence on IO. Scheduling, foreground refresh, priority,
  expiry, actions, cache and repeat/suppression rules are unchanged.

The four pre-existing Saved Places files are preserved byte-for-byte; no Saved
Places integration, version changes, permissions, dependencies, CI changes,
Journey changes or remote-data changes are included.

## Verification performed

- Release source diff and current thread/call-site review.
- `git diff --check` (including separate checks of new files).
- Added `AnnouncementReadinessTest`: loading phases/errors do not emit root
  readiness changes; Home Ready without a match counts; Easy current and Find
  results both count; repeated results do not emit duplicate readiness values;
  refreshing after a result does not reset the session suppression latch.
- Added fake-service/coroutine regression tests: registration callbacks during
  register, cancellation/timeout during blocked registration, cancellation while
  awaiting a fix, partial registration failure, serial Home replacement/choice
  handling and resolver failure recovery, missing/error/duplicate/late cellular
  callbacks and cancellation, lazy TTS/reuse, disposal during initialization or
  in-flight speech, and cancellation before TTS initialization completes.
- Gradle lint/unit tests and APK/device tests could not execute locally. Existing
  announcement/session, GNSS eligibility, locality/cache and Journey tests must
  also pass in the Android build environment before release.

## Required device comparison before closing the P0

Use the same physical device, OS, SIMs, installed packs and refresh scenarios for
`f562476` and the candidate. Include API 26-28 for cached-cell fallback and a modern
dual-SIM device for asynchronous callbacks. Do not clear application data or
install a baseline over user data without a backup.

1. Start screen recording and Android System Tracing/Perfetto with scheduler,
   Binder, input, view/rendering and frame-timeline tracks. Reproduce the stalled
   taps; inspect the main-thread stack/slices during each delayed input dispatch.
2. Reset and collect frame statistics around each identical scenario:

   ```text
   adb shell dumpsys gfxinfo com.actionanand.localtell.app reset
   adb shell dumpsys gfxinfo com.actionanand.localtell.app framestats
   adb logcat -d -v threadtime
   ```

   Use `.debug` when testing the debug application ID. Save logs/traces locally;
   do not upload potentially sensitive logs automatically.
3. Switch Home → Easy → More repeatedly with one tap per tab; scroll long Home
   diagnostics, Easy results, More children and Offline Data. Repeat while GPS is
   acquiring/timeout, while offline, with slow/failed announcement networking,
   immediately after a cold start, and after at least ten background/foreground
   cycles. Exercise Read aloud then immediately switch tabs/rotate/leave Easy.
4. Record tap-to-first-frame delays, frame counts/jank and the associated blocking
   stacks. Inspect logcat for ANR, skipped frames, Binder exceptions and TTS errors.
   Confirm no duplicate GNSS requests or lingering listener after cancellation.
5. For announcement isolation only, compare a temporary local diagnostic build
   with the announcement composable omitted against the same build with it
   present, while keeping repository refresh/session rules running. Revert that
   temporary edit afterwards. No permanent feature switch or product-rule change
   is part of this patch.
6. Verify cache reuse, precise/approximate/assisted consent, permission revocation,
   dual-SIM/neighbour diagnostics, Maps/Uber return, Journey start/stop/reminder,
   offline-pack actions, and announcement dismissal/revision/foreground rules.

Also inspect existing synchronous Location-settings checks on lifecycle/user
actions and Offline Data's separate PackStore callers if traces still show main
thread stalls; those paths were not newly introduced in the release diff. Do not
attribute remaining stalls to them without a captured stack.

Do not declare this P0 closed until lint/build/tests pass and one-tap navigation,
scrolling and foreground transitions are observed on a device with frame traces.

## Blocking validation versus optional follow-up

Blocking before release: run Android compilation/lint/unit tests in a configured
toolchain and perform the device comparison above. The source review and the
new tests have not established production runtime responsiveness.

Platform limit: the 45-second GNSS and five-second cellular deadlines bound
callback waiting. They cannot forcibly interrupt a wedged synchronous Binder
registration/cache-read/removal call. These calls run on IO. GPS cleanup waits
for an in-progress registration before removing; returning early would risk an
orphaned listener. A system-service failure that rejects removeUpdates cannot be
made into a successful removal by client-side coroutine code and must be checked
in device traces.

Optional follow-up after measurement: inspect the separate synchronous
Location-settings/Offline Data paths identified above if their stacks appear in
a remaining stall. Broad refactoring or additional performance dependencies are
not required by this corrective patch.
