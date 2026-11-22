# S23 Local AI Lab v0.2 (experimental)
Private, standalone Android project. Production camera and SEQ weather apps are not modified.

What's new:
- Uses the generated neon phone + AI chip + privacy-shield artwork as its launcher icon.
- Improves the dark interface heading and text contrast.
- Measures LiteRT-LM model load time, first nonempty text output, completion time and approximate output tokens/s.
- Records CPU and GPU results separately, shows an approximate throughput ratio when the same prompt was tested.
- Automatically routes exact validated calculations to Kotlin tools rather than the AI model.
- Shows process PSS, available system RAM, battery percent and battery temperature. Does not claim chip temperature or app-specific power draw.
- No INTERNET permission, analytics, model uploads, microphone or messaging access.
- Existing model in app-private storage is preserved during an install-over update signed with the same debug key.

Important: estimated tokens/s uses output characters divided by four, not tokenizer counts. CPU 12,000-call microbenchmark is separate from AI inference. GPU backend may be device/driver dependent. Snapdragon NPU is not enabled.

Test: install v0.2, check imported model remains available, run same prompt in CPU and GPU, compare section 04. Try the exact prompt `15% of 240` for deterministic routing.
