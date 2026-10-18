# AutoPhoto AI Lab v0.3.9.1 — Persistent Scene Diagnostics

A separate offline Android APK, based on working v0.3.9. It preserves the established Auto, MobileIE, Learned 3D LUT, Adaptive Masked AI, ML Kit Semantic Person AI and SegFormer Scene Semantic AI implementations without altering their trained models or correction algorithms.

## UI fix
In v0.3.9, **before/after preview taps and Save overwrite the single status TextView**, erasing the processing report. Consequently a user could see a correct image with no visible report and we could not tell if real scene segmentation ran or fell back.

The v0.3.9.1 fix adds a dedicated, persistent, selectable diagnostics text panel immediately above the photo. After Scene Semantic AI runs, it displays **ACTIVE** with separate sky/water/people coverage (%) and both inference and total times, or **FALLBACK** with the precise ONNX failure stage/reason and measured time. The panel stays visible after comparing and saving photos, and clears when opening another picture or selecting a different processing mode.

This is a UI/diagnostic fix only: real on-device inference status on the S23 Ultra is not established until the user reports it.

### Isolation
New unique package: `com.example.autophoto.alternatives.v0391`.
Previous working v0.3.9 and all earlier experimental apps remain installable.
Direct GitHub Release APK is automatically generated on successful compilation under tag `autophoto-ai-lab-v0.3.9.1`.

The app is fully offline and all model files are embedded in the APK at build time.
