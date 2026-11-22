"""Minimal OIDC issuer for the e2e test: serves the discovery document and the JWKS as JSON.

Runs in a python:alpine container with /srv holding openid-configuration.json and certs.json.
"""
import http.server
import pathlib

ROUTES = {
    "/realms/auth-service/.well-known/openid-configuration": "openid-configuration.json",
    "/realms/auth-service/protocol/openid-connect/certs": "certs.json",
}


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        name = ROUTES.get(self.path.split("?")[0])
        if name is None:
            self.send_response(404)
            self.end_headers()
            return
        body = pathlib.Path("/srv", name).read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


http.server.ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
