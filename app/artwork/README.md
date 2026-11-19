# Launcher artwork

`launcher-head.png` is the transparent head extracted from the owner-provided TechNet phoenix mascot image.
It is the source for the PisoPhone launcher icon, including adaptive, monochrome and legacy density variants.

Regenerate from the repository root with `python scripts/build_launcher_icons.py` (requires Pillow).
The navy background is `app/src/main/res/drawable/ic_launcher_background.xml`.
Keep the foreground inside the adaptive icon safe circle so launcher masks do not crop feather tips.
