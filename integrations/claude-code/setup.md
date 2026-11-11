# Claude Code entegrasyonu

Ön koşul: `integrations/install.sh` çalıştırılmış (`~/.local/bin/kb`; `~/kb-tools/` altında `AGENT-RULES.md`; `init`; servis).
Bu dosyadaki adımları kurulum yapmaz; `integrations/install.sh --dry-run` onları yazdırır, onaydan sonra elle uygulanır.
Hook'lar F2'de kurulur. Token yok (ADR-0020).

## 1. MCP sunucusu (F1+)

```bash
claude mcp add --scope user --transport http kb http://127.0.0.1:7411/mcp
```

Kontrol: Claude Code içinde `/mcp`. `OriginGuard` eklenince bağlantının geçtiği F2'de doğrulanır (ADR-0020).

## 2. Hook'lar — `~/.claude/settings.json`

KB hook'ları (F2). Hook komutu `kb hook <olay> <istemci>`dir (ADR-0027 karar 4): stdin'i sunucuya taşır, cevabı
yazdırır, her hatada boş çıktı ve 0 ile çıkar (fail-open). Komut her zaman mutlak yolla yazılır, çıplak `kb` asla:
hook her dizinde çalışır ve PATH'teki bir `kb` oraya konmuş olabilir. Linux'ta yol install.sh'nin koyduğu
`$HOME/.local/bin/kb`'dir. Eski `~/kb-tools/kb-hook.sh <olay> claude` satırları geçiş süresince çalışır; o betik de yalnız
bu yolu çağırır.

```json
{
  "hooks": {
    "SessionStart": [
      { "hooks": [
        { "type": "command", "command": "$HOME/.local/bin/kb hook session-start claude", "timeout": 5 }
      ] }
    ],
    "UserPromptSubmit": [
      { "hooks": [
        { "type": "command", "command": "$HOME/.local/bin/kb hook user-prompt-submit claude", "timeout": 3 }
      ] }
    ],
    "PostToolUse": [
      { "matcher": "AskUserQuestion",
        "hooks": [ { "type": "command", "command": "$HOME/.local/bin/kb hook post-tool-use claude", "timeout": 5 } ] }
    ]
  }
}
```

Windows: Claude Code hook komutunu Git Bash ile, Git Bash yoksa PowerShell ile çalıştırır (code.claude.com/docs/en/hooks,
2026-11-11). Komut yine mutlak yoldur: `%LOCALAPPDATA%\agentkb\tools\kb.cmd` (native olunca `kb.exe`), örneğin Git Bash'te
`"$LOCALAPPDATA/agentkb/tools/kb.cmd" hook session-start claude`. Satırları install.ps1 yazacak; davranış Windows test
makinesinde doğrulanacak (W0).

## 3. Global talimat

```bash
ln -s ~/kb-tools/AGENT-RULES.md ~/.claude/CLAUDE.md   # veya mevcut dosyanın sonuna ekle
```

## 4. Yerleşik hafıza (tek beyin, F2)

1. `kb import-memories` (mevcut auto memory içeriği sinyal olarak alınır).
2. Sonra auto memory'yi kapat: `~/.claude/settings.json` içine `"autoMemoryEnabled": false` (ya da Claude Code'da `/memory`
   menüsündeki anahtar; aynı ayarı yazar). Kaynak: code.claude.com settings-reference, 2026-11-06.
