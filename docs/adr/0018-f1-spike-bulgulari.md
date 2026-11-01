# ADR-0018: F1 spike bulguları

- Durum: Kabul edildi
- Tarih: 2026-11-01
- İlgili: ADR-0002, ADR-0003

## Bağlam
ROADMAP F1 şu soruları soruyordu: Spring Boot 4, Spring AI 2.0.x MCP ve gömülü Neo4j tek süreçte çalışıyor mu; iki MCP ucu
birlikte ayakta kalıyor mu; Claude Code ve Codex bağlanıyor mu; Türkçe aramada hangi analizör "İzmir"i buluyor?
Opsiyonel LadybugDB ölçümü yapılmadı.

## Karar
1. **ADR-0002 doğrulandı.** Gömülü Neo4j Community kalır; B planı (ayrı süreç) gerekmedi.
2. **ADR-0003 doğrulandı.** Tek süreçte iki Streamable HTTP ucu çalışıyor.
3. **Sürümler:**

   | Bileşen | Sürüm |
   |---|---|
   | Java | 21 |
   | Maven | 3.9.16 (wrapper, `only-script`) |
   | Spring Boot | 4.1.2 |
   | Spring AI | 2.0.2 (`mcp-core` 2.1.0 onunla gelir) |
   | Neo4j Community | 2026.10.1 |

4. **`/mcp`:** `spring-ai-starter-mcp-server-webmvc`, `protocol: STREAMABLE`, `@McpTool`.
5. **`/mcp/curator`:** MCP SDK `HttpServletStreamableServerTransportProvider`, `ServletRegistrationBean` ile. Transport ve
   sunucu bean yapılmaz, çünkü `McpSyncServer` beani Spring AI auto-config'ini kapatır. `WebMvcStreamableServerTransportProvider`
   denenmedi; servlet yolu ilk denemede çalıştı.
6. **Analizör: `standard-folding`** (geçici). Ölçülen tabloda Türkçe harf farkından kaçan tek analizör. `docs/ARCHITECTURE.md:89-90`
   analizör seçimini değerlendirme setine bağlıyor, `EvalRunner` ise F2'de geliyor. Bu yüzden karar F2'de eval ile doğrulanır.
   Bedeli: `ı` ile `i` birleşir, `ısı` sorgusu `İSİ`'yi de getirir. "Gizleme yok, işaretle ve sırala" kuralıyla uyumlu bir
   fazla eşleşmedir.

## Analizör ölçümü
45 analizör ölçüldü. Her analizöre `İzmir, IZMIR, izmir, Izmir, ısı, ISI, ISITMA, İSİ` indekslendi. Hücreler dönen değerlerdir.

| Analizör | izmir | İzmir | IZMIR | ısı | ISI |
|---|---|---|---|---|---|
| `standard-folding` | dört "izmir" biçimi | aynı | aynı | ısı, ISI, İSİ | ısı, ISI, İSİ |
| `standard-no-stop-words` (+ aynı sonucu veren 39 analizör) | dört "izmir" biçimi | aynı | aynı | ısı | ISI, İSİ |
| `turkish` | İzmir, izmir | İzmir, izmir | IZMIR | ısı, ISI | ısı, ISI |
| `keyword`, `whitespace`, `unicode_whitespace` | izmir | İzmir | IZMIR | ısı | ISI |

- `turkish` Türkçe'ye özgü olmasına rağmen `izmir` ile `IZMIR`'i bulmuyor: `I` → `ı` dönüşümü bunu yapıyor. Seçilmedi.
- `ISITMA` hiçbir sorguda dönmedi; önek araması yapılmadı. `ş`, `ğ`, `ü` katlaması ölçülmedi.
- Tablonun tamamı `FulltextAnalyzerTest` çıktısıdır: `kb-server/target/f1-analyzer-table.md`.

