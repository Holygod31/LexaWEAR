# LexaWEAR — Clothing Classifier Training Guide

This guide explains how to train a custom clothing type classifier and integrate it into the app. The trained model replaces the ML Kit Image Labeling fallback with a purpose-built clothing classifier, which is significantly more accurate for the specific garment types LexaWEAR supports.

---

## How it works

`VisionAnalyzer` uses a two-tier approach:

**Tier 1 — Custom TFLite model (preferred)**
Drop `clothing_classifier.tflite` into `app/src/main/assets/`. The app picks it up automatically on next launch and uses it for all clothing type detection. No code change required.

**Tier 2 — ML Kit Image Labeling (fallback)**
Used automatically when `clothing_classifier.tflite` is absent. Less accurate but requires no training. This is the current state before you train.

---

## Requirements

**Python environment:**
```
pip install tensorflow tflite-model-maker Pillow numpy
```

**Hardware:**
- CPU only: 4–6 hours for 20 epochs on a modern laptop
- GPU (CUDA or Apple Silicon MPS): 30–60 minutes
- Google Colab Pro (recommended): ~45 minutes on GPU runtime, free

**Storage:** DeepFashion2 is ~25 GB. Make sure you have space.

---

## Step 1 — Get the dataset

We recommend **DeepFashion2** as the primary dataset.

**DeepFashion2:**
- URL: https://github.com/switchablenorms/DeepFashion2
- Licence: CC BY-NC 4.0 (non-commercial use)
- Size: ~491,000 images across 13 clothing categories
- Download requires filling a Google Form on the repository page

**Alternative datasets (if DeepFashion2 is unavailable):**

| Dataset | Images | Categories | Notes |
|---|---|---|---|
| Fashion-MNIST | 70,000 | 10 | Very small images (28×28), good for quick testing |
| iMaterialist (FGVC5) | 1M+ | 228 | Kaggle competition, fine-grained |
| Clothing1M | 1M | 14 | Noisy labels, use with caution |
| Your own photos | Any | 15 | Best results — photos taken in same conditions as app usage |

For best real-world accuracy, supplement any public dataset with photos taken on actual phones in typical use conditions (hands holding garments, various lighting).

---

## Step 2 — Prepare the dataset

The training script expects this folder structure:

```
dataset/
  shirt/
    img001.jpg
    img002.jpg
    ...
  tshirt/
    img001.jpg
    ...
  jacket/
  coat/
  sweater/
  hoodie/
  blazer/
  suit/
  vest/
  dress/
  pants/
  jeans/
  shorts/
  underwear/
  socks/
```

**Minimum images per class:** 200 (500+ recommended for good accuracy).

**DeepFashion2 → folder structure:**
DeepFashion2 uses category IDs (1–13). Map them to folder names:

| DeepFashion2 ID | Category | Target folder |
|---|---|---|
| 1 | short sleeve top | tshirt |
| 2 | long sleeve top | shirt |
| 3 | short sleeve outwear | jacket |
| 4 | long sleeve outwear | coat |
| 5 | vest | vest |
| 6 | sling | underwear |
| 7 | shorts | shorts |
| 8 | trousers | pants |
| 9 | skirt | dress |
| 10 | short sleeve dress | dress |
| 11 | long sleeve dress | dress |
| 12 | vest dress | dress |
| 13 | sling dress | dress |

A helper script for converting DeepFashion2 annotations to this folder structure is beyond the scope of this guide, but the annotation format is documented in the DeepFashion2 repository.

---

## Step 3 — Run the training script

```bash
python tools/train_clothing_classifier.py \
    --dataset_dir /path/to/your/dataset \
    --output_dir  ./output \
    --epochs      20
```

**Options:**

| Flag | Default | Description |
|---|---|---|
| `--dataset_dir` | required | Path to the prepared dataset folder |
| `--output_dir` | `./output` | Where to save the model and labels file |
| `--epochs` | 20 | Total training epochs (phase 1 + phase 2) |
| `--batch_size` | 32 | Reduce to 16 if you get out-of-memory errors |
| `--img_size` | 224 | Input image size — matches `TFLITE_INPUT_SIZE` in `VisionAnalyzer` |
| `--learning_rate` | 0.001 | Initial learning rate |
| `--validation_split` | 0.2 | Fraction of data held out for validation |

