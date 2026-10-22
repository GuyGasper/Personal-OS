#!/usr/bin/env python3
"""Promote tested image digests to existing OKE deployments; restore images on failure."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.request


COMPONENTS = {"api": ("personal-os-api", "api"), "frontend": ("personal-os-frontend", "frontend")}
SHA = re.compile(r"(?:[a-f0-9]{40}|[a-f0-9]{64})")
NAMESPACE = "personal-os"


def release_images(path, source_sha, registry, namespace):
    if not SHA.fullmatch(source_sha):
        raise ValueError("Expected a full commit SHA")
    data = json.loads(path.read_text())
    if data.get("tag") != source_sha or data.get("platform") != "linux/arm64":
        raise ValueError("Release artifact does not match this commit and ARM64 platform")
    images = {}
    for component in COMPONENTS:
        reference = data.get(component + "_image", "")
        prefix = f"{registry}/{namespace}/personal-os/{component}@sha256:"
        if not reference.startswith(prefix) or not re.fullmatch(r"[a-f0-9]{64}", reference[len(prefix):]):
            raise ValueError(f"Expected a digest in the configured private {component} repository")
        images[component] = reference
    return images


def current_main(source_sha):
    repository = os.environ["GITHUB_REPOSITORY"]
    if not re.fullmatch(r"[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+", repository):
        raise ValueError("Invalid repository")
    api = os.environ.get("GITHUB_API_URL", "https://api.github.com")
    request = urllib.request.Request(
        f"{api}/repos/{repository}/git/ref/heads/main",
        headers={"Authorization": "Bearer " + os.environ["GH_TOKEN"], "Accept": "application/vnd.github+json"},
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.load(response)["object"]["sha"] == source_sha


class Deployment:
    def __init__(self, context):
        self.context = context

    def kubectl(self, *args, capture=True, input_text=None):
        request_timeout = "0" if args[0] == "rollout" else "30s"
        command = ["kubectl", "--context", self.context, "--namespace", NAMESPACE, "--request-timeout=" + request_timeout, *args]
        result = subprocess.run(command, input=input_text, text=True, capture_output=capture, timeout=360)
        if result.returncode:
            if capture:
                raise RuntimeError(result.stderr.strip() or "kubectl request failed")
            raise RuntimeError("kubectl operation failed")
        return result.stdout if capture else ""

    def get(self, name):
        return json.loads(self.kubectl("get", "deployment", name, "-o", "json"))

    @staticmethod
    def image(deployment, container):
        return next(item["image"] for item in deployment["spec"]["template"]["spec"]["containers"]
                    if item["name"] == container)

    @staticmethod
    def database_patch(deployment, container_patch):
        pod = deployment["spec"]["template"]["spec"]
        current = next(item for item in pod["containers"] if item["name"] == "api")
        keys = ("JDBC_URL", "DB_USERNAME", "DB_PASSWORD")
        for item in current.get("env", []):
            if item["name"] in keys:
                reference = item.get("valueFrom", {}).get("secretKeyRef", {})
                if "value" in item or reference.get("name") != "database-credentials" or reference.get("key") != item["name"]:
                    raise ValueError("Remove conflicting database environment overrides before using database-credentials")
        for mount in current.get("volumeMounts", []):
            if mount["mountPath"] == "/etc/database" and mount["name"] != "database-ca":
                raise ValueError("The /etc/database mount is already used by another volume")
        for volume in pod.get("volumes", []):
            if volume["name"] == "database-ca" and volume.get("secret", {}).get("secretName") != "database-credentials":
                raise ValueError("The database-ca volume is already used by another source")
        container_patch["envFrom"] = [item for item in current.get("envFrom", [])
                                      if item.get("secretRef", {}).get("name") != "database-credentials"]
        # Explicit key references fail before startup if the Secret or any key is missing.
        # The deployer needs no read access to Secret data; kubelet resolves these keys.
        container_patch["env"] = [{"name": key, "valueFrom": {"secretKeyRef": {
            "name": "database-credentials", "key": key, "optional": False}}} for key in keys]
        container_patch["volumeMounts"] = [{"name": "database-ca", "mountPath": "/etc/database", "readOnly": True}]
        return [{"name": "database-ca", "secret": {
            "secretName": "database-credentials", "optional": False,
            "items": [{"key": "DB_SSL_ROOT_CERT", "path": "root.crt"}]}}]

    @staticmethod
    def database_snapshot(deployment):
        pod = deployment["spec"]["template"]["spec"]
        api = next(item for item in pod["containers"] if item["name"] == "api")
        return {
            "env": [item for item in api.get("env", []) if item["name"] in {"JDBC_URL", "DB_USERNAME", "DB_PASSWORD"}],
            "envFrom": [item for item in api.get("envFrom", []) if item.get("secretRef", {}).get("name") == "database-credentials"],
            "volumeMounts": [item for item in api.get("volumeMounts", []) if item["mountPath"] == "/etc/database"],
            "volumes": [item for item in pod.get("volumes", []) if item["name"] == "database-ca"],
        }

    @staticmethod
    def restore_database_patch(deployment, container_patch, snapshot):
        pod = deployment["spec"]["template"]["spec"]
        api = next(item for item in pod["containers"] if item["name"] == "api")
        previous = {item["name"]: item for item in snapshot["env"]}
        container_patch["env"] = [previous.get(key, {"name": key, "$patch": "delete"})
                                  for key in ("JDBC_URL", "DB_USERNAME", "DB_PASSWORD")]
        container_patch["envFrom"] = [item for item in api.get("envFrom", [])
                                      if item.get("secretRef", {}).get("name") != "database-credentials"] + snapshot["envFrom"]
        container_patch["volumeMounts"] = snapshot["volumeMounts"] or [{"mountPath": "/etc/database", "$patch": "delete"}]
        return snapshot["volumes"] or [{"name": "database-ca", "$patch": "delete"}]

    def rollback(self, name, container, expected, desired, database_snapshot):
        self.patch(name, container, expected, desired, database_restore=database_snapshot)

    def patch(self, name, container, expected, desired, dry_run=False, database_restore=None):
        deployment = self.get(name)
        if self.image(deployment, container) != expected:
            raise RuntimeError(f"{name} changed outside this deployment; refusing to overwrite it")
        container_patch = {"name": container, "image": desired}
        pod_patch = {"containers": [container_patch]}
        if (name, container) == COMPONENTS["api"]:
            pod_patch["volumes"] = (self.database_patch(deployment, container_patch) if database_restore is None
                                    else self.restore_database_patch(deployment, container_patch, database_restore))
        patch = {
            "metadata": {"resourceVersion": deployment["metadata"]["resourceVersion"]},
            "spec": {"template": {"spec": pod_patch}},
        }
        args = ["patch", "deployment", name, "--type=strategic", "--patch-file=/dev/stdin"]
        if dry_run:
            args.append("--dry-run=server")
        self.kubectl(*args, input_text=json.dumps(patch))

    def rollout(self, name, seconds=300):
        self.kubectl("rollout", "status", "deployment/" + name, f"--timeout={seconds}s", capture=False)

    def load_balancer_ip(self):
        service = json.loads(self.kubectl("get", "service", "personal-os-web", "-o", "json"))
        addresses = service.get("status", {}).get("loadBalancer", {}).get("ingress", [])
        if not addresses or not addresses[0].get("ip"):
            raise ValueError("The existing public load balancer has no external IP")
        return addresses[0]["ip"]


def promote(client, images, smoke, snapshot, dry_run=False):
    previous = {}
    for component, (name, container) in COMPONENTS.items():
        deployment = client.get(name)
        if deployment["spec"].get("replicas", 1) < 1:
            raise ValueError(f"{name} is scaled down; refusing to start it implicitly")
        previous[component] = client.image(deployment, container)
        if component == "api":
            database_snapshot = Deployment.database_snapshot(deployment)
    ip = client.load_balancer_ip()
    # Validate both patches server-side before changing either deployment.
    for component, (name, container) in COMPONENTS.items():
        client.patch(name, container, previous[component], images[component], dry_run=True)
    snapshot.parent.mkdir(parents=True, exist_ok=True)
    snapshot.write_text(json.dumps({"images": previous, "load_balancer_ip": ip}, indent=2) + "\n")
    if dry_run:
        print("Database configuration and both image updates passed server-side dry run; no resources changed.")
        return
    changed = []
    try:
        for component, (name, container) in COMPONENTS.items():
            if component == "api" or previous[component] != images[component]:
                # Record intent before patch: an API timeout may hide a successful mutation.
                changed.append(component)
                print(f"Updating {name}; waiting before starting the next component.", flush=True)
                client.patch(name, container, previous[component], images[component])
            client.rollout(name)
            if client.image(client.get(name), container) != images[component]:
                raise RuntimeError(f"{name} is not running the requested image")
        # Retry briefly for backend draining and load balancer reconciliation.
        for attempt in range(3):
            try:
                smoke(ip)
                break
            except (RuntimeError, subprocess.SubprocessError):
                if attempt == 2:
                    raise
                time.sleep(5)
        print("Deployment verified: both published digests are ready and HTTPS checks passed.")
    except BaseException:
        failures = []
        for component in reversed(changed):
            name, container = COMPONENTS[component]
            try:
                current = client.image(client.get(name), container)
                if current == images[component]:
                    if component == "api":
                        client.rollback(name, container, current, previous[component], database_snapshot)
                    else:
                        client.patch(name, container, current, previous[component])
                    client.rollout(name, seconds=180)
                elif current != previous[component]:
                    raise RuntimeError("Deployment changed externally; rollback left it untouched")
            except (Exception,) as error:
                failures.append(f"{name}: {error}")
        if failures:
            print("Rollback requires attention: " + "; ".join(failures))
        else:
            print("Previous image digests restored.")
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-file", type=Path)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--registry")
    parser.add_argument("--namespace")
    parser.add_argument("--context")
    parser.add_argument("--snapshot", type=Path, default=Path(".local/previous-images.json"))
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--check-source", action="store_true")
    args = parser.parse_args()
    try:
        if args.check_source:
            eligible = current_main(args.source_sha)
            with open(os.environ["GITHUB_OUTPUT"], "a") as output:
                output.write(f"current={'true' if eligible else 'false'}\n")
            print("This is the current main commit." if eligible else "Skipped: a newer main commit superseded this release.")
            return
        if not all([args.release_file, args.registry, args.namespace, args.context, os.environ.get("KUBECONFIG")]):
            raise ValueError("Select the kubeconfig/context, release artifact, registry, and namespace explicitly")
        images = release_images(args.release_file, args.source_sha, args.registry, args.namespace)
        def smoke(ip):
            subprocess.run(["bash", str(Path(__file__).with_name("smoke-test.sh")), ip], check=True, timeout=180)
        promote(Deployment(args.context), images, smoke, args.snapshot, args.dry_run)
    except (ValueError, KeyError, OSError, RuntimeError, subprocess.SubprocessError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
