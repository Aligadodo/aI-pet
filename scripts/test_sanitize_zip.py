from __future__ import annotations

import hashlib
import tempfile
import unittest
import zipfile
from pathlib import Path

import sanitize_zip


class SanitizeZipTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="sweetpet-zip-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.notices = self.root / "notices"
        (self.notices / "licenses").mkdir(parents=True)
        (self.notices / "THIRD_PARTY_NOTICES.txt").write_text("notice\n", encoding="utf-8")
        (self.notices / "licenses" / "LICENSE.txt").write_text("license\n", encoding="utf-8")

    def test_removes_private_metadata_and_is_reproducible(self) -> None:
        source = self.root / "source.zip"
        with zipfile.ZipFile(source, "w") as archive:
            archive.writestr("app\\main.py", "print('ok')\n")
            archive.writestr("tests/__pycache__/test.cpython-311.pyc", b"private path")
            archive.writestr("start.lnk", b"D:\\private\\workspace")

        first = self.root / "first.zip"
        second = self.root / "second.zip"
        sanitize_zip.sanitize(source, first, self.notices)
        sanitize_zip.sanitize(source, second, self.notices)

        self.assertEqual(hashlib.sha256(first.read_bytes()).digest(), hashlib.sha256(second.read_bytes()).digest())
        with zipfile.ZipFile(first) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual("print('ok')\n", archive.read("app/main.py").decode("utf-8"))
            self.assertNotIn("start.lnk", archive.namelist())
            self.assertFalse(any("__pycache__" in name for name in archive.namelist()))
            self.assertIn("THIRD_PARTY_NOTICES.txt", archive.namelist())
            self.assertIn("THIRD_PARTY_LICENSES/LICENSE.txt", archive.namelist())

    def test_rejects_parent_traversal(self) -> None:
        source = self.root / "unsafe.zip"
        with zipfile.ZipFile(source, "w") as archive:
            archive.writestr("../escape.txt", "no")
        output = self.root / "unsafe-output.zip"

        with self.assertRaises(ValueError):
            sanitize_zip.sanitize(source, output, self.notices)
        self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
