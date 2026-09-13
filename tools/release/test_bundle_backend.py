import unittest
import tempfile
from pathlib import Path
import sqlite3
from bundle_backend import model_entries
from stage_app_resources import backup_database


class BundlePolicyTest(unittest.TestCase):
    def test_database_backup_includes_wal_records_but_no_sidecars(self):
        with tempfile.TemporaryDirectory() as folder:
            source, target = Path(folder) / "source.db", Path(folder) / "target.db"
            connection = sqlite3.connect(source)
            try:
                connection.execute("PRAGMA journal_mode=WAL")
                connection.execute("CREATE TABLE birds(name TEXT)")
                connection.execute("INSERT INTO birds VALUES ('synthetic')")
                connection.commit()
                backup_database(source, target)
                self.assertFalse(Path(str(target) + "-wal").exists())
                self.assertFalse(Path(str(target) + "-shm").exists())
                copied = sqlite3.connect(target)
                try:
                    self.assertEqual(copied.execute("SELECT count(*) FROM birds").fetchone()[0], 1)
                finally:
                    copied.close()
            finally:
                connection.close()

    def test_apple_metadata_is_not_a_model(self):
        self.assertEqual(model_entries({"files": [{"path": "perch/._saved_model.pb"}, {"path": "perch/saved_model.pb"}]}), [{"path": "perch/saved_model.pb"}])

    def test_unsafe_or_unknown_model_paths_fail(self):
        for path in ("/perch/secret", "perch/../../secret", "private/key"):
            with self.assertRaises(ValueError):
                model_entries({"files": [{"path": path}]})


if __name__ == "__main__":
    unittest.main()
