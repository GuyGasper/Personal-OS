import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
from test_deploy_release import deploy, FakeDeployment, IMAGES


class WorkerDeployment(FakeDeployment):
    def __init__(self):
        super().__init__()
        self.worker = {"metadata": {"resourceVersion": "2"}, "spec": {"suspend": False,
            "jobTemplate": {"spec": {"template": {"spec": {"containers": [
                {"name": "strava-sync", "image": "worker:old"}]}}}}}}
        self.worker_patches = []
        self.worker_fail_preflight = False
        self.worker_lose_response = False

    def cronjob(self):
        return copy.deepcopy(self.worker)

    def patch_cronjob(self, expected, desired, dry_run=False):
        if self.worker_fail_preflight and dry_run:
            raise RuntimeError("Worker preflight rejected")
        self.worker_patches.append((expected, desired, dry_run))
        self.assert_worker_image(expected)
        if not dry_run:
            self.worker["spec"]["jobTemplate"]["spec"]["template"]["spec"]["containers"][0]["image"] = desired
            if self.worker_lose_response and desired == IMAGES["api"]:
                raise RuntimeError("Worker response lost after apply")

    def assert_worker_image(self, image):
        if deploy.Deployment.cronjob_image(self.worker) != image:
            raise RuntimeError("External worker update")


class StravaDeployTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.client = WorkerDeployment()
        self.smoke = Mock()
        self.path = Path(self.temp.name) / "previous.json"

    def run_deploy(self, dry_run=False):
        deploy.promote(self.client, IMAGES, self.smoke, self.path, dry_run, manage_strava=True)

    def test_promotes_worker_only_after_api_rollout_and_https(self):
        self.smoke.side_effect = lambda ip: self.client.assert_worker_image("worker:old")
        self.run_deploy()
        self.client.assert_worker_image(IMAGES["api"])
        self.assertFalse(self.client.worker["spec"]["suspend"])
        self.assertEqual(json.loads(self.path.read_text())["strava_sync_image"], "worker:old")

    def test_worker_preflight_failure_does_not_change_application(self):
        self.client.worker_fail_preflight = True
        with self.assertRaisesRegex(RuntimeError, "preflight"):
            self.run_deploy()
        self.assertTrue(all(dry for name, image, dry in self.client.patches))

    def test_worker_patch_response_loss_restores_all_images(self):
        self.client.worker_lose_response = True
        with self.assertRaisesRegex(RuntimeError, "response lost"):
            self.run_deploy()
        self.client.assert_worker_image("worker:old")
        for component, (name, container) in deploy.COMPONENTS.items():
            self.assertEqual(self.client.image(self.client.get(name), container), component + ":old")

    def test_dry_run_never_changes_worker(self):
        self.run_deploy(dry_run=True)
        self.client.assert_worker_image("worker:old")
        self.assertTrue(all(item[2] for item in self.client.worker_patches))

    def test_worker_patch_preserves_schedule_and_suspension(self):
        client = deploy.Deployment("selected-context")
        with patch.object(client, "cronjob", return_value=self.client.cronjob()), patch.object(client, "kubectl") as kubectl:
            client.patch_cronjob("worker:old", IMAGES["api"], dry_run=True)
        payload = json.loads(kubectl.call_args.kwargs["input_text"])
        self.assertEqual(payload["metadata"]["resourceVersion"], "2")
        self.assertEqual(set(payload["spec"]), {"jobTemplate"})
        self.assertIn("--dry-run=server", kubectl.call_args.args)