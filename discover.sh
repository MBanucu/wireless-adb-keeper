# shellcheck shell=bash
# Shared settings for the scripts that look for adbd.
#
# Sourced, not executed.

# The keeper app pins adbd here when it can (pin-adb-port.sh).
ADB_PIN_PORT=${ADB_PIN_PORT:-5555}

# adbd's random TLS port is a listening socket in Linux's ephemeral range.
# Read the kernel's value when it is readable; on Android SELinux denies Termux
# access to /proc/sys/net/ipv4/ip_local_port_range, so the documented Linux
# default is the normal path on the phone. PORT_START/PORT_END override both.
if [ -z "${PORT_START:-}" ] || [ -z "${PORT_END:-}" ]; then
    _discover_kstart=""
    _discover_kend=""
    { read -r _discover_kstart _discover_kend < /proc/sys/net/ipv4/ip_local_port_range; } 2>/dev/null || true
    PORT_START=${PORT_START:-${_discover_kstart:-32768}}
    PORT_END=${PORT_END:-${_discover_kend:-60999}}
    unset _discover_kstart _discover_kend
fi
