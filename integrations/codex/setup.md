# Codex entegrasyonu

Ön koşul: Claude Code kurulumundaki gibi `~/kb-tools/` ve `kb init`.

## 1. MCP sunucusu ve özellikler — `~/.codex/config.toml` (F1+)

```toml
[mcp_servers.kb]
url = "http://127.0.0.1:7411/mcp"

[features]
# Normal modda yapılandırılmış soru aracı (soru-cevap yakalama için; AGENT-RULES ile birlikte)
default_mode_request_user_input = true
# Codex Memories açılmaz (varsayılan kapalı) — tek beyin agentkb
```

Token ve ortam değişkeni gerekmez (ADR-0020). `OriginGuard` eklenince bağlantının geçtiği F2'de doğrulanır.

Kontrol: `codex mcp list`, Codex içinde `/mcp`.

## 2. Hook'lar — `~/.codex/hooks.json`

Codex'te hook'lar varsayılan olarak açık.

```json
{
  "hooks": {
    "SessionStart": [
      { "hooks": [
        { "type": "command", "command": "$HOME/kb-tools/kb-hook.sh session-start codex", "timeout": 5 }
      ] }
    ],
    "UserPromptSubmit": [
      { "hooks": [
        { "type": "command", "command": "$HOME/kb-tools/kb-hook.sh user-prompt-submit codex", "timeout": 3 }
      ] }
    ],
    "PostToolUse": [
      { "matcher": "request_user_input",
        "hooks": [ { "type": "command", "command": "$HOME/kb-tools/kb-hook.sh post-tool-use codex", "timeout": 5 } ] }
    ]
  }
}
```

F2'de doğrula:
- Zaman aşımı alanının adı ve birimi.
- `request_user_input` için `tool_input`/`tool_response` biçimi.
- Codex hook bağlamını transcript'te görünür gösterir; KB brief ve recall kısa tutulur.

## 3. Global talimat

```bash
ln -s ~/kb-tools/AGENT-RULES.md ~/.codex/AGENTS.md   # veya mevcut dosyanın sonuna ekle
```

## 4. Yerleşik hafıza

Codex Memories'i açma. Daha önce açtıysan: `kb import-memories` ile içeriği al, sonra kapat.
