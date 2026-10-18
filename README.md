# AutoPhoto AI Lab v0.3.6 — Adaptive Masked Refinement

This is a new **separately installable offline Android experiment** for Samsung Galaxy S23 Ultra.

**Distinct package:** `com.example.autophoto.alternatives.v036`; all v0.3.5 and previous APKs remain installable. Auto, independent MobileIE and independent Learned 3D LUT are preserved unchanged from the working v0.3.5 source.

## New Adaptive Masked AI mode

1. **Neural predictions:** Perform actual pretrained MobileIE low-light TensorFlow Lite inference once, and actual pretrained image-adaptive 3D-LUT ONNX classification and learned LUT interpolation once. Model weights and their input resolutions remain unchanged.
2. **Regional analysis:** Compare original and both real neural output images on a 640px analysis grid; build overlapping *soft tonal and colour* masks for deep shadows, bright highlights, pink/sensitive warm tones, green foliage-like colours and local contrast boundaries. These are NOT object labels or semantic person/flower segmentation.
3. **Confidence-weighted selection:** The less plausible an engine's colour/brightness shift, the less it influences that area. Model agreement, dark-background/bright-subject identification, local gradients, and scene exposure adjust the mobile/LUT mixture per region. The source image remains the conservative reference.
4. **Two-pass edge-aware feathering:** smooth model weights along tonal regions to prevent sharp-edged masks while reducing excessive bleeding across contrast boundaries.
5. **Native-size first blend:** combine the model outputs at photo resolution, with shadow, clipping, colour-shift and highlight safeguards.
6. **Quality-control second pass:** compare preliminary output against original again; reduce potentially unnatural tone shifts in deep-black regions, highlights and strong detail boundaries; protect the original RGB texture rather than performing aggressive sharpening.

The application shows timing for both neural inferences and total duration. The regional masking and QC passes will add some processing time compared with v0.3.5, intentionally prioritizing natural photographic quality.

## What this version does *not* do

No semantic object segmentation or object-specific AI recognition, no separately trained mask network, no invented scene content, and no guarantee of superior results across photographs. The existing training domain of each model is unchanged. Test on identical rose, foliage, backlit subjects, skies, and low-light photos.

Official pretrained source models are fetched during CI builds only, packed in the APK and used completely offline:
- https://github.com/AVC2-UESTC/MobileIE
- https://github.com/HuiZeng/Image-Adaptive-3DLUT
