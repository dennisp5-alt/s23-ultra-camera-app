# AutoPhoto Quality Experiment v0.4.7 — October 2026

This release is built in an **independently installable Android app** on branch `autophoto-quality-preserving-v047`, starting from the approved v0.4.6 Polished build (`a00bce9ad2c44606cb7f9bbf00a83ae4de3ad47c`).

### Changes
- **Never edit PolishedAIProcessor.kt, AlternativeAIProcessor.kt, or other enhancement algorithms.** v0.4.6 colour/tone/detail and ONNX inference remain exactly unchanged.
- Add **opt-in High-res import** checkbox. Standard (default) remains 4096px long-side exactly as v0.4.6. On sufficient heap, opt-in caps decoded image to a maximum 5120px long-side and 20 million pixels; on 384-511 MiB heaps max 4608px/16MP; lower-memory devices stay at 4096px. **Never attempt native 50 or 200MP full-frame inference in this experimental build.**
- When checking/unchecking while a photo is loaded, reload original from picker; prior processed result is cleared to avoid mismatched preview.
- Try 4096px fallback if a high-resolution decode raises OutOfMemoryError. Processor-stage memory still requires practical on-device testing.
- Preserve useful **non-sensitive** EXIF from original photo when saving JPEG (date/time, make/model/lens, ISO/shutter/focal/exposure information when available). Normalize orientation=1 and update EXIF output pixel dimensions. *Never transfer original GPS, camera serial, MakerNote, thumbnails, owner/artist names, user comments, proprietary metadata.*
- JPEG encoding remains quality 98 and sRGB. The source photo is never modified. EXIF writing does not invoke an image re-encode. Existing auto correction engines remain hidden and unchanged.
- This version requests a larger Android application heap; it is a test variant **only** and does not prove that all large images will process reliably.

### Acceptance
1. Install next to v0.4.6, with distinct launcher label. Default Polished selected. High-res unchecked. Only Polished and Polished+ visible.
2. Reprocess **exact same untouched 12MP original** in v0.4.6 and v0.4.7 (standard). At matched JPEG full-res pixels output should be visually unchanged because the processing files are byte-identical.
3. Open v0.4.7 saved JPEG and confirm useful capture EXIF is retained where provided; EXIF Orientation=1; no GPS metadata. Image must remain readable.
4. On a 50MP or 200MP source, compare v0.4.7 standard and optional high-res dimensions, temperature, time and memory reliability; no upscaling claims. Compare 100% detail and colour against source and v0.4.6.
5. If high-res crashes or exhibits detail artefacts, keep it unchecked and continue using v0.4.6 as the approved version.
6. For a P3 Samsung JPEG, verify sRGB export appearance remains approved. Wide-gamut preservation is a **separate future experiment**, not included.

### Limitations and rollback
EXIF sources without accessible metadata (some Android picker providers or heavily stripped images) save a valid JPEG without EXIF fields. Metadata is intentionally privacy-filtered. Some third-party tools might display a different creation date based on MediaStore rather than capture EXIF. The user's stable v0.4.6 APK, GitHub branch, release, other camera apps and existing photos are **never modified**.

To revert to approved look use existing v0.4.6 release. Do not merge this experimental branch until real S23 Ultra quality/memory/export checks pass.
