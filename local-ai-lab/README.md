# S23 Local AI Lab — experimental branch
Standalone Android app inside local-ai-lab/, separate from the existing camera app. No edits to production files. No INTERNET permission or telemetry. Inspired by NVIDIA CPU-ARC, but not a reproduction of that research.
Tools: parameterised conversions, percentages, approximate dew point and photographic exposure; CPU benchmark of repeated routines; optional LiteRT-LM 0.19.0 on-device chat with CPU/GPU.
Recommended model: https://huggingface.co/litert-community/Qwen3-0.6B. Download Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm (328 MiB) in your browser, import using the in-app document picker, then Load CPU. The GPU path requires runtime 0.19.0+. No NPU accelerator enabled.
Build using the GitHub Actions artifact from the experimental branch. Do not merge into main without review. CPU benchmarks are NOT LLM/NPU benchmarks; never use the dew point example for warnings.
