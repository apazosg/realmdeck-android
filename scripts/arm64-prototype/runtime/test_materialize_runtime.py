from __future__ import annotations

import io
import tarfile
import unittest
from pathlib import Path

from materialize_runtime import RecipeError, safe_member_name, write_member


class SafetyTests(unittest.TestCase):
    def test_rejects_absolute_and_parent_paths(self) -> None:
        for value in ("/etc/passwd", "../escape", "a/../../escape", "a\\b"):
            with self.subTest(value=value):
                with self.assertRaises(RecipeError):
                    safe_member_name(value)

    def test_rejects_symlink(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            info = tarfile.TarInfo("link")
            info.type = tarfile.SYMTYPE
            info.linkname = "/etc/passwd"
            with self.assertRaises(RecipeError):
                write_member(root, info, io.BytesIO())

    def test_accepts_regular_file_inside_root(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            info = tarfile.TarInfo("nested/file")
            info.size = 3
            self.assertEqual(write_member(root, info, io.BytesIO(b"abc")), 3)
            self.assertEqual((root / "nested/file").read_bytes(), b"abc")


if __name__ == "__main__":
    unittest.main()
