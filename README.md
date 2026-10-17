# AutoPhoto Experiment v0.2 — highlight and natural-colour refinement

Small offline Android app: Open → Auto → Save. Tap the preview to compare original and corrected.

The photo processor samples a luminance histogram, adapts exposure mildly, protects dark and bright scene intent, applies a smooth highlight roll-off, uses neutral-only white balance, and retains RGB chroma without independent channel equalization. It cannot reconstruct details that are already fully clipped in the source.

No AI model, no internet access, no extra controls. JPEG output: 98%. Maximum input edge: 4096 pixels.

Build: Android 10+, Java 17, Gradle 8.9, AGP 8.7.3. GitHub Actions publishes the debug APK.

This experiment is on the isolated `autophoto-experiment` branch; the existing camera application's `main` branch is unchanged.
