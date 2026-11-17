#!/data/data/com.termux/files/usr/bin/python3
"""Print the ports advertised by adbd over mDNS.

Android's wireless debugging advertises `_adb-tls-connect._tcp` itself, so the
port can be read without scanning. adb's own client-side discovery is compiled
out of both the Termux and nixpkgs builds, but the advertisement is standard
mDNS and any browser can read it.

Usage: ./adb-mdns.py [seconds]   (default 4)
Prints one port per line, newest advertisement first.
"""
import re
import sys
import time

try:
    from zeroconf import Zeroconf, ServiceBrowser
except ImportError:
    sys.exit("zeroconf not installed (pip install zeroconf)")

SERVICE = "_adb-tls-connect._tcp.local."
seconds = float(sys.argv[1]) if len(sys.argv) > 1 else 4.0

found = {}  # port -> instance name


class Listener:
    def add_service(self, zc, type_, name):
        info = zc.get_service_info(type_, name, timeout=1500)
        if info:
            found[info.port] = name

    def update_service(self, zc, type_, name):
        self.add_service(zc, type_, name)

    def remove_service(self, *args):
        pass


zc = Zeroconf()
ServiceBrowser(zc, SERVICE, Listener())
time.sleep(seconds)
zc.close()


def generation(item):
    """Android names each new adbd instance "name", "name (2)", "name (3)"..."""
    match = re.search(r"\((\d+)\)\._adb-tls-connect", item[1])
    return int(match.group(1)) if match else 1


def listening(port):
    import socket
    try:
        s = socket.create_connection(("127.0.0.1", port), 0.35)
        s.close()
        return True
    except OSError:
        return False


# Cached advertisements can outlive the adbd that made them, so only print ports
# that still accept connections. They are tried newest advertisement first.
for port, _ in sorted(found.items(), key=generation, reverse=True):
    if listening(port):
        print(port)
