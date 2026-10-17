# AutoPhoto AI Lab v0.3.4 — Natural photo and highlight refinement

**Separate experimental application** for Samsung Galaxy S23 Ultra. Android application ID `com.example.autophoto.alternatives.v034` installs alongside AutoPhoto AI Lab v0.3.3, AutoPhoto Full AI/MIRNet and the existing camera app. No modifications to `MainActivity.autoCorrect()` or `ImageProcessor.correct()`—the working Auto correction remains unchanged.

## Modes

- **Auto:** Completely unchanged from v0.3.3.
- **MobileIE AI:** Retains official pretrained ICCV 2025 `lolv1.tflite` and the same 768px neural guidance; now detects dark-background / bright-subject scenes, limits darkness and colour shifts, and protects pink highlights. True dark photos retain stronger learned enhancement.
- **Learned 3D LUT:** Retains the official pretrained Image-Adaptive-3DLUT sRGB ONNX classifier and three 33^3 learned LUT bases. Uses the original full-resolution trilinear interpolation but protects black levels, highlights, pink colours and near-clipping channels; a **genuine image-specific AI-predicted LUT** is still applied.
- Both modes report neural inference and total processing time.

## QA, measurements and limitations

The supplied 1536×1152 pink rose JPEGs informed the corrections. At v0.3.3, MobileIE delivered a darker warmer photograph and the learned 3D LUT version lifted black backgrounds too much. The v0.3.4 blending changes address these effects conservatively without changing the actual model inference workload or input resolution. They **have not been validated on the phone** yet; outcome must be compared using identical source photos.

Machine learning models are trained for low-light/retouching datasets rather than custom Samsung rendering; image quality cannot be guaranteed. Their neural model weights are downloaded at **CI build time only** and packed into the offline APK, and the installed app makes no network calls.

Official model repositories:
- https://github.com/AVC2-UESTC/MobileIE
- https://github.com/HuiZeng/Image-Adaptive-3DLUT

Both projects supply Apache-2.0 licensed source/model assets.
