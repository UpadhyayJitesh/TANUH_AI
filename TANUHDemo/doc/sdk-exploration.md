# SDK exploration: image models

The app opens on a launcher with two options:

1. **Voice memo (existing flow)**: `VoiceMemoActivity` (formerly `MainActivity`), unchanged.
2. **SDK exploration**: `sdktest/SdkTestActivity`, a test bench for the new `:edgesdk` module.

`:edgesdk` is the SDK candidate. It has no UI. The host app gives it one JSON document, and from that
it loads models from device paths, preprocesses images with OpenCV, runs ONNX Runtime or LiteRT,
decodes the outputs and reports timings and memory.

- [Status](#status)
- [Setup](#setup)
- [Using the test screen](#using-the-test-screen)
- [Config reference](#config-reference)
- [Loading strategies and the "avoid ByteArray" advice](#loading-strategies-and-the-avoid-bytearray-advice)
- [Experiments to run](#experiments-to-run)
- [Code map](#code-map)
- [Extending](#extending)

## Status

First device run (4 sample suspicious images, 3072×3072, EXIF orientation 6, ONNX `path`, CPU):

| image | model6 | model8 | model8-2 | unanimous-and |
|---|---|---|---|---|
| 1000233114.jpg | 19.2594 | 11.5445 | 26.1587 | Positive |
| 1000233115.jpg | 18.1569 | 14.9047 | 28.2998 | Positive |
| 1000233116.jpg | 11.3765 | 13.4772 | 5.3379 | Positive |
| 1000233117.jpg | 16.4177 | 10.9295 | 25.9551 | Positive |

- **Parity:** for every image, the JSON/OpenCV pipeline produced exactly the same tensor as avni's
  Kotlin preprocessor (0 / 196,608 elements different). The logits match desktop Python
  (cv2 + onnxruntime) to 4 decimals.
- **Time per image:**

  | stage | time |
  |---|---|
  | decode | 290–408 ms (the biggest cost) |
  | preprocessing | ~45 ms (full-res Bitmap→Mat copy ~35 ms; the six ops ~6 ms) |
  | inference | 62–85 ms per model |
  | model load | 100–134 ms per model, once |

- **Memory:**
  - Loading by `path` leaves the Java heap flat (+0.1 MB). Native memory grows 22–27 MB per 17.7 MB
    `.onnx` model, because ORT copies the weights.
  - avni's Kotlin preprocessing puts **+41 MB on the Java heap per photo** (`Bitmap.getPixels` on the
    full image). The OpenCV path adds +1.5 MB.
  - The Java heap limit on the test phone is 384 MB. On low-end phones it can be 128–192 MB, so this
    is the bigger out-of-memory risk in avni today.

## Setup

**Native dependency.** The trimmed OpenCV AAR needs `libc++_shared.so` at runtime but only bundles it
for x86. `app/src/main/jniLibs/<abi>/libc++_shared.so` supplies all ABIs; these are NDK r27 copies from
avni's tanuh build output. Without them, the screen shows
`dlopen failed: library "libc++_shared.so" not found`.

**Models** live in app-private storage: `${FILES}/models` = `/data/user/0/com.tanuh.demo/files/models`.
That's where an SDK would keep downloaded models. Do not push to `/sdcard/Android/data/...`: files
there were wiped on reinstall.

Option 1: in the app, tap **Import models into app storage** and pick the files. They're copied in 64 KB chunks.

Option 2: adb (debug builds):

```bash
cd ~/Desktop/tanuh/assets/ml
adb push "mvit2_fold1_6_latest_traced_(model6).onnx" "mvit2_fold1_8_traced_(model8).onnx" \
         "mvit2_fold2_8_latest_traced_(model8-2).onnx" /data/local/tmp/
adb shell run-as com.tanuh.demo mkdir -p files/models
for f in "mvit2_fold1_6_latest_traced_(model6).onnx" "mvit2_fold1_8_traced_(model8).onnx" "mvit2_fold2_8_latest_traced_(model8-2).onnx"; do
  adb shell "run-as com.tanuh.demo cp '/data/local/tmp/$f' files/models/"
done
adb shell run-as com.tanuh.demo ls -l files/models
```

The LiteRT exports are in `~/Desktop/tanuh/assets/ml_tflite/`. Push them the same way when you try
LiteRT.

**Reports**

```bash
adb logcat -c && adb logcat -s SdkTest:I -v raw                 # live
adb pull /sdcard/Android/data/com.tanuh.demo/files/reports/       # saved per Run
```

`/sdcard` here is the phone's built-in shared storage. No SD card is needed.

## Using the test screen

| Button | What it does |
|---|---|
| **Apply** | Parses and validates the JSON in the box and builds a new session. Previously loaded models are unloaded; nothing is loaded yet. Needed after every edit. |
| **Default** | Puts the bundled default config back in the box. Doesn't apply it. |
| **Save** | Writes the box to `/sdcard/Android/data/com.tanuh.demo/files/sdk-config.json`, which is then used instead of the default when the screen opens. |
| **Import models into app storage** | Copies picked files into `${FILES}/models`. |
| **Validate** | For every declared model, shows exists / size / engine / path, with ▶ for models that will run. Lists the folder when a model is missing. Also shows strategy, steps, device RAM and Java heap limit. |
| **Load models** | Loads the models that will run. Reports load time, Δjava, Δnative, ΔPSS and the input/output signature for each. |
| **Unload** | Closes all sessions. Use it between strategy tests. |
| **Pick images** | System photo picker, up to 20 images. |
| **Run** | Per image: decode + EXIF → preprocessing steps → each model → decision rule → optional avni comparison. Shows a thumbnail of what the model saw. |
| **Copy report** | Copies the report to the clipboard. |

Typical cycle: edit JSON → **Apply** → **Unload** → **Load models** → **Run**.

## Config reference

The complete key-by-key reference, with allowed values, impact and recipes, is in
[`sdk-config-reference.md`](sdk-config-reference.md). The summary below is kept for quick lookup.


**Schema 2 layout.** `models` say *what* runs and point to a named entry in `pipelines`. A
pipeline says *how* its input is prepared and read:

```json
"pipelines": {
  "oral-image": {
    "input":  { "type": "image", "decode": { ... }, "steps": [ ... ],
                "tensors": [ { "name": "input", "dtype": "float32", "layout": "CHW", "channel_order": "BGR", "shape": [1,3,256,256] } ] },
    "output": { "decoder": "sigmoid-binary", "threshold": 0.5, "threshold_inclusive": true, "labels": ["Negative","Positive"] },
    "combine": "unanimous-and"
  }
}
```

- **`input.type`:** `image` is implemented. `text`, `audio` and `tensor` are reserved and fail on
  Apply with a clear message until their processors are added.
- **`tensors`:** named and typed (`float32` / `int32` / `int64`). The engines already accept several
  inputs of any of these, which is what a text model will need. An image pipeline produces exactly
  one float32 tensor.
- **One pipeline per model group.** Each pipeline decodes, preprocesses and combines its own models.
  Models of different pipelines are never combined.
- **Old configs.** Schema 1 configs (top-level `image` / `preprocessing` / `postprocessing`) still
  parse, as one pipeline named `default`.
- **Where the old blocks went.** The tables below describe the old top-level blocks. In schema 2,
  `image` → `pipelines.<name>.input.decode`, `preprocessing.steps` → `input.steps`,
  `preprocessing.tensor` → `input.tensors[0]`, `postprocessing` → `output`, and `combine` →
  `pipelines.<name>.combine`.

Keys starting with `_` (for example `_comment`) are ignored, so the JSON can carry its own notes.
Unknown keys are ignored too. Every block except `models` and `preprocessing` is optional.

### `models`, `run_models`

| key | default | meaning |
|---|---|---|
| `name` | required | id used in reports and `run_models` |
| `path` | required | absolute path; placeholders `${FILES}`, `${EXTERNAL_FILES}`, `${CACHE}` |
| `engine` | `auto` | `onnx` / `litert` / `auto` (`.tflite` → litert, else onnx) |
| `enabled` | `true` | used only when `run_models` is absent |
| `input_layout` | – | `CHW` / `HWC`, per-model override when an export disagrees with the shared tensor layout |
| `sha256` | – | expected hash, checked on Validate when `verify_sha256` is true |

`models` is everything the SDK knows about. `run_models` (optional) picks exactly which models run and
in what order. Without `run_models`, every `enabled` model runs, in declaration order. Only models that
run get loaded, and only their results are combined.

### `loading`

| key | default | meaning |
|---|---|---|
| `strategy` | `path` | `path` / `mmap` / `bytes`; see the next section |
| `keep_loaded` | `true` | `false` = load → run → close each model per image: lowest peak memory, but pays the load time on every image |
| `warmup_runs` | `0` | 0–10 untimed, discarded inferences the first time a loaded model is used |

**Warm-up.** The first inference after loading is slower: ORT allocates working memory, starts its
threads, and runs on cold caches with the CPU still ramping up. With 0, the reported time includes
that cost, which is what a user sees on their first scan. With 1, one throw-away run happens first, so
the reported time is steady-state speed.

Warm-up does not make things faster overall. It moves the slow run out of the measurement, and here it
happens during the first scan. Use 0 normally and 1 when comparing speeds. In a production SDK, the
warm-up belongs right after loading, on a dummy input, in the background.

| image | `warmup_runs: 0` | `warmup_runs: 1` |
|---|---|---|
| 1st after load | real run, ~75 ms (includes first-run cost) | warm-up ~75 ms (discarded), then real run ~65 ms |
| later | real run ~65 ms | real run ~65 ms |

### `onnx`

Applies to every `engine: onnx` model. The defaults are avni `OnnxEngine`'s low-RAM settings for three
resident sessions.

| key | default | meaning |
|---|---|---|
| `intra_op_threads` | 4 (≤ cores) | threads per inference |
| `cpu_arena` | `false` | `true` keeps a reusable memory pool: slightly faster repeat runs, more memory held |
| `memory_pattern` | `false` | `true` pre-plans buffers from the first run's shapes: slightly faster, more memory |
| `optimization_level` | `all` | `none` / `basic` / `extended` / `all` graph rewrites at load; `all` = fastest inference, slightly longer load |
| `execution_provider` | `cpu` | `cpu` / `xnnpack` (ARM-tuned CPU kernels) / `nnapi` (varies by phone, deprecated from Android 15; unsupported ops fall back to CPU) |
| `config_entries` | `{}` | raw ORT session keys, e.g. `"session.intra_op.allow_spinning": "0"` |

### `litert`

| key | default | meaning |
|---|---|---|
| `accelerator` | `cpu` | `cpu` / `gpu` (GPU also needs the `litert-gpu` dependency, which isn't added yet) |

The LiteRT engine is compiled in only with `edgesdk.litert=true` in `gradle.properties`.

### `image`

| key | default | meaning |
|---|---|---|
| `apply_exif` | `true` | Rotate/flip upright from the EXIF tag, like `cv2.imread` and avni `decodeUpright`. Android's decoder ignores the tag. The sample photos are orientation 6 (rotated 90°), so with `false` the models see them sideways. |
| `max_decode_side` | `0` | 0 = full resolution (QC parity). N > 0 = the decoder halves the image until its long side is between N and 2N. For example, 1024 decodes 3072² as 1536² (~4× less memory, faster). The report's `sample` shows the factor. The input is then no longer bit-identical to QC, and the avni comparison shows the drift. |

### `preprocessing`

`steps` run in order on a BGR OpenCV `Mat`. Each step reads only its own keys.

| op | params |
|---|---|
| `resize` | `size` [w,h]; `mode` stretch / letterbox / short_side / long_side; `interpolation` nearest / linear / cubic / area / lanczos; `pad_value` |
| `center_crop` | `size` [w,h] |
| `gaussian_blur` | `ksize` (odd), `sigma` |
| `gray_world` | `mean_target`: per-channel mean → target, over the whole canvas (padding included) |
| `clip` | `min`, `max` |
| `round` | `decimals`: round-half-to-even in float32, like `np.round` |
| `to_uint8` | – (rint + saturate, like `cv2.convertScaleAbs`) |
| `scale` | `divide_by` or `multiply` |
| `normalize` | `mean`, `std`, `order` (RGB/BGR: the order the lists are written in) |

The `tensor` block takes `layout` (`CHW`/`HWC`), `channel_order` (`RGB`/`BGR`) and an optional
`shape`, which is checked against the pipeline output.

The default steps are avni's QC pipeline:
`letterbox 256 cubic → gray_world 128 → clip → round 1 → to_uint8 → /255 → CHW BGR`.
Example for an ImageNet-style model, with no code change:

```json
"steps": [
  { "op": "resize", "size": [224, 224], "mode": "short_side", "interpolation": "linear" },
  { "op": "center_crop", "size": [224, 224] },
  { "op": "scale", "divide_by": 255 },
  { "op": "normalize", "mean": [0.485, 0.456, 0.406], "std": [0.229, 0.224, 0.225], "order": "RGB" }
],
"tensor": { "layout": "CHW", "channel_order": "RGB" }
```

### `postprocessing`

| key | default | meaning |
|---|---|---|
| `decoder` | `sigmoid-binary` | `sigmoid-binary` (p = sigmoid(logit[0])) / `softmax-argmax` / `raw` |
| `threshold` | 0.5 | decision threshold on p |
| `threshold_inclusive` | `true` | `true`: p ≥ threshold is positive (QC rule); `false`: p > threshold |
| `labels` | `["Negative","Positive"]` | [negative, positive], or class names for softmax |
| `combine` | `none` | `unanimous-and` (avni) / `any-or` / `majority` / `mean-probability` / `none` |

### `diagnostics`

| key | default | meaning |
|---|---|---|
| `measure_pss` | `true` | add process PSS to memory lines (a few ms each) |
| `verify_sha256` | `false` | stream-hash model files on Validate |
| `compare_with_reference` | `false` | also run avni's Kotlin preprocessor and report tensor and logit differences |
| `reference_params` | – | avni override `input.params` used for that comparison |

## Loading strategies and the "avoid ByteArray" advice

The strategy only changes how the file reaches the runtime. Inference speed and results are the same.
No extra library is involved: `mmap` uses Java's built-in `FileChannel.map`, and `bytes` uses
`File.readBytes`. All three end in the same ONNX Runtime.

| strategy | Java heap | native | weights copied |
|---|---|---|---|
| `path` (recommended) | ~0 | +22 MB / model (measured) | once, native |
| `mmap` | ~0 | about the same as `path` | once, native; the mapped file pages can be dropped |
| `bytes` | +17.7 MB / model during load | +22 MB | twice (Java + native) |
| LiteRT `.tflite` via path | ~0 | small | not copied; mapped from the file |

**Takeaways**

- Never hand the runtime a ByteArray.
- For `.onnx`, `path` and `mmap` are equivalent. ORT parses the protobuf and copies the weights either
  way.
- Real zero-copy weights, which is what the peer advice is after, need LiteRT `.tflite` (mmapped from
  the path) or ORT's `.ort` format with `session.use_ort_model_bytes_directly`.
- At 17.7 MB per model, `bytes` won't OOM a 384 MB heap. It matters for large models, several copies
  at once, or low-end heaps.

## Experiments to run

Change one thing at a time, with `warmup_runs: 1` for speed comparisons, and use
**Apply → Unload → Load models → Run** after each change.

| # | change | what to look at |
|---|---|---|
| 1 | `strategy: mmap` | Δnative and load time vs `path` |
| 2 | `strategy: bytes` | Δjava ≈ +17.7 MB per model during load |
| 3 | `keep_loaded: false` | lower steady memory; per-image load cost |
| 4 | `max_decode_side: 1024` | decode time and memory vs logit drift (avni comparison line) |
| 5 | `execution_provider: xnnpack` | inference ms |
| 6 | `execution_provider: nnapi` | inference ms, first-run cost, verdicts |
| 7 | `intra_op_threads: 2` | latency vs heat/battery |
| 8 | LiteRT `*-tfl` models | Δnative per model (expect far below 22 MB), latency |

## LiteRT (CompiledModel) path

`~/Desktop/tanuh/assets/ml_tflite/` has float32 `.tflite` exports of the three folds, made with
`onnx2tf -k input` (NCHW input kept, so the same preprocessing is used). Their logits match ONNX within
9e-5 on the four sample images.

To use them:

1. Set `edgesdk.litert=true` in `gradle.properties` and rebuild. This adds `com.google.ai.edge.litert:litert:2.3.0`
   and `LiteRtEngine`, which calls `CompiledModel.create(absolutePath, Options(CPU))`.
2. Push the `.tflite` files. Set `"enabled": true` on the `*-tfl` models and `false` on the ONNX ones,
   or add `"run_models": ["model6-tfl", "model8-tfl", "model8-2-tfl"]`.

`LiteRtEngine` was written without being able to compile against LiteRT, which is why it sits behind
the flag. If Gradle reports a different `CompiledModel.create` signature or a Kotlin metadata-version
error, only that file, and possibly the Kotlin version, needs to change.

## Code map

| file | role |
|---|---|
| `edgesdk/.../EdgeSession.kt` | SDK facade: `create(context, json)`, `validate()`, `loadSelected()`, `inferImage(uri or file)`, `unloadAll()` |
| `edgesdk/.../config/EdgeConfig.kt` | JSON schema, parsing, validation, path placeholders |
| `edgesdk/.../image/ImageDecoder.kt` | URI/file → upright Bitmap (EXIF, optional subsampling) |
| `edgesdk/.../preprocess/OpenCvPreprocessor.kt` | JSON step pipeline on OpenCV; tensor layout |
| `edgesdk/.../preprocess/reference/` | avni's `PixelOps` + `mean-target-bgr-rounded`, used as the parity oracle |
| `edgesdk/.../engine/OnnxEngine.kt` | ORT session options, `path` / `mmap` / `bytes` loading |
| `edgesdk/src/litert/.../LiteRtEngine.kt` | LiteRT CompiledModel (optional source set) |
| `edgesdk/.../postprocess/Decoders.kt` | sigmoid / softmax / raw decoding and ensemble rules |
| `edgesdk/.../diag/MemoryProbe.kt` | Java / native / PSS snapshots, device summary |
| `app/.../sdktest/SdkTestActivity.kt` | test-bench UI |
| `app/src/main/assets/sdk-config.default.json` | default config (commented) |

Timings in the report are measured by the app around each call (`System.nanoTime`). They aren't
reported by the runtimes. For per-layer detail, use ORT profiling or LiteRT's `benchmark_model`
tool.

## Extending

| need | change |
|---|---|
| new preprocessing op (CLAHE, colour conversion, ROI crop, …) | one branch in `OpenCvPreprocessor.applyStep` + add the name to `SUPPORTED_OPS` |
| new decision rule | one case in `Decoders.combine` / `decode` |
| new input type (text, audio, …) | a new `InputSpec` subtype + processor producing `InputTensor`s + an `infer*` entry point; schema unchanged |
| new runtime (ExecuTorch, …) | a class implementing `InferenceEngine` + an `EngineType` value + a config block |
| per-model preprocessing or decoder | allow `preprocessing` / `postprocessing` inside a model entry (not implemented; all models currently share one) |
