# AutoPhoto Quality Lab v0.4.0 — Offline Edge-Aware Local Detail Experiment

**Research milestone:** This is the first practical experiment from the [open-source research checkpoint](https://github.com/dennisp5-alt/s23-ultra-camera-app/blob/autophoto-quality-research-checkpoint/docs/AUTOPHOTO_OPEN_SOURCE_QUALITY_RESEARCH_CHECKPOINT.md). It is **not** a replacement for existing working builds.

### Isolation and regression safety
- Based on the confirmed, published v0.3.9.1 source (commit `bf678ad14263c0e6baddaf0851cb938fdaf8a684`).
- Independent application ID `com.example.autophoto.qualitylab.v040`: installs alongside v0.3.9.1, v0.3.8, v0.3.6 and earlier builds.
- The source for Auto, MobileIE, Learned 3D LUT, Adaptive Masked AI, Semantic Person AI and Scene Semantic AI is left untouched. Each original button retains its existing action.
- Includes all current pretrained model assets and the existing offline neural backends; **Local Detail Lab** itself is a lightweight local, non-neural image-processing experiment.

### New "Local Detail Lab (v0.4.0)" processing path
Original Kotlin implementation **inspired by** OpenCV-family concepts, without copying external code or adding OpenCV native dependencies:
1. Sample the input at up to 1024px long edge. Build a 5×5 *edge-aware bilateral luminance base* and local luminance variation map; identify actual mid-scale texture.
2. Interpolate continuous detail-confidence masks at native photo resolution; avoid bright skies and naturally out-of-focus/bokeh areas.
3. Use a bounded original-resolution 3×3 range-guided luminance filter to refine only plausible existing fine detail. Limit high-contrast edges to reduce halos.
4. Protect strong red/pink saturation (especially bottlebrush/rose), skin-like warm hues, deep shadows, highlights, and already clipped channels. Apply a small scalar luminance correction equally to R/G/B; do not deliberately shift hue or resaturate colours.
5. Limit local luminance change to at most ±6 levels on a 0–255 scale. Never upscale, perform neural super-resolution, synthesize details or pretend blurred pixels are sharp.
6. Report full-resolution processing duration, changed-pixel percentage, average absolute luminance change and texture-mask coverage. Keep this diagnostic visible while comparing and saving.

This is **quality evaluation**, not a claim that changing fewer pixels always improves photographs. Reject the effect if it exaggerates noise, creates false detail, alters skin texture or sharpens natural bokeh. There is **no automatic CLAHE**, which can visibly amplify noise and haze. Separate stronger local-contrast proposals should be A/B tested later if this path proves useful.

### Regression suite
- Red bottlebrush — preserve hundreds of filaments, red highlights and soft bokeh.
- Pink rose — protect petal folds, pink/lavender colour and dark backgrounds.
- Close portrait — skin texture and dark hair, with no crunchy sharpening.
- Park landscape — grass, brickwork, distant people and ducks, with subtle *real* water texture correction.
- Add dark/noisy photographs before integrating any stronger denoiser.

### Open-source references to investigate (not bundled here)
- [OpenCV image processing](https://github.com/opencv/opencv), Apache-2.0
- [Advanced Image Enhancer](https://github.com/Shega-PT/advanced-image-enhancer), MIT
- [NAFNet](https://github.com/megvii-research/NAFNet), MIT source, *later isolated experiment*

### Pending
The v0.3.9.1 Scene Semantic AI device-specific ACTIVE/FALLBACK status remains unconfirmed in this research branch. The new Local Detail button does not depend on or change scene segmentation.
