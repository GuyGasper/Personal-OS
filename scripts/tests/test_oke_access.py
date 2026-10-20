import importlib.util
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("oke_access", Path(__file__).parents[1] / "oke-access.py")
access = importlib.util.module_from_spec(spec)
spec.loader.exec_module(access)


def live_rule(marker, rule_id="test"):
    return {"id": rule_id, "description": marker, "direction": "INGRESS", "protocol": "6",
            "source": "8.8.8.8/32", "source-type": "CIDR_BLOCK", "is-stateless": False,
            "tcp-options": {"destination-port-range": {"min": 6443, "max": 6443}}}


class OkeAccessTest(unittest.TestCase):
    def test_remove_uses_supported_cli_arguments(self):
        with patch.object(access, "oci") as oci:
            access.remove_rules("endpoint", ["owned-rule"])
        oci.assert_called_once_with("network", "nsg", "rules", "remove", "--nsg-id",
                                    "endpoint", "--security-rule-ids", '["owned-rule"]')

    def test_only_single_public_ipv4_is_allowed(self):
        for source in ["0.0.0.0/0", "8.8.8.0/24", "127.0.0.1", "10.0.0.1", "::1", "2001:4860:4860::8888"]:
            with self.subTest(source=source), self.assertRaises(ValueError):
                access.narrow_rule(source, "marker")
        rule = access.narrow_rule("8.8.8.8", "marker")
        self.assertEqual(rule["source"], "8.8.8.8/32")
        self.assertEqual(rule["tcpOptions"]["destinationPortRange"], {"min": 6443, "max": 6443})
        self.assertFalse(rule["isStateless"])

    def test_cannot_open_a_load_balancer_or_unrelated_nsg(self):
        with patch.object(access, "oci", return_value={"endpoint-config": {"nsg-ids": ["endpoint"]}}):
            with self.assertRaisesRegex(ValueError, "not attached"):
                access.api_nsg("cluster", "load-balancer")

    def test_cleanup_removes_only_exact_owned_rule(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "access.json"
            path.write_text(json.dumps({"nsg_id": "endpoint", "marker": "this-run", "closed": False}))
            rules = [live_rule("this-run", "mine"), live_rule("admin-rule", "admin"),
                     live_rule("another-run", "other")]
            with patch.object(access, "oci", side_effect=[rules, [rules[1], rules[2]]]), patch.object(access, "remove_rules") as remove:
                access.close_access(path)
            remove.assert_called_once_with("endpoint", ["mine"])
            self.assertTrue(json.loads(path.read_text())["closed"])

    def test_cleanup_finds_a_rule_when_add_response_was_lost(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "access.json"
            path.write_text(json.dumps({"nsg_id": "endpoint", "marker": "created-before-timeout", "closed": False}))
            with patch.object(access, "oci", side_effect=[[live_rule("created-before-timeout")], []]), patch.object(access, "remove_rules") as remove:
                access.close_access(path)
            remove.assert_called_once_with("endpoint", ["test"])

    def test_cleanup_reports_failure_if_rule_remains(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "access.json"
            path.write_text(json.dumps({"nsg_id": "endpoint", "marker": "this-run", "closed": False}))
            rules = [live_rule("this-run")]
            with patch.object(access, "oci", return_value=rules), patch.object(access, "remove_rules"):
                with self.assertRaisesRegex(RuntimeError, "not removed"):
                    access.close_access(path)
            self.assertFalse(json.loads(path.read_text())["closed"])

    def test_cleanup_ignores_unrelated_protocols_or_broad_rules(self):
        for changes in [{"protocol": "1", "tcp-options": None}, {"source": "0.0.0.0/0"},
                        {"tcp-options": {"destination-port-range": {"min": 443, "max": 443}}}]:
            rule = live_rule("same-marker") | changes
            self.assertFalse(access.is_our_narrow_rule(rule))

    def test_open_prunes_only_expired_matching_narrow_rules(self):
        with tempfile.TemporaryDirectory() as directory:
            now = int(time.time())
            rules = [live_rule(f"personal-os-ci:owner/repo:1:1:{now-7200}", "expired"),
                     live_rule(f"personal-os-ci:owner/repo:2:1:{now}", "recent"),
                     live_rule(f"personal-os-ci:other/repo:1:1:{now-7200}", "foreign"),
                     live_rule(f"personal-os-ci:owner/repo:3:1:{now-7200}", "broad") | {"source": "0.0.0.0/0"}]
            response = unittest.mock.MagicMock()
            response.__enter__.return_value.read.return_value = b"8.8.8.8"
            with patch.object(access, "api_nsg", return_value="endpoint"), patch.object(access, "oci", side_effect=[rules, None]), patch.object(access, "remove_rules") as remove, patch.object(access.urllib.request, "urlopen", return_value=response):
                access.open_access("cluster", None, "owner/repo", "123", "1", Path(directory) / "state.json")
            remove.assert_called_once_with("endpoint", ["expired"])

    def test_open_preserves_rules_without_descriptions(self):
        with tempfile.TemporaryDirectory() as directory:
            response = unittest.mock.MagicMock()
            response.__enter__.return_value.read.return_value = b"8.8.8.8"
            with patch.object(access, "api_nsg", return_value="endpoint"), patch.object(access, "oci", side_effect=[[{"id": "admin", "description": None}], None]) as oci, patch.object(access.urllib.request, "urlopen", return_value=response):
                access.open_access("cluster", None, "owner/repo", "123", "1", Path(directory) / "state.json")
            self.assertEqual(oci.call_args.args[:4], ("network", "nsg", "rules", "add"))

    def test_open_records_intent_before_calling_add(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "access.json"
            def mock_oci(*args):
                if args[:4] == ("network", "nsg", "rules", "list"):
                    return []
                self.assertTrue(path.exists(), "Cleanup state must be saved before creating access")
                raise RuntimeError("Add response lost")
            response = unittest.mock.MagicMock()
            response.__enter__.return_value.read.return_value = b"8.8.8.8"
            with patch.object(access, "api_nsg", return_value="endpoint"), patch.object(access, "oci", side_effect=mock_oci), patch.object(access.urllib.request, "urlopen", return_value=response):
                with self.assertRaisesRegex(RuntimeError, "response lost"):
                    access.open_access("cluster", None, "owner/repo", "123", "1", path)
            self.assertEqual(json.loads(path.read_text())["nsg_id"], "endpoint")


if __name__ == "__main__":
    unittest.main()
