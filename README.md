# Pixel Repair Lab v0.3.0 (Standalone, offline experiment)

This build is designed specifically to evaluate the v0.2 Shadow Lab heatmap test on the Toowoomba foliage/sky image. Does NOT change Photo Master AI or the main camera branch.

## Safety changes
- Shadow Lab uses a tighter darkness gate and **5x5 second-ring luminance/chroma structure guard**. This rejects heavily textured foliage/grass, roof patterns and high-contrast colour variation.
- Deep mode performs **chroma-only** cleanup; unlike v0.2 it does not adjust shadow luminance. Real image detail should be less vulnerable to smoothing.
- Balanced and Defects Only remain available.
- The Edits view is now **black-background diagnostic heatmap**, not a tinted photograph. Cyan = weak/sparse edits, yellow = moderate, magenta = strong/dense edits. This is a *visualization of changed RGB values*, not proof of quality.
- The diagnostic newline is fixed.
- Unit tests cover shadow-chroma cleanup, unmodified daylight, sharp edges, natural texture, and no-op at sensitivity zero.

## Installation / output
Experimental app ID `com.dennis.pixelrepair.labv3`, so v0.2 and v0.1 may remain installed side by side. Full-resolution PNG export without modifying original. Camera EXIF metadata not copied.

Built by GitHub Actions from `pixel-repair-lab`; direct APK and raw source-branch mirror named `Pixel-Repair-Lab-v0.3.0.apk`.

Image repairs are deterministic experiments, NOT AI reconstruction. Blur, blocked JPEG detail and lost source data are not recoverable with this engine.
