"""Export the generated artwork into launcher and Windows icon formats.

Only resizing and format conversion are performed here; artwork lives in source/.
Requires Pillow, already available in the development environment.
"""
from pathlib import Path
import hashlib
import json

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
BRAND = ROOT / "assets" / "branding"
SIZES = (16, 20, 24, 32, 40, 48, 64, 96, 128, 256)


def main():
    outputs = []
    for slug, filename in (("dex-tablet", "DeXTablet"), ("dx-manager", "DXManager")):
        target = BRAND / slug
        target.mkdir(parents=True, exist_ok=True)
        with Image.open(BRAND / "source" / f"{slug}-generated.png") as source:
            if source.width != source.height:
                raise ValueError(f"Expected square artwork: {slug}")
            master = source.convert("RGBA").resize((1024, 1024), Image.Resampling.LANCZOS)
        master.save(target / f"{filename}-1024.png")
        for size in SIZES + (512,):
            master.resize((size, size), Image.Resampling.LANCZOS).save(target / f"{filename}-{size}.png")
        master.save(target / f"{filename}.ico", sizes=[(s, s) for s in SIZES], bitmap_format="bmp")
        with Image.open(target / f"{filename}.ico") as icon:
            if icon.ico.sizes() != {(s, s) for s in SIZES}:
                raise ValueError(f"Missing ICO image sizes: {slug}")
            for size in SIZES:
                frame = icon.ico.getimage((size, size))
                if frame.size != (size, size):
                    raise ValueError("Invalid ICO frame")
        if slug == "dex-tablet":
            for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
                resource = ROOT / "app/src/main/res" / f"mipmap-{density}"
                resource.mkdir(parents=True, exist_ok=True)
                master.resize((size, size), Image.Resampling.LANCZOS).save(resource / "ic_launcher.png")
        for path in sorted(target.iterdir()):
            outputs.append({"file": str(path.relative_to(ROOT)).replace("\\", "/"),
                            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    (BRAND / "manifest.json").write_text(json.dumps(outputs, indent=2) + "\n", encoding="utf-8")
    print(f"Verified {len(outputs)} icon assets; ICO sizes: {SIZES}")


if __name__ == "__main__":
    main()
