# AutoPhoto Auto Profile v0.4.8 — Experimental checkpoint

Built from v0.4.7 `3469ddec0f3b0075c2e60faf4e16735d2a40dea3`. The approved v0.4.6 Polished processor and trained LUT are unchanged.

## Processing and colour management
- Source colour space detected in ImageDecoder's header **before** forcing sRGB for the approved Polished pipeline.
- sRGB camera photos (or untagged, assumed sRGB) follow the existing approved sRGB output path, JPEG quality 98 and GPS-free EXIF.
- Display P3 camera originals up to 12.6MP with longest edge <=4096: P3 decoded copy, compare to sRGB decoded source, recover out-of-sRGB chroma in Display P3. The approved sRGB Polished output is physically converted to P3 first; source chroma outside sRGB is reintroduced without altering the Polished luminance. Strict P3 gamut fitting avoids independently clipped channels.
- Before save, require JPEG APP2 ICC_PROFILE and decoded P3 recognition. After EXIF saving, re-verify P3 output; otherwise overwrite with approved sRGB pixels. Never simply relabel sRGB pixels as P3.
- Other wide-gamut/unsupported RGB profiles correctly convert to sRGB, not falsely to P3. Full wide-gamut neural processing is NOT claimed: the unchanged neural LUT remains trained for sRGB.
- Hidden previous 50/200MP high-resolution toggle. Default import limit 4096px remains.
- Separate package `com.example.autophoto.profile.v048`. All existing releases remain.

## Acceptance on Samsung Galaxy S23 Ultra
1. Open untouched Samsung 12MP P3 JPEG and separate sRGB camera JPEG. Confirm detected profile label is appropriate.
2. On sRGB input, output remains valid sRGB JPEG and matches v0.4.6 Polished appearance.
3. On P3 input, output reports P3 ONLY after embedded ICC + decoded P3 profile verification. If device encoding or image provider cannot supply P3, a clearly reported sRGB fallback is correct.
4. Validate P3 export ICC independently with ExifTool or Pillow/ImageCms from the actual saved photo. Compare 100%-pixel crops of skin, saturated red flowers, sky, foliage in a colour-managed viewer; no artificial hue shift or false clipping.
5. Check original capture EXIF remains while GPS, serial/MakerNote and private fields are omitted; orientation normalized.
6. Confirm memory/processing time, preview and Gallery interoperability.
7. No merge into protected v0.4.6 branch without real-device tests and good photographic quality.

Approved rollback: `autophoto-polished-preferred-v046`, released v0.4.6. Build compilation/static assertions alone cannot prove native P3 JPEG quality.
