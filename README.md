# AutoPhoto Polished Only v0.4.3.1 — Simplified UI Experiment

**Separate Android app**, `com.example.autophoto.polished.only.v0431`, built directly from the known working Polished AI v0.4.3 code.

## Exactly the requested visible controls
- Open
- Save
- **Run Polished AI**
- Polished / Polished+ / Wow look choices
- Current photo, original/processed tap-to-compare preview, results and progress

All other mode *buttons* (Auto, MobileIE AI, Learned 3D LUT, Adaptive Masked AI, Semantic Person AI, Scene Semantic AI, Local Detail preset row, and Adaptive Strong Detail) are hidden from the UI, not deleted from the source. The original learned neural LUT inference, tonal/color finishing algorithm and all old processing engines remain unchanged.

The image preview gains the space previously taken by legacy mode controls. Photo picking and JPEG saving work as in v0.4.3. Independent package ID ensures earlier installations remain usable.

### Smoke tests
Open a landscape, night scene, rose, sports picture; select each Polished/Polished+/Wow, process, tap to compare, Save. Ensure the correct look name is shown and only desired buttons appear. Native phone runtime must still be checked after release.
