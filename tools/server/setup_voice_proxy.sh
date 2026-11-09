#!/bin/bash
# HanReader neural voice: a public HTTPS address for the voice container (selfhost-tts-stt-1, 127.0.0.1:8080).
#
# Run as root on the server after the DNS A record of $DOMAIN points to $IP:
#   bash setup_voice_proxy.sh
#
# What it does (idempotent, safe to run again):
#  1. Checks DNS, that nginx (FASTPANEL's) reads /etc/nginx/conf.d, and that the voice container runs.
#  2. Gets a free Let's Encrypt certificate for $DOMAIN (certbot, renewed automatically, nginx reloaded).
#  3. Writes /etc/nginx/conf.d/hanreader-voice.conf: only POST /v1/audio/speech is open, only with the
#     app key (made once, kept in /root/hanreader-voice-key), at most 60 requests a minute per address.
#     nginx puts the container's own SELFHOST_TOKEN on the request, so that token never leaves the server.
# FASTPANEL's own sites are not touched. To undo: rm /etc/nginx/conf.d/hanreader-voice.conf && systemctl reload nginx
set -euo pipefail

DOMAIN="${DOMAIN:-voice.saintjust.space}"
IP="${IP:-45.67.129.73}"
CONTAINER="${CONTAINER:-selfhost-tts-stt-1}"
CONF=/etc/nginx/conf.d/hanreader-voice.conf
KEY_FILE=/root/hanreader-voice-key
WEBROOT=/var/www/letsencrypt

fail() { echo "ОШИБКА: $*" >&2; exit 1; }

getent ahostsv4 "$DOMAIN" | grep -q "$IP" || fail "$DOMAIN пока не указывает на $IP. Добавь A-запись у регистратора домена и подожди 5–30 минут."
nginx -T 2>/dev/null | grep -qE 'include[[:space:]]+/etc/nginx/conf\.d/\*\.conf' || fail "nginx не подключает /etc/nginx/conf.d — напиши разработчику."
TOKEN="$(docker exec "$CONTAINER" printenv SELFHOST_TOKEN)" || fail "контейнер $CONTAINER не отвечает."
[ -n "$TOKEN" ] || fail "у контейнера нет SELFHOST_TOKEN."

if ! command -v certbot >/dev/null; then
    apt-get update -qq && apt-get install -y -qq certbot >/dev/null
fi
mkdir -p "$WEBROOT"
[ -s "$KEY_FILE" ] || { openssl rand -hex 16 > "$KEY_FILE"; chmod 600 "$KEY_FILE"; }
KEY="$(cat "$KEY_FILE")"

HTTP_BLOCK="server {
    listen $IP:80;
    server_name $DOMAIN;
    location /.well-known/acme-challenge/ { root $WEBROOT; }
    location / { return 301 https://\$host\$request_uri; }
}"

if [ ! -s "/etc/letsencrypt/live/$DOMAIN/fullchain.pem" ]; then
    echo "$HTTP_BLOCK" > "$CONF"
    nginx -t -q && systemctl reload nginx
    certbot certonly --webroot -w "$WEBROOT" -d "$DOMAIN" --non-interactive --agree-tos \
        --register-unsafely-without-email --deploy-hook "systemctl reload nginx"
fi

umask 077
cat > "$CONF" <<NGX
# HanReader neural voice (tools/server/setup_voice_proxy.sh). Holds the voice token: root only.
limit_req_zone \$binary_remote_addr zone=hanreader_voice:10m rate=60r/m;

$HTTP_BLOCK

server {
    listen $IP:443 ssl;
    server_name $DOMAIN;
    ssl_certificate /etc/letsencrypt/live/$DOMAIN/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/$DOMAIN/privkey.pem;
    client_max_body_size 64k;
    access_log off;

    location = /v1/audio/speech {
        if (\$http_authorization != "Bearer $KEY") { return 401; }
        limit_req zone=hanreader_voice burst=20 nodelay;
        limit_req_status 429;
        proxy_set_header Authorization "Bearer $TOKEN";
        proxy_set_header Host \$host;
        proxy_read_timeout 60s;
        proxy_pass http://127.0.0.1:8080;
    }
    location = /health { proxy_pass http://127.0.0.1:8080; }
    location / { return 404; }
}
NGX
chmod 600 "$CONF"
nginx -t -q && systemctl reload nginx

CODE="$(curl -s -o /tmp/hanreader-voice-test -w '%{http_code}' -X POST "https://$DOMAIN/v1/audio/speech" \
    -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
    -d '{"model":"kokoro","input":"你好","voice":"zf_xiaoxiao"}')" || true
SIZE="$(stat -c %s /tmp/hanreader-voice-test 2>/dev/null || echo 0)"
rm -f /tmp/hanreader-voice-test
echo
if [ "$CODE" = "200" ] && [ "$SIZE" -gt 1000 ]; then
    echo "Готово: https://$DOMAIN работает (проверка: ответ $CODE, $SIZE байт звука)."
else
    echo "Адрес настроен, но проверка не прошла: ответ $CODE, $SIZE байт. Пришли этот вывод разработчику."
fi
echo "Ключ приложения (впиши в HanReader → Голос → Адрес сервера, и в секрет GitHub HANREADER_VOICE_KEY):"
echo "$KEY"
