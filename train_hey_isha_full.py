import os
import sys
import random
import av
import numpy as np
import torch
import torch.nn as nn
from torch.utils.data import Dataset, DataLoader, random_split
from pathlib import Path
from openwakeword.model import Model

# Paths
WORKSPACE = Path(__file__).resolve().parent
RECORDINGS_DIR = Path(r"D:\Ansh Kesharwani\Documents\Sound recordings")
ASSETS_DIR = WORKSPACE / "android" / "app" / "src" / "main" / "assets"
WAKEWORD_ASSETS = ASSETS_DIR / "wakeword"
MEL_PATH = str(WAKEWORD_ASSETS / "melspectrogram.onnx")
EMB_PATH = str(WAKEWORD_ASSETS / "embedding_model.onnx")
OUTPUT_ONNX = WAKEWORD_ASSETS / "hey_isha.onnx"
BACKUP_ONNX = ASSETS_DIR / "hey_isha.onnx"

SAMPLE_RATE = 16000
CHUNK_SIZE = 1280      # 80ms
WINDOW_FRAMES = 16     # 16 x 80ms = 1.28s

print("=" * 60)
print("TRAINING HEY ISHA WAKE WORD MODEL (OpenWakeWord Architecture)")
print("=" * 60)

print("Loading OpenWakeWord feature extractor...")
oww = Model(
    wakeword_models=[str(WAKEWORD_ASSETS / "hey_mira.onnx")],
    inference_framework="onnx",
    melspec_model_path=MEL_PATH,
    embedding_model_path=EMB_PATH
)
print("Feature extractor initialized successfully.")

def decode_audio(file_path):
    container = av.open(str(file_path))
    stream = container.streams.audio[0]
    resampler = av.AudioResampler(format='s16', layout='mono', rate=SAMPLE_RATE)
    samples = []
    for frame in container.decode(stream):
        for r in resampler.resample(frame):
            samples.append(r.to_ndarray())
    container.close()
    if not samples:
        return np.zeros(0, dtype=np.int16)
    return np.concatenate(samples, axis=1).squeeze().astype(np.int16)

def get_embedding_for_clip(clip_16k_int16):
    """
    Feeds an audio clip of at least 1.28s (16 chunks = 20480 samples)
    through openWakeWord and returns the final (16, 96) embedding.
    """
    oww.preprocessor.reset()
    # Need at least 16 chunks
    n_chunks = len(clip_16k_int16) // CHUNK_SIZE
    for i in range(n_chunks):
        chunk = clip_16k_int16[i * CHUNK_SIZE : (i + 1) * CHUNK_SIZE]
        oww.predict(chunk)
    feat = oww.preprocessor.get_features(WINDOW_FRAMES) # (1, 16, 96)
    return feat[0] # (16, 96)

def resample_linear(audio, factor):
    """Simple linear interpolation for speed/pitch augmentation (+-5%)."""
    old_indices = np.arange(len(audio))
    new_length = int(len(audio) / factor)
    new_indices = np.linspace(0, len(audio) - 1, new_length)
    return np.interp(new_indices, old_indices, audio).astype(np.int16)

print("\nScanning recordings...")
recordings = sorted(list(RECORDINGS_DIR.glob("*.m4a")) + list(RECORDINGS_DIR.glob("*.wav")))
noise_files = sorted(list(RECORDINGS_DIR.glob("*.mp3")))
print(f"Found {len(recordings)} speech recordings (.m4a/.wav) in {RECORDINGS_DIR}")
print(f"Found {len(noise_files)} environmental noise files (.mp3) (horns, vehicles, animals, silent rooms)")

pos_embeddings = []
neg_embeddings = []

target_clip_len = WINDOW_FRAMES * CHUNK_SIZE # 20,480 samples (1.28s)

# ── Extract real environmental noise slices for Hard Negatives & Augmentation
print("\nExtracting environmental noise slices (Horns, Highway, Animals, Silent Rooms)...")
noise_audio_slices = []
for n_idx, n_path in enumerate(noise_files):
    try:
        n_raw = decode_audio(n_path)
        if len(n_raw) >= target_clip_len:
            n_chunks_file = len(n_raw) // target_clip_len
            # Extract up to 3 non-overlapping 1.28s slices per noise file
            for c_i in range(min(3, n_chunks_file)):
                slice_clip = n_raw[c_i * target_clip_len : (c_i + 1) * target_clip_len]
                noise_audio_slices.append(slice_clip)
                neg_embeddings.append(get_embedding_for_clip(slice_clip))
    except Exception as e:
        pass
