import pathlib
import tempfile
import unittest
from unittest.mock import patch

import source_snapshot as snapshot


class SnapshotPolicyTest(unittest.TestCase):
    def test_private_resources_history_and_build_outputs_are_excluded(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(snapshot, "ROOT", pathlib.Path(directory)):
            for relative in (".git/config", "app/build/classes.bin", "app/src/main/assets/model.tflite",
                             "app/src/main/private.db", "app/src/main/session.wav", "private-release/data.json"):
                self.assertFalse(snapshot.allowed(pathlib.Path(directory) / relative), relative)
            self.assertTrue(snapshot.allowed(pathlib.Path(directory) / "app/src/main/resources/owlett/skills/create_plan.md"))

    def test_symlink_cannot_copy_private_file(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(snapshot, "ROOT", pathlib.Path(directory)):
            link = pathlib.Path(directory) / "external.txt"
            link.symlink_to("/not-a-public-resource")
            self.assertFalse(snapshot.allowed(link))

    def test_internal_reference_stops_packaging(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(snapshot, "ROOT", pathlib.Path(directory)):
            source = pathlib.Path(directory) / "app/src/main/Example.kt"
            source.parent.mkdir(parents=True)
            source.write_text('val source = "birdreport"')
            with self.assertRaises(SystemExit):
                snapshot.validate_public_sources([source])
            source.write_text('val source = "ebird"')
            snapshot.validate_public_sources([source])


if __name__ == "__main__":
    unittest.main()
