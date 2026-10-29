import importlib.util
import os
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("strava_authorize", Path(__file__).parents[1] / "strava-authorize.py")
authorize = importlib.util.module_from_spec(spec)
spec.loader.exec_module(authorize)


class StravaAuthorizeTest(unittest.TestCase):
    def test_validates_callback_state_scope_and_redirect(self):
        callback = "http://localhost/strava/callback?code=one-use&state=expected&scope=read,activity:read_all"
        self.assertEqual(authorize.authorization_code(callback, "http://localhost/strava/callback", "expected"), "one-use")
        for invalid in (callback.replace("expected", "wrong"), callback.replace("localhost", "elsewhere"),
                        callback.replace("activity:read_all", "read"), callback.replace("code=one-use", "error=denied")):
            with self.assertRaises(ValueError):
                authorize.authorization_code(invalid, "http://localhost/strava/callback", "expected")

    def test_discovers_numeric_athlete_id_and_checks_it_when_requested(self):
        tokens = {"athlete": {"id": 42}}
        self.assertEqual(authorize.authorized_athlete_id(tokens), 42)
        self.assertEqual(authorize.authorized_athlete_id(tokens, 42), 42)
        with self.assertRaises(ValueError):
            authorize.authorized_athlete_id(tokens, 99)

    def test_rejects_missing_or_invalid_athlete_ids(self):
        for value in (None, "guy_gasper", "42", 0, -1, True):
            with self.assertRaises(ValueError):
                authorize.authorized_athlete_id({"athlete": {"id": value}})
        with self.assertRaises(ValueError):
            authorize.authorized_athlete_id({})

    def test_private_files_are_never_overwritten_and_reject_line_injection(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sync.env"
            authorize.private_env(path, {"STRAVA_REFRESH_TOKEN": "secret"})
            self.assertEqual(path.read_text(), "STRAVA_REFRESH_TOKEN=secret\n")
            if os.name != "nt":
                self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            with self.assertRaises(FileExistsError):
                authorize.private_env(path, {"STRAVA_REFRESH_TOKEN": "replacement"})
            with self.assertRaises(ValueError):
                authorize.private_env(Path(directory) / "invalid.env", {"TOKEN": "line\nINJECT=value"})