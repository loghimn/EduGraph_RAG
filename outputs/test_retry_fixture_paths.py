"""Regression tests for filesystem confinement in retry fixtures."""
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest.mock import patch


fixture_helpers = runpy.run_path(
    str(Path(__file__).with_name("retry-assertion-regression.py"))
)
confined_path = fixture_helpers["confined_path"]
prepare_fixture = fixture_helpers["prepare_fixture"]


class FixturePathTests(unittest.TestCase):
    def test_path_like_payload_is_only_written_as_content(self):
        with tempfile.TemporaryDirectory(prefix="edugraph-path-tests-") as directory:
            root = Path(directory)
            repository = root / "repository"
            fixture = root / "fixture"
            (repository / "outputs").mkdir(parents=True)
            fixture.mkdir()
            (repository / "outputs/maven-bootstrap-tests.sh").write_text("# retry test\n", encoding="utf-8")
            payload = '# ../../escaped.txt\nfor attempt in 1 2 3; do\n'
            source = repository / "outputs/maven-bootstrap-command.sh"
            source.write_text(payload, encoding="utf-8")
            with patch.dict(prepare_fixture.__globals__, {"ROOT": repository}):
                prepare_fixture(fixture, "maven-bootstrap-tests.sh", "wrong-count")
            target = fixture / "outputs/maven-bootstrap-command.sh"
            self.assertEqual(target.read_text(encoding="utf-8"), '# ../../escaped.txt\nfor attempt in 1 2; do\n')
            self.assertEqual(source.read_text(encoding="utf-8"), payload)
            self.assertFalse((root / "escaped.txt").exists())

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
