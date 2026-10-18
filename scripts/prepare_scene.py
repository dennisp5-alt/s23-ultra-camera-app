#!/usr/bin/env python3
"""Fetch and validate the exact official pretrained SkyWater-Seg FP32 ONNX model.
Only runs in GitHub Actions at compile time; phone never downloads anything.
License: MIT, source https://github.com/Vincentqyw/skywater_seg
Weights: https://huggingface.co/Realcat/skywater_seg
"""
from pathlib import Path
import os
import shutil
import numpy as np
import onnx
import onnxruntime as ort
from huggingface_hub import hf_hub_download

assets = Path("app/src/main/assets")
assets.mkdir(parents=True,exist_ok=True)
name = "skywater_segformer_b2_fp32.onnx"
model_source = hf_hub_download(
    repo_id="Realcat/skywater_seg",
    filename=name,
    repo_type="model")
target = assets / name
shutil.copyfile(model_source, target)
print("Downloaded SkyWater-Seg pretrained ONNX",target.stat().st_size,flush=True)
assert 90_000_000 < target.stat().st_size < 130_000_000, (
    "Unexpected model size or incomplete Hugging Face download")

# Inspect ONNX graph and check real inference, not just presence of a file.
onnx_model=onnx.load(str(target))
onnx.checker.check_model(onnx_model)
input0=onnx_model.graph.input[0]
output0=onnx_model.graph.output[0]
print("Model graph input",input0.name, "output",output0.name,flush=True)
print("Model opset",[(i.domain,i.version) for i in onnx_model.opset_import],flush=True)
session = ort.InferenceSession(
    str(target),
    sess_options=ort.SessionOptions(),
    providers=["CPUExecutionProvider"])
t=session.get_inputs()[0]
print("ORT INPUT",t.name,t.shape,t.type,flush=True)
assert t.type=="tensor(float)"
assert t.shape[1]==3
shape=[1,3,384,384]
output=session.run(None,{t.name:np.zeros(shape,dtype=np.float32)})[0]
print("ORT OUTPUT",output.shape,output.dtype,flush=True)
assert output.ndim==4 and output.shape[0]==1 and output.shape[1]==4
assert output.shape[2]>=32 and output.shape[3]>=32
assert output.dtype==np.float32
assert np.all(np.isfinite(output))
print("Scene segmentation ONNX verified: REAL CPU inference passed",flush=True)
