# S23 Local AI Lab v0.3

Standalone Android CPU/GPU on-device AI test. Installs alongside v0.1 and v0.2 to protect data from previous debug-signing keys; the model must be re-imported. Contains bounded repeat testing and native resource cleanup.

The CPU/GPU benchmark runs a fixed prompt in a fresh conversation for each sample. Median reported estimated tokens/s uses characters / 4, not actual model token counts. The optional 2-minute endurance loop runs at most 20 prompts, tests available RAM and battery temperature, and can be stopped after its current prompt. Android battery sensor temperature is not GPU/CPU temperature. App PSS includes shared/native allocation; it is not just neural model memory.

The preferred startup path attempts GPU first and falls back to CPU if GPU initialization fails. A separate NPU accelerator is not bundled because Snapdragon-specific LiteRT-LM compiled models and libraries must be verified. See https://developers.google.com/edge/litert-lm/android

No INTERNET permission, external inference API, telemetry, remote code execution or data transmission. App is a test build only.
