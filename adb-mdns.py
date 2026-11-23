#!/data/data/com.termux/files/usr/bin/python3
"""Print the ports advertised by adbd over mDNS.

Android's wireless debugging advertises `_adb-tls-connect._tcp` itself, so the
port can be read without scanning. While the "Pair device with pairing code"
dialog is open it also advertises `_adb-tls-pairing._tcp`; pass that service as
the second argument to read the pairing port instead.

adb's own client-side discovery is compiled out of both the Termux and nixpkgs
builds (`adb mdns services` says "mdns is not supported"), but the
advertisement is standard mDNS and any browser can read it.

Usage: ./adb-mdns.py [seconds] [service]   (default 4, _adb-tls-connect._tcp.local.)
Prints one port per line, newest advertisement first.
"""
import re
import sys
import time

try:
    from zeroconf import Zeroconf, ServiceBrowser
except ImportError:
    sys.exit("zeroconf not installed (pip install zeroconf)")

DEFAULT_SERVICE = "_adb-tls-connect._tcp.local."
SERVICE = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_SERVICE
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
    match = re.search(r"\((\d+)\)\.", item[1])
    return int(match.group(1)) if match else 1


def listening(port):
    import socket
    try:
        s = socket.create_connection(("127.0.0.1", port), 0.35)
        s.close()
        return True
    except OSError:
        return False


# Cached advertisements can outlive the adbd that made them, so connect ports
# are only printed while they still accept connections. A pairing advertisement
# lives only while the dialog is open and its port is proved by `adb pair`, so
# it is reported as advertised rather than probed with a plain TCP connect.
# Ports are tried newest advertisement first.
probe = SERVICE == DEFAULT_SERVICE
for port, _ in sorted(found.items(), key=generation, reverse=True):
    if not probe or listening(port):
        print(port)
