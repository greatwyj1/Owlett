"""Version-pinned Perch loading using birdnet's existing model and backend classes."""
from pathlib import Path

def load_perch_v2(device: str):
    from birdnet.acoustic.models.perch_v2.model import AcousticModelPerchV2
    from birdnet.acoustic.models.perch_v2.pb import AcousticPBBackendFP32PerchV2
    from birdnet.utils.helper import get_species_from_file

    if device not in {"CPU", "GPU"}:
        raise ValueError("PERCH_DEVICE must be CPU or GPU")
    suffix = "perch_v2_cpu" if device == "CPU" else "perch_v2"
    # Version 1 is the artifact verified for this release, not a floating latest.
    from app.bundled_models import required_model
    bundled = required_model("perch/saved_model.pb")
    if bundled is not None:
        if device != "CPU":
            raise ValueError("The bundled Perch model supports CPU only")
        path = bundled.parent
    else:
        import kagglehub
        path = Path(kagglehub.model_download(f"google/bird-vocalization-classifier/tensorFlow2/{suffix}/1"))
    labels = get_species_from_file(path / "assets" / "labels.csv", encoding="utf8")
    labels.remove("inat2024_fsd50k")
    if len(labels) != 14795:
        raise ValueError("Perch v2 label set does not match the pinned model")
    return AcousticModelPerchV2.load(path, labels, backend_type=AcousticPBBackendFP32PerchV2, backend_kwargs={})
