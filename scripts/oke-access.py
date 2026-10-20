#!/usr/bin/env python3
"""Configure OCI credentials and manage one deployment's temporary OKE API access."""

import argparse
import configparser
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.request


PREFIX = "personal-os-ci:"
LEASE_SECONDS = 3600


def oci(*args):
    result = subprocess.run(["oci", *args], capture_output=True, text=True, timeout=90)
    if result.returncode:
        # OCI errors contain request diagnostics, not credential file contents.
        raise RuntimeError(result.stderr.strip() or "OCI request failed")
    return json.loads(result.stdout).get("data") if result.stdout.strip() else None


def write_private(path, content):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as output:
        output.write(content)
    path.chmod(0o600)


def configure(directory):
    names = ("OCI_TENANCY_OCID", "OCI_USER_OCID", "OCI_FINGERPRINT", "OCI_PRIVATE_KEY", "OCI_REGION")
    values = {name: os.environ.get(name, "") for name in names}
    for name, value in values.items():
        if not value:
            raise ValueError(f"Missing production deployment setting: {name}")
    for name, kind in (("OCI_TENANCY_OCID", "tenancy"), ("OCI_USER_OCID", "user")):
        if not re.fullmatch(rf"ocid1\.{kind}\.[a-zA-Z0-9._-]+", values[name]):
            raise ValueError(f"Invalid {name}")
    if not re.fullmatch(r"[a-z0-9-]+", values["OCI_REGION"]):
        raise ValueError("Invalid OCI_REGION")
    if not re.fullmatch(r"[a-fA-F0-9]{2}(?::[a-fA-F0-9]{2}){15}", values["OCI_FINGERPRINT"]):
        raise ValueError("Invalid OCI_FINGERPRINT")
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    key = directory / "api-key.pem"
    write_private(key, values["OCI_PRIVATE_KEY"].rstrip() + "\n")
    config = configparser.ConfigParser(interpolation=None)
    config["DEFAULT"] = {
        "user": values["OCI_USER_OCID"], "tenancy": values["OCI_TENANCY_OCID"],
        "fingerprint": values["OCI_FINGERPRINT"], "region": values["OCI_REGION"], "key_file": str(key),
    }
    import io
    output = io.StringIO()
    config.write(output)
    write_private(directory / "oci-config", output.getvalue())
    if os.environ.get("GITHUB_ENV"):
        with open(os.environ["GITHUB_ENV"], "a") as env:
            for name, value in {
                "OCI_CLI_CONFIG_FILE": directory / "oci-config",
                "KUBECONFIG": directory / "kubeconfig",
                "OKE_ACCESS_STATE": directory / "access.json",
                "OKE_DEPLOY_DIR": directory,
            }.items():
                env.write(f"{name}={value}\n")
    print("Configured temporary OCI API credentials; secret contents are hidden.")


def api_nsg(cluster_id, requested=None):
    cluster = oci("ce", "cluster", "get", "--cluster-id", cluster_id)
    ids = cluster.get("endpoint-config", {}).get("nsg-ids", [])
    if requested:
        if requested not in ids:
            raise ValueError("Requested NSG is not attached to the selected OKE API endpoint")
        return requested
    if len(ids) != 1:
        raise ValueError("Set OKE_ENDPOINT_NSG_ID to one of the API endpoint's NSGs")
    return ids[0]


def narrow_rule(ip, marker):
    address = ipaddress.ip_address(ip)
    if address.version != 4 or not address.is_global:
        raise ValueError("Runner address must be a single public IPv4 address")
    return {
        "direction": "INGRESS", "protocol": "6", "source": f"{address}/32",
        "sourceType": "CIDR_BLOCK", "isStateless": False, "description": marker,
        "tcpOptions": {"destinationPortRange": {"min": 6443, "max": 6443}},
    }


