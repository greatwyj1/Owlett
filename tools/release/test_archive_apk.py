import datetime
import pathlib
import tempfile
import unittest
from unittest.mock import patch

from archive_apk import archive, digest, replace_latest


class ArchiveTest(unittest.TestCase):
    def test_archive_is_repeatable_and_preserves_original_timestamp(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root / "input.apk"
            source.write_bytes(b"synthetic apk fixture")
            metadata = dict(versionName="1.0.1", sha256=digest(source),
                            builtAt=datetime.datetime.fromtimestamp(source.stat().st_mtime).astimezone().isoformat())
            target = archive(source, metadata, root, "internal")
            self.assertEqual(source.stat().st_mtime, target.stat().st_mtime)
            self.assertEqual(target, archive(source, metadata, root, "internal"))
            self.assertEqual(2, len(list(root.glob("*.apk"))))
            self.assertTrue(target.with_suffix(".json").is_file())

    def test_existing_artifact_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root / "input.apk"
            source.write_bytes(b"synthetic original")
            metadata = dict(versionName="1.0.0", sha256=digest(source), builtAt="2026-09-08T21:00:00+08:00")
            target = archive(source, metadata, root, "internal")
            target.write_bytes(b"different existing artifact")
            with self.assertRaises(SystemExit):
                archive(source, metadata, root, "internal")
            self.assertEqual(b"different existing artifact", target.read_bytes())

    def test_version_cannot_escape_output_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            with self.assertRaises(SystemExit):
                archive(root / "unused", dict(versionName="../../private", sha256="abc", builtAt="2026-09-09T10:00:00+08:00"), root, "internal")

    def test_latest_alias_preserves_both_archives_and_replaces_partial_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            previous = root / "previous.apk"
            previous.write_bytes(b"old verified package")
            target = root / "versioned.apk"
            target.write_bytes(b"new verified package")
            latest = root / "latest.apk"
            latest.write_bytes(previous.read_bytes())
            latest.with_suffix(".apk.tmp").write_bytes(b"partial")
            replace_latest(target, latest)
            self.assertTrue(latest.is_symlink())
            self.assertEqual(target.read_bytes(), latest.read_bytes())
            self.assertEqual(b"old verified package", previous.read_bytes())
            replace_latest(target, latest)
            self.assertFalse(latest.with_suffix(".apk.tmp").exists())

    def test_failed_latest_copy_preserves_previous_alias_and_cleans_stage(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            target = root / "versioned.apk"
            target.write_bytes(b"new")
            latest = root / "latest.apk"
            latest.write_bytes(b"old")
            with patch.object(pathlib.Path, "symlink_to", side_effect=NotImplementedError), \
                    patch("archive_apk.shutil.copy2", side_effect=OSError("disk full")):
                with self.assertRaises(OSError):
                    replace_latest(target, latest)
            self.assertEqual(b"old", latest.read_bytes())
            self.assertEqual(b"new", target.read_bytes())
            self.assertFalse(latest.with_suffix(".apk.tmp").exists())


if __name__ == "__main__":
    unittest.main()
