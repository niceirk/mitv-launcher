#!/usr/bin/env bash
# Первая настройка телевизора с компьютера (нужен adb, на телевизоре включена «Отладка по сети»):
#   ./tools/setup-tv.sh 192.168.1.50            — скачать последнюю версию с GitHub и поставить
#   ./tools/setup-tv.sh 192.168.1.50 my.apk     — поставить свой файл
set -euo pipefail
IP="$1"; APK="${2:-}"
P=ru.nikita.tvhome
adb connect "$IP:5555" >/dev/null
A=(adb -s "$IP:5555")
if [ -z "$APK" ]; then
  APK="$(mktemp --suffix=.apk)"
  curl -fsSL "https://github.com/niceirk/mitv-launcher/releases/latest/download/launcher.apk" -o "$APK"
fi
"${A[@]}" install -r "$APK"
"${A[@]}" shell appops set $P GET_USAGE_STATS allow            # «N ч за месяц» на полке
"${A[@]}" shell appops set $P REQUEST_INSTALL_PACKAGES allow   # обновление по кнопке
"${A[@]}" shell appops set $P SYSTEM_ALERT_WINDOW allow        # Telegram и срочные новости поверх приложений
"${A[@]}" shell pm grant $P android.permission.WRITE_SECURE_SETTINGS   # таймер сна
"${A[@]}" shell cmd notification allow_listener $P/$P.NotifService     # сообщения Telegram
"${A[@]}" shell cmd package set-home-activity $P/.MainActivity         # главный экран по умолчанию
echo "Готово: лаунчер установлен и настроен. Город погоды — в быстрых настройках."
