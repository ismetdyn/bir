# Codex entegrasyonu

Ön koşul: Claude Code kurulumundaki gibi `integrations/install.sh` çalıştırılmış. Adımları `integrations/install.sh --dry-run`
yazdırır; onaydan sonra elle uygulanır.

## 1. MCP sunucusu ve özellikler — `~/.codex/config.toml` (F1+)

```toml
[mcp_servers.kb]
url = "http://127.0.0.1:7411/mcp"
# Yazma araçları (kb_remember, kb_propose) codex exec'te onay istemeden çağrılsın
default_tools_approval_mode = "approve"

[features]
# Normal modda yapılandırılmış soru aracı (soru-cevap yakalama için; AGENT-RULES ile birlikte)
default_mode_request_user_input = true
# Codex Memories açılmaz (varsayılan kapalı) — tek beyin agentkb
```

Codex 0.161.0'da ölçüldü (ADR-0022 karar 28): okuma araçları `readOnlyHint: true` ve `destructiveHint: false` taşıdığı
için ayarsız da onaysız çalışır. `destructiveHint` varsayılanı (`true`) ile `codex exec` çağrıyı "requires approval"
diye reddediyordu. `default_tools_approval_mode` yazma araçları içindir; araç başına
`[mcp_servers.kb.tools.<ad>] approval_mode = "approve"` de çalışır (ADR-0018).

Token ve ortam değişkeni gerekmez (ADR-0020). `OriginGuard` eklenince bağlantının geçtiği F2'de doğrulanır.

Kontrol: `codex mcp list`, Codex içinde `/mcp`.

## 2. Hook'lar — `~/.codex/hooks.json`

Codex'te hook'lar varsayılan olarak açık. Hook komutu `kb hook <olay> <istemci>`dir (ADR-0027 karar 4; fail-open, bkz.
`integrations/claude-code/setup.md` §2). Codex komutu Linux'ta `$SHELL -lc`, Windows'ta `cmd.exe /C` ile çalıştırır
(openai/codex `hooks/src/engine/command_runner.rs`, 2026-11-11); `$HOME` cmd'de açılmadığı için komut yol değil, PATH'teki
`kb`'yi çağırır. Windows davranışı test makinesinde doğrulanacak (W0).

```json
{
  "hooks": {
    "SessionStart": [
      { "hooks": [
        { "type": "command", "command": "kb hook session-start codex", "timeout": 5 }
      ] }
    ],
    "UserPromptSubmit": [
      { "hooks": [
        { "type": "command", "command": "kb hook user-prompt-submit codex", "timeout": 3 }
      ] }
    ],
    "PostToolUse": [
      { "matcher": "request_user_input",
        "hooks": [ { "type": "command", "command": "kb hook post-tool-use codex", "timeout": 5 } ] }
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
