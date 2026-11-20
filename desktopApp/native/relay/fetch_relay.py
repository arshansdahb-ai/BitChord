"""Fetch the pinned official portable connector; no installer, service or elevation."""
from pathlib import Path
import hashlib
import json
import urllib.request

VERSION = '2026.10.5'
dest = Path(__file__).resolve().parents[2] / 'voice-resources/windows/relay'
dest.mkdir(parents=True, exist_ok=True)
release = json.load(urllib.request.urlopen('https://api.github.com/repos/cloudflare/cloudflared/releases/tags/' + VERSION))
asset = next(item for item in release['assets'] if item['name'] == 'cloudflared-windows-amd64.exe')
file = dest / 'cloudflared.exe'
if not file.exists():
    urllib.request.urlretrieve(asset['browser_download_url'], file)
actual = hashlib.file_digest(file.open('rb'), 'sha256').hexdigest()
assert actual == 'db4e4c413612a0916f3e1679d3caaf376f94911952d163028fb726fb2e0516a9'
assert asset['digest'] == 'sha256:' + actual
urllib.request.urlretrieve('https://raw.githubusercontent.com/cloudflare/cloudflared/' + VERSION + '/LICENSE', dest / 'LICENSE')
(dest / 'provenance.json').write_text(json.dumps({'version': VERSION, 'url': asset['browser_download_url'], 'sha256': actual, 'size': file.stat().st_size}, indent=2))
print('Verified official portable cloudflared', VERSION)
