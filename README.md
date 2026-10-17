# AutoPhoto AI Lab v0.3.3 — MobileIE vs pretrained Adaptive 3D LUT

An **independent**, completely offline Android testing app for Galaxy S23 Ultra and similar phones. App ID: `com.example.autophoto.alternatives`. The existing AutoPhoto, MIRNet Full AI and camera app are unchanged.

## Comparison modes
- **AUTO:** the original AutoPhoto v0.2.2 histogram and natural-colour processor, verbatim.
- **MobileIE AI:** actual trained ICCV2025 MobileIE low-light `lolv1.tflite` inference; confidence-weighted neural tone corrections transferred to the native-resolution photo with highlight/chroma safeguards.
- **Learned 3D LUT:** actual pretrained Image-Adaptive-3DLUT classifier from Hui Zeng `sRGB/classifier.pth`, exported during CI to ONNX, predicting **image-dependent weights** of the official **three pretrained 33x33x33 LUT bases**. Full-resolution RGB trilinear LUT interpolation plus protective natural-colour tone blending. Not a hand-coded/fixed LUT masquerading as AI.

All models are downloaded from official, Apache-2.0 code repos during APK build only and are included in the APK. The installed app makes no network calls and doesn't require downloading model weights.

**Quality guidance:** These were trained on other low-light/retouching datasets, not the S23 Ultra. Compare same photo in each mode; learned neural output does not guarantee improvement, and Auto may remain best on many photos. Inputs max edge 4096px; MobileIE neural guidance at max 768px while LUT correction applies at the full imported bitmap size. No invented missing highlight details. Each mode records real on-device inference and total processing times.

### Source
- MobileIE: https://github.com/AVC2-UESTC/MobileIE , original Apache 2.0 licence.
- Image-Adaptive-3DLUT: https://github.com/HuiZeng/Image-Adaptive-3DLUT , original Apache 2.0 licence.

Preserves existing icon, Photo Picker and tap-to-compare. The old MIRNet models and APKs remain separately available.
