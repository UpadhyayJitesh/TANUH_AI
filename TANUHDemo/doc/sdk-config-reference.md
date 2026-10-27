# SDK config reference (schema 2)

This document explains every key in the JSON passed to the SDK
(`EdgeSession.create(context, json)`). For each key it covers what the key does, the allowed values,
the default when the key is left out, and how changing it affects the results.

The values described as **current** are those in `app/src/main/assets/sdk-config.default.json`, the
config verified on device. It reproduces avni's production QC pipeline exactly.

- [1. The big picture](#1-the-big-picture)
- [2. General rules](#2-general-rules)
- [3. Root keys](#3-root-keys)
- [4. `models`](#4-models)
- [5. `run_models`](#5-run_models)
- [6. `pipelines`](#6-pipelines)
  - [6.1 `input`](#61-input)
  - [6.2 `input.decode`](#62-inputdecode)
  - [6.3 `input.steps`](#63-inputsteps)
  - [6.4 `input.tensors`](#64-inputtensors)
  - [6.5 `output`](#65-output)
  - [6.6 `combine`](#66-combine)
- [7. `loading`](#7-loading)
- [8. `onnx`](#8-onnx)
- [9. `litert`](#9-litert)
- [10. `diagnostics`](#10-diagnostics)
- [11. Recipes](#11-recipes)
- [12. Error messages you may see on Apply](#12-error-messages-you-may-see-on-apply)

---

## 1. The big picture

```
config
├── schema_version      which version of this format the JSON is written in
├── models[]            WHAT exists: name, file path, engine, which pipeline it uses
├── run_models          WHICH models run (optional)
├── pipelines{}         HOW input is prepared and output is read (shared by the models that use it)
│   └── <name>
│       ├── input       preprocessing: type, decode, steps, tensors
│       ├── output      post-processing for each model: decoder, threshold, labels
│       └── combine     merging the models of this pipeline into one RESULT
├── loading             how models get into memory
├── onnx / litert       runtime tuning
└── diagnostics         test-bench reporting and checks
```

What happens for one photo:

```
photo ─▶ decode ─▶ steps ─▶ tensors ─▶ each model in the pipeline ─▶ output (per model) ─▶ combine ─▶ RESULT
         (input.decode) (input.steps) (input.tensors)    (loading, onnx/litert)    (output)        (combine)
```

Which keys can change the **verdict**:

| Can change the verdict | Only changes speed / memory / reporting |
|---|---|
| `models[].path`, `run_models` / `enabled`, everything in `pipelines` (`input`, `output`, `combine`) | `loading`, `onnx`, `litert`, `diagnostics` |

The exception is `onnx.optimization_level` and the execution provider: they can shift logits by
tiny floating-point amounts (around 1e-5), which never matters except right at the threshold.

---

## 2. General rules

- **Comments:** any key starting with `_` (e.g. `_comment`, `_steps_comment`) is ignored, at any
  level. Use them freely to document the config inside the config.
- **Unknown keys are ignored.** A typo like `"stratgey"` is silently ignored and the default is
  used. Check **Validate** after editing.
- **Optional blocks:** everything except `models` and `pipelines` (or `preprocessing` in schema 1)
  can be left out, and defaults apply.
- **Errors appear on Apply.** Unknown names, wrong values and missing required keys stop at
  **Apply** with a message (see section 12). The models are never run with a half-valid config.
- **Path placeholders** in `models[].path`:

  | placeholder | resolves to (on the test phone) | use |
  |---|---|---|
  | `${FILES}` | `/data/user/0/com.tanuh.demo/files` | app-private storage; recommended, and where an SDK would keep downloaded models |
  | `${EXTERNAL_FILES}` | `/storage/emulated/0/Android/data/com.tanuh.demo/files` | app-specific shared storage; was wiped on reinstall during testing |
  | `${CACHE}` | `/data/user/0/com.tanuh.demo/cache` | temporary; Android may delete it at any time |

---

## 3. Root keys

| key | type | default | current | meaning |
|---|---|---|---|---|
| `schema_version` | number | 2 if `pipelines` exists, else 1 | `2` | Version of this format. `1` = old layout (top-level `image` / `preprocessing` / `postprocessing`), read as one pipeline called `default`. `2` = this layout. Anything else is rejected. |
| `models` | array | required | 6 entries | see section 4 |
| `run_models` | array of names | all `enabled` models | absent | see section 5 |
| `pipelines` | object | required in schema 2 | `oral-image` | see section 6 |
| `loading`, `onnx`, `litert`, `diagnostics` | object | defaults | present | see sections 7–10 |

---

## 4. `models`

Each entry declares one model file. Declaring a model does not load or run it.

```json
{ "name": "model8", "engine": "onnx", "pipeline": "oral-image",
  "path": "${FILES}/models/mvit2_fold1_8_traced_(model8).onnx" }
```

| key | type | required / default | allowed values | effect |
|---|---|---|---|---|
| `name` | string | **required**, unique | any, e.g. `model8`, `model8-tfl` | The id shown in reports and used by `run_models`. Duplicate names fail. |
| `path` | string | **required** | absolute path, may use placeholders | Where the file is on the phone. A wrong path shows **MISSING** in Validate (with the folder's contents), and running fails with "Model file not found". |
| `engine` | string | `auto` | `onnx` (also `ort`, `onnxruntime`), `litert` (also `tflite`), `auto` | Which runtime runs the file. `auto` picks LiteRT for `.tflite` and ONNX Runtime otherwise. The engine must match the file type; ONNX Runtime can't open a `.tflite`. |
| `pipeline` | string | the only pipeline, if there is exactly one | a key under `pipelines` | Which recipe prepares this model's input and reads its output. Required once there are several pipelines. An unknown name fails. |
| `enabled` | boolean | `true` | `true` / `false` | Only used when `run_models` is absent. `false` keeps the model declared but not run. |
| `input_layout` | string | none | `CHW`, `HWC` | Rarely needed. Converts the pipeline's tensor layout for this one model only, e.g. a `.tflite` export that expects HWC while the pipeline produces CHW. Only applies to 4-D float inputs. |
| `sha256` | string | none | 64 hex characters | Expected file fingerprint. Checked by Validate when `diagnostics.verify_sha256` is `true` (OK or MISMATCH). |

**Current values:** three ONNX folds, enabled, and three LiteRT copies with `"enabled": false`.

How changes affect results:

- **A different `path`** means a different model, so different logits and possibly different verdicts.
- **Switching the `-tfl` models on** gives the same verdicts: their logits match ONNX within 1e-4
  on the sample images. What differs is memory and speed (see section 9).

---

## 5. `run_models`

Optional list of model names, e.g. `["model8", "model8-tfl"]`.

| situation | what runs |
|---|---|
| absent (current) | every model with `enabled: true`, in the order of `models` |
| present | exactly these models, in this order; `enabled` is ignored |

- Only models that run are loaded by **Load models**.
- Order sets the order of the report lines. It doesn't change the combined verdict, because the
  rules don't depend on order.
- A name that isn't declared in `models` fails on Apply.

Examples of the impact:

| `run_models` | effect |
|---|---|
| `["model6","model8","model8-2"]` | full ensemble; RESULT from `unanimous-and` across 3 |
| `["model8"]` | single fold; no RESULT line (combining needs 2 or more models), just model8's own label |
| `["model8","model8-tfl"]` | same fold on two runtimes, side by side; set `combine: "none"` to avoid a meaningless combined verdict |

---

## 6. `pipelines`

An object of named recipes. The name (here `oral-image`) is your choice and appears in reports as
`[oral-image]`.

```json
"pipelines": {
  "oral-image": { "input": { ... }, "output": { ... }, "combine": "unanimous-and" }
}
```

- **One preprocessing run per pipeline per photo.** The photo is decoded and preprocessed once,
  and the tensor goes to every model of that pipeline. The three folds share one 256×256 tensor.
- **Separate pipelines are independent.** Each one decodes, preprocesses and combines its own models.
  A model in pipeline A is never combined with a model in pipeline B.
- **When to add a second pipeline:** a model trained with different preprocessing (other size,
  RGB, ImageNet normalisation, …), or one whose output means something else.

### 6.1 `input`

| key | type | default | allowed values | meaning |
|---|---|---|---|---|
| `type` | string | `image` | `image`; `text`, `audio`, `tensor` are reserved | The kind of input. Decides which other keys are valid. Reserved types fail on Apply with "not implemented in this SDK build yet". |
| `decode` | object | defaults | see 6.2 | How the photo file becomes pixels. Image only. |
| `steps` | array | **required** | see 6.3 | Preprocessing operations, in order. |
| `tensors` | array | one tensor with defaults | see 6.4 | How the result is handed to the model. |

### 6.2 `input.decode`

| key | type | default | current | allowed values | effect on results |
|---|---|---|---|---|---|
| `apply_exif` | boolean | `true` | `true` | `true` / `false` | `true` rotates or flips the photo upright using its EXIF orientation tag, like `cv2.imread` in the QC notebook. Android's decoder ignores the tag. Your photos are all orientation 6 (stored rotated 90°), so `false` would feed them sideways, which can change verdicts because the models aren't rotation-invariant. Keep `true`; `false` only exists to test that sensitivity. |
| `max_decode_side` | integer ≥ 0 | `0` | `0` | `0`, or e.g. `512`, `1024`, `2048` | `0` decodes at full resolution: exact QC parity, but the slowest step (≈330–420 ms and ≈38 MB for 3072×3072). With N > 0 the decoder halves the image until its long side is between N and 2N. With `1024`, a 3072² photo decodes at 1536² (≈4× less memory, faster). The 256×256 tensor is then built from different pixels, so logits shift slightly and the avni comparison stops showing 0 differences. Needs QC sign-off before production use. |

Decode for 3072×3072:

| `max_decode_side` | decoded size (`sample`) | pixel memory |
|---|---|---|
| 0 | 3072×3072 (1) | ≈38 MB |
| 2048 | 3072×3072 (1); 3072/2 = 1536 < 2048, so no halving | ≈38 MB |
| 1024 | 1536×1536 (2) | ≈9 MB |
| 512 | 768×768 (4) | ≈2.4 MB |
| 256 | 384×384 (8) | ≈0.6 MB |

### 6.3 `input.steps`

An ordered array. Each step is `{ "op": "<name>", ...its own parameters }`. The image moves through the
steps as an OpenCV image in BGR order. Each step works on the previous step's result.

**Order matters.** For example, `to_uint8` before `round` gives different numbers than the current
order. The six current steps reproduce the QC notebook exactly, so changing any of them changes the
model input and moves the logits.

#### `resize`

| param | default | current | allowed values |
|---|---|---|---|
| `size` | `[256, 256]` | `[256, 256]` | `[width, height]`, or `[n]` for a square |
| `mode` | `stretch` | `letterbox` | `stretch`, `letterbox`, `short_side`, `long_side` |
| `interpolation` | `linear` | `cubic` | `nearest`, `linear` (also `bilinear`), `cubic` (also `bicubic`), `area`, `lanczos` (also `lanczos4`) |
| `pad_value` | `0` | `0` | 0–255 (letterbox only) |

`mode`, for a 4000×3000 photo with `size [256, 256]`:

| mode | result | crops? | distorts? |
|---|---|---|---|
| `letterbox` (current) | shrunk to 256×192, centred on a 256×256 canvas filled with `pad_value` | no | no |
| `stretch` | squashed straight to 256×256 | no | **yes** |
| `short_side` | shorter side → `size[0]`: 341×256. Usually followed by `center_crop`. | no (the crop step does it) | no |
| `long_side` | longer side → `size[0]`: 256×192, no padding | no | no |

Your sample photos are square, so `letterbox` and `stretch` give the same image for them. They differ
on non-square photos.

`interpolation`: `cubic` matches the QC notebook (`cv2.INTER_CUBIC`) and is required for parity.
`area` is often the best quality for big reductions; `linear` is faster; `nearest` is blocky.
Changing it changes pixels slightly, so logits shift slightly.

`pad_value`: the colour of the letterbox bars (0 = black, as the notebook). It only matters for
non-square photos. It also feeds into `gray_world`'s average, because the bars are included in it.

#### `center_crop`

| param | default | allowed |
|---|---|---|
| `size` | `[224, 224]` | `[w, h]`, at most the current image size, otherwise Apply passes but Run fails |

Keeps the centre and cuts the edges. Not used today; letterbox never cuts anything. Typical for
ImageNet-style models, together with `short_side`.

#### `gaussian_blur`

| param | default | allowed |
|---|---|---|
| `ksize` | `3` | odd integer (an even value is bumped up by 1) |
| `sigma` | `0` | ≥ 0; 0 lets OpenCV derive it from `ksize` |

Smooths noise. Not used today. Adding it changes every pixel, so logits change.

#### `gray_world`

| param | default | current | allowed |
|---|---|---|---|
| `mean_target` | `128` | `128` | any > 0 |
| `precision` | `float32` | `float32` | `float32` (numpy / QC notebook), `float64` (avni's `rounding: legacy` arithmetic) |

Lighting and white-balance correction: it computes each colour's average over the whole image
(letterbox bars included, as the notebook does), then scales that colour so its average becomes
`mean_target`. The report shows the averages and the scales it applied, e.g.
`mean B=119.029 G=140.461 R=162.761 scale B=1.07536 G=0.91128 R=0.78643`.

- **Removing it:** photos taken under yellow or blue light reach the model with that colour cast.
  This is a large change in model input; expect different logits and possibly verdicts.
- **A different target, e.g. 100:** a darker input overall, unlike anything the models were trained on.

Afterwards values are decimals (`f32`) and may exceed 255, which is why `clip` follows.

#### `clip`

| param | default | current |
|---|---|---|
| `min` | `0` | `0` |
| `max` | `255` | `255` |

Limits values to [min, max]. Needed after `gray_world`, whose scaling can push bright pixels past 255.

#### `round`

| param | default | current | allowed |
|---|---|---|---|
| `decimals` | `1` | `1` | 0, 1, 2, … |
| `mode` | `half_even` | `half_even` | `half_even` (`np.round`, QC), `half_up` (Java `Math.round`, avni legacy) |

Rounds to N decimals, round-half-to-even like `np.round` (137.64 → 137.6). It serves no visual
purpose; it's there because the QC notebook does it. Removing it or changing `decimals` shifts some
values by a fraction of a level, giving a tiny logit change and losing exact parity.

#### `to_uint8`

| param | default | allowed |
|---|---|---|
| `mode` | `half_even` | `half_even` (`cv2.convertScaleAbs`, QC), `half_up` (avni legacy `uint8_round: true`), `truncate` (avni legacy `uint8_round: false`) |

Rounds to whole numbers and limits to 0–255. After it
the image is `u8`. The preview thumbnail shows the last `u8` state, which is what the model "sees".

#### `scale`

| param | current | meaning |
|---|---|---|
| `divide_by` | `255` | value ÷ N |
| `multiply` | – | value × N (use one of the two) |

Converts to the range the model was trained on: ÷255 gives 0–1. Without it the model gets 0–255
values, which is a completely wrong input range and gives meaningless logits. If a pipeline ends
without any float step, the SDK converts to float without scaling and adds a warning note to the
report.

#### `normalize`

| param | default | example (ImageNet) |
|---|---|---|
| `mean` | `[0, 0, 0]` | `[0.485, 0.456, 0.406]` |
| `std` | `[1, 1, 1]` | `[0.229, 0.224, 0.225]` |
| `order` | `RGB` | the order the two lists are written in (`RGB` or `BGR`) |

Computes (value − mean) ÷ std per colour. It isn't used by the TANUH models; adding it would
completely change their input. It's here for models trained with mean/std normalisation, usually
after `scale ÷255`.

#### The current steps on one pixel (illustrative)

| after step | red value of one pixel (scale factor from image 1000233114) |
|---|---|
| decode | 175 |
| resize | 175 (integer, 256×256 image) |
| gray_world (×0.78643) | 137.625… |
| clip | 137.625… |
| round 1 | 137.6 |
| to_uint8 | 138 |
| scale ÷255 | 0.5412 |

### 6.4 `input.tensors`

A list describing what is handed to the model. An image pipeline produces exactly one entry.

```json
"tensors": [ { "name": "input", "dtype": "float32", "layout": "CHW", "channel_order": "BGR", "shape": [1, 3, 256, 256] } ]
```

| key | default | current | allowed | effect |
|---|---|---|---|---|
| `name` | none (bind by position) | `input` | the model's input name | Must match the model. Load prints it (`in input [1, 3, 256, 256] FLOAT`). A wrong name fails at Run with "has no input 'x'. Inputs: [...]". |
| `dtype` | `float32` | `float32` | `float32`; `int32` and `int64` are for future text models | Images must be float32 (Apply fails otherwise). |
| `layout` | `CHW` | `CHW` | `CHW`, `HWC` | `CHW` stores all Blue values, then all Green, then all Red. `HWC` stores B,G,R per pixel. The wrong layout scrambles the image for the model and gives meaningless logits, often with no error. |
| `channel_order` | `RGB` | `BGR` | `RGB`, `BGR` | The colour order the model was trained with. The MViT2 folds use BGR (OpenCV). Swapping it exchanges red and blue, which for oral images (lots of red) shifts logits a lot. |
| `shape` | inferred | `[1, 3, 256, 256]` | e.g. `[1, 3, 224, 224]`, `[1, 256, 256, 3]` for HWC | A safety check: if the steps produce a different size, Run fails with a clear message instead of feeding the model a wrong-size input. |

### 6.5 `output`

How one model's raw number(s) become a label. It applies to each model of the pipeline separately.
The model's first output is read.

| key | default | current | allowed | effect |
|---|---|---|---|---|
| `decoder` | `sigmoid-binary` | `sigmoid-binary` | `sigmoid-binary`, `softmax-argmax`, `raw` | see below |
| `threshold` | `0.5` | `0.5` | 0–1 | Probability cut-off (sigmoid-binary and mean-probability). |
| `threshold_inclusive` | `true` | `true` | `true` / `false` | `true`: p ≥ threshold is positive (the QC rule). `false`: p > threshold. Only matters when p equals the threshold exactly. |
| `labels` | `["Negative","Positive"]` | same | list of strings | Binary: [negative, positive]. Softmax: one name per class, in output order. |

Decoders:

| decoder | for models that output | computes | report shows |
|---|---|---|---|
| `sigmoid-binary` (current) | one number (a logit), e.g. shape [1,1] | p = 1 / (1 + e^-logit); positive if p ≥ threshold | `model8 Positive p=1.0000 logit=11.5445` |
| `softmax-argmax` | one score per class | picks the highest class; its softmax probability is the confidence | the class label and probability |
| `raw` | anything | nothing; passes the numbers through | label `raw` and the first values |

Impact of `threshold`, with your logits:

| threshold | p needed | logit needed | your 4 images (lowest logit 5.34, p 0.9952) |
|---|---|---|---|
| 0.5 (current) | ≥ 0.5 | ≥ 0 | all Positive |
| 0.9 | ≥ 0.9 | ≥ 2.20 | all Positive |
| 0.99 | ≥ 0.99 | ≥ 4.60 | all Positive |
| 0.999 | ≥ 0.999 | ≥ 6.91 | 1000233116 / model8-2 turns **Negative**, so the combined RESULT turns Negative (unanimous-and) |

The threshold is a clinical decision. Change it only together with the QC team.

### 6.6 `combine`

How the verdicts of all models in this pipeline become one RESULT line.

| value | RESULT is positive when | your example: model labels P, P, N |
|---|---|---|
| `unanimous-and` (current; avni) | **every** model is positive | Negative (strictest, fewest positives) |
| `majority` | more than half are positive | Positive |
| `any-or` | **at least one** is positive | Positive (most sensitive, most positives) |
| `mean-probability` | the average p ≥ threshold | depends on the three probabilities |
| `none` | no RESULT line; only per-model labels | – |

- A RESULT line needs 2 or more models, except `mean-probability`, which works with one.
- Choosing the rule trades sensitivity against specificity, so it's a clinical/QC decision.
  `unanimous-and` is what avni ships.

---

## 7. `loading`

How model files get into memory. **It never changes results**, only load time, memory and latency.

| key | default | current | allowed | effect |
|---|---|---|---|---|
| `strategy` | `path` | `path` | `path`, `mmap`, `bytes` | see below |
| `keep_loaded` | `true` | `true` | `true` / `false` | `true`: models stay loaded between photos (load once, ≈80–180 ms each; ≈22–27 MB native memory each). `false`: each model is loaded, run and closed per photo, one at a time. Lowest peak memory (one model at a time), but every photo pays the load time again. |
| `warmup_runs` | `0` | `0` | 0–10 (clamped) | Untimed, discarded inferences the first time a loaded model is used. `0`: the first photo's time includes first-run slowness (e.g. 86 ms vs ≈65 ms); that's what a user sees on a first scan. `1`: the timed numbers show steady-state speed, which is fair for comparing runtimes. It doesn't make anything faster overall; the slow run still happens, untimed. |

`strategy`, per 17.7 MB `.onnx` model:

| strategy | how | Java heap | native | use |
|---|---|---|---|---|
| `path` (recommended) | the runtime opens the file itself | ≈0 | +22–27 MB (measured) | default |
| `mmap` | `FileChannel.map` → direct buffer → runtime | ≈0 | about the same as path | equivalent for `.onnx`; ORT still copies the weights |
| `bytes` | whole file into a Java ByteArray first | **+17.7 MB** during load | +22 MB | baseline only; the out-of-memory risk the peer advice warns about |

For LiteRT `.tflite`, `path` means memory-mapped by the runtime and `bytes` is rejected.

---

## 8. `onnx`

Applies to every model with `engine: onnx`. The defaults are avni's `OnnxEngine` settings for
keeping 3 models loaded on low-RAM phones. **It doesn't change verdicts**; logits can move by about
1e-5 between providers and optimisation levels.

| key | default | current | allowed | effect |
|---|---|---|---|---|
| `intra_op_threads` | CPU cores, at most 4 | `4` | 1–8 | Threads per inference. More threads is usually faster up to the number of fast cores; too many slows things down on budget phones and uses more battery. Try 2 vs 4. |
| `cpu_arena` | `false` | `false` | `true` / `false` | `true` keeps a reusable memory pool: slightly faster repeat runs, more native memory held. |
| `memory_pattern` | `false` | `false` | `true` / `false` | `true` pre-plans buffers from the first run: slightly faster, more memory. |
| `optimization_level` | `all` | `all` | `none`, `basic`, `extended`, `all` (anything else is treated as `all`) | Graph rewrites at load. `all` gives the fastest inference with a slightly longer load. `none` is useful only for debugging. |
| `execution_provider` | `cpu` | `cpu` | `cpu`, `xnnpack`, `nnapi` | `cpu`: works everywhere. `xnnpack`: ARM-tuned CPU kernels, often faster. `nnapi`: Android's neural-network API (NPU/DSP/GPU); varies by phone, often a big first-run cost, deprecated from Android 15; unsupported layers fall back to CPU. Unknown values fail at Load. |
| `config_entries` | `{}` | `{}` | string → string | Raw ONNX Runtime session settings passed through unchanged. Example: `{"session.intra_op.allow_spinning": "0"}` stops idle threads from spinning, which saves CPU and battery and is slightly slower. Keys starting with `_` are ignored. |

---

## 9. `litert`

Applies to every model with `engine: litert`. These models only run if the app was built with
`edgesdk.litert=true` in `gradle.properties`; otherwise running one fails with "This build has no
LiteRT engine".

| key | default | current | allowed | effect |
|---|---|---|---|---|
| `accelerator` | `cpu` | `cpu` | `cpu`, `gpu` | `gpu` can be much faster but needs the `litert-gpu` dependency (not added yet), and not every layer is supported on GPU. |

LiteRT memory-maps the `.tflite` from its path, so expect much less native memory per model than
ONNX's +22 MB. Verifying that on device is the point of the LiteRT experiment.

---

## 10. `diagnostics`

Test-bench extras. **They never change results**; they add measurements and checks to the report.
Switch them off in production.

| key | default | current | allowed | effect |
|---|---|---|---|---|
| `measure_pss` | `true` | `true` | `true` / `false` | Adds the process's total memory (PSS) to memory lines (`PSS=225.5 MB`). PSS is what Android uses when deciding to kill apps. Costs a few ms per reading. |
| `verify_sha256` | `false` | `false` | `true` / `false` | Validate fingerprints each model file in 64 KB chunks and compares it with `models[].sha256`. Catches corrupted or wrong files. Takes ≈1 s per 17 MB file. |
| `parity_trace` | `false` | `true` | `true` / `false` | Writes a line-by-line trace of every intermediate value (input file hash, decoded pixels hash, canvas hash and means, gray-world values, tensor hash and stats, model sha256 and logits) to `<external files>/parity-traces/sdk-*.txt`, in the same format avni's debug build writes. `tools/parity/compare_traces.py` compares the two stage by stage. Costs ≈0.1–0.3 s per image. |
| `compare_with_reference` | `false` | `true` | `true` / `false` | Also runs avni's Kotlin preprocessing on the same photo and compares tensors number by number (`0 / 196608 elements differ`). If any differ, it also prints both logits per model. Costs ≈70–290 ms and ≈41 MB of Java heap per photo (avni's own cost). |
| `reference_pipeline` | first image pipeline that runs | `oral-image` | a pipeline name | Which pipeline the comparison applies to. |
| `reference_params` | none (comparison skipped) | avni's settings | avni's `input.params` format | The recipe avni's code is run with. It's copied from avni's `tanuh-ensemble-override.json`. It must describe the same recipe as the pipeline's `steps` to show 0 differences. Changing a step and leaving this alone shows how far your change moved the input from production. |

`reference_params` keys (avni's names, not the SDK's):

| key | current | SDK equivalent |
|---|---|---|
| `size`, `resize_mode`, `pad_value`, `interpolation` | `[256,256]`, `letterbox`, `0`, `cubic` | the `resize` step |
| `ignore_color_profile` | `true` | always on in the SDK: OpenCV reads the bitmap's raw stored values, so Display-P3 photos are not converted to sRGB (avni [PE-138]) |
| `mean_target` | `128` | `gray_world` |
| `round_decimals`, `rounding` | `1`, `numpy` | `round` (half-to-even) |
| `uint8_cast`, `uint8_round` | `true`, `true` | `to_uint8` |
| `scale` | `0.00392…` (1/255) | `scale divide_by 255` |
| `channel_order`, `layout` | `BGR`, `CHW` | `tensors[0]` |

---

## 11. Recipes

**Only one fold, fast iteration**

```json
"run_models": ["model8"]
```

**Same fold, ONNX vs LiteRT side by side** (needs `edgesdk.litert=true`)

```json
"run_models": ["model8", "model8-tfl"],
"pipelines": { "oral-image": { ..., "combine": "none" } },
"loading": { "strategy": "path", "keep_loaded": true, "warmup_runs": 1 }
```

**Low memory: one model in memory at a time**

```json
"loading": { "strategy": "path", "keep_loaded": false }
```

**Faster decode (needs QC sign-off; watch the avni diff line)**

```json
"decode": { "apply_exif": true, "max_decode_side": 1024 }
```

**Speed comparison of ONNX backends**

```json
"loading": { "warmup_runs": 1 },
"onnx": { "execution_provider": "xnnpack" },
"diagnostics": { "compare_with_reference": false, "measure_pss": false }
```

**A second, different image model next to the folds** (e.g. an ImageNet-style quality classifier)

```json
"models": [
  ...the three folds with "pipeline": "oral-image"...,
  { "name": "quality", "engine": "onnx", "pipeline": "quality-check", "path": "${FILES}/models/quality.onnx" }
],
"pipelines": {
  "oral-image": { ...unchanged... },
  "quality-check": {
    "input": {
      "type": "image",
      "steps": [
        { "op": "resize", "size": [224, 224], "mode": "short_side", "interpolation": "linear" },
        { "op": "center_crop", "size": [224, 224] },
        { "op": "scale", "divide_by": 255 },
        { "op": "normalize", "mean": [0.485, 0.456, 0.406], "std": [0.229, 0.224, 0.225], "order": "RGB" }
      ],
      "tensors": [ { "dtype": "float32", "layout": "CHW", "channel_order": "RGB", "shape": [1, 3, 224, 224] } ]
    },
    "output": { "decoder": "softmax-argmax", "labels": ["good", "blurry", "dark"] },
    "combine": "none"
  }
}
```

The report then shows an `[oral-image]` block and a `[quality-check]` block per photo. The photo is
decoded once and shared, because both pipelines use the same `decode` settings.

---

## 11b. Reproducing avni's current production settings

`app/src/main/assets/sdk-config.avni-current.json` reproduces what avni does on the test phone today. Its
synced server payload (logcat `MeanTargetBgrRounded`) has `resize=stretch`, `rounding=legacy`,
`uint8Round=true` and `ignoreColorProfile=false`. The same SDK, with only a different JSON, gives the
same model input as avni:

```json
"steps": [
  { "op": "resize", "size": [256, 256], "mode": "stretch", "interpolation": "cubic" },
  { "op": "gray_world", "mean_target": 128, "precision": "float64" },
  { "op": "clip", "min": 0, "max": 255 },
  { "op": "round", "decimals": 1, "mode": "half_up" },
  { "op": "to_uint8", "mode": "half_up" },
  { "op": "scale", "multiply": 0.00392156862745098 }
]
```

`sdk-config.default.json` is the QC-notebook pipeline (letterbox, numpy rounding). Switching between the
two is only a JSON change.

## 12. Error messages you may see on Apply

| message (start) | cause | fix |
|---|---|---|
| `Unsupported schema_version` | not 1 or 2 | set `2` |
| `Missing array 'models'` / `Missing object 'pipelines'` | required block absent | add it |
| `Duplicate model names` | two entries share a `name` | rename one |
| `model 'x' uses unknown pipeline 'y'` | typo in `pipeline` | match a key under `pipelines` |
| `model 'x' has no 'pipeline' and there are several` | more than one pipeline, model doesn't say which | add `pipeline` |
| `run_models refers to 'x'` | name not in `models` | fix the name |
| `Nothing to run` | no `run_models` and every model is `enabled: false` | enable one |
| `input.type 'text' is reserved but not implemented` | non-image type | use `image` for now |
| `Unsupported preprocessing op(s) [...]` | typo in `op` | use a name from 6.3 |
| `an image input produces exactly one tensor` | `tensors` has 0 or 2+ entries | keep one |
| `loading.strategy must be one of` | typo | `path`, `mmap` or `bytes` |
| `output.decoder must be one of` / `combine must be one of` | typo | see 6.5 / 6.6 |
| `diagnostics.reference_pipeline 'x' is not a declared pipeline` | typo | match a pipeline name |
| `Unresolved placeholder in path` | e.g. `${FILE}` | use `${FILES}`, `${EXTERNAL_FILES}` or `${CACHE}` |

Errors that only appear on **Load** or **Run**: model file not found, a wrong tensor `name`, a
`shape` mismatch, `center_crop` larger than the image, an unknown `execution_provider`, and LiteRT
models in a build without LiteRT.
