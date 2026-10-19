#!/usr/bin/env python3
"""Bundle a genuine pretrained compact RGB DnCNN blind denoiser as ONNX.

Source: https://github.com/cszn/KAIR/releases/tag/v1.0
Model: dncnn_color_blind.pth (RGB residual denoiser, 20 conv layers)
Training: blind Gaussian noise; *not* claimed to restore authentic texture
from an arbitrary smartphone JPEG. Studio Clean uses guarded residual fusion.
Executed ONLY at GitHub APK build time. On device, fully offline ONNX CPU.
"""
from pathlib import Path
from urllib.request import Request, urlopen
import hashlib
import io
import numpy as np
import onnx
import onnxruntime as ort
import torch
from torch import nn

URL = "https://github.com/cszn/KAIR/releases/download/v1.0/dncnn_color_blind.pth"
ASSET = Path("app/src/main/assets/studio_dncnn_color_blind.onnx")
ASSET.parent.mkdir(parents=True, exist_ok=True)
with urlopen(Request(URL, headers={"User-Agent": "AutoPhoto-StudioClean/0.4.9"}),
             timeout=120) as stream:
    checkpoint = stream.read()
assert 2_000_000 < len(checkpoint) < 4_000_000, "Unexpected model size"
assert not checkpoint.startswith(b"<"), "Expected real PyTorch weights, not HTML"
print("DnCNN pretrained weight bytes", len(checkpoint),
      "SHA256", hashlib.sha256(checkpoint).hexdigest(), flush=True)

# Exact KAIR DnCNN color blind architecture: 20 convolutions with 19 ReLUs.
layers = [nn.Conv2d(3,64,3,padding=1,bias=True), nn.ReLU(inplace=False)]
for _ in range(18):
    layers += [nn.Conv2d(64,64,3,padding=1,bias=True), nn.ReLU(inplace=False)]
layers += [nn.Conv2d(64,3,3,padding=1,bias=True)]
class Network(nn.Module):
    def __init__(self):
        super().__init__()
        self.model = nn.Sequential(*layers)
    def forward(self,x):
        return x-self.model(x)
model=Network().eval()
weights=torch.load(io.BytesIO(checkpoint), map_location="cpu", weights_only=True)
if isinstance(weights,dict) and "params" in weights: weights=weights["params"]
assert isinstance(weights,dict)
model.load_state_dict(weights,strict=True)
print("Strictly loaded pretrained CNN layers:",sum(isinstance(m,nn.Conv2d) for m in model.modules()))
example=torch.rand(1,3,256,256)
torch.onnx.export(model,example,str(ASSET),opset_version=17,
                  input_names=["rgb"],output_names=["denoised"],
                  do_constant_folding=True)
onnx.checker.check_model(onnx.load(str(ASSET)))
session=ort.InferenceSession(str(ASSET),providers=["CPUExecutionProvider"])
assert session.get_inputs()[0].shape == [1,3,256,256]
rng=np.random.default_rng(1234)
reference=rng.uniform(.2,.8,(1,3,256,256)).astype(np.float32)
noise=rng.normal(0,25/255,reference.shape).astype(np.float32)
noisy=np.clip(reference+noise,0,1)
result=session.run(None,{session.get_inputs()[0].name:noisy})[0]
assert result.shape==noisy.shape and np.isfinite(result).all()
assert float(np.abs(result-noisy).mean())>0.0001, "Untrained/identity model"
# Don't claim a PSNR improvement from random noise applied to random fields:
# this check only validates meaningful, finite learned weights and inference.
print("Studio denoiser ONNX",ASSET.stat().st_size,
      "mean absolute learned correction",float(np.abs(result-noisy).mean()))
