# AutoPhoto S23 v0.2.2 — Fast Picker + Stronger Auto

Standalone Android 10+ photo correction experiment based on the natural-colour **v0.2 processing family**, with tuned brightness, shadow, contrast and highlights for preprocessed Samsung Galaxy S23 Ultra photos.

Changes:
- **Android Photo Picker** replaces the old document-picker browser. Modern system picker with recent-photo grid and quick selection; actual UI varies by Android provider.
- Stronger automatic exposure, midtone contrast and highlight shoulder, while protecting dark night photos, high-key subjects and already-clipped highlights.
- Moderate confident neutral white balance adjustment; no universal RGB histogram equalisation or forced saturation / oversharpening.
- Image selected asynchronously; preview capped at a 4096px maximum edge for stable memory on very large phone images.
- Modern adaptive camera icon, offline processing, three buttons: **Open / Auto / Save**.
- APK uses applicationId `com.example.autophoto.s23u` and version `0.2.2`.

Limits: no restoration of highlights that are fully white in the source, no RAW pipeline, and no guaranteed colourimetric accuracy without a reference target. Requires real-device image-quality testing. Existing v0.2 and aggressive v0.3 experiments are unchanged.
