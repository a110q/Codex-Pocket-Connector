"""Generate Android logo resources from the user-provided artwork (Pillow required)."""
from pathlib import Path
import math
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "android/app/src/main/res"
source = Image.open(ROOT / "design/brand/codex-pocket-logo.png").convert("RGBA")
art = source.crop(source.getchannel("A").getbbox())

# Transparent padding preserves the complete artwork on light and dark surfaces.
mark = Image.new("RGBA", (512, 512))
mark_art = art.copy()
mark_art.thumbnail((488, 488), Image.Resampling.LANCZOS)
mark.alpha_composite(mark_art, ((512 - mark_art.width) // 2, (512 - mark_art.height) // 2))
mark_dir = RES / "drawable-nodpi"
mark_dir.mkdir(parents=True, exist_ok=True)
mark.save(mark_dir / "codex_mark.png", optimize=True)

# Adaptive icons use a 108dp layer. Keep every visible pixel inside the central
# 66dp safe circle so round, square and squircle launcher masks preserve the ears.
cx, cy = art.width / 2, art.height / 2
radius = max(math.hypot(x + .5 - cx, y + .5 - cy)
             for y in range(art.height) for x in range(art.width)
             if art.getpixel((x, y))[3])
side = 432  # 108dp at xxxhdpi
scale = (side * 32 / 108) / radius
icon_art = art.resize((round(art.width * scale), round(art.height * scale)), Image.Resampling.LANCZOS)
foreground = Image.new("RGBA", (side, side))
foreground.alpha_composite(icon_art, ((side - icon_art.width) // 2, (side - icon_art.height) // 2))
icon_dir = RES / "drawable-xxxhdpi"
icon_dir.mkdir(parents=True, exist_ok=True)
foreground.save(icon_dir / "ic_launcher_foreground.png", optimize=True)
print("Generated in-app logo and adaptive launcher foreground.")
