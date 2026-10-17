# AutoPhoto S23 v0.2.1 — Smartphone HDR-aware
Standalone Android 10+ experiment, using v0.2 as its base (NOT the deliberately extreme v0.3).

- Modest global sRGB tone correction for already-processed phone JPEG/HEIC.
- Scene-aware histogram with dark/high-key protection, controlled highlight shoulder, subdued midtone contrast.
- Confidence-checked near-neutral white balance, gently tapered near highlights.
- Gamut-limited chroma preservation; no added sharpening or vibrance.
- Input preview max edge 4096 pixels; original remains untouched.
- Automatic JPG output to Pictures/AutoPhoto at quality 98.
- Three controls: Open / Auto / Save; tap photo to compare.
- Dedicated app ID `com.example.autophoto.s23u` and custom adaptive camera icon.

**Limits:** Not a RAW editor, cannot recreate channel detail clipped in the source, and cannot guarantee accurate colour without a colour reference. Tune on real S23 Ultra samples before claiming superior results.

Isolated `autophoto-s23u-v02` branch derived from v0.2. No changes to main camera app.
