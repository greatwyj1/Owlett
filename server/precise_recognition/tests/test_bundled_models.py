import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from app.bundled_models import model_root, required_model


class LocalModelTest(unittest.TestCase):
    def test_explicit_missing_root_fails(self):
        with patch.dict(os.environ, {"OWLETT_MODEL_DIR": "/missing/owlett-model-fixture"}):
            with self.assertRaises(FileNotFoundError):
                model_root()

    def test_incomplete_bundle_does_not_fall_back_to_network(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, {"OWLETT_MODEL_DIR": folder}):
            with self.assertRaises(FileNotFoundError):
                required_model("perch/saved_model.pb")

    def test_resolves_local_model(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, {"OWLETT_MODEL_DIR": folder}):
            path = Path(folder) / "perch/saved_model.pb"
            path.parent.mkdir()
            path.write_bytes(b"synthetic model")
            self.assertEqual(required_model("perch/saved_model.pb"), path)


if __name__ == "__main__":
    unittest.main()
