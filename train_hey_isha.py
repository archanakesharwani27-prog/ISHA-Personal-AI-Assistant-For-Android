import os, sys, subprocess, shutil, random
import numpy as np
from pathlib import Path

# PATHS
RECORDINGS_DIR = Path(r"D:\Ansh Kesharwani\Documents\Sound recordings")
WORK_DIR       = Path(r"D:\Projects\aura_shell_backup\hey_isha_train_workspace")
OUTPUT_ONNX    = Path(r"D:\Projects\aura_shell_backup\android\app\src\main\assets\hey_isha.onnx")

SAMPLE_RATE   = 16000
N_MELS        = 40
N_FFT         = 512
HOP_LENGTH    = 160
CHUNK_SEC     = 1.5
EPOCHS        = 60
BATCH_SIZE    = 32
LR            = 1e-3
TEST_SPLIT    = 0.15

CHUNK_SAMPLES = int(CHUNK_SEC * SAMPLE_RATE)

def pip_install(pkg):
    subprocess.check_call([sys.executable, "-m", "pip", "install", pkg, "-q"])

REQUIRED = ["torch", "torchaudio", "librosa", "soundfile", "scikit-learn", "onnx", "onnxscript"]
for p in REQUIRED:
    try:
        __import__(p.replace("-","_"))
    except ImportError:
        print(f"Installing {p}...")
        pip_install(p)

import torch, torchaudio, librosa, soundfile as sf
import torch.nn as nn
from torch.utils.data import Dataset, DataLoader, random_split

print("All packages ready!")

def check_ffmpeg():
    if shutil.which("ffmpeg") is None:
        print("ERROR: ffmpeg not found. Install: winget install ffmpeg")
        sys.exit(1)
    print("ffmpeg found")

def convert_m4a_to_wav(src_dir, dst_dir):
    dst_dir.mkdir(parents=True, exist_ok=True)
    m4a_files = list(src_dir.glob("*.m4a"))
    print(f"Converting {len(m4a_files)} recordings...")
    converted = []
    for f in m4a_files:
        out = dst_dir / (f.stem + ".wav")
        if not out.exists():
            ret = subprocess.run(["ffmpeg","-y","-i",str(f),"-ar",str(SAMPLE_RATE),"-ac","1","-c:a","pcm_s16le",str(out)], capture_output=True)
            if ret.returncode != 0:
                continue
        converted.append(out)
    print(f"  {len(converted)} WAV files ready")
    return converted

def load_wav(path):
    audio, sr = sf.read(str(path), dtype="float32")
    if sr != SAMPLE_RATE:
        audio = librosa.resample(audio, orig_sr=sr, target_sr=SAMPLE_RATE)
    if audio.ndim > 1:
        audio = audio.mean(axis=1)
    return audio

