#!/usr/bin/env python3
"""
build_adult_blocklist.py
========================

Downloads several well-maintained, **adult/pornography-specific** domain
blocklists, merges them into ONE clean, de-duplicated, *sorted* host list, and
writes it (plain + gzipped) ready to drop into the Android app as an asset.

NOTE (OPTIONAL): The app now downloads and merges these lists itself at runtime
(see DomainBlocklist in ContentFilter.kt), so you do NOT need this script for
normal use. Its only remaining job is to generate the OPTIONAL offline "seed"
(assets/blocklist/adult_hosts.txt.gz) that gives day-one/offline coverage before
the first online refresh. Leave the seed out for pure runtime fetching.

WHY A SCRIPT INSTEAD OF A BUNDLED FILE
--------------------------------------
These lists update constantly. Shipping a frozen copy goes stale. Run this
whenever you want a fresh blocklist (e.g. monthly) and replace the asset.

WHAT IT PRODUCES
----------------
    app/src/main/assets/blocklist/adult_hosts.txt      (one host per line, sorted)
    app/src/main/assets/blocklist/adult_hosts.txt.gz   (the one the app loads)

The Kotlin `DomainBlocklist` object expects the **sorted, gzipped** file and
binary-searches it on disk, so it scales to millions of entries with almost no
RAM. The file MUST stay sorted (this script guarantees it). Do not hand-edit it
in a way that breaks ASCII sort order, or lookups silently miss.

HOW MATCHING WORKS AT RUNTIME (so you understand what to put in the list)
-------------------------------------------------------------------------
The app blocks a page if its host, OR any parent of its host, is in this file.
  - "redtube.com" in the list  ->  blocks redtube.com AND m.redtube.com etc.
  - "0----q.tumblr.com" in the list -> blocks ONLY that subdomain, NOT tumblr.com
That is exactly why we keep full hostnames verbatim and DON'T collapse them to
their registrable domain: many entries are a single bad subdomain on an
otherwise-fine host (tumblr.com, blogspot.com, itch.io, ...).

SOURCES (all adult-specific, not general "bad words"/ad/malware lists)
----------------------------------------------------------------------
  - StevenBlack hosts, porn-only extension      (MIT)
  - Sinfonietta pornography-hosts               (MIT)
  - The Blocklist Project, porn.txt             (Unlicense)
  - Universite Toulouse 1 (UT1) categories      (CC BY-SA) via GitHub mirrors:
        mixed_adult, lingerie, (optional) dating, and the adult "urls" list
  - (OPTIONAL) the FULL UT1 "adult" database (millions of domains). It is NOT on
    GitHub as one raw file; download it yourself from the Toulouse server and
    point USE_OFFICIAL_UT1_ADULT / OFFICIAL_UT1_ADULT_TGZ at it (see below).

LICENCE NOTE
------------
UT1 is CC BY-SA: if you redistribute the merged list, share-alike + attribution
apply. StevenBlack/Sinfonietta (MIT) and Blocklist Project (Unlicense) are
permissive. Using the list privately inside your app to block sites is fine;
the obligations bite on *redistribution*. Check each source's LICENSE yourself.

USAGE
-----
    python3 build_adult_blocklist.py
    python3 build_adult_blocklist.py --out app/src/main/assets/blocklist
    python3 build_adult_blocklist.py --include-dating
    python3 build_adult_blocklist.py --ut1-adult-tgz ~/Downloads/adult.tar.gz

Requires only the Python 3 standard library.
"""

from __future__ import annotations

import argparse
import gzip
import io
import os
import re
import sys
import tarfile
import urllib.request

