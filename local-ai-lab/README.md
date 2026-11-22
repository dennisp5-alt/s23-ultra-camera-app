# S23 Local AI Lab v0.4.1

Experimental Android app for private, on-device language-model tests on the Galaxy S23 Ultra, using Google LiteRT-LM (`litertlm-android` 0.19.0) on CPU and GPU. Test build only: no `INTERNET` permission, no telemetry, no external inference API.

v0.4 is Milestone 1 from the technical audit: a trustworthy measuring instrument with a safe engine lifecycle.

## What changed from v0.3

- **Real measurements.** Token counts, prefill and decode rates and time to first token come from LiteRT-LM (`BenchmarkInfo`), not characters ÷ 4.
- **Controlled benchmark.**
  - *Synthetic* runs use LiteRT-LM's own `benchmark()` with exact prefill and decode token counts and a fresh engine each run, so load time is measured too.
  - *Prompt* runs use real prompts on a loaded engine, with greedy decoding, a fixed output cap, thinking off and a fresh conversation each run.
  - The Full protocol covers prefill 64, 256 and 1,024 tokens with 128 decode tokens, 2 warm-ups discarded (4 for the first length of each backend) and 10 measured runs each, then 2 prompts × 5. Synthetic and prompt runs use the same context size.
  - Each run records thermal status, headroom, battery temperature, charging state and battery current.
  - Results export as JSON or CSV, or share as a text summary.
- **Model check.** Five factual questions with thinking off. It detects the thinking Qwen3 file's template problem on LiteRT-LM 0.18.0+ (replies off topic) and any thinking text.
- **Model manifest.** Import is copy, SHA-256, fsync, then atomic move: a failed import never removes the previous model. File name, size, hash, runtime and the last check result are recorded.
- **Engine lifecycle.**
  - The engine lives in a `ViewModel` (no UI types), so rotation, theme changes and resizing no longer unload the model.
  - One operation at a time behind a mutex.
  - A reply holds the lock until native generation has finished, so unloading or closing the app mid-reply can't free memory that is still in use.
  - Stop cancels the native process and waits for it.
- **Memory attribution.**
  - Phase snapshots: idle, after load, peak during a reply (sampled every 0.5 s), after the reply and after unload.
  - Each snapshot splits into native heap, graphics, Java heap, code and other.
  - A load → reply → unload leak test (5 or 20 cycles) compares each cycle with the first after-unload reading. It passes when memory stays within 50 MB of that reading and drifts less than 5 MB per cycle. The one-time amount the first load leaves behind (native library, allocator) is reported separately.
  - The context size (`maxNumTokens`) can be chosen explicitly.
- **Thermal gate.**
  - Android's thermal status listener and headroom forecast (polled at most every 10 s, the documented limit) replace the battery-temperature-only check.
  - Runs refuse to start at "severe" status or a headroom forecast of 0.95 or more.
  - A reply is cancelled if the phone reaches "severe".
- **Fixes.** `OfflineTools` rejects temperatures below absolute zero for the given unit, and keeps dew point within the formula's −40 to 50 °C range.
- **Removed.**
  - the regex microbenchmark;
  - the single-run GPU/CPU ratio;
  - the conversation history that made later replies slower.
- **One stable app id** (`com.dennisp.s23lab`) from now on. It installs alongside v0.1–v0.3; import the model once.

## How to run the Milestone 1 checks

Run with the phone unplugged, starting cool.

1. Import the model. For benchmarks use the no-think Qwen3 0.6B file (`Qwen3-0.6B-int4` from litert-community).
2. In **02 / Engine**, choose Auto, keep context 1280, and tap **Load**.
3. In **01 / Model**, tap **Run model check**. All five answers should be on topic, with no thinking text.
4. In **04 / Benchmark**, run **Full protocol (CPU + GPU)**, about 20–40 minutes. Then **Save JSON**, or **Share summary**.
5. In **05 / Memory**, run **Profile + 20 cycles** on GPU, then again on CPU. **Save JSON** each time.
6. Rotate the phone and leave the app while a reply is running; it should neither crash nor lose the model.

## Notes

- Battery temperature is not CPU or GPU temperature. Battery current is recorded raw: its units and sign vary by manufacturer.
- "Graphics" is the GPU memory Android attributes to the app. Some driver memory may not appear in app-level figures. Confirm with `adb shell dumpsys meminfo com.dennisp.s23lab`.
- NPU is not enabled. Google lists the Snapdragon 8 Gen 2 (SM8550) for LiteRT NPU, but nothing has been verified on this phone.
- CI publishes a pre-release APK on every push: `-beta` for feature branches. With the repository secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`, builds share one key and upgrade in place. Without them, uninstall the previous v0.4 build first.

## v0.4.1 (after the first S23 Ultra run, 22 Nov 2026)

- Synthetic runs now use the context chosen in 02. In v0.4.0 they used the model's default while prompt runs used 1280, so the two kinds of run weren't comparable. Each run records its context.
- The first prompt length of each backend gets 2 extra warm-ups. On the S23 Ultra the first GPU loads took 17–24 s and decoded at about half speed.
- The leak test compares cycles with the first unload, not with idle before any load. The first S23 Ultra profile (52 MB kept after the first load, then flat at +0.4 MB per cycle) now passes, as it should.
- Summaries say how many runs were on charge.
