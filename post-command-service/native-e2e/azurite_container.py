"""Create a blob container in Azurite with a Shared Key signed REST call (standard library only).

  python azurite_container.py http://smoke-azurite:10000/devstoreaccount1 post-media-dev
"""
import base64
import hashlib
import hmac
import sys
import urllib.error
import urllib.request
from email.utils import formatdate

ACCOUNT = "devstoreaccount1"
# Azurite's well-known development key (public, emulator only)
KEY = "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw=="
VERSION = "2021-08-06"

endpoint, container = sys.argv[1].rstrip("/"), sys.argv[2]
date = formatdate(usegmt=True)
string_to_sign = "\n".join([
    "PUT", "", "", "", "", "", "", "", "", "", "", "",          # verb + 11 empty standard headers
    f"x-ms-date:{date}\nx-ms-version:{VERSION}",                # canonicalized headers
    f"/{ACCOUNT}/{ACCOUNT}/{container}\nrestype:container",     # canonicalized resource (path style)
])
signature = base64.b64encode(hmac.new(base64.b64decode(KEY), string_to_sign.encode(), hashlib.sha256).digest()).decode()
# No body: with data=b"" urllib adds a form Content-Type that is not in the signed string
request = urllib.request.Request(f"{endpoint}/{container}?restype=container", method="PUT", headers={
    "Content-Length": "0", "x-ms-date": date, "x-ms-version": VERSION,
    "Authorization": f"SharedKey {ACCOUNT}:{signature}"})
try:
    with urllib.request.urlopen(request) as response:
        print("container created:", response.status)
except urllib.error.HTTPError as error:
    print("container:", error.code, "(409 = already exists)" if error.code == 409 else error.read()[:300])
    sys.exit(0 if error.code == 409 else 1)
