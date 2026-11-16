# Android device evidence protocol (2026-10-05)

Status: tooling prepared, **no phone profiling run performed**. Host tests do not
prove Snapdragon RAM, battery, thermal, audio, or NPU behavior. Do not reuse Mac
OPUS scores for current ML Kit. Keep the historical 5/40 synthetic safety failures
in Task 6 visible: better performance or a new TTS is not a safety fix.

## Record a real session

Install using `tools/dev/install-mlkit-device.sh` and wait for READY first. Keep
exactly one authorized device connected. In the repository root run:

```bash
python3 tools/dev/profile-device.py --output reports/device-profile-2026-10-05-run1
```

The output directory must not exist. The script only reads the phone; it does not
launch, stop, clear data, simulate speech, or change power/thermal settings.
It writes local private logs (directory mode 0700); use synthetic speech only.
Do not publish raw logs or device identifiers without reviewing/redacting them.

1. Note exact app/build, model versions, phone SKU/SoC/RAM, Android version,
   ambient conditions, battery %, charger state and CPU/backend configuration.
   `identity.json` captures available device properties and `package.txt` installed
   version. Absent SoC properties must be confirmed manually, not guessed.
2. For a cold-process run, manually close/reopen the app and describe this action
   in initial note. A process restart is **not** a cold filesystem/page-cache test.
   Capture startup memory separately while loading if required; the snapshots
   are not a peak sampler. Never infer peak memory from settled PSS alone.
3. Complete 100 turns, alternating VI→EN and EN→VI with a fixed recorded sentence
   list and approximately fixed pauses. Press Enter in terminal after each turn;
   enter direction, success/error, expected/observed safety outcome, and state.
   Do not label failed turns successful or omit them. `q` preserves partial data.
4. Each snapshot includes UTC time, App Summary PSS (KiB), Java/native heap PSS,
   raw meminfo (including heap allocation where OS exposes it), battery, thermal
   and PID-scoped Logcat. Missing fields are blank, not zero. Thermal services may
   be unavailable. Battery temperature commonly uses tenths °C; retain raw values.
   Logcat is bounded to 1000 buffered records, may overlap snapshots and is not a
   complete trace. Check backend records explicitly; no record ≠ NPU execution.
5. Repeat a warm run in a different output directory. Compare settled warm PSS
   trend, direction-switch behavior, failures and restart/PID changes. Inspect
   startup logs separately for actual ORT backend. A12 compatibility evidence must
   remain separate from the target Snapdragon 8 device and generation.

USB charging changes battery/thermal behavior. Do not report USB-connected battery
% change as isolated app energy consumption; use a controlled power setup for that.
This script does not derive inference latency from wall-clock snapshot spacing.

## Manual failure acceptance (separate runs)

The isolated Room migration test can run on an authorized dedicated test device:

```bash
./gradlew :android:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voxbridge.store.LearningMigrationTest
```

This installs the debug/test APKs and uses only `learning-migration-test.db`, not
the user's learning database. It does not run model inference. Host coverage in
`python3 scripts/test_learning_migration_sql.py` executes the actual migration SQL
and checks Room's exported schema; it is not an Android/SQLCipher runtime test.

Only on a dedicated test installation: deny microphone permission; interrupt
initial model download; retry with Wi-Fi restored; background/foreground during
capture and synthesis; switch languages repeatedly; test silence and repaired
output confirmation. Record expected vs observed behavior, logs and recovery.
Offline acceptance is performed after ML Kit has downloaded its models. Do not
delete the user's model cache/learning data to manufacture failures. No failure
injection or airplane-mode test is executed by the profiling script.

No automatic pass/fail is declared: approve memory/latency thresholds using a real
baseline, and resolve ASR safety holdout failures with independently labeled
evidence. Performance improvements cannot certify safety-critical use.