print(f"Extracted {len(noise_audio_slices)} real environmental negative noise slices.")

print("\nExtracting positive and negative features from speech recordings...")
for idx, rec_path in enumerate(recordings):
    raw_audio = decode_audio(rec_path)
    if len(raw_audio) < target_clip_len:
        # Pad with silence if short
        pad_len = target_clip_len - len(raw_audio)
        raw_audio = np.pad(raw_audio, (0, pad_len))

    # Calculate energy in 80ms chunks
    n_chunks = len(raw_audio) // CHUNK_SIZE
    energies = np.array([np.sum(raw_audio[i*CHUNK_SIZE:(i+1)*CHUNK_SIZE].astype(np.float32)**2) for i in range(n_chunks)])

    # Find 1.28s (16 chunks) window with max energy = "Hey/Hello/Ok Isha"
    if n_chunks <= WINDOW_FRAMES:
        best_chunk = 0
    else:
        window_energies = [np.sum(energies[i:i+WINDOW_FRAMES]) for i in range(n_chunks - WINDOW_FRAMES + 1)]
        best_chunk = int(np.argmax(window_energies))

    start_sample = best_chunk * CHUNK_SIZE
    end_sample = start_sample + target_clip_len
    pos_clip = raw_audio[start_sample:end_sample]

    if len(pos_clip) < target_clip_len:
        pos_clip = np.pad(pos_clip, (0, target_clip_len - len(pos_clip)))

    # ── 1. Base positive embedding
    emb = get_embedding_for_clip(pos_clip)
    pos_embeddings.append(emb)

    # ── 2. Time-shifted variants (-1 chunk, +1 chunk)
    for shift in [-1, 1, 2]:
        shifted_start = max(0, min(len(raw_audio) - target_clip_len, (best_chunk + shift) * CHUNK_SIZE))
        shift_clip = raw_audio[shifted_start:shifted_start + target_clip_len]
        if len(shift_clip) == target_clip_len:
            pos_embeddings.append(get_embedding_for_clip(shift_clip))

    # ── 3. Volume augmentations (0.6x, 0.8x, 1.2x, 1.4x)
    for vol in [0.6, 0.8, 1.2, 1.4]:
        aug_clip = np.clip(pos_clip.astype(np.float32) * vol, -32767, 32767).astype(np.int16)
        pos_embeddings.append(get_embedding_for_clip(aug_clip))

    # ── 4. Noise augmentation (subtle ambient noise)
    noise = np.random.normal(0, random.uniform(80, 250), len(pos_clip)).astype(np.float32)
    noisy_clip = np.clip(pos_clip.astype(np.float32) + noise, -32767, 32767).astype(np.int16)
    pos_embeddings.append(get_embedding_for_clip(noisy_clip))

    # ── 4b. Real environmental noise augmentation (horns, traffic, room noise)
    if noise_audio_slices:
        random_noise = random.choice(noise_audio_slices)
        alpha = random.uniform(0.10, 0.30)
        real_noisy = np.clip(pos_clip.astype(np.float32) + alpha * random_noise.astype(np.float32), -32767, 32767).astype(np.int16)
        pos_embeddings.append(get_embedding_for_clip(real_noisy))

    # ── 5. Speed / pitch variation (+- 4%)
    for spd in [0.96, 1.04]:
        resampled = resample_linear(pos_clip, spd)
        if len(resampled) > target_clip_len:
            resampled = resampled[:target_clip_len]
        elif len(resampled) < target_clip_len:
            resampled = np.pad(resampled, (0, target_clip_len - len(resampled)))
        pos_embeddings.append(get_embedding_for_clip(resampled))

    # ── 6. Negative samples from THIS recording (silence before/after utterance)
    for i in range(0, n_chunks - WINDOW_FRAMES + 1, 4):
        if abs(i - best_chunk) >= 18: # >= 1.44s away
            neg_clip = raw_audio[i*CHUNK_SIZE : i*CHUNK_SIZE + target_clip_len]
            if len(neg_clip) == target_clip_len:
                neg_embeddings.append(get_embedding_for_clip(neg_clip))

    # ── 7. Negative sample: Reversed speech (exact voice formants, backward)
    rev_clip = np.flip(pos_clip).copy()
    neg_embeddings.append(get_embedding_for_clip(rev_clip))

    # ── 8. Hard Negative: "Hey" only (first half, followed by silence)
    half = target_clip_len // 2
    hey_only = np.pad(pos_clip[:half], (0, target_clip_len - half))
    neg_embeddings.append(get_embedding_for_clip(hey_only))

    # ── 9. Hard Negative: "Isha" only (silence, followed by second half)
    isha_only = np.pad(pos_clip[half:], (half, 0))
    neg_embeddings.append(get_embedding_for_clip(isha_only))

    # ── 10. Hard Negative: Swapped order ("Isha Hey")
    swapped = np.concatenate([pos_clip[half:], pos_clip[:half]])
    neg_embeddings.append(get_embedding_for_clip(swapped))

    # ── 11. Hard Negative: Scrambled quarters
    q = target_clip_len // 4
    scrambled = np.concatenate([pos_clip[2*q:3*q], pos_clip[:q], pos_clip[3*q:], pos_clip[q:2*q]])
    neg_embeddings.append(get_embedding_for_clip(scrambled))

    if (idx + 1) % 20 == 0 or (idx + 1) == len(recordings):
        print(f"Processed {idx + 1}/{len(recordings)} files -> {len(pos_embeddings)} positive, {len(neg_embeddings)} negative samples")

