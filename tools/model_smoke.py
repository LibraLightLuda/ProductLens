"""Desktop-only validation of the exact bundled weights; not Android/device evidence."""
from pathlib import Path
import hashlib
import json
import time
import numpy as np
from PIL import Image
from ai_edge_litert.interpreter import Interpreter

root = Path(__file__).resolve().parents[1]
assets = root / 'app/src/main/assets'
fixtures = root / 'app/src/androidTest/assets'
manifest = json.loads((assets / 'models.json').read_text(encoding='utf-8'))
for model in manifest:
    assert hashlib.sha256((assets / model['file']).read_bytes()).hexdigest() == model['sha256']

def create(filename):
    model = Interpreter(model_path=str(assets / filename), num_threads=2)
    model.allocate_tensors()
    return model

def infer(model, image):
    info = model.get_input_details()[0]
    _, h, w, _ = info['shape']
    data = np.asarray(image.convert('RGB').resize((w, h), Image.Resampling.BILINEAR), dtype=np.float32)
    if info['dtype'] == np.float32:
        data = (data - 127.5) / 127.5
    else:
        data = data.astype(info['dtype'])
    model.set_tensor(info['index'], data[None])
    model.invoke()
    return [model.get_tensor(out['index']) for out in model.get_output_details()]

def cosine(a, b):
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))

start = time.perf_counter()
embedder = create('mobilenet_v3_small.tflite')
a, b = Image.open(fixtures / 'burger.jpg'), Image.open(fixtures / 'cat.jpg')
va, vb = infer(embedder, a)[0].reshape(-1), infer(embedder, b)[0].reshape(-1)
query = a.resize((a.width * 3 // 4, a.height * 3 // 4), Image.Resampling.LANCZOS)
vq = infer(embedder, query)[0].reshape(-1)
assert va.size >= 128 and np.isfinite(va).all() and np.isfinite(vb).all()
same, different = cosine(vq, va), cosine(vq, vb)
assert same > different and same > .90, (same, different)
detector = create('efficientdet_lite0.tflite')
outputs = infer(detector, a)
assert all(np.isfinite(out).all() for out in outputs)
report = dict(platform='Windows desktop CPU, not Android', embedding_dimensions=int(va.size),
              resized_burger_to_burger_cosine=same, resized_burger_to_cat_cosine=different,
              detected_output_shapes=[list(map(int, out.shape)) for out in outputs],
              elapsed_seconds=time.perf_counter() - start, model_hashes_verified=True)
(root / 'evidence/desktop-model-smoke.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report, indent=2))
