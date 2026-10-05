# Meta glasses FOV simulator

Branch: `codex/meta-glasses-simulator`.

Enable **Settings → Environment → Meta glasses FOV** during playback.
The setting defaults to Off and is remembered globally across launches.
It applies to the library, movies, subtitles, controls, and passthrough.

Each eye is masked to ±35° horizontally and ±33° vertically using its runtime
OpenXR projection tangents, including asymmetric projections. Head movement
reveals other parts of the scene. Movie size, stereo layout and camera
projection remain intact. Four scissored opaque-black clears cover the outside
area after all content is rendered; no additional scene rendering, readback or
shader is required. Scissor state is restored before submitting the eye.

This simulates the nominal 70° × 66° angular window, to pixel rounding. It does
not simulate optical distortion, lens border shape, fit, eye movement, display
resolution, or the complete Meta device. The reported 84° diagonal cannot be
reproduced simultaneously by a rectangular 70° × 66° rectilinear window;
Meta does not publish an optical aperture shape here, so no invented shape is
used. A headset with a smaller native FOV cannot reveal the missing area.

Sources:
- https://developers.meta.com/vr/essentials/field-of-view/
- https://av.watch.impress.co.jp/docs/series/rt/2142663.html

Headset checks: enable during playback, turn your head, open every popup,
return to Library, restart to check persistence, and disable to restore the
full headset FOV. Repeat in passthrough to confirm opaque black borders.
