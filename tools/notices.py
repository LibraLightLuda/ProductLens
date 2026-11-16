"""Collect resolved runtime metadata and preserve distributed license notices; no dependency."""
from pathlib import Path
import hashlib
import json
import re
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
assets = root / 'app/src/main/assets'
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
rows = []
notices = {}
for line in (root / 'evidence/runtime-dependencies.tsv').read_text(encoding='utf-8').splitlines():
    coord, artifact_path = line.split('\t', 1)
    group, artifact, version = coord.split(':')
    poms = list((cache / group / artifact / version).glob('*/*.pom'))
    licenses = []
    for pom in poms:
        tree = ET.parse(pom)
        for entry in tree.findall('.//{*}licenses/{*}license'):
            licenses.append(' '.join(filter(None, (entry.findtext('{*}name'), entry.findtext('{*}url')))))
    rows.append(f'{coord}\n  ' + ('; '.join(sorted(set(licenses))) or 'See upstream POM, parent POM and embedded notices.'))
    artifact_file = Path(artifact_path)
    if zipfile.is_zipfile(artifact_file):
        with zipfile.ZipFile(artifact_file) as z:
            for entry in z.namelist():
                if re.search(r'(^|/)(license|notice|copying)([.\-_]|$)', entry, re.I) and not entry.endswith('/'):
                    b = z.read(entry)
                    if len(b) < 400000:
                        text = b.decode('utf-8', errors='replace')
                        notices.setdefault(hashlib.sha256(b).hexdigest(), (f'{coord}: {entry}', text))
heading = '''ProductLens - Libraries, models and attribution

Kotlin, AndroidX, Compose, CameraX, Coroutines, ZXing, MediaPipe, OkHttp/Okio, Coil: Apache License 2.0 unless the resolved dependency notices below state otherwise.

Google ML Kit Korean Text Recognition 16.0.1 is a proprietary Google SDK, not fully open source. Google terms and privacy, including potential diagnostics/usage telemetry, apply:
https://developers.google.com/ml-kit/terms

MobileNet V3 Small float32 v1 weights: Apache 2.0. Embedded model metadata license and https://www.kaggle.com/models/google/mobilenet-v3
EfficientDet Lite0 float32 v1 weights: Apache 2.0. https://www.kaggle.com/models/tensorflow/efficientdet
Google MediaPipe official distribution: https://storage.googleapis.com/mediapipe-models/

Contains information from Open Food Facts / Open Beauty Facts / Open Pet Food Facts / Open Products Facts, made available under the Open Database License (ODbL 1.0). Individual contents: DbCL. Product images: CC BY-SA 3.0. Product result screens link to the original source.
https://opendatacommons.org/licenses/odbl/1-0/
https://opendatacommons.org/licenses/dbcl/1-0/
https://creativecommons.org/licenses/by-sa/3.0/

Optional barcode lookup uses the official UPCitemdb free trial API without credentials. UPCitemdb is not an openly licensed product database. Its service terms apply; results show their source and original product link. No photos are uploaded.
https://www.upcitemdb.com/terms
https://www.upcitemdb.com/wp/docs/main/development/plan/

User catalogue photographs stay on the device. No stock catalogue or fake products are bundled. Test photos are only in the test APK.

Resolved runtime coordinates and upstream POM license declarations:
'''
parts = [heading, '\n'.join(rows), '\nFull Apache 2.0 license:\n', (assets / 'APACHE-2.0.txt').read_text(encoding='utf-8')]
for extra in sorted(assets.glob('*LICENSE*.txt')):
    parts += ['\n' + extra.name + '\n', extra.read_text(encoding='utf-8')]
for title, text in notices.values():
    parts += ['\n\n' + title + '\n', text]
(assets / 'licenses.txt').write_text('\n'.join(parts), encoding='utf-8')
models = []
for filename, task, name in [('mobilenet_v3_small.tflite', 'image_embedder', 'mobilenet_v3_small'), ('efficientdet_lite0.tflite', 'object_detector', 'efficientdet_lite0')]:
    data = (assets / filename).read_bytes()
    models.append(dict(file=filename, bytes=len(data), sha256=hashlib.sha256(data).hexdigest(), license='Apache-2.0',
                       url=f'https://storage.googleapis.com/mediapipe-models/{task}/{name}/float32/1/{filename}'))
    if name == 'mobilenet_v3_small':
        evidence = [s.decode('ascii') for s in re.findall(rb'[\x20-\x7e]{12,}', data) if b'license' in s.lower() or b'Apache' in s]
        (root / 'evidence/model-license-metadata.txt').write_text('\n'.join(evidence), encoding='utf-8')
(assets / 'models.json').write_text(json.dumps(models, indent=2), encoding='utf-8')
print(f'Collected {len(rows)} runtime artifacts, {len(notices)} distinct embedded notices and {len(models)} model hashes.')
