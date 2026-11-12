import hashlib
import json
import os
import shutil
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import encrypt_release_images as e  # noqa: E402

KEY = bytes(range(1, 33))


def image(chip=5, mode=2, size=600):
    h = bytearray(b"\xe9\x03" + bytes([mode, 0x2F]) + b"\0" * 20)
    struct.pack_into("<H", h, 12, chip)
    return bytes(h) + b"x" * size


def table(nvs_enc=False, spiffs_enc=False, app_enc=True):
    def ent(t, s, o, z, n, enc):
        return b"\xaa\x50" + struct.pack("<BBII", t, s, o, z) + n.ljust(16, b"\0") + struct.pack("<I", 1 if enc else 0)
    body = (ent(1, 2, 0x9000, 0x5000, b"nvs", nvs_enc) + ent(1, 0, 0xE000, 0x2000, b"otadata", True)
            + ent(1, 1, 0x10000, 0x1000, b"phy_init", True) + ent(0, 0x10, 0x20000, 0x1C0000, b"ota_0", app_enc)
            + ent(0, 0x11, 0x1E0000, 0x1C0000, b"ota_1", True) + ent(1, 0x82, 0x3A0000, 0x60000, b"spiffs", spiffs_enc))
    return body + b"\xeb\xeb" + b"\xff" * 14 + hashlib.md5(body).digest()


def make_bundle(root, boot=None, parts=None, app=None):
    env = e.ENV_DEFAULT
    os.makedirs(os.path.join(root, env))
    files = {"bootloader.bin": (boot or image(), 0), "partitions.bin": (parts or table(), 0x8000),
             "boot_app0.bin": (b"\xff" * 0x2000, 0xE000), "firmware.bin": (app or image(size=3000), 0x20000)}
    entries = []
    for n, (d, off) in files.items():
        with open(os.path.join(root, env, n), "wb") as f:
            f.write(d)
        entries.append({"file": f"{env}/{n}", "offset": off, "size": len(d), "sha256": hashlib.sha256(d).hexdigest()})
    with open(os.path.join(root, "manifest.json"), "w") as f:
        json.dump({"builds": [{"env": env, "esptool_chip": "esp32c3", "files": entries}]}, f)


class T(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.key = os.path.join(self.tmp, "keys", "k.bin")
        os.makedirs(os.path.dirname(self.key))
        with open(self.key, "wb") as f:
            f.write(KEY)
        self.root = os.path.join(self.tmp, "b")

    def refused(self, text, **kw):
        make_bundle(self.root, **kw)
        with self.assertRaises(e.Refused) as c:
            e.prepare(self.root, self.key, os.path.join(self.tmp, "out"))
        self.assertIn(text, str(c.exception))

    def test_wrong_chip_refused(self):
        self.refused("chip id", boot=image(chip=0))

    def test_not_dio_refused(self):
        self.refused("DIO", boot=image(mode=0))

    def test_encrypted_nvs_refused(self):
        self.refused("nvs", parts=table(nvs_enc=True))

    def test_encrypted_spiffs_refused(self):
        self.refused("spiffs", parts=table(spiffs_enc=True))

    def test_unencrypted_app_refused(self):
        self.refused("not flagged encrypted", parts=table(app_enc=False))

    def test_damaged_table_refused(self):
        t = bytearray(table())
        t[5] ^= 1
        self.refused("MD5", parts=bytes(t))

    def test_corrupt_file_refused(self):
        make_bundle(self.root)
        with open(os.path.join(self.root, e.ENV_DEFAULT, "firmware.bin"), "ab") as f:
            f.write(b"z")
        with self.assertRaises(e.Refused):
            e.prepare(self.root, self.key, os.path.join(self.tmp, "out"))

    def test_bad_keys_refused(self):
        for bad in (b"", b"\0" * 32, b"\xff" * 32, KEY[:16], KEY + KEY):
            with open(self.key, "wb") as f:
                f.write(bad)
            with self.assertRaises(e.Refused):
                e.check_key(self.key)

    def test_key_inside_git_tree_refused(self):
        os.makedirs(os.path.join(self.tmp, ".git"))
        with self.assertRaises(e.Refused) as c:
            e.check_key(self.key)
        self.assertIn("git", str(c.exception))

    @unittest.skipUnless(shutil.which("espsecure") or shutil.which("espsecure.py"), "espsecure not installed")
    def test_good_bundle_encrypts_and_round_trips(self):
        make_bundle(self.root)
        plan = e.prepare(self.root, self.key, os.path.join(self.tmp, "out"))
        self.assertEqual([o for o, _ in plan], [0, 0x8000, 0xE000, 0x20000])
        for _, path in plan:
            with open(path, "rb") as f:
                self.assertEqual(len(f.read()) % 16, 0)
        self.assertIn("--flash-mode keep", e.flash_command(plan))


if __name__ == "__main__":
    unittest.main()
