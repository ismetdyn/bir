# ADR-0003: Tek süreçli sunucu, Streamable HTTP ve ayrı uç noktalar

- Durum: Kabul edildi (v2 ile güncellendi: 2026-10-29)
- Tarih: 2026-10-29

## Bağlam
Claude Code ve Codex aynı indeksi kullanmalı. Gömülü Neo4j tek süreç kilidi tutar. Küratör, çalışan ajanlardan farklı
yetkilere sahip olmalı. CLI/TUI, hook'lar ve web GUI de aynı sunucuya ulaşmalı.

## Karar
agentkb sürekli çalışan bir systemd user servisidir ve `127.0.0.1:7411` üzerinde şu uçları sunar:
- `/mcp` — çalışan ajanlar (Streamable HTTP, `agent` token)
- `/mcp/curator` — küratör (Streamable HTTP, `curator` token, ayrı araç seti)
- `/hooks/*` — hook taşıyıcısı (`hook` token)
- `/api/v1/**` — CLI/TUI (`user` token)
- `/ui/**` — web GUI (tek kullanımlık link + oturum)

stdio kullanılmaz.

## Gerekçe
- stdio'da her istemci kendi sürecini başlatır; iki süreç aynı Neo4j dizinini açamaz.
- Tek indeks, tek dosya izleyici, tek yazıcı kuyruğu.
- Ayrı uç noktalar araç listelerini ayırır (çalışan ajan küratör araçlarını görmez) ve kapsamlar sunucuda zorlanır.

## Sonuçlar
- Spring AI 2.0.1'de `spring.ai.mcp.server.protocol=STREAMABLE` açıkça ayarlanmalı.
- Spring AI auto-config tek MCP sunucusu kurar; `/mcp/curator` MCP Java SDK ile elle kurulur (F1 spike'ta doğrulanır).
- Kimlik doğrulama, DNS rebinding ve CSRF korumaları zorunlu (SPEC §12).