# --------------------------------------------------------------------------- #
# SOURCES — comment a line out to drop a source; add your own the same way.
# format: "hosts"   -> lines look like "0.0.0.0 example.com"
#         "domains" -> lines are bare hosts, one per line
#         "urls"    -> lines are "host/path/..."; we keep only the host
# --------------------------------------------------------------------------- #
SOURCES = [
    ("StevenBlack porn-only", "hosts",
     "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts"),
    ("Sinfonietta pornography", "hosts",
     "https://raw.githubusercontent.com/Sinfonietta/hostfiles/master/pornography-hosts"),
    ("Blocklist Project porn", "hosts",
     "https://raw.githubusercontent.com/blocklistproject/Lists/master/porn.txt"),
    ("UT1 mixed_adult", "domains",
     "https://raw.githubusercontent.com/olbat/ut1-blacklists/master/blacklists/mixed_adult/domains"),
    ("UT1 lingerie", "domains",
     "https://raw.githubusercontent.com/olbat/ut1-blacklists/master/blacklists/lingerie/domains"),
    # NOTE: UT1 also publishes an "adult/urls" file, but it is PATH-level data
    # (e.g. "tumblr.com/<user>", "etsy.com/...", "archiveofourown.org/..."). This
    # app blocks by HOST, so reducing those URLs to their host would over-block
    # entire mainstream platforms. Path-level blocking needs a different mechanism,
    # so the urls file is deliberately excluded here.
]

# Toggled on by --include-dating. Dating sites aren't porn, so off by default.
DATING_SOURCE = ("UT1 dating", "domains",
    "https://raw.githubusercontent.com/olbat/ut1-blacklists/master/blacklists/dating/domains")

# The full UT1 "adult" list (millions of hosts) is distributed as a tarball from
# the Toulouse server, NOT as a raw GitHub file. Download it yourself:
#     https://dsi.ut-capitole.fr/blacklists/download/adult.tar.gz
# then pass --ut1-adult-tgz /path/to/adult.tar.gz . We read the "domains" member.
OFFICIAL_UT1_ADULT_MEMBER = "adult/domains"

# A host we keep: letters/digits/hyphens in labels, a dot, a 2+ char TLD.
_HOST_RE = re.compile(r"^(?=.{1,253}$)(?:[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?\.)+[a-z]{2,}$")
_IP_RE = re.compile(r"^\d{1,3}(?:\.\d{1,3}){3}$")
_SKIP_HOSTS = {"localhost", "localhost.localdomain", "local", "broadcasthost",
               "0.0.0.0", "ip6-localhost", "ip6-loopback"}

# SAFETY NET: mainstream apex domains we refuse to block outright, even if a
# source lists them. This only drops the EXACT apex (and its "www."): a genuinely
# adult subdomain like "somemodel.tumblr.com" is still kept and blocked. Add any
# big platform you never want fully cut off. (Specific bad subdomains survive.)
NEVER_BLOCK = {
    "google.com", "youtube.com", "gmail.com", "bing.com", "duckduckgo.com",
    "wikipedia.org", "wikimedia.org", "tumblr.com", "blogspot.com", "blogger.com",
    "wordpress.com", "reddit.com", "x.com", "twitter.com", "facebook.com",
    "instagram.com", "tiktok.com", "pinterest.com", "quora.com", "medium.com",
    "github.com", "gitlab.com", "stackoverflow.com", "amazon.com", "ebay.com",
    "etsy.com", "wattpad.com", "goodreads.com", "archiveofourown.org",
    "deviantart.com", "freepik.com", "123rf.com", "pond5.com", "tenor.com",
    "itch.io", "apple.com", "microsoft.com", "cloudflare.com", "archive.org",
}


def _norm(host: str) -> str | None:
    """Lowercase, strip a port / leading 'www.' / trailing dot. Return None if junk."""
    host = host.strip().lower().strip(".")
    if not host or host in _SKIP_HOSTS:
        return None
    host = host.split(":", 1)[0]          # drop :port
    if host.startswith("www."):
        host = host[4:]
    if _IP_RE.match(host):                # we block by name, not by IP
        return None
    return host if _HOST_RE.match(host) else None


