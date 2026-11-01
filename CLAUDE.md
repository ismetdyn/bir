# CLAUDE.md — agentkb geliştirme talimatları

agentkb: Claude Code ve Codex için yerel, graf tabanlı bir bilgi bankası. Bilgi gömülü Neo4j'de durur, sunucu onu tek
başına yazar ve ajanlara MCP üzerinden Markdown olarak sunar; git sadece anlık görüntü ve senkron içindir. Yanında bir
küratör ajan ve CLI/TUI/GUI istemcileri var.

## Önce oku
1. `docs/SPEC.md` — ne yapıyoruz, ne yapmıyoruz, veri modeli, kabul ölçütleri
2. `docs/ARCHITECTURE.md` — modüller, yazma yolu, tarayıcı, secret'lar, anlık görüntü, uçlar
3. `docs/ROADMAP.md` — **aktif faz**; dışına çıkma
4. `docs/adr/` — kararlar. 0001, 0004, 0005 ve 0007'nin yerini başkaları aldı; 0010 emekli. Aykırı bir şey gerekiyorsa önce yeni ADR öner
5. Konuya göre: `docs/MCP-TOOLS.md`, `docs/SECRETS.md`, `docs/CURATOR.md`, `docs/CLIENTS.md`

## Sadelik (ADR-0016)
Bu sistemi bir insan okuyup anlayabilmeli. Listede olmayan mekanizma, alan veya protokol ekleme; gerekiyorsa gerekçesini
yazıp sor. Var olan aracı yeniden yazma (sürümleme ve senkron için git, sorgu için Cypher). Bir veri tek yerde durur.
Grafa damıtılmış bilgi girer; ham dosyalar doküman deposunda, uzun metin grafta değil (ADR-0017).

## Teknoloji
- Java 21, Maven çoklu modül (`kb-api`, `kb-core`, `kb-server`, `kb-client`, `kb-gui`), wrapper `./mvnw`
- Spring Boot 4.x, Spring AI 2.0.x (`spring-ai-starter-mcp-server-webmvc`, `@McpTool`), MCP Java SDK (`/mcp/curator`)
- Neo4j Community embedded (`GraphStore` arkasında), Jackson (JSON/YAML), sistem `git` (+ `git-lfs`)
- `kb-client`: picocli + TamboUI, GraalVM native-image
- GUI: Dear ImGui (`imgui-java`), ayrı `kb-gui` modülü (ADR-0021)
- Paket kökü: `io.github.ismetdyn.agentkb`

Yeni bağımlılık eklemeden önce sor. Sürümleri tahmin etme; Maven Central veya Context7'den doğrula.

## Komutlar
```bash
./mvnw verify                                   # tüm modüller: derleme + test + ArchUnit + Spotless kontrol
./mvnw -pl kb-core test                         # saf çekirdek testleri (hızlı)
./mvnw -Peval verify                            # değerlendirme seti (eval/questions.yaml, seed/ ile)
./mvnw -pl kb-client -Pnative package           # native kb binary
java -jar kb-server/target/agentkb-server.jar --agentkb.repo=./seed
npx @modelcontextprotocol/inspector http://127.0.0.1:7411/mcp
```

## Değişmez kurallar (ihlal eden kod kabul edilmez)
1. **Tek yazıcı.** Grafa sadece `WriteService` yazar (anlık görüntüden içeri alma ve restore dahil). MCP katmanı
   grafa doğrudan yazmaz.
2. **Secret değeri** sadece bu bilgisayardaki grafta (`secret_value`) durur; anlık görüntü, git, log, audit, MCP/REST
   okuma çıktısı ve hata mesajlarına girmez. Değeri sadece `GET /api/v1/secrets/{id}` döndürür. Ajanlar
   placeholder (`kb:secret/<id>`) ve `kb run` kullanır; ajanın değeri görmemesi zorlanan bir sınır değil kuraldır
   (ADR-0020). `kb_remember` secret görünümlü değeri reddeder, hook yakalaması
   onu sinyale yazmadan maskeler. Testler sahte secret dizelerini bütün çıktılarda ve git geçmişinde arar.
3. **Sunucu LLM çağırmaz** ve dışarıya HTTP isteği yapmaz; sadece `127.0.0.1`'e bağlanır (`kb sync`'in git'i hariç).
4. **Yerel erişim.** `OriginGuard` atlanamaz ve loopback dışı bağlanma reddedilir (ADR-0020). Token yok.
   Küratör sınırları (parti, yazma sayısı, yasak işlemler) prompt'a değil koda dayanır.
