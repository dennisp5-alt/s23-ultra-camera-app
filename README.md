# AutoPhoto AI Lab v0.3.7 — Offline person-semantic masked enhancement

Separate installable experiment (package `com.example.autophoto.alternatives.v037`), branching from known-good v0.3.6. Prior versions and the original Auto pipeline are not modified.

### All five correction modes
- **Auto:** unchanged.
- **MobileIE:** original pretrained low-light TensorFlow Lite pipeline, unchanged.
- **Learned 3D LUT:** original pretrained ONNX classifier and learned LUT basis, unchanged.
- **Adaptive Masked AI:** v0.3.6 confidence-weighted multiple-mask algorithm, unchanged when this button is used.
- **Semantic Person AI (new):** adds Google MediaPipe general person segmentation model (256×256 float32, pretrained) to the previous two real neural output engines. A person-confidence mask differentiates reliable human foreground from background. Uses modest, exposure-only person-local brightening, avoids skin-hue editing, preserves boundaries and texture and maintains original-image QC. When detection is not confident, reports "no confident person mask" and proceeds with the existing tonal workflow plus conservative scene-proxy corrections.

### Limitations and honesty
- **Only people** are genuinely semantically segmented by an additional neural network. The person's mask is optimized for selfies and medium-distance people: small groups and partially obscured subjects may not be identified; app reports the outcome. The model's *presence* in the APK does not guarantee successful segmentation on this particular photo.
- **Sky, foliage, water and haze** are **not** separate pretrained segmentation classes. These are explicitly conservative estimates from colour, luminance, texture and image location, not reliable object recognition. Water-like low-contrast zones receive a small local contrast adjustment rather than aggressive brightness lift; this is **not learned dehazing or texture reconstruction**.
- Two original pretrained engines run once each. Person inference adds another small model and may take extra seconds but not minutes on most supported devices; this is not yet benchmarked on an actual Galaxy S23 Ultra.
- The new segmentation is used **only in the Semantic Person AI button**. The existing v0.3.6 masked mode should behave the same as previously.

### Sources and offline handling
The official Google MediaPipe `selfie_segmentation.tflite` model is downloaded **at CI build time only** from `https://storage.googleapis.com/mediapipe-assets/selfie_segmentation.tflite`, with tensor dimensions and format checked before APK compilation. This pretrained model and the existing official models are bundled inside the Android APK. The installed application uses no network connection.
- MediaPipe: https://github.com/google-ai-edge/mediapipe
- MobileIE: https://github.com/AVC2-UESTC/MobileIE
- Learned LUT: https://github.com/HuiZeng/Image-Adaptive-3DLUT

### Quality tests
Use the same rose, portrait, and landscape images to compare v0.3.6 masked vs v0.3.7 semantic. Read the displayed person-mask status, coverage and processing time. Observe natural skin texture, sky brightness, lower-water haze and foliage colour; do not infer model correctness from brightening alone.
