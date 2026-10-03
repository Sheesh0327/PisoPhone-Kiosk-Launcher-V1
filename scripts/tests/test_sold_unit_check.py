import os, sys, unittest
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import sold_unit_check as s

SUMMARY = """SECURE_BOOT_EN (BLOCK0)      Secure boot enabled     = False R/W (0b0)
SPI_BOOT_CRYPT_CNT (BLOCK0)  Flash encryption        = Enable R/W (0b111)
DIS_USB_JTAG (BLOCK0)        USB JTAG                = True R/W (0b1)
DIS_PAD_JTAG (BLOCK0)        pad JTAG                = True R/W (0b1)
ENABLE_SECURITY_DOWNLOAD (BLOCK0)  x                 = True R/W (0b1)
"""


class T(unittest.TestCase):
    def test_parse(self):
        v = s.parse_summary(SUMMARY)
        self.assertEqual(v["SPI_BOOT_CRYPT_CNT"], "Enable")
        self.assertFalse(s.is_set(v["SECURE_BOOT_EN"]))

    def test_fuses_locked_flags_missing_secure_boot(self):
        problems = s.fuse_report(s.parse_summary(SUMMARY), True)
        self.assertEqual(len(problems), 1)
        self.assertIn("SECURE_BOOT_EN", problems[0])

    def test_fresh_board_expected_clear(self):
        self.assertTrue(s.fuse_report(s.parse_summary(SUMMARY), False))

    def test_flash_verdicts(self):
        self.assertEqual(s.flash_verdict(b"\xe9" + bytes(100))[0], "PLAINTEXT")
        self.assertEqual(s.flash_verdict(b"hello wifi_pass=abc" * 50)[0], "PLAINTEXT")
        self.assertEqual(s.flash_verdict(os.urandom(8192))[0], "CIPHERTEXT")
        self.assertEqual(s.flash_verdict(os.urandom(8192), "secret")[0], "CIPHERTEXT")
        self.assertEqual(s.flash_verdict(os.urandom(100) + b"secret" + os.urandom(100), "secret")[0], "PLAINTEXT")


unittest.main() if __name__ == "__main__" else None
