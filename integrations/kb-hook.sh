#!/usr/bin/env bash
# kb-hook.sh — Claude Code ve Codex hook'larını agentkb sunucusuna taşır. Mantık sunucudadır (/hooks/*).
# Kullanım (hook komutu olarak): kb-hook.sh <session-start|user-prompt-submit|post-tool-use> <claude|codex>
# stdin: hook JSON'u. stdout: sunucunun döndürdüğü hook JSON'u (boş olabilir).
# Kural: ASLA oturumu bloklama. Her hata durumunda boş çıktı + exit 0 (fail-open).

set -u
event="${1:-}"
client="${2:-claude}"
URL="${AGENTKB_URL:-http://127.0.0.1:7411}"

# Küratör gibi otomatik oturumlar KB hook'larını kapatır (oturum sayısını şişirmesin, bağlamı kirletmesin)
[ "${KB_HOOKS:-on}" = "off" ] && exit 0

case "$event" in
  session-start|user-prompt-submit|post-tool-use) ;;
  *) exit 0 ;;
esac

# Sunucu sadece bu makinede çalışır (değişmez kural 3/4, ADR-0020) ve hook istemleri ve cevapları
# taşır: AGENTKB_URL'nin host'u localhost, 127.0.0.0/8 veya [::1] değilse hiçbir şey gönderme.
# İsim çözümlemesi yok; user info (@) kabul edilmez, gerçek host'u gizleyebilir.
octet='(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])'
loopback="^https?://(localhost|127\\.$octet\\.$octet\\.$octet|\\[::1\\])(:[0-9]+)?(/.*)?\$"
[[ "${URL,,}" =~ $loopback ]] || exit 0

timeout_s=2

# --noproxy '*': ortamdaki bir http_proxy/all_proxy loopback isteğini de makine dışına taşırdı.
response="$(curl -fsS --noproxy '*' --max-time "$timeout_s" \
  -H "Content-Type: application/json" \
  --data-binary @- \
  "$URL/hooks/$event?client=$client" 2>/dev/null)" || exit 0

[ -n "$response" ] && printf '%s' "$response"
exit 0