# ── 12. Cross-Utterance Spliced Negatives (Real human speech mixtures)
print("\nGenerating cross-utterance speech mixtures (non-keyword real voice chatter)...")
for _ in range(250):
    idx_a, idx_b = random.sample(range(len(recordings)), 2)
    rec_a = decode_audio(recordings[idx_a])
    rec_b = decode_audio(recordings[idx_b])
    # Take random 1.28s slices from both and blend them
    s_a = random.randint(0, max(0, len(rec_a) - target_clip_len))
    s_b = random.randint(0, max(0, len(rec_b) - target_clip_len))
    clip_a = rec_a[s_a : s_a + target_clip_len]
    clip_b = rec_b[s_b : s_b + target_clip_len]
    if len(clip_a) < target_clip_len: clip_a = np.pad(clip_a, (0, target_clip_len - len(clip_a)))
    if len(clip_b) < target_clip_len: clip_b = np.pad(clip_b, (0, target_clip_len - len(clip_b)))
    
    # Interleave halves: half of A + half of B (sounds like conversational speech, but never "Hey Isha")
    half = target_clip_len // 2
    blended = np.concatenate([clip_a[:half], clip_b[half:]])
    neg_embeddings.append(get_embedding_for_clip(blended))

# ── 13. Formant Harmonic Negatives (Simulated vowels and vocal tract frequencies)
print("Generating vocal tract formant harmonics and room acoustic noise...")
t = np.linspace(0, 1.28, target_clip_len)
for _ in range(150):
    # Formants: F1 (300-800Hz), F2 (900-2200Hz), F3 (2400-3200Hz)
    f1 = random.uniform(300, 800)
    f2 = random.uniform(900, 2200)
    f3 = random.uniform(2400, 3200)
    vocal = (0.5 * np.sin(2 * np.pi * f1 * t) + 
             0.3 * np.sin(2 * np.pi * f2 * t) + 
             0.2 * np.sin(2 * np.pi * f3 * t)) * random.uniform(3000, 14000)
    neg_embeddings.append(get_embedding_for_clip(vocal.astype(np.int16)))

# ── 14. Room Mic Hum, Silence & White/Pink Noise
for _ in range(120):
    silence = np.random.normal(0, random.uniform(2, 20), target_clip_len).astype(np.int16)
    neg_embeddings.append(get_embedding_for_clip(silence))

for _ in range(120):
    wn = np.random.normal(0, random.uniform(150, 800), target_clip_len).astype(np.int16)
    neg_embeddings.append(get_embedding_for_clip(wn))

for _ in range(100):
    freq = random.uniform(150, 3500)
    sine = (np.sin(2 * np.pi * freq * t) * random.uniform(2000, 12000)).astype(np.int16)
    neg_embeddings.append(get_embedding_for_clip(sine))

pos_embeddings = np.array(pos_embeddings, dtype=np.float32)
neg_embeddings = np.array(neg_embeddings, dtype=np.float32)

