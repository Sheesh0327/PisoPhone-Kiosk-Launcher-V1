# tools: the router program, the setup builder and the test checklist

Programs the project builds from. Scripts the owner runs on their own computer are in `scripts/` (`scripts/README.md`).

## Layout
| Path | What it is |
|---|---|
| `pisoportal/` | The router's coin portal, a Rust program. Source, tests, and the programs CI builds for each router family. See `pisoportal/README.md`. |
| `build_piso_setup.py` | Builds the one-file router setup (`setup/piso-setup*.sh`) and the website copies from `setup/src/`, the router scripts in `router/` and the portal programs. |
| `checklist/` | Turns `docs/REAL_WORLD_TESTING.md` into a tickable test page (`build.py`, `template.html`) and writes the results back (`apply.py`), with their tests. |

## Build
```
python3 tools/build_piso_setup.py            # after changing setup/src/, router/ or setup/pisophone_setup.py
python3 tools/checklist/build.py out.html    # the tickable page of the real-world test list
```
Raise `setup/RELEASE` when a change alters what routers install, and send it through `beta` first (`CLAUDE.md` section 5).
The portal program is built by CI (`router-program.yml`); see `pisoportal/README.md`.

## Test
```
python3 tools/build_piso_setup.py --check      # fails when a committed generated file is out of date
python3 tools/checklist/test_apply.py
python3 tools/checklist/test_page.py           # a browser; needs: pip install playwright
cd tools/pisoportal && cargo test              # and the other commands in pisoportal/README.md
```
CI runs the setup check in the `pisoportal` job of `quality.yml`, and the checklist tests in `checklist.yml` when
`docs/REAL_WORLD_TESTING.md` or `tools/checklist/` changes.

## Generated files
`build_piso_setup.py` writes `setup/piso-setup*.sh` (with their `.sha256`), `website/setup/*`, `website/install.sh` and
`website/pisophone_setup.py`. `pisoportal/bin/*` is written by CI. Never edit any of them by hand (`CLAUDE.md` section 4).

## See also
`setup/README.md` (the setup program and the router installer), `router/README.md`, `docs/REAL_WORLD_TESTING.md`, `scripts/README.md`.
