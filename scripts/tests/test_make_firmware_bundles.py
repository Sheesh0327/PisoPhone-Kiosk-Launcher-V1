import hashlib, os, struct, sys, tempfile, unittest, zipfile
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
import make_firmware_bundles as m

ROOT = os.path.join(HERE, "..", "..")
SUBTYPES = {("app", "ota_0"): 0x10, ("app", "ota_1"): 0x11, ("data", "ota"): 0x00, ("data", "phy"): 0x01, ("data", "nvs"): 0x02,
            ("data", "coredump"): 0x03, ("data", "spiffs"): 0x82}
# the Arduino core's min_spiffs.csv, which the development environments use (PlatformIO does not ship it with this repository)
MIN_SPIFFS = """nvs,data,nvs,0x9000,0x5000,
otadata,data,ota,0xe000,0x2000,
app0,app,ota_0,0x10000,0x1E0000,
app1,app,ota_1,0x1F0000,0x1E0000,
spiffs,data,spiffs,0x3D0000,0x20000,
coredump,data,coredump,0x3F0000,0x10000,
"""
# the encrypted layout this repository shipped until the boot_app0 overlap was found (otadata at 0xf000, nvs ending there)
OLD_ENCRYPTED = """nvs,data,nvs,0x9000,0x6000,encrypted
otadata,data,ota,0xf000,0x2000,encrypted
phy_init,data,phy,0x11000,0x1000,encrypted
ota_0,app,ota_0,0x20000,0x1C0000,encrypted
ota_1,app,ota_1,0x1E0000,0x1C0000,encrypted
spiffs,data,spiffs,0x3A0000,0x60000,encrypted
"""


def put(path, data):
    with open(path, "wb") as f:
        f.write(data)


def table_bin(csv_text):
    """What gen_esp32part.py writes for a table (entries only, which is all the bundler reads)."""
    out = b""
    for line in csv_text.splitlines():
        line = line.split("#")[0].strip()
        if not line:
            continue
        name, typ, sub, off, size, flags = [x.strip() for x in line.split(",")] + [""] * (6 - len(line.split(",")))
        t = 0 if typ == "app" else 1
        out += b"\xaa\x50" + struct.pack("<BBII", t, SUBTYPES[(typ, sub)], int(off, 16), int(size, 16))
        out += name.encode().ljust(16, b"\0") + struct.pack("<I", 1 if "encrypted" in flags else 0)
    return out + b"\xeb\xeb" + b"\xff" * 30


def fake_builds(root, release_csv):
    for env, _chip, _n, _boff, kind in m.ENVS:
        d = os.path.join(root, "build", env)
        os.makedirs(d)
        csv = MIN_SPIFFS if kind == "dev" else release_csv
        put(os.path.join(d, "bootloader.bin"), b"\xe9" + b"b" * 5000)
        put(os.path.join(d, "partitions.bin"), table_bin(csv))
        put(os.path.join(d, "firmware.bin"), b"\xe9" + env.encode() * 1000)
    os.makedirs(os.path.join(root, "merged"))
    for env, *_ in m.ENVS[:2]:
        put(os.path.join(root, "merged", env + ".bin"), b"\xe9merged-" + env.encode())
    put(os.path.join(root, "boot_app0.bin"), b"\xff" * m.BOOT_APP0_SIZE)


