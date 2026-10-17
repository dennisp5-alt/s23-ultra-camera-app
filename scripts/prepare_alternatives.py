#!/usr/bin/env python3
"""Fetch verified upstream pretrained files and export the learned 3D-LUT classifier.
Runs ONLY during APK compilation, never on the phone."""
import io, json, struct, urllib.request
from pathlib import Path
import numpy as np
import torch
import torch.nn as nn
import onnx

ROOT = Path("app/src/main/assets")
ROOT.mkdir(parents=True, exist_ok=True)

def grab(url):
    req=urllib.request.Request(url, headers={"User-Agent":"AutoPhoto-AI-Lab-build/0.3.3"})
    with urllib.request.urlopen(req, timeout=90) as source:
        result=source.read()
    print("Fetched", url.rsplit("/",1)[-1], len(result),"bytes", flush=True)
    return result

mobile=grab("https://raw.githubusercontent.com/AVC2-UESTC/MobileIE/main/pretrain/lolv1.tflite")
assert mobile[4:8]==b"TFL3" and 10000<len(mobile)<200000, "Unexpected MobileIE trained model"
(ROOT/"mobileie_lolv1.tflite").write_bytes(mobile)

base="https://raw.githubusercontent.com/HuiZeng/Image-Adaptive-3DLUT/master/pretrained_models/sRGB"
lutState=torch.load(io.BytesIO(grab(base+"/LUTs.pth")), map_location="cpu", weights_only=False)
clsState=torch.load(io.BytesIO(grab(base+"/classifier.pth")), map_location="cpu", weights_only=False)
print("LUT keys:",list(lutState.keys()),"classifier keys:",list(clsState.keys())[:8], flush=True)

def block(fin,fout,normal=False):
    layers=[nn.Conv2d(fin,fout,3,stride=2,padding=1),nn.LeakyReLU(0.2)]
    if normal:layers.append(nn.InstanceNorm2d(fout,affine=True))
    return layers

class Classifier(nn.Module):
    def __init__(self):
        super().__init__()
        self.model=nn.Sequential(
            nn.Upsample(size=(256,256),mode="bilinear"),
            nn.Conv2d(3,16,3,stride=2,padding=1),
            nn.LeakyReLU(0.2),
            nn.InstanceNorm2d(16,affine=True),
            *block(16,32,True),
            *block(32,64,True),
            *block(64,128,True),
            *block(128,128,False),
            nn.Dropout(p=0.5),
            nn.Conv2d(128,3,8)
        )
    def forward(self,x):
        return self.model(x).flatten(1)

model=Classifier().eval()
model.load_state_dict(clsState,strict=True)
example=torch.rand(1,3,256,256)
with torch.no_grad():
    predicted=model(example)
assert tuple(predicted.shape)==(1,3)
print("Classifier smoke output", predicted.tolist(), flush=True)
onnxPath=ROOT/"lut_classifier.onnx"
torch.onnx.export(model,example,str(onnxPath),opset_version=17,
                  input_names=["image"],output_names=["weights"],
                  do_constant_folding=True)
onnx.checker.check_model(onnx.load(str(onnxPath)))
print("Exported pretrained ONNX",onnxPath.stat().st_size,flush=True)

D=33
basis=np.empty((3,3,D,D,D),dtype=np.float32)
for k in range(3):
    entry=lutState[str(k)]
    assert "LUT" in entry, "Missing trained LUT"
    data=entry["LUT"].detach().cpu().numpy()
    assert data.shape==(3,D,D,D), f"Unexpected learned LUT shape {data.shape}"
    basis[k]=data
assert np.isfinite(basis).all()
lutFile=ROOT/"lut_basis_33.bin"
lutFile.write_bytes(basis.astype("<f4").tobytes(order="C"))
assert lutFile.stat().st_size==3*3*33**3*4
print("Saved THREE genuine learned LUT bases:", lutFile.stat().st_size,flush=True)

# Inspect pretrained MobileIE model structure without requiring TensorFlow on runner.
try:
    import tflite
    m=tflite.Model.GetRootAsModel(mobile,0)
    g=m.Subgraphs(0)
    for name,idx in [("input",g.Inputs(0)),("output",g.Outputs(0))]:
        t=g.Tensors(idx)
        print("MobileIE",name,"shape",t.ShapeAsNumpy().tolist(),
              "type",t.Type(),flush=True)
except Exception as e:
    print("MobileIE metadata probe warning:",repr(e),flush=True)
