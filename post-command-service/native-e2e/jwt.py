"""RS256 key, JWKS and tokens for the e2e test, with only the standard library and the openssl CLI.

  python3 jwt.py init  DIR ISSUER        -> DIR/key.pem, DIR/openid-configuration.json, DIR/certs.json
  python3 jwt.py token DIR ISSUER SUB    -> prints a signed access token for subject SUB
"""
import base64
import json
import pathlib
import subprocess
import sys
import time


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def init(directory: pathlib.Path, issuer: str) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    key = directory / "key.pem"
    subprocess.run(["openssl", "genrsa", "-out", str(key), "2048"], check=True, capture_output=True)
    modulus = subprocess.run(["openssl", "rsa", "-in", str(key), "-noout", "-modulus"],
                             check=True, capture_output=True, text=True).stdout.strip().split("=", 1)[1]
    jwk = {"kty": "RSA", "kid": "e2e", "use": "sig", "alg": "RS256",
           "n": b64url(bytes.fromhex(modulus)), "e": "AQAB"}
    (directory / "certs.json").write_text(json.dumps({"keys": [jwk]}))
    (directory / "openid-configuration.json").write_text(json.dumps({
        "issuer": issuer,
        "jwks_uri": issuer + "/protocol/openid-connect/certs",
        "authorization_endpoint": issuer + "/protocol/openid-connect/auth",
        "token_endpoint": issuer + "/protocol/openid-connect/token",
        "subject_types_supported": ["public"],
        "id_token_signing_alg_values_supported": ["RS256"],
        "response_types_supported": ["code"],
    }))


def token(directory: pathlib.Path, issuer: str, subject: str) -> str:
    now = int(time.time())
    header = {"alg": "RS256", "typ": "JWT", "kid": "e2e"}
    claims = {"iss": issuer, "sub": subject, "iat": now, "exp": now + 3600,
              "preferred_username": "e2e-" + subject[:8], "scope": "openid",
              "realm_access": {"roles": ["USER"]}}
    signing_input = b64url(json.dumps(header).encode()) + "." + b64url(json.dumps(claims).encode())
    signature = subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(directory / "key.pem")],
                               input=signing_input.encode(), check=True, capture_output=True).stdout
    return signing_input + "." + b64url(signature)


if __name__ == "__main__":
    command, directory, issuer = sys.argv[1], pathlib.Path(sys.argv[2]), sys.argv[3]
    if command == "init":
        init(directory, issuer)
    else:
        print(token(directory, issuer, sys.argv[4]))
