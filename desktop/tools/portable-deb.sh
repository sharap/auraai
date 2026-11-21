#!/usr/bin/env bash
#
# Приводит зависимости собранного .deb в пригодный для раздачи вид.
#
# Делает две вещи.
#
# 1. Дописывает то, чего jpackage найти не может. libVLC грузится через JNA,
#    а ffmpeg запускается процессом — ни то, ни другое не линкуется, поэтому
#    в выведенный список не попадает. Без них пакет ставится и молча ничего
#    не играет.
#
# 2. Делает имена переносимыми между Debian и Ubuntu. Зависимости пакету
#    выписывает jpackage — по тем библиотекам, что нашёл **на машине
#    сборки**. В Ubuntu 24.04 половина из них переименована переходом на
#    64-битный time_t (`libasound2` → `libasound2t64`), в Debian 12 имена
#    прежние. Поэтому пакет, собранный на одной из систем, на другой не
#    ставится вовсе: dpkg ищет имя, которого там нет. Лечится тем, чем и
#    задумано в dpkg, — списком через `|`. Любое библиотечное имя получает
#    пару: с `t64` и без. Существует из пары одно, и его хватает;
#    несуществующее не мешает — альтернатива на то и альтернатива.
#
# Правится **только** control: данные пакета не трогаются вовсе.
# Распаковывать и собирать сто семьдесят мегабайт заново ради одной строки
# незачем, а заодно не меняются ни права, ни контрольные суммы файлов.
#
# Взято из соседнего проекта Ratatosk-desktop (тоже GPL-3.0) и дополнено
# первым пунктом.
#
# Использование: tools/portable-deb.sh [каталог со сборками]
set -euo pipefail

root="${1:-composeApp/build/compose/binaries}"

# Что jpackage не выводит, потому что оно не линкуется.
required="libvlc5, vlc-plugin-base, ffmpeg"

# Внутри архива всё принадлежит root. Под обычным пользователем это
# сохраняет fakeroot — им же jpackage собирает сам пакет.
if [[ -z "${PORTABLE_DEB_FAKEROOT:-}" ]]; then
    exec fakeroot env PORTABLE_DEB_FAKEROOT=1 "$0" "$@"
fi

shopt -s nullglob globstar

patched=0
for deb in "$root"/**/*.deb; do
    member="$(ar t "$deb" | grep '^control\.tar' | head -1)"
    if [[ -z "$member" ]]; then
        echo "в $deb нет control.tar — пропускаю" >&2
        continue
    fi

    work="$(mktemp -d)"
    trap 'rm -rf "$work"' EXIT

    ar p "$deb" "$member" > "$work/$member"
    case "$member" in
        *.zst) zstd -d -q -f "$work/$member" -o "$work/control.tar" ;;
        *.gz)  gzip -d -c "$work/$member" > "$work/control.tar" ;;
        *.xz)  xz -d -c "$work/$member" > "$work/control.tar" ;;
        *)     cp "$work/$member" "$work/control.tar" ;;
    esac

    mkdir -p "$work/ctl"
    tar -xf "$work/control.tar" -C "$work/ctl"

    python3 - "$work/ctl/control" "$required" <<'PY'
import re
import sys

path, required = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8") as f:
    control = f.read()


def pair(name: str) -> str:
    """Библиотечному имени — пара с `t64` и без; остальным ничего."""
    if "|" in name or not name.startswith("lib"):
        return name
    other = name[: -len("t64")] if name.endswith("t64") else name + "t64"
    return f"{name} | {other}"


def portable(match: re.Match) -> str:
    deps = [d.strip() for d in match.group(1).split(",") if d.strip()]
    for extra in (d.strip() for d in required.split(",") if d.strip()):
        # Сравнение по первому имени пары: повторный запуск не должен
        # добавлять то, что уже стоит.
        if not any(d.split("|")[0].strip() == extra for d in deps):
            deps.append(extra)
    return "Depends: " + ", ".join(pair(d) for d in deps)


patched, found = re.subn(r"^Depends:(.*)$", portable, control, count=1, flags=re.M)
if not found:
    sys.exit("в control нет строки Depends")
# Совпало с прежним — пакет уже в порядке: скрипт зовётся и повторно,
# когда сборка ничего не пересобирала, и это не повод падать.
if patched != control:
    with open(path, "w", encoding="utf-8") as f:
        f.write(patched)
PY

    tar --owner=root --group=root --numeric-owner -cf "$work/control.tar" -C "$work/ctl" .
    case "$member" in
        *.zst) zstd -19 -q -f "$work/control.tar" -o "$work/$member" ;;
        *.gz)  gzip -9 -c "$work/control.tar" > "$work/$member" ;;
        *.xz)  xz -9 -c "$work/control.tar" > "$work/$member" ;;
        *)     cp "$work/control.tar" "$work/$member" ;;
    esac

    # `r` заменяет член на месте: порядок в .deb (debian-binary, control,
    # data) обязателен, и перекладывать члены нельзя.
    ar r "$deb" "$work/$member" 2>/dev/null

    rm -rf "$work"
    trap - EXIT
    patched=$((patched + 1))
    echo "зависимости приведены в порядок: $deb"
    echo "зависимости: $(dpkg-deb -f "$deb" Depends)"
done

if [[ "$patched" -eq 0 ]]; then
    echo "пакетов не нашлось в $root" >&2
    exit 1
fi
