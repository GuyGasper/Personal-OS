#!/usr/bin/env python3
"""Export tested ARM64 images and publish the same image configurations to OCIR."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


SHA = re.compile(r"(?:[a-f0-9]{40}|[a-f0-9]{64})")
DIGEST = re.compile(r"sha256:[a-f0-9]{64}")
COMPONENTS = ("api", "frontend")


def docker(*args, capture=False):
    result = subprocess.run(
        ["docker", *args], check=True, text=True,
        stdout=subprocess.PIPE if capture else None,
    )
    return result.stdout if capture else None


def file_digest(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def inspect_image(tag):
    image = json.loads(docker("image", "inspect", tag, capture=True))[0]
    if image.get("Os") != "linux" or image.get("Architecture") != "arm64":
        raise ValueError(f"{tag} must be a linux/arm64 image")
    if not DIGEST.fullmatch(image.get("Id", "")):
        raise ValueError(f"{tag} has an invalid image configuration digest")
    return image


def validate_source_sha(source_sha):
    if not SHA.fullmatch(source_sha):
        raise ValueError("Use the full source commit SHA")


def export_images(bundle_dir, source_sha, api_image, frontend_image):
    validate_source_sha(source_sha)
    manifest = {"version": 1, "source_sha": source_sha, "platform": "linux/arm64", "images": {}}
    for component, tag in zip(COMPONENTS, (api_image, frontend_image)):
        expected_tag = f"personal-os-{component}:ci-{source_sha}"
        if tag != expected_tag:
            raise ValueError(f"Expected tested image tag {expected_tag}")
        image = inspect_image(tag)
        manifest["images"][component] = {"tag": tag, "id": image["Id"]}

    bundle_dir.mkdir(parents=True, exist_ok=True)
    archive = bundle_dir / "images.tar"
    docker("image", "save", "--output", str(archive), api_image, frontend_image)
    manifest["archive_sha256"] = file_digest(archive)
    (bundle_dir / "bundle.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Exported verified ARM64 images for {source_sha}")


def read_bundle(bundle_dir, source_sha):
    validate_source_sha(source_sha)
    manifest = json.loads((bundle_dir / "bundle.json").read_text())
    if manifest.get("version") != 1 or manifest.get("source_sha") != source_sha:
        raise ValueError("Image bundle does not belong to the source commit being published")
    if manifest.get("platform") != "linux/arm64":
        raise ValueError("Image bundle must target linux/arm64")
    for component in COMPONENTS:
        image = manifest["images"][component]
        if image.get("tag") != f"personal-os-{component}:ci-{source_sha}":
            raise ValueError(f"Unexpected {component} image tag")
        if not DIGEST.fullmatch(image.get("id", "")):
            raise ValueError(f"Invalid {component} image configuration digest")
    if file_digest(bundle_dir / "images.tar") != manifest.get("archive_sha256"):
        raise ValueError("Image archive checksum does not match the tested bundle")
    return manifest


def publish_images(bundle_dir, source_sha, registry, namespace, release_file):
    if not re.fullmatch(r"[a-z0-9]+(?:[.-][a-z0-9]+)*", registry):
        raise ValueError("Invalid registry hostname")
    if not re.fullmatch(r"[a-z0-9_-]+", namespace):
        raise ValueError("Invalid OCI registry namespace")
    manifest = read_bundle(bundle_dir, source_sha)
    docker("image", "load", "--input", str(bundle_dir / "images.tar"))

    # Check both loaded image identities before any image is pushed.
    for component in COMPONENTS:
        tested = manifest["images"][component]
        if inspect_image(tested["tag"])["Id"] != tested["id"]:
            raise ValueError(f"Loaded {component} image differs from the tested image")

    release = {"tag": source_sha, "platform": "linux/arm64"}
    for component in COMPONENTS:
        tested = manifest["images"][component]
        repository = f"{registry}/{namespace}/personal-os/{component}"
        destination = f"{repository}:{source_sha}"
        docker("image", "tag", tested["tag"], destination)
        docker("image", "push", destination)
        pushed = inspect_image(destination)
        if pushed["Id"] != tested["id"]:
            raise ValueError(f"Published {component} image differs from the tested image")
        digests = pushed.get("RepoDigests", [])
        reference = next((item for item in digests if item.startswith(repository + "@")), None)
        if reference is None or not DIGEST.fullmatch(reference.split("@", 1)[1]):
            raise ValueError(f"No published registry digest was recorded for {component}")
        release[f"{component}_image"] = reference

    release_file.parent.mkdir(parents=True, exist_ok=True)
    release_file.write_text(json.dumps(release, indent=2) + "\n")
    print(release_file.read_text())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    export = commands.add_parser("export", help="Save images after container/browser tests pass")
    publish = commands.add_parser("publish", help="Publish an exported tested image bundle")
    for command in (export, publish):
        command.add_argument("--bundle-dir", type=Path, required=True)
        command.add_argument("--source-sha", required=True)
    export.add_argument("--api-image", required=True)
    export.add_argument("--frontend-image", required=True)
    publish.add_argument("--registry", required=True)
    publish.add_argument("--namespace", required=True)
    publish.add_argument("--release-file", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "export":
            export_images(args.bundle_dir, args.source_sha, args.api_image, args.frontend_image)
        else:
            publish_images(args.bundle_dir, args.source_sha, args.registry, args.namespace, args.release_file)
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