print(f"\nFinal Dataset Size:")
print(f"  Positive ('Hey Isha'): {len(pos_embeddings)} samples")
print(f"  Negative (Non-wake):   {len(neg_embeddings)} samples")
print(f"  Embedding Shape:       {pos_embeddings.shape[1:]}  (16 frames x 96 features)")

# ── PyTorch Dataset
class WakeWordDataset(Dataset):
    def __init__(self, pos, neg):
        X = np.concatenate([pos, neg], axis=0)
        y = np.concatenate([np.ones(len(pos), dtype=np.float32), np.zeros(len(neg), dtype=np.float32)])
        self.X = torch.from_numpy(X) # (N, 16, 96)
        self.y = torch.from_numpy(y).unsqueeze(1) # (N, 1)

    def __len__(self):
        return len(self.X)

    def __getitem__(self, idx):
        return self.X[idx], self.y[idx]

dataset = WakeWordDataset(pos_embeddings, neg_embeddings)
n_val = int(len(dataset) * 0.15)
n_train = len(dataset) - n_val
train_ds, val_ds = random_split(dataset, [n_train, n_val], generator=torch.Generator().manual_seed(42))

train_loader = DataLoader(train_ds, batch_size=32, shuffle=True)
val_loader = DataLoader(val_ds, batch_size=32, shuffle=False)

# ── OpenWakeWord Classifier Model Architecture
class OpenWakeWordClassifier(nn.Module):
    def __init__(self, input_dim=96, hidden_dim=32):
        super().__init__()
        # Conv1D feature extractor
        self.conv = nn.Sequential(
            nn.Conv1d(input_dim, hidden_dim, kernel_size=3, padding=1),
            nn.LayerNorm([hidden_dim, 16]),
            nn.ReLU(),
            nn.Conv1d(hidden_dim, hidden_dim, kernel_size=3, padding=1),
            nn.LayerNorm([hidden_dim, 16]),
            nn.ReLU()
        )
        # Self-attention over 16 time frames
        self.attention = nn.MultiheadAttention(embed_dim=hidden_dim, num_heads=1, batch_first=True)
        self.attn_norm = nn.LayerNorm(hidden_dim)
        # Classification head
        self.head = nn.Sequential(
            nn.Linear(hidden_dim, 1),
            nn.Sigmoid()
        )

    def forward(self, x):
        # x: (batch, 16, 96)
        x_conv = x.transpose(1, 2) # (batch, 96, 16)
        out = self.conv(x_conv)   # (batch, 32, 16)
        out_trans = out.transpose(1, 2) # (batch, 16, 32)
        attn_out, _ = self.attention(out_trans, out_trans, out_trans)
        out = self.attn_norm(out_trans + attn_out)
        out = out.mean(dim=1)     # (batch, 32)
        score = self.head(out)    # (batch, 1)
        return score

device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
print(f"\nTraining OpenWakeWordClassifier on: {device}")
model = OpenWakeWordClassifier().to(device)

criterion = nn.BCELoss()
optimizer = torch.optim.AdamW(model.parameters(), lr=1e-3, weight_decay=1e-4)
scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=40)

EPOCHS = 40
best_val_loss = float("inf")
best_state = None

print(f"{'Epoch':>5} | {'Train Loss':>10} | {'Train Acc':>9} | {'Val Loss':>8} | {'Val Acc':>7}")
print("-" * 52)

for epoch in range(1, EPOCHS + 1):
    model.train()
    train_loss, train_correct, train_total = 0.0, 0, 0
    for X_b, y_b in train_loader:
        X_b, y_b = X_b.to(device), y_b.to(device)
        optimizer.zero_grad()
        preds = model(X_b)
        loss = criterion(preds, y_b)
        loss.backward()
        nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optimizer.step()

        train_loss += loss.item() * len(y_b)
        train_correct += ((preds >= 0.5) == (y_b >= 0.5)).sum().item()
        train_total += len(y_b)
    scheduler.step()

    model.eval()
    val_loss, val_correct, val_total = 0.0, 0, 0
    with torch.no_grad():
        for X_b, y_b in val_loader:
            X_b, y_b = X_b.to(device), y_b.to(device)
            preds = model(X_b)
            loss = criterion(preds, y_b)
            val_loss += loss.item() * len(y_b)
            val_correct += ((preds >= 0.5) == (y_b >= 0.5)).sum().item()
            val_total += len(y_b)

    t_loss = train_loss / train_total
    t_acc = train_correct / train_total
    v_loss = val_loss / val_total
    v_acc = val_correct / val_total

    if epoch % 5 == 0 or epoch == 1 or epoch == EPOCHS:
        print(f"{epoch:5d} | {t_loss:10.4f} | {t_acc:8.2%} | {v_loss:8.4f} | {v_acc:6.2%}")

    if v_loss < best_val_loss:
        best_val_loss = v_loss
        best_state = {k: v.cpu().clone() for k, v in model.state_dict().items()}

