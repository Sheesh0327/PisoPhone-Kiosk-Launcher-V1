"""Build launcher resources from app/artwork/launcher-head.png (requires Pillow)."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
NAVY = "#050D22"
head = Image.open(ROOT / "app/artwork/launcher-head.png").convert("RGBA")
head = head.crop(head.getchannel("A").getbbox())
# Fit the complete silhouette inside a circle, including all feather tips.
cx, cy = head.width / 2, head.height / 2
radius = max(((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
             for y in range(head.height) for x in range(head.width)
             if head.getpixel((x, y))[3] > 0)


def foreground(size, safe_radius):
    scale = safe_radius / radius
    art = head.resize((round(head.width * scale), round(head.height * scale)), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (size, size))
    canvas.alpha_composite(art, ((size - art.width) // 2, (size - art.height) // 2))
    return canvas


# Android adaptive layers are 108dp; keep artwork inside the central 66dp circle.
layer = foreground(432, 124)
out = RES / "drawable-nodpi"
out.mkdir(exist_ok=True)
layer.save(out / "ic_launcher_foreground.png", optimize=True)
mono = Image.new("RGBA", layer.size, "white")
mono.putalpha(layer.getchannel("A"))
mono.save(out / "ic_launcher_monochrome.png", optimize=True)
for density, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    large = size * 4
    art = foreground(large, large * .43)
    for rounded, name in [(False, "ic_launcher"), (True, "ic_launcher_round")]:
        tile = Image.new("RGBA", (large, large), NAVY)
        tile.alpha_composite(art)
        mask = Image.new("L", (large, large))
        draw = ImageDraw.Draw(mask)
        if rounded:
            draw.ellipse((0, 0, large - 1, large - 1), fill=255)
        else:
            draw.rounded_rectangle((0, 0, large - 1, large - 1), radius=large * .22, fill=255)
        tile.putalpha(mask)
        tile.resize((size, size), Image.Resampling.LANCZOS).save(
            RES / f"mipmap-{density}" / f"{name}.webp", lossless=True)
print("Launcher icons regenerated for all five densities and adaptive/themed launchers.")
