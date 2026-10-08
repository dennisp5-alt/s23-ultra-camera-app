# Pixel Repair Lab v0.2 — standalone offline Android experiment

Two distinct builds: v0.1 is preserved; v0.2 has application ID `com.dennis.pixelrepair.labv2` so both can remain installed side by side with different GitHub Actions debug signatures.

Features: full-resolution native pixel inspection, original v0.1 **Defects only** mode, **Balanced** conservative local shadow chroma repair, **Shadow Lab** stronger shadow colour-noise repair with selective deep-shadow luminance cleanup. Sensitivity 0 yields exact input RGB values. No generative AI and no artificial image detail. Daylight/edges receive explicit guards.

The four display modes are **Split / Original / Repaired / Edits**. Edits is an intentionally exaggerated colour highlight of where any RGB values actually changed; it does not represent the edited photo's colours. Diagnostics separately show isolated defect and broader fine-noise counts. Export is a lossless PNG, original left intact.

Limits: processing does not fix blur, defocus, JPEG blocking, large scratches or clipped detail. Metadata/EXIF are not preserved. Supports images up to 24 million pixels; memory/device performance should be tested. For small areas changed, before/after may appear identical at fit-to-screen; zoom in.

Install from GitHub release asset `pixel-repair-v0.2.0` after the GitHub Actions build and unit tests pass.