def is_our_narrow_rule(rule):
    try:
        network = ipaddress.ip_network(rule.get("source", ""))
    except ValueError:
        return False
    ports = (rule.get("tcp-options") or {}).get("destination-port-range", {})
    return (rule.get("direction") == "INGRESS" and str(rule.get("protocol")) == "6"
            and rule.get("source-type") == "CIDR_BLOCK"
            and not rule.get("is-stateless", False)
            and network.version == 4 and network.prefixlen == 32
            and network.network_address.is_global
            and ports == {"min": 6443, "max": 6443})


def remove_rules(nsg_id, ids):
    if ids:
        oci("network", "nsg", "rules", "remove", "--nsg-id", nsg_id,
            "--security-rule-ids", json.dumps(ids))


def open_access(cluster_id, requested, owner, run_id, attempt, state_file):
    if not re.fullmatch(r"[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+", owner):
        raise ValueError("Invalid repository name")
    if not run_id.isdigit() or not attempt.isdigit():
        raise ValueError("Expected numeric Actions run ID and attempt")
    nsg_id = api_nsg(cluster_id, requested)
    marker = f"{PREFIX}{owner}:{run_id}:{attempt}:{int(time.time())}"
    state = {"nsg_id": nsg_id, "marker": marker, "closed": False}
    # Save before the API call so cleanup can find a rule even if its response is lost.
    write_private(state_file, json.dumps(state) + "\n")
    rules = oci("network", "nsg", "rules", "list", "--nsg-id", nsg_id, "--all")
    pattern = re.compile(rf"^{re.escape(PREFIX + owner)}:\d+:\d+:(\d+)$")
    expired = []
    for rule in rules:
        match = pattern.fullmatch(rule.get("description") or "")
        if match and is_our_narrow_rule(rule) and time.time() - int(match.group(1)) > LEASE_SECONDS:
            expired.append(rule["id"])
    remove_rules(nsg_id, expired)
    with urllib.request.urlopen("https://api.ipify.org", timeout=20) as response:
        address = response.read().decode().strip()
    rule = narrow_rule(address, marker)
    oci("network", "nsg", "rules", "add", "--nsg-id", nsg_id, "--security-rules", json.dumps([rule]))
    print(f"Allowed this runner only: {address}/32, TCP 6443.")


def close_access(state_file):
    if not state_file.exists():
        print("No temporary access was requested.")
        return
    state = json.loads(state_file.read_text())
    if state.get("closed"):
        return
    rules = oci("network", "nsg", "rules", "list", "--nsg-id", state["nsg_id"], "--all")
    ids = [rule["id"] for rule in rules
           if rule.get("description") == state["marker"] and is_our_narrow_rule(rule)]
    remove_rules(state["nsg_id"], ids)
    remaining = oci("network", "nsg", "rules", "list", "--nsg-id", state["nsg_id"], "--all")
    if any(rule.get("description") == state["marker"] for rule in remaining):
        raise RuntimeError("Temporary OKE access was not removed; inspect the API endpoint NSG")
    state["closed"] = True
    write_private(state_file, json.dumps(state) + "\n")
    print("Temporary runner access removed; existing admin rules preserved.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    config = commands.add_parser("configure")
    config.add_argument("--directory", type=Path, required=True)
    opening = commands.add_parser("open")
    opening.add_argument("--cluster-id", required=True)
    opening.add_argument("--nsg-id")
    opening.add_argument("--owner", required=True)
    opening.add_argument("--run-id", required=True)
    opening.add_argument("--attempt", required=True)
    opening.add_argument("--state-file", type=Path, required=True)
    closing = commands.add_parser("close")
    closing.add_argument("--state-file", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "configure":
            configure(args.directory)
        elif args.command == "open":
            open_access(args.cluster_id, args.nsg_id, args.owner, args.run_id, args.attempt, args.state_file)
        else:
            close_access(args.state_file)
    except (ValueError, KeyError, OSError, RuntimeError, subprocess.SubprocessError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
