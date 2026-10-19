# AutoPhoto Polished Preferred v0.4.6 — handover and rollback

Source branch: `autophoto-polished-selective-v045` commit `018915e08f7c4043cb128d2886e0a923cb3d70ca`. New branch: `autophoto-polished-preferred-v046`.

## Motivation
The October 2026 bottlebrush, portrait and sunset landscape A/B trials all preferred **Polished** over Polished+ and Wow. The safest useful release is a lean interface selecting the proven finish automatically without changing the image processing code.

## Exact changes
- `PolishedAIProcessor.kt` is NOT MODIFIED in this branch. The Polished (original v0.4.3) and experimental Polished+/Wow implementations remain byte-for-byte intact.
- `MainActivity.kt`: `polishedStyle` now defaults to `Style.POLISHED`; the two visible finishing choices are **Polished** and **Polished+**.
- The **Wow button is constructed but not attached to the screen**. No experimental algorithm is removed, making later comparisons or reintroduction possible.
- Open/save, status, touch-to-compare, offline ONNX LUT inference, 4096px sRGB decoding, and JPEG saving remain unchanged.
- New package ID `com.example.autophoto.polished.preferred.v046` and launcher label `AutoPhoto Polished 0.4.6` distinguish this release and allow installation beside v0.4.5 and earlier APKs.
- Dedicated branch / tag `autophoto-polished-preferred-v0.4.6`; older branches, tags, releases and the standalone camera app are untouched.

## Acceptance on Samsung Galaxy S23 Ultra
1. Install alongside v0.4.5; launcher shows AutoPhoto Polished 0.4.6.
2. Open sample photograph and confirm **Polished** shows a checkmark before any manual selection.
3. Only Polished and Polished+ are visible in finish row; Wow hidden.
4. Process original with default Polished and compare exported JPEG against v0.4.5 Polished from same original — match expected (save re-encoding may affect bytes but visual pipeline identical).
5. Verify choosing Polished+ still works, run/save works, and re-opening photos updates status to the currently chosen preset.
6. Inspect portrait eyes/skin/hair, bottlebrush stamens, sunset grass/sky, dark photo background, contrast and highlight clipping at native zoom.

No additional enhancement strength is introduced in v0.4.6. The release is a UI/default preference checkpoint, not a newly validated photo-quality improvement.