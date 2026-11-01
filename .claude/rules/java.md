---
paths:
  - "pom.xml"
  - "kb-*/**"
---

# agentkb Java kuralları

Genel kurallar `CLAUDE.md`'de; burada Java kodu yazarken kolay atlanan, projeye özgü olanlar var.

- Kod, yorum ve log anahtarları İngilizce (`CLAUDE.md`, global Türkçe yorum kuralını ezer).
- `@McpTool` varsayılanı `destructiveHint: true` görünür ve Codex onay ister. Okuma araçlarını `readOnlyHint: true` işaretle;
  etkisini Codex'te doğrula (ADR-0018).
- `McpSyncServer` beani tanımlama: Spring AI auto-config'ini kapatır. İkinci uç `/mcp/curator` elle kurulur (ADR-0018).
- Web'e dokunan koruma servlet `Filter` olur; `/mcp/curator` DispatcherServlet dışında olduğu için interceptor onu korumaz
  (ADR-0020).
- Neo4j ve Spring Boot sürüm çakışmaları için ADR-0018'e bak; yeni exclusion veya override'a neden yorumu yaz.
- Neo4j Bolt, HTTP ve UDC kapalı kalır; yeni dinleyen soket açan değişiklik `127.0.0.1:7411` dışında port açamaz.