5. **Silme yok.** Fact ve ilişki değişince eskisi kapanır (`valid_to`), yenisi `SUPERSEDES` ile bağlanır; `valid_from`
   (ilk görülme) hiç değişmez. Sinyaller de silinmez; kapanınca sonucu yazılır, ürettiğine `PRODUCED` ile bağlanır.
   Normatif değişiklikler (karar, kural, şema, arşiv) sadece `Proposal` + kullanıcı onayı ile.
   Tek istisna: `kb revert --batch` geri alınan partinin açtığı kayıtları siler, kapattıklarını yeniden açar.
6. **Yazma politikası** `kb-core`'da saftır ve tablo güdümlü testlerle korunur: düşük güvenli kaynak yükseği ezemez.
7. **Cypher sadece okuma** (`CypherGuard`); `LOAD CSV` ve `secret_value` yasak; ilişki türleri sadece
   `schema/relations.yaml` izin listesinden.
8. **Anlık görüntü deterministiktir:** `id`'ye göre sıralı satırlar, sabit alan sırası; `Signal` düğümleri (ve onlara
   bağlı kenarlar) ve `secret_value` yazılmaz. Restore aynı grafı vermelidir ve anlık görüntüde olmayan yerel verileri
   (secret değerleri, sinyaller) korur (test). Satır sayısı graftakiyle tutmazsa commit yok; force-push hiç yok.
9. **Tarayıcı** sadece `allowed-roots` altında okur; secret değerini sadece Secret düğümüne yazar.
10. **Log/audit'e** içerik, Cypher metni veya secret yazılmaz (hash, sayı, süre).
11. **Kayıpsız içeri alma.** Senkron ve restore hiçbir kaydı reddetmez veya kırpmaz; sınırlar ve şema doğrulaması
    sadece MCP/REST'ten gelen yeni yazmalara uygulanır. Parti geri alınınca kapattığı sinyaller yeniden açılır.
12. **Gizleme yok.** Arama, hatırlatma ve `kb_related` kayıtları zamanına (`valid_to`) ya da durumuna (`status`) göre
    süzmez; sıralar ve işaretler: güncel önce, sonra geçmiş (eski değer, biten ilişki, arşiv), en son ham sinyal. Bütçe
    sıralamadan sonra uygulanır. Brief şu anın özetidir ama arşivli komşuyu düşürmez.

## Kod kuralları
- Kod, yorumlar ve commit mesajları **İngilizce** (yayınlanabilirlik); dokümanlar Türkçe kalabilir.
- Alan modelleri `record`; `Optional` dönüş; JSpecify `@Nullable`. `kb-core` Spring/Neo4j'ye bağımlı olamaz.
- Paket yönü ARCHITECTURE §3'teki gibi; ArchUnit testi var.
- MCP araçları istisna fırlatmaz; `{error, hint}` döner.
- `String.toLowerCase()` locale'e bağlıdır (Türkçe'de `I → ı`, `Locale.ROOT`'ta `İ → i̇`). Kimlik ve alias
  normalizasyonu tek bir yerde (`kb-core`'da `Ids.normalize()`); Türkçe testleri var. Başka yerde `toLowerCase()` çağırma.
- Conventional commits: `feat(write): ...`, `fix(scan): ...`, `test: ...`, `docs(adr): ...`

## Çalışma şekli
- Faza başlarken ROADMAP maddelerini görev listesine dök; bitince işaretle; bitti tanımını kontrol et.
- **Önce test:** `WritePolicy`, Markdown görünümü, secret desenleri, `CypherGuard`, extractor'lar, anlık görüntü →
  restore, senkron birleştirme, `kb run` maskeleme. Negatif durumlar dahil.
- Golden veri: `seed/` (bkz. `seed/README.md`) ve `fixtures/scanner/` (+ `expected.yaml`).
- `eval/questions.yaml` ve `fixtures/scanner/expected.yaml` hakem dosyalarıdır; değiştirmen gerekirse öner. Bitirmeden önce `./mvnw verify` çalıştır.
- Bir ADR'ye aykırı çözüm gerekiyorsa dur ve gerekçeyi yaz. Belirsiz gereksinimde SPEC'i değiştirmeden önce sor.
- "Doğrula" diye işaretli maddeleri (Codex hook alanları, headless bayraklar, iki MCP uç noktası) varsayma; dene ve yaz.
