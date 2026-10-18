# AutoPhoto Polished Impact v0.4.4 — Development Checkpoint

Based on Polished Only v0.4.3.1 (`ef3935848cdf684b35337c1bdde2f99f216bb911`).

- **Polished:** unchanged reference processor, same learned LUT and original finishing numbers.
- **Polished+:** distinct richer tonal depth, texture-aware dimension, selective colour boost, and gamut mapping.
- **Wow:** stronger cinematic/premium tonal separation and selective detail for visible differentiation, with the same safety masks.
- Skin, red and magenta flowers, bright single-channel highlights, green foliage, bokeh, and dark scenes protected heuristically.
- **No semantic subject detection** in the Polished route: its 'defined regions' are local texture maps, not an identified person/flower. No generative reconstruction.
- Pre-gamut 'clipping-risk pixels' remains a risk/pressure diagnostic, NOT proven clipped output.
- The interface, offline neural-LUT route, original-source-per-run semantics, previous processing engines, and saved JPEG directory are preserved.
- Distinct package `com.example.autophoto.polished.impact.v044` means it installs beside previous releases.

## Galaxy S23 Ultra acceptance tests

Use identical input JPEGs at consistent zoom in Polished, Polished+, Wow and original:
1. Red bottlebrush and pink/orange flowers — detailed stamens, smooth petals, preserved channel highlights.
2. Close portraits — realistic skin, hair separation, not plasticky or crunchy.
3. Park and water — credible greens, water reflections, depth without halos.
4. Clouds, power lines, rooftops — no bright outlines or broken edges.
5. Night sports field and fire — controlled highlights, black areas remain dark.
6. Assess visual differences at matched brightness; inspect exports, clipping-pressure percentage, battery, heat and processing time.

A successful compilation only verifies build/package integrity, not photographic quality. Preserve previous v0.4.3.1 as rollback.
