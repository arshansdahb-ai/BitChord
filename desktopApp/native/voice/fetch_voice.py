from pathlib import Path
import hashlib, json, urllib.request, zipfile

root = Path(__file__).resolve().parents[3]
dest = root / 'desktopApp/voice-resources/windows/piper'
dest.mkdir(parents=True, exist_ok=True)
release = json.load(urllib.request.urlopen('https://api.github.com/repos/rhasspy/piper/releases/tags/2023.11.14-2'))
asset = next(a for a in release['assets'] if a['name'] == 'piper_windows_amd64.zip')
archive = root / 'desktopApp/build/voice-download/piper_windows_amd64.zip'
archive.parent.mkdir(parents=True,exist_ok=True)
if not archive.exists(): urllib.request.urlretrieve(asset['browser_download_url'], archive)
assert archive.stat().st_size == asset['size']
with zipfile.ZipFile(archive) as z:
    for entry in z.infolist():
        path = Path(entry.filename)
        if entry.is_dir(): continue
        relative = path.relative_to('piper')
        target = (dest / relative).resolve()
        assert target.is_relative_to(dest.resolve())
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(z.read(entry))

base = 'https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/joe/medium/'
expected = 'abf4b998e34045cbd00eaf050de2de620f81ded6eb8b85a24b022a1c2b3f08d2'
target = dest / 'en_US-joe-medium.onnx'
if not target.exists(): urllib.request.urlretrieve(base + target.name, target)
for name in ['en_US-joe-medium.onnx.json', 'MODEL_CARD']:
    urllib.request.urlretrieve(base + name, dest / name)
assert hashlib.file_digest((dest / 'en_US-joe-medium.onnx').open('rb'), 'sha256').hexdigest() == expected
for name,url in {
    'PIPER-LICENSE.md':'https://raw.githubusercontent.com/rhasspy/piper/2023.11.14-2/LICENSE.md',
    'CC0-1.0.txt':'https://raw.githubusercontent.com/spdx/license-list-data/main/text/CC0-1.0.txt',
}.items():
    urllib.request.urlretrieve(url, dest / name)
manifest = {'runtime_version':'2023.11.14-2','runtime_url':asset['browser_download_url'],
    'runtime_archive_sha256':hashlib.file_digest(archive.open('rb'),'sha256').hexdigest(),
    'model_url':base+'en_US-joe-medium.onnx','model_sha256':expected,
    'files':{str(f.relative_to(dest)):hashlib.file_digest(f.open('rb'),'sha256').hexdigest() for f in dest.rglob('*') if f.is_file()}}
(dest / 'provenance.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print(json.dumps({'directory':str(dest),'files':len(manifest['files']),'total_bytes':sum(f.stat().st_size for f in dest.rglob('*') if f.is_file())}))
