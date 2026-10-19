"""Test the commit, archive, architecture, and image identity publishing guards."""

import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("image_bundle", Path(__file__).parents[1] / "image-bundle.py")
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)

SOURCE = "a" * 40
API_ID = "sha256:" + "b" * 64
FRONTEND_ID = "sha256:" + "c" * 64


class ImageBundleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)
        (self.path / "images.tar").write_bytes(b"test image archive")
        self.manifest = {
            "version": 1, "source_sha": SOURCE, "platform": "linux/arm64",
            "archive_sha256": hashlib.sha256(b"test image archive").hexdigest(),
            "images": {
                "api": {"tag": f"personal-os-api:ci-{SOURCE}", "id": API_ID},
                "frontend": {"tag": f"personal-os-frontend:ci-{SOURCE}", "id": FRONTEND_ID},
            },
        }
        self.save()

    def save(self):
        (self.path / "bundle.json").write_text(json.dumps(self.manifest))

    def publish(self):
        bundle.publish_images(self.path, SOURCE, "iad.ocir.io", "tenancy", self.path / "release.json")

    def test_rejects_a_bundle_from_another_commit_before_loading(self):
        self.manifest["source_sha"] = "d" * 40
        self.save()
        with patch.object(bundle, "docker") as docker, self.assertRaisesRegex(ValueError, "source commit"):
            self.publish()
        docker.assert_not_called()

    def test_rejects_modified_archive_before_loading(self):
        (self.path / "images.tar").write_bytes(b"changed after tests")
        with patch.object(bundle, "docker") as docker, self.assertRaisesRegex(ValueError, "checksum"):
            self.publish()
        docker.assert_not_called()

    def test_rejects_an_amd64_bundle_before_loading(self):
        self.manifest["platform"] = "linux/amd64"
        self.save()
        with patch.object(bundle, "docker") as docker, self.assertRaisesRegex(ValueError, "linux/arm64"):
            self.publish()
        docker.assert_not_called()

    def test_checks_both_loaded_images_before_pushing_either(self):
        inspected = [{"Id": API_ID}, {"Id": "sha256:" + "e" * 64}]
        with patch.object(bundle, "docker") as docker, patch.object(bundle, "inspect_image", side_effect=inspected):
            with self.assertRaisesRegex(ValueError, "differs from the tested"):
                self.publish()
        self.assertFalse(any(call.args[:2] == ("image", "push") for call in docker.call_args_list))
        self.assertFalse((self.path / "release.json").exists())

    def test_publishes_the_tested_images_and_records_registry_digests(self):
        inspected = [
            {"Id": API_ID}, {"Id": FRONTEND_ID},
            {"Id": API_ID, "RepoDigests": ["iad.ocir.io/tenancy/personal-os/api@sha256:" + "1" * 64]},
            {"Id": FRONTEND_ID, "RepoDigests": ["iad.ocir.io/tenancy/personal-os/frontend@sha256:" + "2" * 64]},
        ]
        with patch.object(bundle, "docker") as docker, patch.object(bundle, "inspect_image", side_effect=inspected):
            self.publish()
        pushes = [call.args for call in docker.call_args_list if call.args[:2] == ("image", "push")]
        self.assertEqual(pushes, [
            ("image", "push", f"iad.ocir.io/tenancy/personal-os/api:{SOURCE}"),
            ("image", "push", f"iad.ocir.io/tenancy/personal-os/frontend:{SOURCE}"),
        ])
        release = json.loads((self.path / "release.json").read_text())
        self.assertEqual(release["platform"], "linux/arm64")
        self.assertEqual(release["api_image"], "iad.ocir.io/tenancy/personal-os/api@sha256:" + "1" * 64)

    def test_inspects_actual_image_architecture(self):
        payload = json.dumps([{"Id": API_ID, "Os": "linux", "Architecture": "amd64"}])
        with patch.object(bundle, "docker", return_value=payload), self.assertRaisesRegex(ValueError, "linux/arm64"):
            bundle.inspect_image("some-image:test")


if __name__ == "__main__":
    unittest.main()