**What the script does:**
1. Loads images from the dataset folder
2. Applies data augmentation (rotation, flip, zoom) to improve generalisation
3. Phase 1: trains only the top classification layers (MobileNetV2 base frozen)
4. Phase 2: fine-tunes the top 30 layers of MobileNetV2
5. Exports to TFLite with float16 quantisation (~6–8 MB)
6. Saves a `labels.txt` documenting the class output order

**Expected output:**
```
output/
  clothing_classifier.tflite   ← the model to deploy
  labels.txt                   ← verify this matches CLOTHING_LABELS
```

**Expected accuracy:** 75–85% on a balanced dataset with 500+ images per class. Below 70% means more data or more epochs are needed.

---

## Step 4 — Verify the label order

Open `output/labels.txt`. It will look like this:

```
 0 : shirt           → SH
 1 : tshirt          → TS
 2 : jacket          → JK
 ...
```

Open `VisionAnalyzer.kt` and find `CLOTHING_LABELS`:

```kotlin
val CLOTHING_LABELS = listOf(
    "SH",  // index 0 — must match labels.txt index 0
    "TS",  // index 1 — must match labels.txt index 1
    "JK",  // index 2
    ...
)
```

The order must match exactly. If you trained with a different class order (e.g. because you renamed folders or added/removed classes), update `CLOTHING_LABELS` to match `labels.txt`.

**This is the most common source of errors.** A mismatch means the app will label a jacket as a shirt, etc.

---

## Step 5 — Deploy the model

1. Copy `output/clothing_classifier.tflite` to `app/src/main/assets/`
2. Build and run the app
3. Open the camera and point it at a clothing item
4. The app will automatically use the custom model — no code change required

You can verify it's being used: in `CameraFragment`, the result label will show `TFLite: JK (87%)` instead of `ML Kit: jacket (72%)` in the debug output.

---

## Updating CLOTHING_LABELS

If you retrain with a different class mapping, update `CLOTHING_LABELS` in `VisionAnalyzer.kt`:

```kotlin
// Current default (matches train_clothing_classifier.py CLASS_MAPPING)
val CLOTHING_LABELS = listOf(
    "SH", "TS", "JK", "CT", "SW", "HD", "BZ",
    "SU", "VS", "DR", "PT", "JN", "ST", "UW", "SC"
)
```

Change the list to match your `labels.txt` order. The length must equal the number of classes your model was trained on.

---

## Confidence threshold

The model's softmax output is compared against `TFLITE_TYPE_THRESHOLD = 0.55f` in `VisionAnalyzer`. If the top class probability is below this:
- The type field is omitted from the result
- `typeLowConfidence = true` is set
- `CameraFragment` announces "Item type unclear — please select it manually"

Tune this threshold if your model is consistently above or below it on real-world images. A well-trained model on a good dataset should push most correct predictions above 0.7.

---

## Running on Google Colab

1. Upload your dataset to Google Drive
2. Open a new Colab notebook with GPU runtime
3. Mount your Drive:
   ```python
   from google.colab import drive
   drive.mount('/content/drive')
   ```
4. Install dependencies:
   ```bash
   !pip install tensorflow Pillow numpy
   ```
5. Upload `tools/train_clothing_classifier.py` to Colab
6. Run:
   ```bash
   !python train_clothing_classifier.py \
       --dataset_dir /content/drive/MyDrive/lexawear_dataset \
       --output_dir  /content/drive/MyDrive/lexawear_output \
       --epochs 25
   ```
7. Download `clothing_classifier.tflite` from your Drive output folder

---

## Troubleshooting

**Out of memory during training:**
Reduce `--batch_size` to 16 or 8.

**Validation accuracy stuck below 60%:**
- Check that folder names match `CLASS_MAPPING` in the script exactly
- Make sure images are actual clothing photos, not cartoons or sketches
- Try more epochs (`--epochs 40`) or more data

**Model file not found / app still using ML Kit:**
- Verify the file is at `app/src/main/assets/clothing_classifier.tflite` exactly
- Check `VisionAnalyzer.isCustomModelAvailable` — add a log statement if needed
- Rebuild the app after adding the asset

**Wrong type detected (model loaded but wrong output):**
- Almost certainly a `CLOTHING_LABELS` order mismatch
- Compare `labels.txt` with `VisionAnalyzer.CLOTHING_LABELS` index by index

---

## Relationship to ColorPalette

The clothing classifier only handles type detection (T field). Colour detection is handled entirely by pixel analysis in `VisionAnalyzer.extractDominantColor()`, which uses k-means clustering mapped to `ColorPalette`. The model does not need to detect or output colour information.