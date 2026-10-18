# AutoPhoto AI Lab v0.3.8 — Fixed Offline Person Segmentation

This is a **separate experimental Android application** for the Samsung Galaxy S23 Ultra. Package `com.example.autophoto.alternatives.v038` installs alongside previous v0.3.7/v0.3.6 and all existing Auto and camera apps.

## Root cause fixed
v0.3.7 downloaded the official raw MediaPipe `selfie_segmentation.tflite` and attempted to load it with TensorFlow Lite's generic Java Interpreter. That model contains `Convolution2DTransposeBias`, a **custom operator** which is registered in MediaPipe's custom-op resolver but unavailable to generic TensorFlow Lite Java. The original build verified the *shape and packaging* of the model, not that Android could execute it. Consequently v0.3.7 showed "Person model unavailable — tonal fallback" in every test, irrespective of the size of the person.

v0.3.8 uses the actual bundled `com.google.mlkit:segmentation-selfie:16.0.0-beta6` Android SDK with `SINGLE_IMAGE_MODE`. This includes a compatible offline person segmentation model and runtime **within the installed APK**, rather than downloading a model at runtime. The Android manifest does not request Internet permission.

## Processing
- **Auto, MobileIE, Learned 3D LUT and Adaptive Masked AI:** v0.3.6 implementations maintained unchanged.
- **Semantic Person AI:** Uses ML Kit's local trained model for soft person/background segmentation and the existing v0.3.7 semantic-aware masked blending and gentle person-specific exposure enhancement. Subtle conservative colour-and-tone proxies for sky/water-like regions remain.
- **Fallback:** When the segmenter returns insufficient confidence, shows "No confident person detected" rather than "model unavailable." If it actually throws, show the precise failure stage and exception in the app's status for diagnostic follow-up.
- **Performance:** Reports total processing time, segmentation time and the model's person-pixel coverage, when detected.

This is **not** complete scene segmentation. Distant people, partly obscured people and unusual angles may not be detected. Grass, sky and water are still handled by conservative colour/tone/texture heuristics. Avoid claiming pixel-perfect sky/water masks or restoration of missing detail.

Test first on the supplied close portrait, then the smaller people in the park landscape. If the close portrait reports an error, provide a screenshot of the new detailed status line. The compiled APK validates code and dependencies; **real on-device inference still requires user testing**.

Official implementation: https://developers.google.com/ml-kit/vision/selfie-segmentation/android
