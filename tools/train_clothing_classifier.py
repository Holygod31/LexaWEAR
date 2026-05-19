#!/usr/bin/env python3
"""
LexaWEAR — Clothing Classifier Training Script
===============================================
Trains a MobileNetV2-based clothing type classifier and exports it to TFLite.

Usage
-----
  python tools/train_clothing_classifier.py \
      --dataset_dir /path/to/deepfashion2 \
      --output_dir  ./output \
      --epochs      20

Output
------
  output/clothing_classifier.tflite   ← drop this into app/src/main/assets/
  output/labels.txt                   ← verify order matches VisionAnalyzer.CLOTHING_LABELS

Requirements
------------
  pip install tensorflow tflite-model-maker Pillow numpy

Hardware
--------
  CPU:  ~4–6 hours for 20 epochs on a modern laptop
  GPU:  ~30–60 minutes (CUDA or Apple Silicon MPS)
  Colab Pro: recommended free option — GPU runtime, ~45 min

See docs/clothing_classifier_training.md for full instructions.
"""

import argparse
import os
import sys
import json

# ── Argument parsing ──────────────────────────────────────────────────────────

parser = argparse.ArgumentParser(description="Train LexaWEAR clothing classifier")
parser.add_argument("--dataset_dir",  required=True,  help="Path to prepared dataset directory")
parser.add_argument("--output_dir",   default="output", help="Where to save the TFLite model")
parser.add_argument("--epochs",       type=int, default=20, help="Number of training epochs")
parser.add_argument("--batch_size",   type=int, default=32, help="Training batch size")
parser.add_argument("--img_size",     type=int, default=224, help="Input image size (width = height)")
parser.add_argument("--learning_rate",type=float, default=0.001, help="Initial learning rate")
parser.add_argument("--validation_split", type=float, default=0.2, help="Fraction of data for validation")
args = parser.parse_args()

# ── Class mapping ─────────────────────────────────────────────────────────────
#
# Maps dataset folder names → LexaWEAR type codes.
# This is the canonical class order. VisionAnalyzer.CLOTHING_LABELS must match
# the order of this list EXACTLY after training.
#
# If you use a different dataset, update the folder names (left column) to
# match your folder structure. Do NOT change the right column (LexaWEAR codes)
# unless you also update VisionAnalyzer.CLOTHING_LABELS.

CLASS_MAPPING = [
    ("shirt",          "SH"),   # index 0
    ("tshirt",         "TS"),   # index 1
    ("jacket",         "JK"),   # index 2
    ("coat",           "CT"),   # index 3
    ("sweater",        "SW"),   # index 4
    ("hoodie",         "HD"),   # index 5
    ("blazer",         "BZ"),   # index 6
    ("suit",           "SU"),   # index 7
    ("vest",           "VS"),   # index 8
    ("dress",          "DR"),   # index 9
    ("pants",          "PT"),   # index 10
    ("jeans",          "JN"),   # index 11
    ("shorts",         "ST"),   # index 12
    ("underwear",      "UW"),   # index 13
    ("socks",          "SC"),   # index 14
]

FOLDER_NAMES  = [c[0] for c in CLASS_MAPPING]
LEXAWEAR_CODES = [c[1] for c in CLASS_MAPPING]
NUM_CLASSES   = len(CLASS_MAPPING)

# ── Import TensorFlow ─────────────────────────────────────────────────────────

try:
    import tensorflow as tf
    import numpy as np
    from tensorflow.keras import layers, models
    from tensorflow.keras.applications import MobileNetV2
    from tensorflow.keras.preprocessing.image import ImageDataGenerator
    print(f"TensorFlow {tf.__version__} loaded.")
except ImportError:
    print("ERROR: TensorFlow not found. Install with:")
    print("  pip install tensorflow")
    sys.exit(1)

# ── Dataset preparation ───────────────────────────────────────────────────────

print(f"\nLoading dataset from: {args.dataset_dir}")
print(f"Expected structure:")
for folder in FOLDER_NAMES:
    print(f"  {args.dataset_dir}/{folder}/  (images)")

# Verify folders exist
missing = [f for f in FOLDER_NAMES if not os.path.isdir(os.path.join(args.dataset_dir, f))]
if missing:
    print(f"\nERROR: Missing dataset folders: {missing}")
    print("See docs/clothing_classifier_training.md → 'Preparing the Dataset'")
    sys.exit(1)

# Data augmentation for training
train_datagen = ImageDataGenerator(
    rescale=1.0 / 255,
    rotation_range=15,
    width_shift_range=0.1,
    height_shift_range=0.1,
    horizontal_flip=True,
    zoom_range=0.1,
    validation_split=args.validation_split
)

val_datagen = ImageDataGenerator(
    rescale=1.0 / 255,
    validation_split=args.validation_split
)

train_generator = train_datagen.flow_from_directory(
    args.dataset_dir,
    classes=FOLDER_NAMES,
    target_size=(args.img_size, args.img_size),
    batch_size=args.batch_size,
    class_mode="categorical",
    subset="training"
)

