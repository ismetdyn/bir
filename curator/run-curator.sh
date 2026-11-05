#!/usr/bin/env bash
# Küratörü headless Claude Code ile çalıştırır. Genelde `kb curator run --if-needed` tarafından çağrılır.
# F4 başında doğrula: --strict-mcp-config, --allowedTools/--disallowedTools söz dizimi (sunucunun tüm araçları için
# "mcp__kbc" mi "mcp__kbc__*" mı), --max-turns.
set -euo pipefail

TOOLS="${KB_TOOLS:-$HOME/kb-tools}"
STATE="${XDG_STATE_HOME:-$HOME/.local/state}/agentkb/curator"
mkdir -p "$STATE"

# Küratör oturumu kullanıcı hook'larını tetiklemesin (oturum sayacı, recall)
export KB_HOOKS=off

ts="$(date +%Y%m%dT%H%M%S)"
claude -p "$(cat "$TOOLS/curator/PROMPT.md")" \
  --mcp-config "$TOOLS/curator/mcp.json" --strict-mcp-config \
  --allowedTools "mcp__kbc" \
  --disallowedTools "Bash,Edit,Write,Read,Glob,Grep,WebFetch,WebSearch,Task,NotebookEdit" \
  --max-turns 60 \
  --output-format json \
  > "$STATE/run-$ts.json" 2> "$STATE/run-$ts.err"

echo "Küratör çalışması tamamlandı: $STATE/run-$ts.json"
