# Plan — F3 kb istemcisi: CLI + TUI

Kaynaklar: `docs/ROADMAP.md` F3 (:78-90), `docs/CLIENTS.md`, `docs/SPEC.md` §6.7, ADR-0012, 0017, 0021, 0022 (karar 17, 21). Dal: `f2-core`'dan açılacak `f3-client`. Plan taslağı 2026-11-07; kullanıcı onayı bekliyor. Sıra yüksek riskten düşüğe gidiyor. Native ve TamboUI spike'ı ilk sırada, çünkü başarısız olursa TUI seçimini ve istemci JSON kararını değiştirir. Kullanıcının bekleyen Claude Code/Codex ayar geçişi bu planın kapsamında değil, todo'da duruyor.

## Dilimler
Y = önce `java-test-engineer` testi yazar, sonra `java-developer` kodu yazar. Testin RED olduğu ve sebebinin eksik özellik olduğu görülmeden kod yazılmaz.

| # | Amaç | Kabul (negatif dahil) | Kapsam | Bağ. | Yazar |
|---|---|---|---|---|---|
| H0 | ADR-0025 ve belge hizası | ADR-0025 yazıldı. ROADMAP, CLIENTS, SPEC §6.7, ARCHITECTURE §2-3, MCP-TOOLS ve `CLAUDE.md` Teknoloji satırı uyumlu | `docs/**`, `CLAUDE.md` | U1-U5 onayı | `documentation-specialist` |
| H1 | Native + TamboUI spike | Native `kb --help` < 50 MB, açılış süresi ölçülüp yazılır. `kb tui` hello ekranı açılır, `q` ile çıkar, `--no-alt-screen` çalışır, JVM'de Pilot testi geçer. Native'de bir record Jackson ile gidip dönüyor (test). Native `kb run --file X=… -- sleep 60` SIGINT ve SIGTERM sonrası geçici dosyayı siler. Önce `--install-exit-handlers` olmadan RED görülür. `kb secret set` pipe ve tty ile çalışır. Mevcut `KbJarIT` değişmeden yeşil kalır | `kb-client/pom.xml` (`native` profili, picocli-codegen, `spring-boot.aot.skip`), `client/tui/**` (hello), `KbNativeIT` | U1, U2 | Y. Native profil: `devops-specialist` |
| CP1 | Lider + kullanıcı: TamboUI kalır mı, yoksa B planı ADR'si mi? İstemcide Jackson mı, mevcut `Json.java` mı? | | | | |
| H2 | `kb-api` ve tipli istemci | `kb-api` yalnız record içerir, bağımlılığı JDK + JSpecify. `server.api` domain sonucunu DTO'ya çevirir. F2 istemci testleri değişmeden geçer (çıktı metni, çıkış kodları, kontrol karakteri silme: ADR-0022 #40). Her DTO için sözleşme testi: sunucunun JSON'u istemcide eşit record verir. Bilinmeyen alan yok sayılır. Eksik zorunlu alan NPE değil exit 1 verir. ArchUnit: `kb-api` dışa bağımlı olamaz, `kb-api`'yi sunucuda yalnız `server.api` kullanır; ikisi de fixture ile düşer. `JsonApi`'ye PUT, PATCH, DELETE ve ham byte gövde eklenir | `pom.xml` (modules), `kb-api/**`, `server/api/*Controller` (mevcutlar), `client/{JsonApi,Json,MaintenanceCommands,ImportMemoriesCommand}*`, `ArchitectureRulesTest` + fixture'lar | CP1 | Y |
| H3 | Yükleme staging'i (sunucu) | `PUT /api/v1/uploads/{sha}` + `Upload-Length` yeni yüklemede 201, mevcutta 200 döner, ikisinde de `Upload-Offset` verir. Blob zaten varsa offset = N. `PATCH` (`application/offset+octet-stream`, ≤ 1 MiB) 204 ve yeni offset döner. Negatifler: geçersiz hex/N → 400; aynı sha farklı N → 409; offset uymazsa 409 + doğru offset; aynı JVM'de eşzamanlı PATCH → 409 (`OverlappingFileLockException` dahil); k+len > N → 413; 1 MiB üstü → 413 (filtre); yanlış Content-Type → 415; N > usableSpace → 507; 20 MB üstü ve git-lfs yok → PUT'ta 422. Path traversal denenir: yalnız `[0-9a-f]{64}` kabul edilir. `-Xmx64m` altında 200 parçalık yükleme OOM vermez (gövde heap'e alınmıyor kanıtı). 7 günden eski staging dosyası açılışta ve PUT'ta silinir. `DELETE` 204 döner | `server/docs/UploadStore*`, `server/api/UploadController*`, `application.yml` (`agentkb.documents.*`) | H2 ile paralel (dosya kümesi ayrı); `RestErrorHandler` H2'nin, eşleme isteği `send` ile gider | Y |
| H4 | Tamamlama ve blob deposu | `POST /api/v1/documents {sha256,name,about?,origin?,replaces?}` 201 `{id,path}` döner; aynı içerik 200 + mevcut doc. Offset ≠ N → 409. Hash uymazsa 422 ve staging silinir. Secret bulunursa 422: satır numarası verilir, değer verilmez; staging silinir. `name`/`origin` secret görünümlüyse (`user:pw@` URL'si) veya sınırı aşıyorsa 400. Blob önce yazılır (`.tmp` + `ATOMIC_MOVE`), sonra node `WriteService.addDocument` ile girer (tek yazıcı). Hash, tarama ve kopya yazıcı kilidi dışında yapılır; test: eşzamanlı `kb_remember` bekletilmez. 20 MB üstü dosyada `git lfs track` çalışır; `git lfs install --local` yalnız bilgi reposunda yapılır, global `~/.gitconfig`'e yazılmaz. `--replaces` → `SUPERSEDES`. `kb sync` `blobs/` ve `.gitattributes`'i commit'ler (bugün yalnız nodes/edges commit'leniyor). LFS push başarısızsa sync hata verir (ADR-0017 m.7). İki geçici repo arasında blob taşınır. Revert node'u siler, blob kalır | `server/docs/DocumentService*`, `api/DocumentController*`, `write/WriteService` (+`DocumentWrite`), `snapshot/SyncGit*`, `kb-core/**/secret/SecretScan*` | H3 | Y |
| CP2 | `security-reviewer` + `java-reviewer`: `SecretLeakIT` genişler. Sahte secret'lı doküman reddedilmeli; secret log, hata gövdesi, anlık görüntü ve git geçmişinde olmamalı. Disk, kilit ve traversal sınırları da incelenir | | | | |
| H5 | Yeni yazma işlemleri (`WriteService`) | `closeFact`, `closeRelation`, `editEntity` (aliases, summary), `archive`/`unarchive`, `resolveConflict(accept-new\|keep-old)`, `accept`/`rejectProposal` (Source.USER). `valid_from` değişmez, silme yok. Her işlem için tablo testi: işlem → `kb revert --batch` → graf öncekiyle aynı (karar D10'a bağlı). accept-new ADR-0022 #23 yolunu kullanır. Düşük güvenli kaynak USER'ı ezemez. Kapalı fact'i kapatma ve olmayan id → 400 `{error,hint}` | `write/**`, `kb-core/**/policy/**` | H4 (`WriteService` tek yazar) | Y |
| CP3 | `java-reviewer` + `security-reviewer`: değişmez 1, 5 ve 6 | | | | |
| H6 | Kalan REST ve CLI komutları | CLIENTS §1'deki uçlar (F4 ve F5 hariç, bkz. kapsam dışı) ve karşılık gelen `kb` komutları. `GET /entities/{id}`: `Accept: text/markdown` → `MarkdownView`, JSON → TUI görünümü. `/secrets?prefix=` değer döndürmez (`SecretLeakIT`). `/cypher` `CypherGuard`'dan geçer. `/scan` `allowed-roots` dışını reddeder. Liste uçlarında üst sınır var (`search` ≤ 20, ADR-0017; `activity` 50/500). Her komutun exit kodları 0/1/3 | `server/api/**` (yeni controller'lar), `graph/KnowledgeQueries*` (activity, document), `client/*Command*` | H2, H5 | Y |
| H7 | Doküman okuma, istemci ve bütünlük | `kb_read doc:ded48257b6f3` seed'de mutlak yolu, sha, ABOUT ve FROM fact'lerini verir. `kb_remember(document=)` FROM kenarı açar (MCP-TOOLS.md:78 zaten vaat ediyor); bilinmeyen doc → `{error,hint}`. `GET /documents`, `GET /documents/{id}`. `kb check`: `missing-blob`, `blob-hash-mismatch`. `GET /doctor`: git, git-lfs, repo ve LFS `--local` filtresi, data-dir 0700. `kb doc add` durum tutmaz. Stub sunucu k. parçada koparsa yeniden çalıştırma offset'ten devam eder. Yolda değişen dosya → 422. 20 MB üstü + lfs yok → hiç PATCH atılmadan 422. Sunucu kapalı → 3 | `mcp/worker/**`, `graph/KnowledgeQueries*`, `kb-core/**/render/**`, `snapshot/CheckService*`, `client/Doc*Command*`, `client/DoctorCommand*` | H4, H6 | Y |
| CP4 | `security-reviewer` (doküman taraması uçtan uca) + `java-reviewer` | | | | |
| H8a | TUI 1: arama, detay, düzenleme, arşiv | Pilot testleri: `/` ile ara, `↵` detay, `e/a/r` formları (şema doğrulayıcılı, reddi göster), `d` onaylı arşiv. TamboUI importu yalnız `client.tui` içinde (kb-client ArchUnit + fixture). Secret değeri ekranda görünmez | `client/tui/**` | H6 | Y |
| H8b | TUI 2: çelişki, öneri, aktivite + geri alma, bayat | `c` yan yana kabul/ret, `p` fark ile kabul/ret, `l` filtre + `revert`, `s` "doğruladım" (USER ile aynı değer, ADR-0022 #22) | `client/tui/**` | H8a | Y |
| H9 | Native dağıtım ve kurulum | `install.sh` native `kb` varsa onu, yoksa jar başlatıcısını kurar; `--dry-run` hiçbir şey yazmaz. Testler yalnız geçici HOME ile koşar. Native ile bitti tanımı senaryosu çalışır | `integrations/install.sh`, `kb-client/pom.xml` | H8b | `devops-specialist` |
| CP5 | Lider: bitti tanımı ("her işlem TUI'den yapılabiliyor"), `./mvnw verify`, `-pl kb-client -Pnative package`, `-Peval verify` | | | | |

Bağımsız inceleme: `java-reviewer` her checkpoint'te. `security-reviewer` H3, H4, H5, H6 (`/secrets?prefix`, `/cypher`, `/scan`) ve H7'de. Takım modu H2 ∥ H3 ve H6 ∥ H7 (istemci kısmı) için açılır. `WriteService` ve `RestErrorHandler`'ın her zaman tek yazarı var.

## ADR-0025 kararları
1. **Yükleme protokolü:** "Sıra numarası" byte offset olarak yorumlanır (tus 1.0 core ile IETF resumable-upload draft-15'in kesişimi; tus uyumluluğu iddia edilmez). Kimlik istemcinin hesapladığı sha256. Akış: `PUT`/`PATCH`/`DELETE /uploads/{sha}`, sonra ayrı `POST /documents` tamamlaması. Parça başına checksum yok, dosya başına sha256 sunucuda yeniden doğrulanır. Staging `<data-dir>/uploads`'ta; 7 gün boşta kalan dosya açılışta ve PUT'ta silinir, zamanlayıcı yok.
2. **Blob:** `blobs/xx/<sha>.tmp` → `ATOMIC_MOVE`. Sıra: önce blob, sonra node. Blob hiç silinmez; revert yalnız node'u siler.
3. **Doküman secret taraması:** UTF-8 REPLACE ile çözülen metin, örtüşen pencerelerle `SecretPatterns` desenlerine bakılır. Ret mesajı yalnız satır/offset verir. Sıkıştırılmış içerik (PDF stream, zip, docx) denetlenmez. Bu kabul edilen bir risktir ve belgelenir; metin çıkarma bağımlılığı yok.
4. **`name`/`origin`** secret kontrolünden ve uzunluk sınırından geçer, çünkü anlık görüntüye ve git'e girerler.
5. **git-lfs:** 20 MB üstü dosya git-lfs ister, yoksa PUT'ta 422. `git lfs install --local` yalnız bilgi reposunda yapılır, kullanıcının global ayarına yazılmaz. `kb sync` `blobs/` ve `.gitattributes`'i stage'ler; LFS push hatası sync hatasıdır.
6. **`kb-api`:** yalnız record. Domain record'ları sunucuda kalır, `server.api` eşler. Bu, domain → wire bağımlılığını önler. İstemci Jackson kullanır; CP1'de native doğrulanırsa geçerli.
7. **Native:** Mandrel 23.1.13 (JDK 21). `native` profili yalnız kb-client'ta; build her zaman `-pl kb-client`, çünkü kökten `-Pnative` kb-server'da Boot AOT'yi tetikler. Ayarlar: picocli-codegen, `--no-fallback`, `--install-exit-handlers`, `spring-boot.aot.skip=true`.
8. **TUI:** TamboUI 0.6.0 BOM ile sabitlenir. Backend jline3 (Panama Java 22 istiyor). `kb tui` bir `TuiCommand` alt sınıfı olur. TamboUI yalnız `client.tui` içinde (ArchUnit), böylece B planı ucuz kalır.
9. **REST yazmaları:** `Source.USER`, istek başına bir parti, Episode yok (ADR-0023: episode'suz yazma hata değil).
10. **Kapatmalar geri alınabilir mi:** önerim kapatılan Fact ve ilişkiye `closed_batch` alanı eklemek; BatchRevert de bunu açar. Alternatif: geri alınamaz kabul etmek. Bkz. açık soru 1.
11. **Çelişki çözümü:** accept-new = bekleyen değer USER olarak yeniden yazılır (#23). keep-old = bekleyen fact kapanır, güncel fact'e dokunulmaz. Sinyal F4'e kadar açık kalır (ADR-0022 #7).
12. **Öneri kabulü:** kabul/ret her türde `status`'u değiştirir. `archive` uygulanır. `decision` → `Decision` (rule = ilk satır, body = içerik, APPLIES_TO target). `schema` ve `rule-change` için açık soru 2.
13. **`GET /entities/{id}`** Accept başlığına göre Markdown (tek render yeri `MarkdownView`) ya da JSON döner.
14. **Kapsam dışı (F3):** `/signals*`, reopen, "Atılanlar", `/curator/*`, `kb curator run` (F4); `kb gui` (F5); kullanıcı ayar geçişi (todo'da).

## Yeni bağımlılıklar ve araçlar (hepsi onay ister)
| Öğe | Dilim | Sürüm / kaynak |
|---|---|---|
| `dev.tamboui:tamboui-bom` (import) + `tamboui-toolkit`, `tamboui-jline3-backend`, `tamboui-picocli` (transitive `org.jline:jline:3.25.1`), MIT | H1 | 0.6.0. Araştırmacı Maven Central metadata'sında doğruladı (2026-11-06); ben yeniden doğrulamadım |
| `info.picocli:picocli-codegen` (yalnız annotation processor) | H1 | 4.7.7 (Maven Central, araştırmacı) |
| `org.graalvm.buildtools:native-maven-plugin` | H1 | Boot 4.1.2 yönetiyor: 1.1.14. En son sürüm 1.1.18. Öneri: Boot'unkini kullanmak |
| ~~`jackson-databind` (kb-client)~~ | — | CP1'de çıkarıldı: native 55,8 MB > 50 MB (ADR-0025 karar 6). `Json.java` kalır |
| `archunit-junit5` (kb-client test) | H8a | 1.5.1 (parent'ta zaten var) |
| U1 `mise install java@mandrel-23.1.13.0-Final+java21` | H1 | Kullanıcı başına kurulur, varsayılan java değişmez |
| U2 `sudo dnf install libstdc++-static git-lfs` | H1, H4 | root ister. Makine fc45: OS `7.2.15-300.fc45` |
| ~~U3 `Json.java` silinmesi~~ | — | Silinmez (CP1) |

Yükleme için yeni bağımlılık yok: `java.net.http`, `FileChannel` ve `MessageDigest` yeterli.

## Riskler
| Risk | Azaltma |
|---|---|
| TamboUI veya Jackson native'de çalışmaz | H1 spike'ı ve CP1 go/no-go. B planı ADR-0012'de (Go/Bubble Tea) |
| TamboUI 0.x API'si değişir (8 ayda 6 minor sürüm) | Sürüm sabitlenir, `client.tui` ince tutulur, ArchUnit ile yalıtılır |
| Blob'lar senkrona girmez | `SyncGit` bugün yalnız iki dosyayı commit'liyor (:86, :96-108). H4'te genişler; iki repolu test |
| Uzun IO yazıcı kilidini tutar | Hash, tarama ve kopya kilit dışında; kilit altında yalnız node yazılır; testi var |
| Disk dolar, yarım yüklemeler birikir | Disk alanı kontrolü (usableSpace), 7 günlük temizlik, isteğe bağlı üst sınır (açık soru 3) |
| Sıkıştırılmış dokümandaki secret kaçar | Kabul edilen risk ADR'de; `kb doc add` uyarı basar |
| Shell erişimli ajan `kb fact set` ile USER (güven 4) olarak yazar | ADR-0020 güven modeli bunu kabul ediyor. AGENT-RULES yasak cümlesi ve `security-reviewer` kaydı |
| Testler gerçek HOME'a veya git ayarına dokunur (F2 dersi) | Her testte geçici `user.home`, `HOME` ve `GIT_CONFIG_GLOBAL`. git-lfs `--local`. Review bunu grep ile arar |
| Native'de "unknown protocol: http" | Spike'ta görülürse `--enable-url-protocols=http` |
| git-lfs veya GraalVM kurulmazsa | Bu testler ayrı grupta toplanır ve "koşmadı" diye açıkça raporlanır; sessiz skip yok |

Doğrulanmamış varsayımlar:
- Native boyut ve açılış süresi (tahmin 15-30 MB).
- Pilot Testing'in hangi artifact'ta olduğu.
- Tomcat'in `offset+octet-stream` gövdesini önden okumadığı (H3 heap testi gösterir).
- `kb_search`'ün Document döndürüp döndürmediği (`Document.name` tam metin indeksinde, ama sorgu yalnız Entity'yi çözüyor olabilir).


## Lider kararları (açık sorular, sektör örneğine göre)
1. **Kapatma ve arşiv geri alınabilir:** kapatılan Fact ve ilişkiye `closed_batch` yazılır, `kb revert --batch` onu da açar (Datomic'te geri çekme işlemin kendisine bağlıdır; ADR-0022 karar 22–23 ile tutarlı).
2. **`kind=schema` önerisi kabul edilince** anahtar `schema/facts.yaml`'a yazılır ve şema yeniden yüklenir (MCP-TOOLS'un verdiği söz). `rule-change` kabulü yalnız durumu değiştirir, kural metnini kullanıcı düzenler.
3. **Toplam boyut:** üst sınır yok, yalnız boş disk alanı kontrolü (tek kullanıcı, yerel disk).
4. **Aynı içerik yeniden eklenince** yeni `--about` ek `ABOUT` kenarı açar; `--replaces` bayrağı CLIENTS'a eklenir.
5. **Yükleme "sıra numarası"** byte offset olarak uygulanır (tus 1.0 / IETF resumable upload taslağının ortak yolu); parça 1 MiB sınırında, kaldığı yerden devam eder. Kullanıcının "sıra numaralı parça" isteğinin sektör standardı karşılığıdır.
6. **Sıkıştırılmış dokümanlarda** (PDF, zip, docx) secret taraması yapılamaz; kabul edilen risk olarak ADR-0025'e yazılır, `kb doc add` uyarır.