class T(unittest.TestCase):
    def run_build(self, release_csv):
        tmp = tempfile.mkdtemp()
        fake_builds(tmp, release_csv)
        return tmp, m.build("9.9.9", "c0ffee" * 7, os.path.join(tmp, "build"), os.path.join(tmp, "boot_app0.bin"), os.path.join(tmp, "merged"), os.path.join(tmp, "out"))

    def committed_csv(self):
        with open(os.path.join(ROOT, "esp32_firmware", "partitions_encrypted.csv")) as f:
            return f.read()

    def test_both_bundles_are_built_from_the_committed_layout(self):
        tmp, paths = self.run_build(self.committed_csv())
        self.assertEqual([os.path.basename(p) for p in paths], ["PisoPhone-firmware-dev-9.9.9.zip", "PisoPhone-firmware-release-9.9.9.zip"])
        dev, rel = [zipfile.ZipFile(p) for p in paths]
        self.assertIn("esp32-c3-dev/esp32-c3-dev-merged.bin", dev.namelist())
        self.assertIn("esp32dev-dev/firmware.bin", dev.namelist())
        self.assertNotIn("esp32-c3-production-encrypted/firmware.bin", dev.namelist())
        self.assertIn("esp32-c3-production-encrypted/firmware.bin", rel.namelist())
        self.assertIn("PROVISIONING_SOLD_UNIT.md", rel.namelist())
        readme = rel.read("README.txt").decode()
        self.assertIn("boot_app0.bin    at 0x00e000", readme, "boot_app0 goes to otadata, 0xe000")
        self.assertIn("firmware.bin     at 0x020000", readme, "the app is in the first OTA slot")
        self.assertIn("--address 0x20000", readme)
        self.assertIn("NO keys", readme)
        self.assertIn("has NOT yet been", readme)
        self.assertIn("run on a board", readme)

    def test_no_key_material_in_any_zip(self):
        _tmp, paths = self.run_build(self.committed_csv())
        for p in paths:
            for n in zipfile.ZipFile(p).namelist():
                self.assertFalse(n.endswith((".pem", ".key")) or "flash_encryption_key" in n.split("/")[-1], n)
                self.assertNotIn(b"PRIVATE KEY", zipfile.ZipFile(p).read(n))

    def test_checksums_match_and_the_zip_is_reproducible(self):
        tmp, paths = self.run_build(self.committed_csv())
        for p in paths:
            z = zipfile.ZipFile(p)
            for line in z.read("SHA256SUMS").decode().splitlines():
                digest, name = line.split("  ", 1)
                self.assertEqual(hashlib.sha256(z.read(name)).hexdigest(), digest, name)
        with open(paths[1], "rb") as f:
            first = f.read()
        again = m.build("9.9.9", "c0ffee" * 7, os.path.join(tmp, "build"), os.path.join(tmp, "boot_app0.bin"), os.path.join(tmp, "merged"), os.path.join(tmp, "out2"))
        with open(again[1], "rb") as f:
            self.assertEqual(first, f.read())

    def test_the_old_encrypted_layout_is_refused(self):
        tmp = tempfile.mkdtemp()
        fake_builds(tmp, OLD_ENCRYPTED)
        with self.assertRaises(m.BundleError) as e:
            m.build("9.9.9", "x" * 40, os.path.join(tmp, "build"), os.path.join(tmp, "boot_app0.bin"), os.path.join(tmp, "merged"), os.path.join(tmp, "out"))
        self.assertIn("overwrite", str(e.exception))

    def test_a_partition_that_is_not_flagged_encrypted_is_refused(self):
        csv = self.committed_csv().replace("spiffs,   data, spiffs,  0x3A0000, 0x60000,  encrypted", "spiffs,   data, spiffs,  0x3A0000, 0x60000,")
        self.assertNotEqual(csv, self.committed_csv())
        tmp = tempfile.mkdtemp()
        fake_builds(tmp, csv)
        with self.assertRaises(m.BundleError) as e:
            m.build("9.9.9", "x" * 40, os.path.join(tmp, "build"), os.path.join(tmp, "boot_app0.bin"), os.path.join(tmp, "merged"), os.path.join(tmp, "out"))
        self.assertIn("not flagged encrypted", str(e.exception))

    def test_an_app_that_does_not_fit_is_refused(self):
        tmp = tempfile.mkdtemp()
        fake_builds(tmp, self.committed_csv())
        put(os.path.join(tmp, "build", "esp32-c3-production-encrypted", "firmware.bin"), b"\xe9" + b"x" * 0x1C0000)
        with self.assertRaises(m.BundleError) as e:
            m.build("9.9.9", "x" * 40, os.path.join(tmp, "build"), os.path.join(tmp, "boot_app0.bin"), os.path.join(tmp, "merged"), os.path.join(tmp, "out"))
        self.assertIn("does not fit", str(e.exception))


if __name__ == "__main__":
    unittest.main()
