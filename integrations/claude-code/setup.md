# Claude Code entegrasyonu

Ön koşul: `~/kb-tools/` altında `kb-hook.sh`, `AGENT-RULES.md`; `kb init` çalıştırılmış
(`~/.config/agentkb/tokens/` dolu). Hook'lar F2'de kurulur.

## 1. MCP sunucusu (F1+)

```bash
claude mcp add --scope user --transport http kb http://127.0.0.1:7411/mcp \
  --header "Authorization: Bearer $(cat ~/.config/agentkb/tokens/agent)"
```

Token `~/.claude.json`'a yazılır; dosya 0600 olmalı. Kontrol: Claude Code içinde `/mcp`.

## 2. Hook'lar — `~/.claude/settings.json`

KB hook'ları (F2):

```json
{
  "hooks": {
    "SessionStart": [
      { "hooks": [
        { "type": "command", "command": "$HOME/kb-tools/kb-hook.sh session-start claude", "timeout": 5 }
      ] }
    ],
    "UserPromptSubmit": [
      { "hooks": [
        { "type": "command", "command": "$HOME/kb-tools/kb-hook.sh user-prompt-submit claude", "timeout": 3 }
      ] }
    ],
    "PostToolUse": [
      { "matcher": "AskUserQuestion",
        "hooks": [ { "type": "command", "command": "$HOME/kb-tools/kb-hook.sh post-tool-use claude", "timeout": 5 } ] }
    ]
  }
}
```

## 3. Global talimat

```bash
ln -s ~/kb-tools/AGENT-RULES.md ~/.claude/CLAUDE.md   # veya mevcut dosyanın sonuna ekle
```

## 4. Yerleşik hafıza (tek beyin, F2)

1. `kb import-memories` (mevcut auto memory içeriği sinyal olarak alınır).
2. Claude Code'da `/memory` menüsünden auto memory'yi kapat. (Ayar dosyasındaki anahtar adını kurulumda doğrula.)
