# Parity traces: avni-client vs TANUHDemo SDK

Both codebases write the same line-by-line trace of every intermediate value for each image. Comparing
the two files shows, stage by stage, that the JSON-driven SDK and avni's production pipeline did exactly
the same thing.

| stage line | what it proves |
|---|---|
| `INPUT bytes sha256` | both apps received the same image file (exact bytes) |
| `DECODE w h exif rgba_sha256` | same upright decoded pixels (EXIF applied the same way) |
| `CANVAS w h bgr_sha256 mean_*` | same 256×256 letterboxed 8-bit image |
| `GRAYWORLD mean_* scale_*` | same gray-world means and scale factors |
| `TENSOR shape layout order f32_sha256 p*_mean/min/max` | the model received exactly the same input floats |
| `MODEL sha256 logit prob label` | the same model file (plaintext SHA-256) gave the same logit and label |
| `TIME stage ms` | timings, shown in the report but never compared |

The writer is `ParityTrace.kt`. The same file is in both codebases; only the package line differs:

- avni: `packages/openchs-android/android/app/src/main/java/com/openchsclient/diag/ParityTrace.kt`
- SDK: `edgesdk/src/main/java/com/tanuh/edgesdk/diag/ParityTrace.kt`

## Turning it on

| app | how | trace file on the phone |
|---|---|---|
| avni-client | build and install the **debug** variant (e.g. `tanuhDebug`). Release builds never trace. | `/sdcard/Android/data/org.tanuh.openchsclient/files/parity-traces/avni-<time>.txt` |
| TANUHDemo | `"diagnostics": { "parity_trace": true }` (on in the default config) | `/sdcard/Android/data/com.tanuh.demo/files/parity-traces/sdk-<time>.txt` |

A new file starts at each app launch. Every line is also written to logcat with the tag `ParityTrace`.

## Running a comparison

1. **avni:** capture or attach the same suspicious images in the oral-screening form, so the
   ensemble runs. avni writes one record per model call, three per image.
2. **SDK:** in TANUHDemo → SDK exploration, pick the same images → **Run**. It writes one record per
   image with all three models.
3. Pull both traces and compare them:

```bash
adb pull /sdcard/Android/data/org.tanuh.openchsclient/files/parity-traces/ avni-traces/
adb pull /sdcard/Android/data/com.tanuh.demo/files/parity-traces/ sdk-traces/
python3 tools/parity/compare_traces.py avni-traces/avni-*.txt sdk-traces/sdk-*.txt --md parity-report.md
# different files of the same photos (avni re-saved them):
python3 tools/parity/compare_traces.py avni-traces/avni-*.txt sdk-traces/sdk-*.txt --pair-by content
```

The report pairs images by `INPUT sha256` and models by `MODEL sha256`. Each stage is marked MATCH or
DIFF, with the differing values and Δ, alongside the logits per model, the combined verdict, and
average timings per stage. The exit code is 0 when everything matches.

## Getting identical input files (important)

avni's form does not keep the original photo. `MediaV2FormElement` / `GuidedCameraHelper` re-save it
with `maxWidth 1280 × maxHeight 960` (contain) by default. A 3072×3072 photo becomes a 960×960 JPEG
of ≈600 KB, stored as `/sdcard/Android/data/org.tanuh.openchsclient/files/Avni/media/images/<uuid>.jpg`,
and that smaller file is what avni's models see.

To compare the two code paths on identical input, give the SDK the files avni actually processed:

```bash
adb pull /sdcard/Android/data/org.tanuh.openchsclient/files/Avni/media/images/ avni-images/
adb shell mkdir -p /sdcard/Download/avni-images
adb push avni-images/*.jpg /sdcard/Download/avni-images/
```

Then, in TANUHDemo, **Pick images** → *Browse* → Download/avni-images → **Run**. Both traces now share
`INPUT sha256`, and the default `--pair-by hash` compares every stage.

To compare the original photos (SDK) against avni's re-saved ones, use `--pair-by content`. Images are
matched by the closest canvas colours, and the report shows how much the resizing changes each stage
and each logit.

## Matching avni's current production settings

avni's synced payload on the test phone is older than the repo's override: `resize=stretch`,
`rounding=legacy`, `uint8Round=true`, `ignoreColorProfile=false`. With the QC default config, TENSOR
therefore differs (about 5% of pixels one level apart, from half-up vs half-even rounding). To show
that the SDK reproduces avni exactly from JSON alone, load `sdk-config.avni-current.json`:

```bash
adb push app/src/main/assets/sdk-config.avni-current.json /sdcard/Android/data/com.tanuh.demo/files/sdk-config.json
```

Reopen the SDK screen (the saved config loads automatically), tap **Apply**, then run the test-images folder.

## Things that make traces differ (and what they mean)

- **INPUT differs:** the apps were not given the same file. avni's camera or attachment flow may have
  re-encoded or resized the photo (it does by default; see above). Feed the SDK avni's saved files, or
  use `--pair-by content`.
- **DECODE differs, INPUT matches:** a different decode or EXIF handling.
- **CANVAS differs:** a different resize, letterbox or colour-profile handling. avni uses
  `ignore_color_profile: true` (raw stored values for Display-P3 photos); the SDK's OpenCV path always
  reads raw pixel memory, which is equivalent.
- **TENSOR differs, CANVAS matches:** a different gray-world, rounding, uint8 or scale step.
- **MODEL only on one side:** different model files (sha256). avni's sha256 is that of the decrypted
  plaintext model.

Tracing costs about 0.1–0.3 s per image (hashing the full-resolution bitmap), so leave it off when
timing.
