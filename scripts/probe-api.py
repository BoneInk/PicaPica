#!/usr/bin/env python3
"""Probe authentication transport using a synthetic, non-existent account only."""
import argparse
import hashlib
import hmac
import json
import re
import http.client
import socket
import ssl
import time
import urllib.error
import urllib.request
import urllib.parse
import uuid
from datetime import datetime, timezone

API = "https://picaapi.picacomic.com/"
API_KEY = "C69BAF41DA5ABD1FFEDC6D2FEA56B"
KEY = r"~d}$Q7$eIni=V)9\RK/P.RM4;9[7|@/CA}b~OW!3?EV`:<>M7pddUBL5n|0/*Cn"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--direct", action="store_true", help="Ignore HTTP(S) proxy environment for this probe only")
    parser.add_argument("--edge", choices=["104.19.53.76", "172.67.80.1", "172.67.7.24", "104.21.235.3"], help="Use a documented route while preserving TLS hostname and certificate validation")
    parser.add_argument("--proxy", help="Explicit HTTP proxy for this probe only; credentials are not accepted")
    parser.add_argument("--channel", choices=["1", "2", "3"], default="2", help="Public client protocol channel for a single compatibility check")
    args = parser.parse_args()
    proxy = urllib.parse.urlparse(args.proxy) if args.proxy else None
    if proxy and (proxy.scheme != "http" or not proxy.hostname or not proxy.port or proxy.username or proxy.password or proxy.path not in ("", "/")):
        parser.error("Use http://host:port without credentials or path")
    stamp, nonce = str(int(time.time())), uuid.uuid4().hex
    path = "auth/sign-in"
    signature = hmac.new(KEY.encode(), (path + stamp + nonce + "POST" + API_KEY).lower().encode(), hashlib.sha256).hexdigest()
    headers = {
        "api-key": API_KEY, "accept": "application/vnd.picacomic.com.v1+json",
        "app-channel": args.channel, "time": stamp, "nonce": nonce, "signature": signature,
        "app-version": "2.2.1.3.3.4", "app-build-version": "45", "app-platform": "android",
        "app-uuid": "defaultUuid", "image-quality": "original", "User-Agent": "okhttp/3.8.1",
        "Content-Type": "application/json; charset=utf-8",
    }
    # There are deliberately no real credential parameters or environment variables.
    data = json.dumps({"email": "picapica-connectivity-probe@example.invalid", "password": "not-a-real-account"}).encode()
    req = urllib.request.Request(API + path, data=data, headers=headers)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({"http": args.proxy, "https": args.proxy})) if proxy else urllib.request.build_opener(urllib.request.ProxyHandler({})) if args.direct else urllib.request.build_opener()
    if args.edge:
        class EdgeConnection(http.client.HTTPSConnection):
            def connect(self):
                if proxy:
                    self.set_tunnel(args.edge, self.port)
                self.sock = socket.create_connection((proxy.hostname, proxy.port) if proxy else (args.edge, self.port), self.timeout, self.source_address)
                if proxy:
                    self._tunnel()
                self.sock = self._context.wrap_socket(self.sock, server_hostname=self.host)

        class EdgeHandler(urllib.request.HTTPSHandler):
            def https_open(self, request):
                return self.do_open(EdgeConnection, request, context=ssl.create_default_context())

        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), EdgeHandler())
    result = {"time_utc": datetime.now(timezone.utc).isoformat(), "endpoint": API + path, "channel": args.channel, "mode": "direct" if args.direct else "environment_proxy", "real_account_tested": False}
    if args.edge:
        result.update(mode="documented_route_via_explicit_proxy" if proxy else "direct_documented_route", edge=args.edge, tls_hostname_verified=True)
    elif proxy:
        result["mode"] = "explicit_proxy"
    started = time.monotonic()
    try:
        try:
            response = opener.open(req, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            result["http_status"] = response.code
            try:
                payload = json.loads(response.read(65536))
                result["api_code"] = payload.get("code")
                error_id = str(payload.get("error", ""))
                if re.fullmatch(r"[A-Za-z0-9_.-]{1,32}", error_id):
                    result["error_code"] = error_id
                message = str(payload.get("message", "")).lower()
                if response.code == 429 or payload.get("code") == 429 or error_id == "1023" or "too many requests" in message or "rate limit" in message:
                    result["result"] = "reachable_but_rate_limited_not_a_credential_check"
                elif any(text in message for text in ("invalid email or password", "invalid username or password", "invalid credentials", "bad credentials", "wrong password", "invalid password", "incorrect password")):
                    result["result"] = "reachable_explicit_credentials_rejected"
                else:
                    result["result"] = "unexpected_response"
            except (ValueError, UnicodeDecodeError):
                result["result"] = "non_json_response"
    except Exception as error:
        result["result"] = "transport_failed"
        result["error_type"] = type(error).__name__
        result["error"] = str(error).split("?")[0][:180]
    result["elapsed_seconds"] = round(time.monotonic() - started, 3)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["result"] in ("reachable_but_rate_limited_not_a_credential_check", "reachable_explicit_credentials_rejected") else 1


if __name__ == "__main__":
    raise SystemExit(main())
