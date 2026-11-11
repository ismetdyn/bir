#!/usr/bin/env bash
# kb-hook.sh — geçiş süresince ince sarmalayıcı (ADR-0027 karar 4): hook'u `kb hook`'a devreder. Mantık sunucudadır
# (/hooks/*), taşıma `kb hook`'tadır. Yeni kurulumda hook komutu doğrudan `$HOME/.local/bin/kb hook <olay> <istemci>`
# olur (integrations/claude-code/setup.md, integrations/codex/setup.md).
# Kullanım (hook komutu olarak): kb-hook.sh <session-start|user-prompt-submit|post-tool-use> <claude|codex>
# stdin: hook JSON'u. stdout: sunucunun döndürdüğü hook JSON'u (boş olabilir).
# Kural: ASLA oturumu bloklama. kb yoksa ya da başlayamazsa boş çıktı + exit 0 (fail-open).

set -u
# Küratör gibi otomatik oturumlar KB hook'larını kapatır; o zaman kb (bir JVM) hiç başlamaz.
if [ "${KB_HOOKS:-on}" = "off" ]; then exit 0; fi

# Yalnız install.sh'nin koyduğu mutlak yol: hook her dizinde çalışır, PATH'teki bir kb oraya konmuş olabilir.
kb="$HOME/.local/bin/kb"
if [ ! -x "$kb" ]; then exit 0; fi

"$kb" hook "$@" 2>/dev/null
exit 0
