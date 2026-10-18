# AutoPhoto AI Lab v0.3.5 — Offline masked multi-pass neural enhancement

A separate installable Android experiment for the Samsung Galaxy S23 Ultra. App ID `com.example.autophoto.alternatives.v035`. The original Auto mode, existing v0.3.3 and v0.3.4 AI Lab apps, MIRNet Full AI and main camera app are preserved.

## Processing modes

- **Auto**: The known-good existing histogram/colour routine, completely unchanged.
- **MobileIE**: Original trained `lolv1.tflite`, v0.3.4 natural-colour safeguards, unchanged.
- **Learned 3D LUT**: Original pretrained classifier exported as ONNX and three learned 33×33×33 LUT bases; v0.3.4 safeguards, unchanged.
- **Masked Multi-pass AI** (NEW): runs each genuine pretrained neural engine **once**, then builds multiple differentiable masks from the same original image:
  1. Neural enhancement predictions — infer MobileIE and the per-photo learned adaptive LUT once each
  2. Build soft luminance, shadow, highlight, magenta/pink colour-family, green foliage-colour-family, and local-contrast boundary masks on the original photo downsampled to ≤640px long edge
  3. Apply joint-luminance/edge-aware 3×3 filtering to the AI blend weight masks; bilinearly interpolate them at full output resolution
  4. Blend the two AI predictions differently in each local region, with final shadow, highlight, colour-shift and clipping protection at original decoded resolution

This is **tone/colour based regional masking**, *not* semantic/AI object segmentation. For example, a pink colour-range mask may also cover similarly coloured surfaces; it does not identify a rose as an object.

## Quality and speed

- Proven fast independent inference engines from v0.3.4 are retained, but masked mode invokes both once, so expect more runtime than either single engine and less than MIRNet if the phone behaves as before.
- The new masks avoid abrupt tile/region boundaries and do not re-run inference for every region; there are additional pixel-composition and filter passes.
- The mask engine does not invent lost detail, reconstruct saturated pixels, perform generative edits, or guarantee higher quality.
- Reports total processing time plus both model inference times after processing. Compare original, Auto, the two AI modes, and Masked AI on the **same source JPEG**, especially the pink rose, daytime scenes, darker photos, skin tones, foliage and bright skies.

## Sources

- [MobileIE, ICCV 2025](https://github.com/AVC2-UESTC/MobileIE)
- [Image-Adaptive 3DLUT](https://github.com/HuiZeng/Image-Adaptive-3DLUT)

Both models are downloaded only by GitHub Actions at build time and included offline within the APK. The app does not require internet access.
