# Pixel Repair Lab — standalone Android experiment

**Version 0.1.0**. Separate offline app; application ID `com.dennis.pixelrepair`. Created in the independent `pixel-repair-lab` branch without modifying the camera or Photo Master AI apps.

## V0.1 scope
- Import photographs using Android's system image picker
- Preserve original dimensions for photographs up to 24 million pixels
- Inspect each pixel and selectively repair high-confidence isolated hot/dead pixels and colour speckles
- Adjustable sensitivity, before/after split preview and pinch-to-zoom
- Save a repaired **lossless PNG** using Android's save-document picker
- No internet, no access to other photos, no generative image synthesis, no blanket sharpening, no AI detail hallucination

**Limits:** A conservative algorithmic pixel repair experiment, not neural AI or inpainting. It cannot reliably fix heavy motion blur, large scratches, large JPEG block damage, defocus, large-area banding, etc. Processing is non-destructive; original file untouched. Image metadata/EXIF is not copied to output. Images above 24MP are rejected, not silently resized. Repeatable tests are included.

## Build
GitHub Actions workflow **Pixel Repair Lab APK** runs on pushes to the `pixel-repair-lab` branch. Download the debug APK from its build artifact. Android API 29+.
