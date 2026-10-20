import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location("deploy_release", Path(__file__).parents[1] / "deploy-release.py")
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)

SOURCE = "a" * 40
IMAGES = {component: f"iad.ocir.io/tenancy/personal-os/{component}@sha256:" + str(index) * 64
          for index, component in enumerate(deploy.COMPONENTS, 1)}


class FakeDeployment:
    image = staticmethod(deploy.Deployment.image)

    def __init__(self):
        self.deployments = {
            name: {"metadata": {"resourceVersion": "1"}, "spec": {
                "replicas": 1, "template": {"spec": {"containers": [{"name": container, "image": component + ":old"}]}}
            }} for component, (name, container) in deploy.COMPONENTS.items()
        }
        self.patches = []
        self.fail_preflight = None
        self.fail_rollout = None
        self.fail_patch = None
        self.raced = False

    def get(self, name):
        return copy.deepcopy(self.deployments[name])

    def load_balancer_ip(self):
        return "129.153.0.152"

    def patch(self, name, container, expected, desired, dry_run=False):
        if dry_run and name == self.fail_preflight:
            raise RuntimeError("Preflight rejected")
        if not dry_run and name == self.fail_patch and desired in IMAGES.values():
            raise RuntimeError("Patch rejected")
        self.patches.append((name, desired, dry_run))
        if not dry_run:
            current = self.deployments[name]["spec"]["template"]["spec"]["containers"][0]
            if current["image"] != expected:
                raise RuntimeError("Concurrent image update")
            current["image"] = desired

    def rollout(self, name, seconds=300):
        image = self.deployments[name]["spec"]["template"]["spec"]["containers"][0]["image"]
        if name == self.fail_rollout and image in IMAGES.values():
            raise RuntimeError("Rollout failed")


class DeployReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)
        self.client = FakeDeployment()
        self.smoke = Mock()

    def run_deploy(self, dry_run=False):
        deploy.promote(self.client, IMAGES, self.smoke, self.path / "previous.json", dry_run)

    def assert_previous(self):
        for component, (name, container) in deploy.COMPONENTS.items():
            self.assertEqual(self.client.image(self.client.get(name), container), component + ":old")

    def test_dry_run_validates_both_patches_without_changing_images(self):
        self.run_deploy(dry_run=True)
        self.assert_previous()
        self.assertTrue(all(call[2] for call in self.client.patches))
        self.smoke.assert_not_called()

    def test_second_preflight_failure_cannot_change_first_deployment(self):
        self.client.fail_preflight = "personal-os-frontend"
        with self.assertRaisesRegex(RuntimeError, "Preflight"):
            self.run_deploy()
        self.assert_previous()
        self.assertTrue(all(call[2] for call in self.client.patches))

    def test_second_patch_failure_restores_the_first_deployment(self):
        self.client.fail_patch = "personal-os-frontend"
        with self.assertRaisesRegex(RuntimeError, "Patch rejected"):
            self.run_deploy()
        self.assert_previous()

    def test_patch_response_loss_still_restores_the_changed_image(self):
        original = self.client.patch
        def patch_then_timeout(name, container, expected, desired, dry_run=False):
            original(name, container, expected, desired, dry_run)
            if not dry_run and name == "personal-os-frontend" and desired == IMAGES["frontend"]:
                raise RuntimeError("Patch response lost after apply")
        self.client.patch = patch_then_timeout
        with self.assertRaisesRegex(RuntimeError, "response lost"):
            self.run_deploy()
        self.assert_previous()

    def test_rollout_failure_restores_both_previous_images(self):
        self.client.fail_rollout = "personal-os-frontend"
        with self.assertRaisesRegex(RuntimeError, "Rollout failed"):
            self.run_deploy()
        self.assert_previous()
        self.smoke.assert_not_called()

    def test_only_one_component_can_be_updating_at_a_time(self):
        pending = set()
        original_patch = self.client.patch
        original_rollout = self.client.rollout

        def patch_with_capacity_check(name, container, expected, desired, dry_run=False):
            if not dry_run and desired in IMAGES.values():
                if pending - {name}:
                    raise RuntimeError("Another component still needs rollout capacity")
                pending.add(name)
            return original_patch(name, container, expected, desired, dry_run)

        def complete_rollout(name, seconds=300):
            original_rollout(name, seconds)
            pending.discard(name)

        self.client.patch = patch_with_capacity_check
        self.client.rollout = complete_rollout
        self.run_deploy()
        self.assertEqual(pending, set())
        self.smoke.assert_called_once()

    def test_api_rollout_failure_leaves_frontend_untouched(self):
        self.client.fail_rollout = "personal-os-api"
        with self.assertRaisesRegex(RuntimeError, "Rollout failed"):
            self.run_deploy()
        self.assert_previous()
        self.assertFalse(any(name == "personal-os-frontend" and not dry_run
                             for name, image, dry_run in self.client.patches))
        self.smoke.assert_not_called()

    def test_https_failure_restores_both_images(self):
        self.smoke.side_effect = RuntimeError("HTTPS failed")
        with patch.object(deploy.time, "sleep"), self.assertRaisesRegex(RuntimeError, "HTTPS"):
            self.run_deploy()
        self.assert_previous()
        self.assertEqual(self.smoke.call_count, 3)

    def test_success_checks_https_and_records_previous_images(self):
        self.run_deploy()
        for component, (name, container) in deploy.COMPONENTS.items():
            self.assertEqual(self.client.image(self.client.get(name), container), IMAGES[component])
        self.smoke.assert_called_once_with("129.153.0.152")
        previous = json.loads((self.path / "previous.json").read_text())["images"]
        self.assertEqual(previous, {"api": "api:old", "frontend": "frontend:old"})

    def test_rejects_another_commits_release_and_mutable_images(self):
        path = self.path / "release.json"
        path.write_text(json.dumps({"tag": "b"*40, "platform": "linux/arm64"}))
        with self.assertRaisesRegex(ValueError, "this commit"):
            deploy.release_images(path, SOURCE, "iad.ocir.io", "tenancy")
        data = {"tag": SOURCE, "platform": "linux/arm64", "api_image": IMAGES["api"], "frontend_image": "other.example/repo:latest"}
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, "private frontend repository"):
            deploy.release_images(path, SOURCE, "iad.ocir.io", "tenancy")

    def test_does_not_overwrite_an_external_image_change_during_rollback(self):
        def fail_and_change(ip):
            self.client.deployments["personal-os-api"]["spec"]["template"]["spec"]["containers"][0]["image"] = "external:change"
            raise RuntimeError("HTTPS failure")
        self.smoke.side_effect = fail_and_change
        with patch.object(deploy.time, "sleep"), self.assertRaisesRegex(RuntimeError, "HTTPS"):
            self.run_deploy()
        self.assertEqual(self.client.image(self.client.get("personal-os-api"), "api"), "external:change")

    def test_patch_carries_resource_version_and_only_target_container(self):
        client = deploy.Deployment("selected-context")
        document = self.client.get("personal-os-api")
        with patch.object(client, "get", return_value=document), patch.object(client, "kubectl") as kubectl:
            client.patch("personal-os-api", "api", "api:old", IMAGES["api"])
        payload = json.loads(kubectl.call_args.kwargs["input_text"])
        self.assertEqual(payload["metadata"], {"resourceVersion": "1"})
        self.assertEqual(payload["spec"]["template"]["spec"]["containers"], [{"name": "api", "image": IMAGES["api"]}])
        self.assertNotIn("replicas", payload["spec"])


if __name__ == "__main__":
    unittest.main()
