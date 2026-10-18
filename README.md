# AutoPhoto AI Lab v0.3.9 — Offline Sky / Water / Person Segmentation

This is a **separate experimental** offline Android app for the Samsung Galaxy S23 Ultra, using app ID `com.example.autophoto.alternatives.v039`. It installs alongside all earlier variants, especially the known-good v0.3.8 and v0.3.6.

## All existing modes retained
- Auto: exact known-good processing, unchanged.
- MobileIE: existing real TensorFlow Lite low-light inference, unchanged.
- Learned 3D LUT: existing real ONNX scene-colour model plus trained 3D LUT bases, unchanged.
- Adaptive Masked AI: original confidence-weighted tonal mask passes, unchanged.
- Semantic Person AI: existing working v0.3.8 bundled ML Kit person segmentation, unchanged.

## NEW: Scene Semantic AI (v0.3.9)
Runs a **separate, genuinely pretrained** SegFormer B2 segmentation ONNX model, classes 0=background, 1=sky, 2=water, 3=person. On Android the float32 ONNX model runs in ONNX Runtime CPU, without a network connection. The official model is trained on ADE20K and released by Vincent Qin / Realcat as MIT licensed:

- Source: https://github.com/Vincentqyw/skywater_seg
- Weights: https://huggingface.co/Realcat/skywater_seg
- Exact model: `skywater_segformer_b2_fp32.onnx` (~99 MB)

The model is downloaded **only by GitHub Actions at APK build time**. Our build pipeline installs ONNX Runtime Python and performs real CPU inference on the trained model to validate shape and operator compatibility before packaging. In-app ONNX Runtime Android uses the same pretrained ONNX weights via its CPU backend.

On-device processing:
1. Download-free, offline four-class segmentation at 384×384, softmax confidence masks for sky/water/person.
2. Genuine MobileIE and pretrained image-adaptive LUT models run once.
3. Scene masks guide the existing confidence-weighted feathered regional corrections. Sky brightness is guarded; water is not indiscriminately brightened; any water local-contrast boost is minimal and is based on existing recorded detail. Person pixels can receive restrained exposure correction while retaining original colours.
4. Full-resolution second-pass quality check rejects excessive changes.
5. Display actual processing time, segmentation time, and mask coverage for sky/water/people.

## Cautions
The segmentation network is more complex than our two small AI engines and may add significant CPU processing time on the Galaxy S23 Ultra. Its published benchmark speed is from a desktop/GPU environment, **not** the S23 Ultra. The model does not label grass, trees, buildings or animals; green foliage remains heuristic. Scene detection may fail, especially for distant people. If ONNX initialization or segmentation fails, the new mode reports the reason and falls back to existing tonal masks. It never attempts to generate missing image content or extract impossible details from haze.

Test with the supplied portrait (close subject), landscape (distant people, pond water, pale sky), and rose photos; compare v0.3.9 with v0.3.8 and v0.3.6 rather than assuming the extra model must improve every image.
