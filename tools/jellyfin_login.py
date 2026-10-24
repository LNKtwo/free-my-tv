#!/usr/bin/env python3
"""Signs in to your Jellyfin server and saves a token for Free My TV's picks in config.properties.

    python3 tools/jellyfin_login.py [http://server:8096]     (default: jellyfin_url from config.properties)

Run it in your own terminal: the password is asked here and goes only to your server, and the token isn't shown.
Jellyfin lists the token under Dashboard -> Devices as "Free My TV"; deleting it there revokes it. Signing in again
replaces it. Then run ./build.sh and install the new FreeMyTV.apk.
"""
import getpass
import json
import os
import re
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
    url = (sys.argv[1] if len(sys.argv) > 1 else saved.group(1) if saved else "").rstrip("/")
    if not url.startswith(("http://", "https://")):
        sys.exit("Usage: python3 tools/jellyfin_login.py http://<server>:8096")
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
    for key, value in (("jellyfin_url", url), ("jellyfin_token", token)):
        line = key + "=" + value
        text, found = re.subn(r"^\s*" + key + r"\s*=.*$", lambda _: line, text, flags=re.M)
        if not found:
            text = text.rstrip("\n") + "\n" + line + "\n"
    with open(CONFIG, "w", encoding="utf-8") as f:
        f.write(text)
    print("Saved a token for %s in config.properties. Now run ./build.sh." % user)


main()
