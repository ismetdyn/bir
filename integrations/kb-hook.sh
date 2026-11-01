#!/usr/bin/env bash
# kb-hook.sh — Claude Code ve Codex hook'larını agentkb sunucusuna taşır. Mantık sunucudadır (/hooks/*).
# Kullanım (hook komutu olarak): kb-hook.sh <session-start|user-prompt-submit|post-tool-use> <claude|codex>
# stdin: hook JSON'u. stdout: sunucunun döndürdüğü hook JSON'u (boş olabilir).
# Kural: ASLA oturumu bloklama. Her hata durumunda boş çıktı + exit 0 (fail-open).

set -u
event="${1:-}"
client="${2:-claude}"
TOKEN_FILE="${AGENTKB_HOOK_TOKEN_FILE:-$HOME/.config/agentkb/tokens/hook}"
URL="${AGENTKB_URL:-http://127.0.0.1:7411}"

# Küratör gibi otomatik oturumlar KB hook'larını kapatır (oturum sayısını şişirmesin, bağlamı kirletmesin)
[ "${KB_HOOKS:-on}" = "off" ] && exit 0

case "$event" in
  session-start|user-prompt-submit|post-tool-use|stop) ;;
  *) exit 0 ;;
esac
[ -r "$TOKEN_FILE" ] || exit 0

# UserPromptSubmit her mesajda çalışır: daha kısa zaman aşımı
timeout_s=2
[ "$event" = "user-prompt-submit" ] && timeout_s=1.5

response="$(curl -fsS --max-time "$timeout_s" \
  -H "Authorization: Bearer $(cat "$TOKEN_FILE")" \
  -H "Content-Type: application/json" \
  --data-binary @- \
  "$URL/hooks/$event?client=$client" 2>/dev/null)" || exit 0

[ -n "$response" ] && printf '%s' "$response"
exit 0
