#!/usr/bin/env python3

import base64
import getpass
import hashlib
import secrets
import sys

ITERATIONS = 310_000
SALT_BYTES = 16
HASH_BYTES = 32


def encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def main() -> int:
    password = getpass.getpass("New CAMPEX admin password (ASCII, at least 14 characters): ")
    confirmation = getpass.getpass("Confirm password: ")
    if password != confirmation:
        print("Passwords do not match.", file=sys.stderr)
        return 1
    if len(password) < 14 or not password.isascii():
        print("Use an ASCII password with at least 14 characters.", file=sys.stderr)
        return 1

    salt = secrets.token_bytes(SALT_BYTES)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("ascii"), salt, ITERATIONS, dklen=HASH_BYTES)
    print(f"pbkdf2-sha256${ITERATIONS}${encode(salt)}${encode(digest)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
