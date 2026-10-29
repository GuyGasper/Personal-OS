#!/usr/bin/env python3
"""Authorize one Strava athlete and write private bootstrap files without printing credentials."""

import argparse
import base64
import getpass
import json
import os
from pathlib import Path
import secrets
import urllib.error
import urllib.parse
import urllib.request


def authorization_code(redirect, expected_redirect, expected_state):
    actual = urllib.parse.urlsplit(redirect)
    expected = urllib.parse.urlsplit(expected_redirect)
    if (actual.scheme, actual.netloc, actual.path) != (expected.scheme, expected.netloc, expected.path):
        raise ValueError("The callback URL does not match the selected redirect URI")
    query = urllib.parse.parse_qs(actual.query)
    if query.get("state") != [expected_state]:
        raise ValueError("OAuth state does not match this authorization attempt")
    if "activity:read_all" not in query.get("scope", [""])[0].split(","):
        raise ValueError("Authorize activity:read_all to sync all of your activities")
    code = query.get("code", [])
    if len(code) != 1 or not code[0]:
        raise ValueError("The callback URL contains no authorization code")
    return code[0]


def private_env(path, values):
    # Never overwrite an existing bootstrap, and never emit credential values.
    for key, value in values.items():
        if not key.replace("_", "").isalnum() or any(c in str(value) for c in "\r\n"):
            raise ValueError("Invalid environment-file entry")
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        stream.writelines(f"{key}={value}\n" for key, value in values.items())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client-id", required=True, type=int)
    parser.add_argument("--athlete-id", required=True, type=int)
    parser.add_argument("--redirect-uri", default="http://localhost/strava/callback")
    parser.add_argument("--directory", type=Path, default=Path(".local/strava"))
    args = parser.parse_args()
    try:
        if args.client_id <= 0 or args.athlete_id <= 0:
            raise ValueError("Client and athlete IDs must be positive")
        parts = urllib.parse.urlsplit(args.redirect_uri)
        if parts.scheme not in ("https", "http") or not parts.netloc or parts.query or parts.fragment:
            raise ValueError("Select an HTTP(S) redirect URI without a query or fragment")
        if args.directory.exists():
            raise ValueError("Select a new private output directory; existing credentials are never overwritten")
        state = secrets.token_urlsafe(32)
        url = "https://www.strava.com/oauth/authorize?" + urllib.parse.urlencode({
            "client_id": args.client_id, "redirect_uri": args.redirect_uri, "response_type": "code",
            "approval_prompt": "force", "scope": "read,activity:read_all", "state": state,
        })
        client_secret = getpass.getpass("Strava client secret: ")
        print("Open this URL and authorize your account:\n" + url)
        print("The localhost page may fail to load. Copy its complete URL after authorization.")
        redirect = getpass.getpass("Complete callback URL (hidden): ")
        code = authorization_code(redirect, args.redirect_uri, state)
        request = urllib.request.Request("https://www.strava.com/oauth/token",
                data=urllib.parse.urlencode({"client_id": args.client_id, "client_secret": client_secret,
                    "code": code, "grant_type": "authorization_code"}).encode(),
                headers={"Content-Type": "application/x-www-form-urlencoded"})
        with urllib.request.urlopen(request, timeout=30) as response:
            tokens = json.load(response)
        if tokens.get("athlete", {}).get("id") != args.athlete_id:
            raise ValueError("The authorized athlete differs from --athlete-id; no credential files were written")
        refresh = tokens.get("refresh_token")
        if not isinstance(refresh, str) or not refresh:
            raise ValueError("Strava returned no refresh token")
        args.directory.mkdir(parents=True, mode=0o700)
        private_env(args.directory / "sync.env", {
            "STRAVA_ATHLETE_ID": args.athlete_id, "STRAVA_CLIENT_ID": args.client_id,
            "STRAVA_CLIENT_SECRET": client_secret, "STRAVA_REFRESH_TOKEN": refresh,
            "STRAVA_TOKEN_ENCRYPTION_KEY": base64.b64encode(secrets.token_bytes(32)).decode(),
        })
        private_env(args.directory / "api.env", {
            "STRAVA_READ_TOKEN": secrets.token_urlsafe(32), "STRAVA_WEBHOOK_TOKEN": secrets.token_urlsafe(32),
        })
        print(f"Wrote private sync.env and api.env in {args.directory}. See docs/strava.md for installation.")
    except (ValueError, OSError, urllib.error.URLError, KeyError):
        # urllib exceptions can contain request context; never print their bodies or secrets.
        parser.exit(1, "Authorization failed. Check the callback state/scopes, athlete ID, output directory and Strava app settings.\n")


if __name__ == "__main__":
    main()