val_generator = val_datagen.flow_from_directory(
    args.dataset_dir,
    classes=FOLDER_NAMES,
    target_size=(args.img_size, args.img_size),
    batch_size=args.batch_size,
    class_mode="categorical",
    subset="validation"
)

print(f"\nTraining samples:   {train_generator.samples}")
print(f"Validation samples: {val_generator.samples}")
print(f"Classes: {train_generator.class_indices}")

# ── Model architecture ────────────────────────────────────────────────────────
#
# MobileNetV2 pre-trained on ImageNet, fine-tuned for clothing classification.
# The base layers are frozen for the first phase, then partially unfrozen
# for fine-tuning in the second phase.

print(f"\nBuilding MobileNetV2 model (input: {args.img_size}×{args.img_size}×3)...")

base_model = MobileNetV2(
    input_shape=(args.img_size, args.img_size, 3),
    include_top=False,
    weights="imagenet"
)
base_model.trainable = False  # Phase 1: freeze base

model = models.Sequential([
    base_model,
    layers.GlobalAveragePooling2D(),
    layers.BatchNormalization(),
    layers.Dense(256, activation="relu"),
    layers.Dropout(0.4),
    layers.Dense(NUM_CLASSES, activation="softmax")
])

model.compile(
    optimizer=tf.keras.optimizers.Adam(learning_rate=args.learning_rate),
    loss="categorical_crossentropy",
    metrics=["accuracy"]
)

model.summary()

# ── Phase 1: Train top layers ─────────────────────────────────────────────────

phase1_epochs = max(5, args.epochs // 3)
print(f"\nPhase 1: Training top layers ({phase1_epochs} epochs)...")

callbacks = [
    tf.keras.callbacks.EarlyStopping(patience=5, restore_best_weights=True),
    tf.keras.callbacks.ReduceLROnPlateau(factor=0.5, patience=3, min_lr=1e-6),
]

history1 = model.fit(
    train_generator,
    epochs=phase1_epochs,
    validation_data=val_generator,
    callbacks=callbacks
)

# ── Phase 2: Fine-tune top layers of base model ───────────────────────────────

phase2_epochs = args.epochs - phase1_epochs
print(f"\nPhase 2: Fine-tuning ({phase2_epochs} epochs)...")

# Unfreeze the top 30 layers of the base model.
base_model.trainable = True
for layer in base_model.layers[:-30]:
    layer.trainable = False

model.compile(
    optimizer=tf.keras.optimizers.Adam(learning_rate=args.learning_rate / 10),
    loss="categorical_crossentropy",
    metrics=["accuracy"]
)

history2 = model.fit(
    train_generator,
    epochs=phase2_epochs,
    validation_data=val_generator,
    callbacks=callbacks
)

# ── Evaluation ────────────────────────────────────────────────────────────────

print("\nEvaluating on validation set...")
val_loss, val_acc = model.evaluate(val_generator)
print(f"Validation accuracy: {val_acc:.4f} ({val_acc * 100:.1f}%)")

if val_acc < 0.70:
    print("\nWARNING: Validation accuracy below 70%. Consider:")
    print("  - More training data per class (aim for 500+ images each)")
    print("  - More epochs (try --epochs 40)")
    print("  - Better data quality (clear clothing images, consistent backgrounds)")

# ── TFLite export ─────────────────────────────────────────────────────────────

os.makedirs(args.output_dir, exist_ok=True)

print(f"\nConverting to TFLite...")
converter = tf.lite.TFLiteConverter.from_keras_model(model)

# Optimise for mobile: float16 quantisation (good balance of size vs accuracy).
converter.optimizations = [tf.lite.Optimize.DEFAULT]
converter.target_spec.supported_types = [tf.float16]

tflite_model  = converter.convert()
output_path   = os.path.join(args.output_dir, "clothing_classifier.tflite")

with open(output_path, "wb") as f:
    f.write(tflite_model)

print(f"TFLite model saved: {output_path}")
print(f"Model size: {os.path.getsize(output_path) / 1024 / 1024:.1f} MB")

# ── Save labels file ──────────────────────────────────────────────────────────
#
# This file documents the class output order so you can verify it matches
# VisionAnalyzer.CLOTHING_LABELS before deploying the model.

labels_path = os.path.join(args.output_dir, "labels.txt")
with open(labels_path, "w") as f:
    f.write("# LexaWEAR clothing classifier output labels\n")
    f.write("# Index : Folder name → LexaWEAR code\n")
    f.write("# VisionAnalyzer.CLOTHING_LABELS must match this order exactly.\n\n")
    for i, (folder, code) in enumerate(CLASS_MAPPING):
        f.write(f"{i:2d} : {folder:<15} → {code}\n")

print(f"Labels file saved: {labels_path}")

# ── Summary ───────────────────────────────────────────────────────────────────

print("\n" + "="*60)
print("DONE. Next steps:")
print(f"  1. Copy {output_path}")
print(f"     → app/src/main/assets/clothing_classifier.tflite")
print(f"  2. Open VisionAnalyzer.kt")
print(f"     Verify CLOTHING_LABELS matches {labels_path}")
print(f"  3. Build and run the app.")
print(f"     The custom model is picked up automatically on next launch.")
print("="*60)