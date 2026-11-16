"""Package only after passing build/tests/lint; verify bundled assets and archive content."""
from pathlib import Path
from collections import Counter
import hashlib
import json
import re
import shutil
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
dist = root / 'dist'
evidence = root / 'evidence'
unit = root / 'app/build/test-results/testDebugUnitTest/TEST-app.productlens.CoreTest.xml'
lint = root / 'app/build/reports/lint-results-debug.xml'
test_files = list(unit.parent.glob('TEST-*.xml'))
results = [ET.parse(p).getroot() for p in test_files]
assert all(int(t.attrib['failures']) == 0 and int(t.attrib['errors']) == 0 for t in results)
passed = sum(int(t.attrib['tests']) - int(t.attrib['skipped']) for t in results)
skipped = sum(int(t.attrib['skipped']) for t in results)
assert passed >= 17
version = re.search(r'versionName = "([0-9.]+)"', (root / 'app/build.gradle.kts').read_text(encoding='utf-8')).group(1)
issues = Counter(issue.attrib['severity'] for issue in ET.parse(lint).getroot())
assert issues['Error'] == 0 and issues['Fatal'] == 0
shutil.copy2(unit, evidence / 'unit-tests.xml')
for p in test_files:
    shutil.copy2(p, evidence / p.name)
shutil.copy2(lint, evidence / 'lint-results.xml')
apk = root / 'app/build/outputs/apk/debug/app-debug.apk'
models = json.loads((root / 'app/src/main/assets/models.json').read_text(encoding='utf-8'))
with zipfile.ZipFile(apk) as z:
    for m in models:
        assert hashlib.sha256(z.read('assets/' + m['file'])).hexdigest() == m['sha256']
    assert z.read('assets/licenses.txt')
    assert any('Kore_ctc' in n for n in z.namelist())
    assert any('Latn_ctc' in n for n in z.namelist())
    abis = sorted({n.split('/')[1] for n in z.namelist() if n.startswith('lib/')})
    assert abis == ['arm64-v8a']
    for abi in abis:
        assert f'lib/{abi}/libmediapipe_tasks_vision_jni.so' in z.namelist()
    assert not any(n.endswith(('burger.jpg', 'cat.jpg')) for n in z.namelist())
deliver_apk = dist / f'ProductLens-{version}-debug.apk'
deliver_test = dist / f'ProductLens-{version}-androidTest.apk'
shutil.copy2(apk, deliver_apk)
shutil.copy2(root / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', deliver_test)
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
report = dict(version=version, jvm_tests=passed, jvm_skipped=skipped, jvm_failures=0, lint=dict(issues), apk_bytes=deliver_apk.stat().st_size,
              apk_sha256=sha(deliver_apk), abis=abis, bundled_korean_and_latin_models=True,
              bundled_model_hashes_verified=True, android_execution='NOT RUN - no device, emulator hypervisor unavailable; software attempt denied by policy',
              desktop_model_test=json.loads((evidence / 'desktop-model-smoke.json').read_text(encoding='utf-8')))
(evidence / 'package-verification.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
source = dist / f'ProductLens-{version}-source.zip'
ignored = {'.git', '.gradle', '.kotlin', '.idea', '.venv-test', 'build', 'dist', '__pycache__'}
files = [p for p in root.rglob('*') if p.is_file() and not any(s in ignored for s in p.relative_to(root).parts) and p.name != 'local.properties']
with zipfile.ZipFile(source, 'w', zipfile.ZIP_DEFLATED) as z:
    for p in files: z.write(p, 'ProductLens/' + p.relative_to(root).as_posix())
with zipfile.ZipFile(source) as z:
    assert z.testzip() is None
    for needed in ['README.md', 'ARCHITECTURE.md', 'LIBRARIES.md', 'TEST_REPORT.md', 'LIMITATIONS.md', 'gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar']:
        assert 'ProductLens/' + needed in z.namelist()
    for m in models:
        assert hashlib.sha256(z.read('ProductLens/app/src/main/assets/' + m['file'])).hexdigest() == m['sha256']
(dist / 'SHA256SUMS.txt').write_text(''.join(f'{sha(p)}  {p.name}\n' for p in [deliver_apk, deliver_test, source]), encoding='ascii')
print(json.dumps(report, indent=2))
print('Source archive verified:', source.stat().st_size, 'bytes;', len(files), 'files')
