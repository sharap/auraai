#!/usr/bin/env bash
# Puts the phone's adb on Wi-Fi, so it does not have to stay plugged in.
#
# Plug the phone in once, run this, unplug. The phone keeps listening on port 5555 until it
# reboots or Wi-Fi drops; run it again after either, with the cable in.
#
# With no arguments it finds the phone's address itself. Pass one to skip the search:
#   tools/wireless-adb.sh 192.168.1.50
#
# `adb tcpip` needs a USB connection to give the order — that is the whole reason the cable is
# wanted at all, and why this cannot be done from the phone's side alone.
set -u

PORT=5555
want=${1:-}

say() { printf '%s\n' "$*"; }

# The address, from the phone itself rather than from a guess.
#
# Not `wlan0`: the interface is called whatever this phone calls it — wlan2 on the one this was
# written for — and a phone usually holds several addresses at once, one of them a VPN's. So every
# wireless interface is asked, and the one chosen is the one sharing a network with this computer,
# because that is the only one that can be connected to. The first wireless address is the
# fallback, for the case where this computer is reached through something cleverer.
find_address() {
    local theirs mine
    theirs=$(adb -d shell ip -f inet addr 2>/dev/null |
        sed -n 's/.*inet \([0-9.]*\)\/[0-9]*.* \(wlan[0-9]*\)$/\1/p')
    [ -z "$theirs" ] && return 0
    mine=$(ip -f inet addr 2>/dev/null | sed -n 's/.*inet \([0-9.]*\)\/.*/\1/p' | grep -v '^127\.')
    local address ours
    for address in $theirs; do
        for ours in $mine; do
            if [ "${address%.*}" = "${ours%.*}" ]; then
                printf '%s\n' "$address"
                return 0
            fi
        done
    done
    printf '%s\n' "$theirs" | head -1
}

connected_wirelessly() {
    adb devices | grep -q ":$PORT[[:space:]]*device"
}

if [ -z "$want" ] && connected_wirelessly; then
    say "Уже по воздуху: $(adb devices | sed -n "s/\(.*:$PORT\)[[:space:]]*device/\1/p")"
    exit 0
fi

address=$want
if [ -z "$address" ]; then
    if ! adb -d get-state >/dev/null 2>&1; then
        say "Телефон не виден по кабелю. Подключите его, разрешите отладку и повторите."
        exit 1
    fi
    address=$(find_address)
fi

if [ -z "$address" ]; then
    say "Не удалось узнать адрес телефона в сети. Посмотрите его в настройках Wi-Fi и передайте:"
    say "  tools/wireless-adb.sh 192.168.1.50"
    exit 1
fi

say "Адрес телефона: $address"
# Restarts adbd listening on TCP. The USB connection drops for a moment and comes back.
adb -d tcpip "$PORT" >/dev/null 2>&1 || true
sleep 2
adb connect "$address:$PORT" >/dev/null 2>&1

for _ in 1 2 3 4 5; do
    if connected_wirelessly; then
        say "Готово. Кабель можно вынимать:"
        adb devices | sed -n '2,$p' | sed '/^$/d' | sed 's/^/  /'
        # Where it was, so the next run can be told without looking anything up.
        printf '%s\n' "$address" > "$(dirname "$0")/.wireless-adb-address"
        exit 0
    fi
    sleep 1
    adb connect "$address:$PORT" >/dev/null 2>&1
done

say "Не подключилось. Обычные причины: телефон и этот компьютер в разных сетях,"
say "или отладка по Wi-Fi выключена в настройках разработчика."
exit 1
