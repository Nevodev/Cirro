"""Refresh the standalone HTML's renderer assets from the Android source."""
from pathlib import Path
import json
import re
import subprocess

root = Path(__file__).resolve().parents[2]
directory = Path(__file__).resolve().parent
mesh = root / "app/src/main/java/com/nevoit/cirro/playback/pearmesh"
source = (mesh / "gl/PearMeshPresets.kt").read_text(encoding="utf-8")


def points(name: str, count: int) -> list[list[float]]:
    match = re.search(r"private val " + re.escape(name) + r" = arrayOf\((.*?)\n    \)", source, re.S)
    if match is None:
        raise ValueError(f"Control map not found: {name}")
    result = [[float(x), float(y)] for x, y in re.findall(r"Vec2\((-?[\d.]+)f, (-?[\d.]+)f\)", match[1])]
    if len(result) != count:
        raise ValueError(f"Unexpected control point count for {name}: {len(result)}")
    return result


presets = []
for i in range(4):
    presets.append(dict(id=f"portrait{i}", name=f"竖屏 {i + 1} · 6 × 6", size=6,
                        **{"from": points(f"preset{i}From", 36), "to": points(f"preset{i}To", 36)}))
for i in range(5):
    presets.append(dict(id=f"landscape{i}", name=f"横屏 {i + 1} · 9 × 9（方形采样）", size=9,
                        **{"from": [[j % 9 / 8, j // 9 / 8] for j in range(81)],
                           "to": points(f"landscapePreset{i}To", 81)}))


def constant(text: str, name: str) -> float:
    match = re.search(re.escape(name) + r"\s*=\s*([\d.]+)f", text)
    if match is None:
        raise ValueError(f"Renderer constant not found: {name}")
    return float(match[1])


renderer = (mesh / "gl/PearMeshGlRenderer.kt").read_text(encoding="utf-8")
cover = (mesh / "gl/PearMeshCoverPreset.kt").read_text(encoding="utf-8")
shader_dir = root / "app/src/main/assets/pearmesh/shaders"
revision = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=root, shell=False,
                          check=True, capture_output=True, text=True, encoding="utf-8").stdout.strip()
data = dict(revision=revision, coverBlur=constant(cover, "BlurMultiplier"),
            lyricsBlurSigma=constant(renderer, "LYRICS_BLUR_SIGMA"),
            kawaseSigmaPerOffset=constant(renderer, "KAWASE_SIGMA_PER_OFFSET"), presets=presets,
            shaders={name: (shader_dir / name).read_text(encoding="utf-8") for name in
                     ["rotation.vert", "rotation.frag", "fullscreen.vert", "blur.frag", "material.frag"]})
path = directory / "index.html"
html = path.read_text(encoding="utf-8")
start, end = "<!-- APP_DATA_START -->", "<!-- APP_DATA_END -->"
before, rest = html.split(start, 1)
_, after = rest.split(end, 1)
payload = json.dumps(data, ensure_ascii=False, separators=(",", ":")).replace("<", "\\u003c")
path.write_text(before + start + '\n<script id="appData" type="application/json">' + payload +
                "</script>\n" + end + after, encoding="utf-8", newline="\n")
print(f"Synced {len(presets)} presets and {len(data['shaders'])} shaders into {path}")
