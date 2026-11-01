# ADR-0003: Tek süreçli sunucu, Streamable HTTP ve ayrı uç noktalar

- Durum: Kabul edildi (v2 ile güncellendi: 2026-10-29; ADR-0020 ile güncellendi: uçların token sütunu kalktı)
- Tarih: 2026-10-29

## Bağlam
Claude Code ve Codex aynı indeksi kullanmalı. Gömülü Neo4j tek süreç kilidi tutar. Küratör, çalışan ajanlardan farklı
yetkilere sahip olmalı. CLI/TUI, hook'lar ve web GUI de aynı sunucuya ulaşmalı.

## Karar
agentkb sürekli çalışan bir systemd user servisidir ve `127.0.0.1:7411` üzerinde şu uçları sunar:
- `/mcp` — çalışan ajanlar (Streamable HTTP)
- `/mcp/curator` — küratör (Streamable HTTP, ayrı araç seti)
- `/hooks/*` — hook taşıyıcısı
- `/api/v1/**` — CLI/TUI
- `/ui/**` — web GUI (tek kullanımlık link + oturum)

stdio kullanılmaz.

## Gerekçe
- stdio'da her istemci kendi sürecini başlatır; iki süreç aynı Neo4j dizinini açamaz.
- Tek indeks, tek dosya izleyici, tek yazıcı kuyruğu.
- Ayrı uç noktalar araç listelerini ayırır (çalışan ajan küratör araçlarını görmez) ve küratör sınırları sunucuda kodla zorlanır.

## Sonuçlar
- Spring AI 2.0.1'de `spring.ai.mcp.server.protocol=STREAMABLE` açıkça ayarlanmalı.
- Spring AI auto-config tek MCP sunucusu kurar; `/mcp/curator` MCP Java SDK ile elle kurulur (F1 spike'ta doğrulanır).
- DNS rebinding ve CSRF korumaları zorunlu (SPEC §13); kimlik doğrulama (token) v2'ye kaldı (ADR-0020).