## Classpath
| Durum | Çözüm |
|---|---|
| Logback ve `neo4j-slf4j-provider` iki SLF4J sağlayıcısı: `LoggerFactory is not a Logback LoggerContext` | `neo4j-slf4j-provider` exclusion |
| Boot BOM, Neo4j'nin sürümlerini düşürüyor: log4j 2.26.1→2.25.5, Jackson 2.22.3→2.21.7, Jackson 3.2.3→3.1.7 | Override yok; testler yeşil. F2'de Neo4j yükseltmesinde tekrar bakılır |
| Jersey 2.48→4.0.3 karışık kaldı | Neo4j HTTP kapalı, yüklenmiyor. HTTP açılırsa bozulur; açılmaz |

Neo4j'de `server.bolt`, `server.http`, `server.https`, routing, UDC ve fleet manager kapalı. Çalışan sunucuda tek dinleyen
soket `127.0.0.1:7411`, UDP yok (`ss -ltnp`, `ss -lunp`). Jar 185 MB.

## İstemciler
Geçici config ile gerçek bağlantı; kullanıcı ayarlarına yazılmadı.

| İstemci | `ping` | `cur_ping` | Not |
|---|---|---|---|
| Claude Code 2.1.316 | `pong` | `cur_pong` | `--mcp-config`, `--strict-mcp-config` |
| Codex CLI 0.161.0 | `pong` | `cur_pong` | Araç başına `approval_mode="approve"` ile |

**Codex bulgusu:** Varsayılanda çağrı `MCP tool call requires approval, but approval policy is never` ile reddedildi. `@McpTool`
varsayılanı `destructiveHint: true` görünür ve Codex buna göre onay ister. F2'de okuma araçları `readOnlyHint: true`
işaretlenmeli; bunun Codex'te onayı kaldırıp kaldırmadığı doğrulanmadı (`docs/MCP-TOOLS.md`, `integrations/codex/setup.md`).

## Sonuçlar: F2'ye girenler
**Güvenlik** (bugün yalnız `ping` olduğu için Sev-3; veri aracı gelince kritik):
1. Token ve `OriginGuard`, ilk veri aracından ve hook ucundan **önce** gelmeli. ROADMAP F2'de bugün sonda duruyor. Ölçüldü: sahte
   `Host` ve `Origin` ile iki uçta da `initialize` HTTP 200 dönüyor.
2. Koruma servlet `Filter` olmalı: `/mcp/curator` DispatcherServlet dışında, interceptor onu korumaz. Filtre iki yolu da
   kapsamalı; negatif testlerle.
3. Hata gövdesi iç detay sızdırıyor: oturumsuz istekte HTTP 400 ve `stackTrace` (`/mcp` 8.742 bayt, `/mcp/curator` 7.434
   bayt). Token filtresi anonim yolu kapatır; tokenlı yol için test eklenmeli.
4. `server.address` env ile `0.0.0.0` yapılabiliyor (test kanıtlı). Değişmez 3 için loopback dışı adresle açılış reddedilmeli.
5. Veri dizini 0700 değil (`SPEC`: Secret gelmeden zorunlu). Gövde ve oturum limiti yok; denetlenmedi.

**İşletim:**
- Açık SSE bağlantısı varken SIGTERM 33 sn sürüyor (graceful timeout 30 sn), 2 WARN yazıyor; Neo4j temiz kapanıyor. Bağlantı
  yokken 1 sn. `spring.lifecycle.timeout-per-shutdown-phase` ile kısaltılabilir.
- Neo4j page cache kendi hesabıyla 10,8 GB ayırıyor; ADR-0002'nin sınırı F2'de uygulanmalı.
- Neo4j `java.nio` add-opens olmadan yedek modda çalışıyor; manifest'e `Add-Opens` gerekebilir (etkisi ölçülmedi).
- Bağımlılık CVE taraması yapılmadı. CI'daki yerini F2 belirler.

## Değerlendirilen alternatifler
- **`turkish` analizörü:** `izmir` ile `IZMIR` eşleşmiyor.
- **`standard-no-stop-words`:** `ısı` ile `ISI` eşleşmiyor.
- **`McpSyncServer` beani:** Auto-config'i kapatır.
- **Ayrı Neo4j süreci (ADR-0002 B planı):** Gerek kalmadı.
