# Host checks without an Android SDK

From the repository root, with JDK 17 and the Kotlin compiler cache prepared by
`tools/e2e-jvm/run.sh`:

```bash
python3 tools/host-tests/run.py
python3 tools/host-tests/performance/run.py --baseline a583a16100407ae790760637a01b427ee2e5a68d
```

The first command runs real core/benchmark tests, selected pure Android runtime
tests, and playback tests with explicit AudioTrack/Google boundary fakes. It also
extracts the production confirmation/replay/playback method bodies into a small
coroutine harness to check policy bypasses, stale synthesis and playback completion.
This does **not** compile the complete ViewModel, Compose UI, or Android application;
it does not test a microphone, speaker, Google runtime, or models.

The performance command compares the current mel frontend with pinned Git source,
using identical filters and deterministic input. It requires the cached 64 KB mel
filter file, checks bitwise equality on eight signals, then measures paired warm
calls. This measures the frontend alone, not overall ASR or pipeline latency.
Generated files go to ignored `build/` directories. On first use the test runner
fetches JUnit/coroutines JARs if absent; it never downloads model weights.

For a historical regression demonstration only:

```bash
python3 tools/host-tests/run.py --callback-ref a583a16100407ae790760637a01b427ee2e5a68d --output build/host-tests-before
```

Two callback tests should fail with that old source. Use the default command for
current acceptance. Logs clearly label the synthetic decision harness; those
decisions are not model accuracy results.

The live-audio prototype adds pure monitor/noise/hysteresis, streaming observer,
scalar telemetry, presentation/haptic policy and PTT cancellation tests to this
harness. It still does not compile the full AudioRecord/ViewModel/Compose path;
validate those with the Android Gradle compile/unit-test/APK tasks separately.
See `docs/development/live-audio-status.md` for thresholds and validation scope.

The tentative-retry tests cover bounded rejected-capture salvage, core draft generation,
unknown-marker handling, cancellation, and display/speech policy. The adapter harness also
executes the production rejected-result publication and render methods against explicit UI
and prompt-playback boundaries. These tests do not measure model draft accuracy; see
`docs/development/tentative-retry-output.md` for the experimental flag and device limitations.
