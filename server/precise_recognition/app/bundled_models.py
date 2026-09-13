"""Resolve portable model bundles without silently falling back to downloads."""
import os
from pathlib import Path


def model_root() -> Path | None:
    configured = os.getenv("OWLETT_MODEL_DIR")
    root = Path(configured).expanduser() if configured else Path(__file__).resolve().parents[1] / "models"
    if configured and not root.is_dir():
        raise FileNotFoundError("OWLETT_MODEL_DIR does not exist")
    return root if root.is_dir() else None


def required_model(relative: str) -> Path | None:
    root = model_root()
    if root is None:
        return None
    path = root / relative
    if not path.is_file():
        raise FileNotFoundError(f"Bundled model missing: {relative}; restore the complete bundle")
    return path


def load_birdnet_analyzer():
    from birdnetlib.analyzer import Analyzer

    model = required_model("birdnetlib/BirdNET_GLOBAL_6K_V2.4_Model_FP32.tflite")
    if model is None:
        return Analyzer()
    labels = required_model("birdnetlib/BirdNET_GLOBAL_6K_V2.4_Labels.txt")

    # birdnetlib 0.18.1 exposes load hooks, but not base model path arguments.
    class BundledAnalyzer(Analyzer):
        def load_labels(self):
            self.label_path = str(labels)
            return super().load_labels()

        def load_model(self):
            self.model_path = str(model)
            return super().load_model()

    return BundledAnalyzer()
