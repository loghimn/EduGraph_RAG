"""Regression tests for filesystem confinement in retry fixtures."""
from pathlib import Path
import runpy
import tempfile
import unittest


confined_path = runpy.run_path(
    str(Path(__file__).with_name("retry-assertion-regression.py"))
)["confined_path"]


class FixturePathTests(unittest.TestCase):
    def test_nested_file_stays_inside_fixture(self):
        with tempfile.TemporaryDirectory(prefix="edugraph-path-tests-") as directory:
            root = Path(directory)
            self.assertEqual(confined_path(root, "outputs/test.sh"), root.resolve() / "outputs/test.sh")

    def test_traversal_and_absolute_paths_are_rejected(self):
        with tempfile.TemporaryDirectory(prefix="edugraph-path-tests-") as directory:
            root = Path(directory)
            for relative in ("../outside", "outputs/../../outside", str(root.resolve() / "absolute"), "."):
                with self.subTest(relative=relative), self.assertRaises(ValueError):
                    confined_path(root, relative)

    def test_symlink_to_outside_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix="edugraph-path-tests-") as directory:
            root = Path(directory)
            inside = root / "fixture"
            inside.mkdir()
            try:
                (inside / "escape").symlink_to(root, target_is_directory=True)
            except OSError as error:
                self.skipTest(f"Symlink creation unavailable: {error}")
            with self.assertRaises(ValueError):
                confined_path(inside, "escape/outside.sh")


if __name__ == "__main__":
    unittest.main()
