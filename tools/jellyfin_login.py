#!/usr/bin/env python3
"""Signs in to your Jellyfin server and sends the token to Free My TV on the TV, for its picks.

    python3 tools/jellyfin_login.py http://server:8096 192.168.1.50     (server: default jellyfin_url from config.properties;
                                                                         TV: default the one adb is connected to)

Run it in your own terminal: the password is asked here and goes only to your server, and the token isn't shown. The
token goes over adb into the app's private files (tools/send_key.py), never into config.properties or the APK, where
any app on the TV could read it. Jellyfin lists it under Dashboard -> Devices as "Free My TV"; deleting it there
revokes it. Signing in again replaces it. The server's address does go in the app: run ./build.sh and install the new
FreeMyTV.apk when it changes.
"""
import getpass
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

CONFIG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "config.properties")


def main():
    if not os.path.exists(CONFIG):
        sys.exit("Run ./build.sh once first: it creates config.properties.")
    with open(CONFIG, encoding="utf-8") as f:
        text = f.read()
    saved = re.search(r"^\s*jellyfin_url\s*=\s*(\S*)", text, re.M)
    args = sys.argv[1:]
    tv = args.pop() if args and not args[-1].startswith(("http://", "https://")) else None
    url = (args[0] if args else saved.group(1) if saved else "").rstrip("/")
    if not url.startswith(("http://", "https://")):
        sys.exit("Usage: python3 tools/jellyfin_login.py http://<server>:8096 [TV IP]")
    user = input("Jellyfin user: ").strip()
    password = getpass.getpass("Password: ")
    request = urllib.request.Request(
        url + "/Users/AuthenticateByName",
        data=json.dumps({"Username": user, "Pw": password}).encode(),
        headers={
            "Content-Type": "application/json",
            # The same DeviceId each time: Jellyfin then replaces the old token instead of adding one.
            "Authorization": 'MediaBrowser Client="Free My TV", Device="TV", DeviceId="freemytv", Version="1.0"',
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            token = json.load(response)["AccessToken"]
    except urllib.error.HTTPError as e:
        sys.exit("Jellyfin said no (HTTP %d): check the user and the password." % e.code)
    except (urllib.error.URLError, OSError) as e:
        sys.exit("Couldn't reach %s: %s" % (url, e))
    # The address stays here for ./build.sh; a token an older version saved here goes.
    line = "jellyfin_url=" + url
    text, found = re.subn(r"^\s*jellyfin_url\s*=.*$", lambda _: line, text, flags=re.M)
    if not found:
        text = text.rstrip("\n") + "\n" + line + "\n"
    text = re.sub(r"^\s*jellyfin_token\s*=.*\n?", "", text, flags=re.M)
    with open(CONFIG, "w", encoding="utf-8") as f:
        f.write(text)
    send = [sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "send_key.py")]
    sent = subprocess.run(send + ([tv] if tv else []) + ["jellyfin_token"], input=token + "\n", text=True)
    if sent.returncode != 0:
        sys.exit("The token didn't reach the TV: fix what send_key.py said, then sign in again.")
    print("Signed in as %s. If jellyfin_url is new or changed, run ./build.sh and install the new FreeMyTV.apk." % user)


main()
