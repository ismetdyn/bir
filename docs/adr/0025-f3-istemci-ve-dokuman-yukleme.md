# ADR-0025: F3 istemci, doküman yükleme ve native kararları

- Durum: Kabul edildi
- Tarih: 2026-11-07
- Değiştirdiği: ADR-0022 karar 1'in `kb-api` ertelemesi (F3'te açılır) ve karar 21'in "ayrıntılar F3 başında" notu; ROADMAP F3; CLIENTS §1–§2; SPEC §6.7; ARCHITECTURE §2–§3; `CLAUDE.md` Teknoloji satırı

## Bağlam
F3 planı (`.engineering/tasks/plan.md`) 2026-11-07'de kullanıcı tarafından onaylandı; onayla birlikte şu bağımlılık ve
araçlar kabul edildi: TamboUI 0.6.0 BOM + `tamboui-toolkit` + `tamboui-jline3-backend` + `tamboui-picocli`,
`picocli-codegen` 4.7.7, `kb-client`'ta Jackson, `kb-client` `Json.java`'nın silinmesi ve Mandrel. Bu ADR, planın
"ADR-0025 kararları" ile "Lider kararları" bölümlerinin kalıcı kaydıdır; ayrıntı ve dilim sırası plandadır.

## Karar

### Doküman yükleme ve depo
1. **Yükleme protokolü:** ADR-0022 karar 21'deki "sıra numarası" **byte offset** olarak uygulanır: tus 1.0 core ile IETF
   resumable-upload taslağının (draft-ietf-httpbis-resumable-upload) kesişimi. tus uyumluluğu iddia edilmez. Kimlik
   istemcinin hesapladığı sha256'dır. Akış: `PUT`/`PATCH`/`DELETE /api/v1/uploads/{sha}`, sonra ayrı
   `POST /api/v1/documents` tamamlaması. `PATCH` gövdesi `application/offset+octet-stream`, en çok 1 MiB (karar 17,
   ADR-0022). Parça başına checksum yok; dosya başına sha256 sunucuda yeniden doğrulanır. Staging `<data-dir>/uploads`'ta;
   7 gün boşta kalan dosya açılışta ve `PUT`'ta silinir, zamanlayıcı yok. Gövde diske akıtılır, heap'e alınmaz.
2. **Blob:** `blobs/xx/<sha>.tmp` yazılır, `ATOMIC_MOVE` ile yerine konur. Sıra: önce blob, sonra node
   (`WriteService.addDocument`, tek yazıcı). Blob hiç silinmez; `kb revert --batch` yalnız node'u siler. Hash, tarama ve
   kopya yazıcı kilidi dışında yapılır.
3. **Doküman secret taraması:** REPLACE ile çözülen metin, örtüşen pencerelerle `SecretPatterns` desenlerine karşı
   taranır; bir Secret'ta saklı değerler de aynı geçişte aranır ve reddedilir. Metin her zaman UTF-8 çözülür; UTF-16 BOM'u
   varsa ya da BOM yok ama ilk 4 KiB UTF-16'ya benziyorsa (NUL sezgisi) ek olarak o UTF-16 da çözülür (ilk bulgu
   kazanır). UTF-8'in kalması, UTF-16 metne eklenmiş ASCII satırı da yakalar (PowerShell `>` sonra `Add-Content`). Ret mesajı yalnız
   satır/offset verir, değer vermez. **Sıkıştırılmış içerik (PDF stream, zip, docx) denetlenmez; bu kabul edilen bir
   risktir.** Metin çıkarma bağımlılığı eklenmez; `kb doc add` uyarı basar.
