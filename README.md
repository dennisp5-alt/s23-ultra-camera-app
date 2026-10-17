# AutoPhoto S23 Full AI v0.3.2 — GPU-first performance test

Keeps **Auto processing exactly unchanged** and preserves Full AI v0.3.1 tone, rose-highlight protection, colour masks, and 400-pixel overlapping MIRNet processing. The model remains bundled and fully offline.

New performance path:
- Attempts TensorFlow Lite GPU delegation on compatible Android hardware.
- Creates the GPU delegate and performs *all inference on the same background worker thread*.
- Requests highest GPU precision rather than permitting fp16 compute precision loss.
- If GPU setup or inference is unsupported, safely cleans up, discards partial predictions and re-runs the *complete* image with 4-thread XNNPACK CPU inference.
- Shows **GPU** or **CPU (XNNPACK)** in the processing status and displays elapsed seconds at completion.

**Important:** GPU support and performance are model/device dependent; the Galaxy S23 Ultra may still use CPU if MIRNet contains operations that cannot run efficiently on the GPU. This is a diagnostic and acceleration experiment, not a guarantee of a large speedup. The initial GPU setup may be slower.

No changes to input decoding, work resolution, overlap, image sharpening, luminance/chroma correction limits, or JPEG saving.
