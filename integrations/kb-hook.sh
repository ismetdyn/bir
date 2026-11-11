#!/usr/bin/env bash
# kb-hook.sh — geçiş süresince ince sarmalayıcı (ADR-0027 karar 4): hook'u `kb hook`'a devreder. Mantık sunucudadır
# (/hooks/*), taşıma `kb hook`'tadır. Yeni kurulumda hook komutu doğrudan `kb hook <olay> <istemci>` olur
# (integrations/claude-code/setup.md, integrations/codex/setup.md).
# Kullanım (hook komutu olarak): kb-hook.sh <session-start|user-prompt-submit|post-tool-use> <claude|codex>
# stdin: hook JSON'u. stdout: sunucunun döndürdüğü hook JSON'u (boş olabilir).
# Kural: ASLA oturumu bloklama. kb yoksa ya da başlayamazsa boş çıktı + exit 0 (fail-open).

set -u
# install.sh başlatıcıyı buraya koyar; hook'un PATH'i onu içermeyebilir.
kb="$HOME/.local/bin/kb"
[ -x "$kb" ] || kb="$(command -v kb 2>/dev/null)" || exit 0

"$kb" hook "$@" 2>/dev/null
exit 0