4. **`name`/`origin`** secret kontrolünden (ör. `user:pw@` URL'si) ve uzunluk sınırından geçer; ikisi de anlık görüntüye ve
   git'e girer (değişmez 2). İhlalde 400.
5. **git-lfs:** 20 MB üstü dosya git-lfs ister, yoksa `PUT`'ta 422. `git lfs install --local` yalnız bilgi reposunda
   yapılır, kullanıcının global git ayarına yazılmaz. `kb sync` `blobs/` ve `.gitattributes`'i stage'ler; LFS push hatası
   sync hatasıdır (ADR-0017 madde 7).
   `git lfs track`, blob `blobs/`'a taşınmadan önce çalışır.
   `kb sync` birleştirmeden önce doküman deposunu ayrı commit'ler; `.gitattributes` git'in union sürücüsüyle birleşir
   (`.git/info/attributes`'te ayarlı).

### Modül, istemci, araç zinciri
6. **`kb-api`:** yalnız record; bağımlılığı JDK + JSpecify. Domain record'ları sunucuda kalır, `server.api` DTO'ya eşler;
   böylece domain → wire bağımlılığı oluşmaz. **CP1 sonucu (2026-11-07, ölçüldü):** Jackson native `kb`'yi 55,8 MB'a
   çıkardı (+15 MB, `java.xml` dahil); 50 MB sınırını aşıyor. Bu yüzden `kb-client` Jackson kullanmaz, F2'deki küçük
   `Json.java` kalır ve `kb-api` record'larına elle eşler (native 40,7 MB). TamboUI native'de çalıştı (`kb tui`,
   `--no-alt-screen`, `q`), `--install-exit-handlers` ile `kb run` geçici dosyası SIGINT/SIGTERM'de siliniyor.
7. **Native:** Mandrel 23.1.13 (JDK 21). `native` profili yalnız `kb-client`'ta; build her zaman `-pl kb-client`, çünkü
   kökten `-Pnative` kb-server'da Boot AOT'yi tetikler. Ayarlar: picocli-codegen, `--no-fallback`,
   `--install-exit-handlers`, `spring-boot.aot.skip=true`.
8. **TUI:** TamboUI 0.6.0, BOM ile sabit. Backend jline3 (Panama Java 22 ister). `kb tui` bir `TuiCommand` alt sınıfıdır.
   TamboUI yalnız `client.tui` paketinde kullanılır (ArchUnit); B planı (ADR-0012, Go/Bubble Tea) ucuz kalsın diye.
9. **REST yazmaları:** `Source.USER`, istek başına bir parti, Episode yok (ADR-0023: episode'suz yazma hata değil).
10. **Kapatmalar geri alınabilir:** kapatılan Fact ve ilişkiye `closed_batch` alanı yazılır; `BatchRevert` bunu da açar
    (karar 22–23 ile tutarlı; `valid_from` değişmez, silme yok).
11. **Çelişki çözümü:** `accept-new` = bekleyen değer USER olarak yeniden yazılır (ADR-0022 #23). `keep-old` = bekleyen fact
    kapanır, güncel fact'e dokunulmaz. Sinyal F4'e kadar açık kalır (ADR-0022 #7).
12. **Öneri kabulü:** kabul/ret her türde `status`'u değiştirir; `archive` uygulanır. `decision` → `Decision` (rule = ilk
    satır, body = içerik, `APPLIES_TO` hedefi). `kind=schema` kabulünde anahtar `schema/facts.yaml`'a yazılır ve şema
    yeniden yüklenir; `rule-change` kabulü yalnız durumu değiştirir, kural metnini kullanıcı düzenler.
13. **`GET /entities/{id}`** `Accept` başlığına göre Markdown (tek render yeri `MarkdownView`) ya da JSON döner.
14. **Kapsam dışı (F3):** `/signals*`, reopen, "Atılanlar", `/curator/*`, `kb curator run` (F4); `kb gui` (F5); kullanıcının
    Claude Code/Codex ayar geçişi.

### Lider kararları
- **Toplam boyut:** üst sınır yok, yalnız boş disk alanı (`usableSpace`) kontrolü (tek kullanıcı, yerel disk).
- **Aynı içerik yeniden eklenince** yeni `--about` ek `ABOUT` kenarı açar; `--replaces <docId>` bayrağı yeni dokümanı
  eskisine `SUPERSEDES` ile bağlar (CLIENTS §2).
- **Sıra numarası** = byte offset (karar 1); kullanıcının "sıra numaralı parça" isteğinin sektör standardı karşılığıdır.
- **Sıkıştırılmış dokümanda taramanın yokluğu** kabul edilen risktir (karar 3).

Kapatma/arşivin geri alınabilirliği, `kind=schema` kabulü ve toplam boyut kararları plandaki açık sorulara lider
tarafından verilen yanıtlardır (sektör örneği: Datomic'te geri çekme işlemin kendisine bağlıdır).

## Sonuçlar
- (+) Yükleme kesilince kaldığı byte'tan sürer; bellek kullanımı parça boyutuyla sınırlı.
- (+) `kb-api` istemci ve sunucu arasında tek sözleşme verir; domain'e bağımlılık yok.
- (+) TamboUI tek pakete hapsolur; vazgeçmek ucuz.
- (−) Yükleme durum ve protokol karmaşıklığı ekler; loopback'te paket kaybı olmasa da kullanıcı bu yolu seçti.
- (−) Sıkıştırılmış dokümandaki secret taramadan kaçabilir.
- (−) `closed_batch` alanı ve `BatchRevert` değişikliği geçmiş davranışı genişletir; her işlem için revert tablo testi gerekir.
- (−) TamboUI 0.x: API sık değişebilir; sürüm sabit, çıkış yolu karar 8.
- Ölçüldü (CP1): native `kb` 40,7 MB, `--help` medyan 5 ms; TamboUI native'de çalışıyor; Jackson boyut yüzünden çıkarıldı. Doğrulanmamış (H3'te ölçülür):
  Tomcat'in `offset+octet-stream` gövdesini önden okumaması.

## Değerlendirilen alternatifler
- **tus 1.0 uyumlu sunucu:** başlık ve uzantı yükü; yalnız kesişen çekirdek alındı.
- **Parça başına checksum:** dosya sha256'sı sunucuda zaten doğrulanıyor; loopback'te gereksiz.
- **Zamanlayıcı ile staging temizliği:** açılış ve `PUT`'ta silme yeterli (ADR-0016).
- **Mevcut `Json.java`'yı korumak:** kayıt/sözleşme için elle ayrıştırma. CP1'de seçildi: Jackson native boyut sınırını aştı.
- **Go/Bubble Tea (ADR-0012 B planı):** TamboUI spike'ı (H1, CP1) başarısız olursa devreye girer.

## Kaynaklar
- tus 1.0 resumable upload: https://tus.io/protocols/resumable-upload
- IETF resumable upload taslağı: draft-ietf-httpbis-resumable-upload (https://datatracker.ietf.org/doc/draft-ietf-httpbis-resumable-upload/)
- TamboUI: https://tamboui.dev
- Mandrel (GraalVM dağıtımı): https://github.com/graalvm/mandrel
- Plan: `.engineering/tasks/plan.md`
