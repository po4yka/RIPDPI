# Local Android emulator pool

Use `scripts/ci/android-avd-pool.py` for local emulator tests, UI inspection,
screenshots, and acceptance runs. The wrapper holds a lease until the test
command and its process group have stopped. It exports `ANDROID_SERIAL` and
`RIPDPI_AVD_NAME` to that command. Use that serial for every device operation.

## Select and run

Install the required SDK image first. The pool uses installed images and does
not download them. Read API and ABI requirements from the selected test lane.

```sh
python3 scripts/ci/android-avd-pool.py status
python3 scripts/ci/android-avd-pool.py run \
  --api 37 --arch arm64-v8a --target google_apis --profile pixel_10_pro_xl \
  -- ./gradlew :app:connectedGithubFullDebugAndroidTest \
  -Pripdpi.localNativeAbis=arm64-v8a
```

The default pool has two slots in the shared Android AVD directory. Slots have
stable names. Compatibility includes the SDK image, ABI, image tag, and hardware
profile. A slot must also be idle and available for an exclusive lease. A busy or
incompatible pool causes the command to fail; it does not create another dated
device or replace an incompatible disk. Serialize work or explicitly remove an
idle pool slot before using a different profile.

The pool uses host-wide AVD and port locks across repository worktrees. Lock files
remain in place after use; the OS releases the lock when the owner exits. Do not
delete lock files to recover a busy lane. Active emulator processes and open AVD
files also prevent reuse and cleanup, including after an unhandled owner failure.

For an interactive investigation, run a shell under the wrapper. Finish the shell
to release the device. Run all commands that use the device inside that shell:

```sh
python3 scripts/ci/android-avd-pool.py run \
  --api 37 --arch arm64-v8a --target google_apis --profile pixel_10_pro_xl \
  -- zsh
```

Reset app state through the existing automation presets when a test needs clean
data. Acceptance can use a leased pool device whose app state is disposable.
Reset the whole device only through an explicitly temporary run.

## Temporary devices

Use a temporary device only when the test requires a different disk state or
profile that cannot use a pool slot. Supply a concrete reason:

```sh
python3 scripts/ci/android-avd-pool.py run \
  --api 37 --arch arm64-v8a --target google_apis --profile pixel_10_pro_xl \
  --temporary --reason 'Test first boot with empty app storage' \
  -- ./gradlew :app:connectedGithubFullDebugAndroidTest \
  -Pripdpi.localNativeAbis=arm64-v8a
```

The wrapper records ownership and the reason. It stops only the emulator it
started. It removes the temporary AVD after success, command failure, or a handled
termination signal. A forced host shutdown or `SIGKILL` can leave a temporary
device. Inspect and remove such devices through cleanup after they become idle.
Keep diagnostic logs outside the AVD directory before deleting it.

## Cleanup

Cleanup first reports candidates without deleting them:

```sh
python3 scripts/ci/android-avd-pool.py cleanup
python3 scripts/ci/android-avd-pool.py cleanup --apply
```

Review the candidates and ensure deletion is in the authorized scope. Cleanup
uses the same locks as runs and skips active or leased devices. It removes only
devices with valid pool ownership metadata. It preserves foreign devices and
rejects symbolic links and paths outside the selected AVD directory.

For old dated RIPDPI devices that have no ownership metadata, use the explicit
legacy path with exact names after the user authorizes their removal:

```sh
python3 scripts/ci/android-avd-pool.py cleanup --legacy \
  --names RIPDPI_Docs_20261010 RIPDPI_PlayVisual_20261010
python3 scripts/ci/android-avd-pool.py cleanup --apply --legacy \
  --names RIPDPI_Docs_20261010 RIPDPI_PlayVisual_20261010
```

No cleanup operation stops an active emulator. Preserve Gradle managed devices,
physical devices, and AVDs from other projects.

## CI

`scripts/ci/start-android-emulator.sh --fresh` is for isolated CI runners with
`CI=true`. It recreates the specified CI device. The local pool wrapper owns
local launch and stop operations; a detached CI launcher does not hold a local
command lease. `scripts/ci/stop-android-emulator.sh` resolves the specified AVD to
its serial and never uses an ambient device selection to stop another emulator.