def _hosts_from_line(line: str, fmt: str):
    line = line.strip()
    if not line or line.startswith(("#", "!", ";")):
        return
    if fmt == "hosts":
        parts = line.split()
        # "0.0.0.0 example.com"  ->  take the last token
        cand = parts[-1] if len(parts) >= 2 else parts[0]
    elif fmt == "urls":
        cand = line.split("/", 1)[0]      # host before the first slash
    else:  # "domains"
        cand = line.split()[0]
    n = _norm(cand)
    if n:
        yield n


def _fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "blocklist-builder/1.0"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read().decode("utf-8", "replace")


def main() -> int:
    ap = argparse.ArgumentParser(description="Build a merged adult-domain blocklist asset.")
    ap.add_argument("--out", default="assets/blocklist",
                    help="output directory (default: assets/blocklist)")
    ap.add_argument("--include-dating", action="store_true",
                    help="also include the UT1 'dating' category")
    ap.add_argument("--ut1-adult-tgz", default=None,
                    help="path to the official UT1 adult.tar.gz for the FULL adult list")
    ap.add_argument("--gz-only", action="store_true",
                    help="write only adult_hosts.txt.gz (delete the plain .txt) — use for the APK asset")
    args = ap.parse_args()

    sources = list(SOURCES)
    if args.include_dating:
        sources.append(DATING_SOURCE)

    hosts: set[str] = set()
    for name, fmt, url in sources:
        try:
            text = _fetch(url)
        except Exception as e:          # noqa: BLE001 - one bad source shouldn't kill the build
            print(f"  !! SKIPPED {name}: {e}", file=sys.stderr)
            continue
        before = len(hosts)
        for line in text.splitlines():
            hosts.update(_hosts_from_line(line, fmt))
        print(f"  + {name:<26} {len(hosts) - before:>8} new  ({len(hosts):>8} total)")

    if args.ut1_adult_tgz:
        try:
            with tarfile.open(args.ut1_adult_tgz, "r:*") as tf:
                member = tf.extractfile(OFFICIAL_UT1_ADULT_MEMBER)
                if member is None:
                    raise KeyError(OFFICIAL_UT1_ADULT_MEMBER)
                before = len(hosts)
                for raw in io.TextIOWrapper(member, encoding="utf-8", errors="replace"):
                    hosts.update(_hosts_from_line(raw, "domains"))
                print(f"  + {'UT1 adult (official)':<26} {len(hosts) - before:>8} new  ({len(hosts):>8} total)")
        except Exception as e:          # noqa: BLE001
            print(f"  !! SKIPPED official UT1 adult: {e}", file=sys.stderr)

    if not hosts:
        print("No hosts collected — aborting.", file=sys.stderr)
        return 1

    # Drop mainstream apexes (keeps adult subdomains of them — see NEVER_BLOCK).
    hosts -= NEVER_BLOCK

    # MUST be sorted: the app binary-searches the file on disk.
    ordered = sorted(hosts)
    os.makedirs(args.out, exist_ok=True)
    txt_path = os.path.join(args.out, "adult_hosts.txt")
    gz_path = txt_path + ".gz"

    with open(txt_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(ordered))
        f.write("\n")
    # mtime=0 -> reproducible gzip (same input => identical bytes => smaller git diffs)
    with open(txt_path, "rb") as src, gzip.GzipFile(gz_path, "wb", mtime=0) as dst:
        dst.write(src.read())

    print(f"\nDONE: {len(ordered):,} unique hosts")
    if args.gz_only:
        os.remove(txt_path)
        print(f"  {gz_path}  ({os.path.getsize(gz_path):,} bytes)  <-- bundled asset")
    else:
        print(f"  {txt_path}  ({os.path.getsize(txt_path):,} bytes)")
        print(f"  {gz_path}  ({os.path.getsize(gz_path):,} bytes)  <-- ship this one")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
