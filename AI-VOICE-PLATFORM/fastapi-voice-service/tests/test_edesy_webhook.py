import hashlib
import hmac
import time

from api.edesy_webhook import _valid_signature


def test_edesy_webhook_signature_requires_fresh_timestamp_and_exact_body():
    secret = "test-secret"
    timestamp = str(int(time.time()))
    body = b'{"CallStatus":"completed"}'
    digest = hmac.new(secret.encode(), timestamp.encode() + b"." + body, hashlib.sha256).hexdigest()
    signature = f"v1={digest}"

    assert _valid_signature(body, timestamp, signature, secret)
    assert not _valid_signature(body + b" ", timestamp, signature, secret)
    assert not _valid_signature(body, timestamp, "v1=incorrect", secret)
    assert not _valid_signature(body, str(int(time.time()) - 301), signature, secret)
