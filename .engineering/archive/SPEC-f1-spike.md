# SPEC — F1 spike

Kapsam: `docs/ROADMAP.md` F1. Ürün şartnamesi `docs/SPEC.md`; bu dosya yalnız spike'ın kabulünü tanımlar. Spike bitince
`.engineering/archive/`'e taşınır.

## Soru
Spring Boot 4 + Spring AI 2.0.x MCP (STREAMABLE) + gömülü Neo4j tek süreçte çalışıyor mu; iki MCP ucu birlikte ayakta
kalıyor mu; Türkçe aramada hangi analizör "İzmir"i buluyor?

## Doğrulanmış sürümler (Maven Central, 2026-11-01)
| Bileşen | Sürüm | Kanıt |
|---|---|---|
| Spring Boot | 4.1.2 | son GA; Spring AI 2.0.2 autoconfigure pom'u 4.1.2'ye bağlı |
| Spring AI | 2.0.2 | `spring-ai-bom` son 2.0.x GA |
| MCP Java SDK (`mcp-core`) | 2.1.0 | `mcp-spring-webmvc:2.0.2` pom'undan; BOM'la gelir, ayrıca sabitlenmez |
| Neo4j Community | 2026.10.1 | son GA; class major 65 (Java 21) |
| Java | 21 | makinede OpenJDK 21.0.12.1 |
| maven-wrapper-plugin | 3.3.4 | son GA |
| Maven | T1'de doğrulanır | son 3.9.x GA |

## Kabul ölçütleri
- **K1** `./mvnw verify` exit 0; `kb-server/target/agentkb-server.jar` üretilir. Yalnız `kb-server` modülü; parent pom modül
  listesiyle. `kb-api`, `kb-core`, `kb-client`, Spotless ve ArchUnit F2'de.
- **K2** Sunucu yalnız `127.0.0.1`'e bağlanır; port `7411` varsayılanı config'te. Dış adrese bağlanmaz (test).
- **K3** Gömülü Neo4j açılır, Cypher çalışır, kapanışta düzgün kapanır; aynı veri dizini yeniden açılınca veri durur (test).
  Veri dizini config'ten gelir, testte geçici dizin.
- **K4** `/mcp` Spring AI auto-config + `@McpTool` ile `ping` sunar. `/mcp/curator` MCP Java SDK ile elle kurulur, kendi
  `ping`'ini sunar.
- **K5** Araç ayrımı: her uç gerçek HTTP üzerinden `initialize`, `tools/list`, `tools/call` ile test edilir; `/mcp` küratör
  aracını, `/mcp/curator` ajan aracını listelemez (negatif test).
- **K6** Tam metin analizörleri `db.index.fulltext.listAvailableAnalyzers` ile listelenir. Her aday için `İzmir`, `IZMIR`,
  `izmir`, `Izmir`, `ısı`, `ISI` indekslenip sorgulanır; sonuç tablosu ADR'ye girer. Hiçbir analizör geçmezse bu da bulgudur.
- **K7** Claude Code ve Codex gerçek olarak bağlanıp `/mcp` ping'ini çağırır. Kullanıcı ayarlarına yazılmaz; geçici config
  kullanılır.
- **K8** Bulgular `docs/adr/0018-*.md`: sürümler, classpath çakışmaları ve çözümü, iki uç yöntemi, analizör tablosu, istemci
  sonucu, ADR-0002 ve ADR-0003 için hüküm, F2'ye açık kalanlar.

## Kapsam dışı
- Token, `OriginGuard`, CSRF: F2 (`docs/ROADMAP.md:64`). Spike'ta `ping` dışında araç yok ve veri okunmaz; yalnız `127.0.0.1`.
- `WriteService`, şema, seed yükleme, `GraphStore` arayüzü (tek uygulamalı arayüz F2'de gerekirse).
- LadybugDB ölçümü (opsiyonel, karar: yapılmaz).

## Durma koşulu
Neo4j ile Boot classpath'i çözülemezse veya iki uç aynı süreçte çalışmazsa çözümü zorlama: bulguyu yaz, ADR-0002'deki B
planını (ayrı Neo4j süreci) kullanıcıya sor.