def slice_audio(audio):
    clips = []
    if len(audio) < CHUNK_SAMPLES // 2:
        return clips
    if len(audio) <= CHUNK_SAMPLES:
        pad = CHUNK_SAMPLES - len(audio)
        audio = np.pad(audio, (pad//2, pad - pad//2))
        clips.append(audio)
    else:
        step = CHUNK_SAMPLES // 2
        for start in range(0, len(audio) - CHUNK_SAMPLES + 1, step):
            clips.append(audio[start:start + CHUNK_SAMPLES])
    return clips

def augment(audio):
    variants = [audio]
    variants.append(np.clip(audio + np.random.randn(len(audio)).astype(np.float32)*0.005,-1,1))
    variants.append(np.clip(audio * random.uniform(1.1,1.4),-1,1))
    variants.append(audio * random.uniform(0.5,0.8))
    try: variants.append(librosa.effects.pitch_shift(audio, sr=SAMPLE_RATE, n_steps=1))
    except: pass
    try: variants.append(librosa.effects.pitch_shift(audio, sr=SAMPLE_RATE, n_steps=-1))
    except: pass
    return variants

mel_transform = torchaudio.transforms.MelSpectrogram(sample_rate=SAMPLE_RATE, n_fft=N_FFT, hop_length=HOP_LENGTH, n_mels=N_MELS)
db_transform  = torchaudio.transforms.AmplitudeToDB()

def audio_to_mel(audio):
    t = torch.from_numpy(audio).unsqueeze(0)
    mel = mel_transform(t)
    mel = db_transform(mel)
    mel = (mel - mel.mean()) / (mel.std() + 1e-8)
    return mel

class WakeWordDataset(Dataset):
    def __init__(self, samples): self.samples = samples
    def __len__(self): return len(self.samples)
    def __getitem__(self, idx): return self.samples[idx]

def build_dataset(positive_wavs):
    samples = []
    pos_count = 0
    print("Building positive samples (Hey Isha)...")
    for wp in positive_wavs:
        audio = load_wav(wp)
        for clip in slice_audio(audio):
            for aug in augment(clip):
                mel = audio_to_mel(aug.astype(np.float32))
                samples.append((mel, torch.tensor(1, dtype=torch.long)))
                pos_count += 1
    print(f"  Positive: {pos_count}")
    neg_count = 0
    target_neg = max(pos_count, 400)
    while neg_count < target_neg:
        c = random.randint(0,2)
        if c == 0:
            audio = np.random.randn(CHUNK_SAMPLES).astype(np.float32) * 0.1
        elif c == 1:
            audio = np.random.randn(CHUNK_SAMPLES).astype(np.float32) * 0.001
        else:
            freq = random.uniform(100,3000)
            t = np.linspace(0, CHUNK_SEC, CHUNK_SAMPLES)
            audio = (np.sin(2*np.pi*freq*t)*0.3).astype(np.float32)
        samples.append((audio_to_mel(audio), torch.tensor(0, dtype=torch.long)))
        neg_count += 1
    print(f"  Negative: {neg_count}")
    print(f"  Total: {len(samples)}")
    random.shuffle(samples)
    return WakeWordDataset(samples)

class HeyIshaNet(nn.Module):
    def __init__(self):
        super().__init__()
        self.features = nn.Sequential(
            nn.Conv2d(1,32,3,padding=1), nn.BatchNorm2d(32), nn.ReLU(True), nn.MaxPool2d(2,2), nn.Dropout2d(0.1),
            nn.Conv2d(32,64,3,padding=1), nn.BatchNorm2d(64), nn.ReLU(True), nn.MaxPool2d(2,2), nn.Dropout2d(0.1),
            nn.Conv2d(64,128,3,padding=1), nn.BatchNorm2d(128), nn.ReLU(True), nn.AdaptiveAvgPool2d((4,4)),
        )
        self.classifier = nn.Sequential(
            nn.Flatten(),
            nn.Linear(128*4*4,256), nn.ReLU(True), nn.Dropout(0.3),
            nn.Linear(256,64), nn.ReLU(True), nn.Dropout(0.2),
            nn.Linear(64,2),
        )
    def forward(self,x): return self.classifier(self.features(x))

def train_model(dataset):
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Training on: {device}")
    n_test = max(1, int(len(dataset)*TEST_SPLIT))
    train_ds, test_ds = random_split(dataset, [len(dataset)-n_test, n_test])
    train_loader = DataLoader(train_ds, batch_size=BATCH_SIZE, shuffle=True, num_workers=0)
    test_loader  = DataLoader(test_ds,  batch_size=BATCH_SIZE, shuffle=False, num_workers=0)
    model = HeyIshaNet().to(device)
    opt   = torch.optim.AdamW(model.parameters(), lr=LR, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=EPOCHS)
    crit  = nn.CrossEntropyLoss()
    best_acc, best_state = 0.0, None
    CHECKPOINT = WORK_DIR / "best_hey_isha.pt"
    print(f"{'Epoch':>5} | {'Loss':>8} | {'Train%':>7} | {'Val%':>6}")
    print("-"*38)
    for epoch in range(1, EPOCHS+1):
        model.train()
        tl, tc, ts = 0,0,0
        for mels,labels in train_loader:
            mels,labels = mels.to(device), labels.to(device)
            opt.zero_grad()
            out = model(mels)
            loss = crit(out,labels)
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(),1.0)
            opt.step()
            tl += loss.item()*len(labels)
            tc += (out.argmax(1)==labels).sum().item()
            ts += len(labels)
        sched.step()
        model.eval()
        vc,vt = 0,0
        with torch.no_grad():
            for mels,labels in test_loader:
                mels,labels = mels.to(device), labels.to(device)
                vc += (model(mels).argmax(1)==labels).sum().item()
                vt += len(labels)
        va = vc/vt if vt>0 else 0
        if epoch%10==0 or epoch==1:
            print(f"{epoch:5d} | {tl/ts:8.4f} | {tc/ts:7.2%} | {va:6.2%}")
        if va > best_acc:
            best_acc = va
            best_state = {k:v.clone() for k,v in model.state_dict().items()}
            torch.save(best_state, CHECKPOINT)
    print(f"\nBest val acc: {best_acc:.2%}")
    model.load_state_dict(best_state)
    return model, device

def export_onnx(model, device):
    model.eval()
    dummy = torch.randn(1,1,N_MELS,150).to(device)
    OUTPUT_ONNX.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(model, dummy, str(OUTPUT_ONNX), export_params=True, opset_version=13, do_constant_folding=True,
        input_names=["mel_input"], output_names=["logits"],
        dynamic_axes={"mel_input":{3:"time_steps"}, "logits":{0:"batch"}})
    print(f"ONNX exported: {OUTPUT_ONNX}  ({OUTPUT_ONNX.stat().st_size/1024:.1f} KB)")

check_ffmpeg()
WORK_DIR.mkdir(parents=True, exist_ok=True)
wav_dir = WORK_DIR / "positive_wav"
positive_wavs = convert_m4a_to_wav(RECORDINGS_DIR, wav_dir)
dataset = build_dataset(positive_wavs)
model, device = train_model(dataset)
export_onnx(model, device)
print("DONE! hey_isha.onnx is ready in assets/")