print(f"\nBest Validation Loss: {best_val_loss:.4f}")
model.load_state_dict(best_state)
model.eval()
model.to("cpu")

# ── Export to ONNX
print("\nExporting model to ONNX...")
dummy_input = torch.randn(1, 16, 96, dtype=torch.float32)

OUTPUT_ONNX.parent.mkdir(parents=True, exist_ok=True)
BACKUP_ONNX.parent.mkdir(parents=True, exist_ok=True)

torch.onnx.export(
    model, dummy_input, str(OUTPUT_ONNX),
    input_names=['embeddings'],
    output_names=['score'],
    dynamic_axes={'embeddings': {0: 'batch'}, 'score': {0: 'batch'}},
    opset_version=14,
    dynamo=False
)
print(f"Saved: {OUTPUT_ONNX} ({OUTPUT_ONNX.stat().st_size / 1024:.1f} KB)")

# Also save copy to assets root
import shutil
shutil.copy2(str(OUTPUT_ONNX), str(BACKUP_ONNX))
print(f"Saved backup: {BACKUP_ONNX}")

# ── Validation Test with openWakeWord
print("\n" + "=" * 60)
print("TESTING TRAINED MODEL IN OPENWAKEWORD ENGINE")
print("=" * 60)

test_oww = Model(
    wakeword_models=[str(OUTPUT_ONNX)],
    inference_framework="onnx",
    melspec_model_path=MEL_PATH,
    embedding_model_path=EMB_PATH
)

model_key = list(test_oww.models.keys())[0]
print(f"Loaded model in OpenWakeWord: '{model_key}'")

# Test 1: Silence
test_oww.preprocessor.reset()
silence_score = test_oww.predict(np.zeros(CHUNK_SIZE, dtype=np.int16))[model_key]
print(f"Test 1 - Silence Score: {silence_score:.4f} (Expected ~0.0)")

# Test 2: Environmental Noise Test (Horns, Highway, Animals)
noise_scores = []
if noise_audio_slices:
    test_noise_clips = random.sample(noise_audio_slices, min(10, len(noise_audio_slices)))
    for n_clip in test_noise_clips:
        test_oww.preprocessor.reset()
        max_n_score = 0.0
        for i in range(0, len(n_clip) - CHUNK_SIZE + 1, CHUNK_SIZE):
            p = test_oww.predict(n_clip[i:i + CHUNK_SIZE])
            max_n_score = max(max_n_score, p[model_key])
        noise_scores.append(max_n_score)
    avg_noise = sum(noise_scores) / len(noise_scores)
    max_noise = max(noise_scores)
    print(f"Test 2 - Environmental Noise Rejection: Avg={avg_noise:.4f}, Max={max_noise:.4f} (Expected <0.10)")

# Test 3: Testing across sample recordings of "Hey/Hello/Ok Isha"
detected_count = 0
test_recs = random.sample(recordings, min(12, len(recordings)))

print(f"\nTest 3 - Testing on {len(test_recs)} random user speech recordings:")
for rec_path in test_recs:
    audio = decode_audio(rec_path)
    test_oww.preprocessor.reset()
    max_score = 0.0
    for i in range(0, len(audio) - CHUNK_SIZE + 1, CHUNK_SIZE):
        p = test_oww.predict(audio[i:i + CHUNK_SIZE])
        score = p[model_key]
        if score > max_score:
            max_score = score
    detected = max_score >= 0.35
    if detected:
        detected_count += 1
    print(f"  {rec_path.name:<24} Peak Score: {max_score:.3f} -> {'[TRIGGERED]' if detected else '[MISSED]'}")

print(f"\nDetection Rate: {detected_count}/{len(test_recs)} ({detected_count/len(test_recs):.1%})")
print("\nAll done! hey_isha.onnx is fully trained and deployed to assets/wakeword/hey_isha.onnx!")
