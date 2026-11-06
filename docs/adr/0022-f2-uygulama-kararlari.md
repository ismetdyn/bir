# ADR-0022: F2 uygulama kararları ve çelişki çözümleri

- Durum: Kabul edildi
- Tarih: 2026-11-01
- Değiştirdiği: ADR-0002'nin `GraphStore` arayüzü maddesi; ROADMAP F2/F3/F4 kapsam dağılımı; ARCHITECTURE §2–§3 modül ve paket listesi; `CLAUDE.md` Teknoloji satırları (`kb-api`, `GraphStore`) ve değişmez 8'in "sabit alan sırası" ifadesi

## Bağlam
F2 planı (`.engineering/tasks/plan.md`) hazırlanırken belgelerin birbiriyle ve F1 bulgularıyla (ADR-0018) çeliştiği yerler
çıktı; bazı işler de fazlar arasında yer değiştirmeli. Plan 2026-11-01'de onaylandı. Bu ADR kararların tek kaynağıdır;
diğer belgeler buraya bağlanır.

## Karar

### Modüller ve katmanlar
1. **Modüller:** `kb-core` F2'nin saf çekirdek diliminde (G3), `kb-client` secret ve `kb run` diliminde (G8) açılır. `kb-client`
   F2'de JVM jar'dır; native-image ve TUI F3'tedir. **`kb-api` F3'e ertelenir:** F2'de paylaşılan DTO yok.
2. **`GraphStore` arayüzü yok.** Tek uygulama var: `EmbeddedNeo4jStore`, `read(fn)` ve `write(fn)` sunar. `write` yalnız `write`
   paketinden çağrılabilir (ArchUnit).

### Komutlar
3. **Yerleşim:** `init` sunucu jar'ında çalışır, Neo4j'i açmaz. `revert`, `sync`, `restore`, `check`, `import-memories`, `run`,
   `secret` REST üzerinden ince `kb-client` ile çalışır; grafa ikinci bir süreç girmez (tek süreç kilidi, ADR-0002).
4. **`kb run` F2'ye çekilir** (F3'ten). Bitti tanımı değişmez.
5. **F2 REST alt kümesi:** `GET/PUT /secrets/{id}`, `POST /revert`, `POST /sync`, `POST /restore`, `GET /check`,
   `POST /signals/imported-memories` (yeni). Kalan `/api/v1` F3'tedir.
6. **`kb check` F2 kapsamı:** sahipsiz kenar, sözlük dışı ilişki, açık çelişki, `git fsck`. Doküman bütünlüğü F3, büyüme raporu F4.

### Sinyal
7. **Signal en küçük hali (F2):** `kind`, `key`, `payload`, `at`, `last_at`, `count`, `status=open`, `ABOUT`. Türler `conflict`,
   `qa-capture`, `imported-memory`. `outcome`, `PRODUCED`, yeniden açma, `empty-search` ve `scan-finding` F4'tedir.

### Eval ve anlık görüntü
8. **Eval:** üretim sınıfı `server.eval`; `-Peval` profili ince bir JUnit sarmalayıcı açar. Eşik 0,85 = 18 soruda en az 16.
   CI gelene kadar eşik `-Peval verify` çıkış kodudur.
9. **Anlık görüntü biçimi:** seed kazanır: anahtarlar özyinelemeli alfabetik, satırlar `id`'ye göre sıralı, UTF-8 ham, satır
   sonu `\n`.
10. **Tazeleme `batch` yazmaz.** Yazsaydı `kb revert --batch` tazelenen eski kaydı silerdi. Bir partinin kapattığı kayıtlar
    `SUPERSEDES` ile bulunur.

### Güvenlik
11. **`OriginGuard` portu** `request.getLocalPort()`'tan okunur; `Host` izin listesi bu portla kurulur. Sabit 7411 testleri
    kırar. Ayar değeri ve varsayılan port (7411) değişmez.

### Çelişki çözümleri
12. **`Person` / `Team`:** seed şeması (`seed/schema/types.yaml`) bunları taşıyor; SPEC §6.1 varlık türlerine eklenir.
13. **Eval kapısı:** F2 kapısı 18 soru ve %85'tir (karar 8). SPEC §15'teki "≥ 30 soru" v1.0 ölçütü olarak kalır.
14. **Hook:** `stop` olayı ve 1,5 sn zaman aşımı kaldırılır; hook 2 sn kullanır (SPEC §12, ARCHITECTURE §11).
15. **Normatif değişiklik:** ajan ve küratör yalnız `Proposal` açar; `user` kaynağı arşivi doğrudan yapar.
16. **H12 yeni ifadesi:** "Sistem kaydı kendiliğinden silmez veya gizlemez; istisnalar `kb revert --batch` ve disk kaybında
    yerel sinyaller."

### MCP aktarım güvenliği
17. **İstek gövdesi sınırı:** `/*` altındaki her istekte gövde en çok 1 MiB; aşılırsa 413. Gerekçe: Spring AI `/mcp`
    aktarımı gövdeyi sınırsız okuyup heap'e alır ve aynı JVM Neo4j'i de taşır (ölçüldü: `-Xmx256m` + 500 MB gövde
    `OutOfMemoryError` verdi). Sınır önce 16 MiB'ti; `-Xmx512m` altında yük bir JSON dizesinin içindeyken 8 ve üzeri
    eşzamanlı 16 MiB gövde `OutOfMemoryError` verip sunucuyu kilitledi, 4 eşzamanlı sorun çıkarmadı (ölçüldü).
    Normal MCP mesajları kilobayt boyutundadır (metin sınırları 300/500/4000 karakter), bu yüzden sınır 1 MiB'e indi.
    Ayrıca sunucu JVM'i `OutOfMemoryError`'da çıkar; systemd yeniden başlatır. MCP belirtimi POST başına tek JSON-RPC mesajı ister; Claude Code ve Codex standart
    istemcidir, bu yüzden sıra numaralı parçalama `/mcp` yolunda uygulanamaz.
18. **`McpErrorBodyFilter`:** MCP HTTP yanıtlarında durum ≥ 400 ise gövde boştur. Aktarımlar `McpError`'u stack trace ile
    serileştirir ve Boot'un `server.error` ayarları bunu kapsamaz. MCP belirtimi buna izin verir (gövde JSON-RPC hatası
    taşıyabilir, zorunlu değil).
19. **Aktarım logger'ları maskelenir:** log satırı kalır, yük `*****` yazılır. Kullanıcı secret içeren istekleri işaretlemeyi
    veya ayrı bir istek sınıfı açmayı düşündü; bu yolda işlemez: sızıntı SDK'nın içinde, ayrıştırma başarısız olurken
    olur; bozuk gövdeden işaret okunamaz; yanlışlıkla secret gönderen ajan bunu bildirmez. Secret değerinin zaten kendi
    yolu var: `PUT /api/v1/secrets/{id}`. Maskelenen logger'lar: `io.modelcontextprotocol`, WebMvc aktarımı, Tomcat
    `Http11Processor` ve `org.apache.tomcat.util.http`, `dispatcherServlet`, `curatorMcpEndpoint` (ölçüldü: geçersiz
    `Accept`, cookie, bozuk istek satırı ve form gövdesi içeriği loga yazıyordu). Bedel: bu logger'larda sınıf adı ve stack
    trace kalmaz. `dispatcherServlet` maskesi kaçan her istisnayı kapsadığı için G8'de REST ile tek üst seviye hata işleyici
    gelir; istisna sınıfını ve `trace_id`'yi kendisi loglar.
20. **Kabul edilen yansıma:** "Method not found", "Tool not found" ve `data.uri` içinde ad, aynı göndericiye geri
    yansır. İç ayrıntı taşımaz ve yalnız göndericiye döner. Bu, değişmez 2'nin açık yorumudur.

### Yazma politikası
22. **Aynı değerle onay:** Aynı anahtar + ortam + değer daha güvenli kaynaktan gelirse fact yerinde değişmez: eskisi
    kapanır, aynı değerle yeni fact açılır (yükselen kaynak, kaynağa göre durum, `valid_from` eskisinden taşınır) ve
    `SUPERSEDES` ile bağlanır. Düşük veya eşit güvenli kaynak aynı değeri yalnız tazeler. Kullanıcı kararı (SPEC §7 kural 1).
23. **Bekleyen çelişkiyi yazmak çözer:** `status: conflict` ile bekleyen değer, güveni güncel değere eşit veya yüksek
    kaynaktan yeniden yazılırsa bekleyen fact ve eski güncel değer kapanır; aynı değerle yeni güncel fact açılır
    (`valid_from` bekleyenden taşınır), ikisine de `SUPERSEDES` ile bağlanır. Kullanıcı kararı.
    Gerekçe (22–23, 2026-11-05 kullanıcı kararı): kayıt hiç yerinde değişmez, her değişiklik yeni sürümdür (SQL:2011
    sürümlü tablolar, Datomic; değişmez 5). Böylece `kb revert --batch` açtığını silip kapattığını açarak partiyi tam geri
    alır; yerinde değişiklik iz bırakmadığı için tek değerli anahtarda iki güncel değer kalıyordu. Bedel: geçmişte aynı
    değer iki satır görünür.

### Cypher güvenliği
24. **`CypherGuard` tehdit modeli:** dürüst ajanlar için bir kuraldır, düşmanca bir yerel sürecin önünde sınır değildir
    (ADR-0020: aynı kullanıcının programları güvenilirdir, dosyaları okuyabilir ve `GET /api/v1/secrets` çağırabilir).
    Sorgu metni üzerinde çalışır (yorumlar ve CR satır sonları dahil, Unicode boşluk, değişken adı olarak kullanılan
    anahtar sözcükler, dinamik özellik erişimi, `LOAD CSV`, prosedürler, `SHOW`) ve ayrıca `EXPLAIN`'in sorguyu salt okunur
    bildirmesini şart koşar. Çıktı süzgeci ikinci hattır: yalnız bilinen secret değerlerinin birebir kopyalarını maskeler
    (örtüşen değerler tek parça). Bilinen özelliği: `RETURN '<tahmin>'` tahmin bir secret değerine eşitse `*****` döner;
    bu tehdit modelinin dışındadır, çünkü aynı süreç değeri `GET /api/v1/secrets/{id}` ile zaten alabilir.
25. **Kabul edilen risk:** tek bir `kb_cypher` sorgusu (örnek: `RETURN range(1, 200000000)`) heap'i Neo4j'in içinde
    tüketebilir; Neo4j'in bellek sınırı orada işlemez (ölçüldü: `db.memory.transaction.max` 64 MiB durdurmadı). JVM
    `ExitOnOutOfMemoryError` ile çıkar, systemd 5 sn içinde yeniden başlatır. Düzeltilmedi: sorguyu yalnız güvenilir bir
    yerel süreç gönderebilir. Risk tablosunda: RISKS-AND-GAPS A35.
26. **Neo4j'in kendi debug logu sorgu metni almaz** (değişmez 10). Ölçüldü: `TransactionMonitor` zaman aşımında sorgu
    metnini yazıyor; sunucunun Neo4j log yapılandırması bunu engeller.
27. **Analizör `standard-folding` kalır** (ADR-0018 madde 6'nın F2 doğrulaması). `-Peval verify` 18/18 (skor 1,0); q12
    İzmir'i dört yazımla buluyor. `ı`/`i` birleşmesinin fazla eşleşmesi kabul edilir.
28. **Okuma araçlarının MCP işaretleri:** `readOnlyHint: true`, `destructiveHint: false`, `idempotentHint: true`,
    `openWorldHint: false`. Ölçüldü (Codex 0.161.0): `destructiveHint` varsayılanı `true` iken `codex exec` okuma aracını
    onay istediği için reddetti; düzeltmeden sonra ayarsız çalıştı. ADR-0018'deki doğrulanmamış varsayımı kapatır.

### Yazma yolu (G7)
29. **`user_stated` kanıtı:** kanıt metni son 2 saatin bir kullanıcı mesajında birebir geçer ve yazılan değeri içerir.
    Ölçüldü: yalnız "kanıt geçsin" kuralında `the` veya tek harf yetiyordu. Kullanıcı kararı (SPEC §7 kural 7).
30. **Port hedefi değerin parçasıdır:** aynı port başka hedefle gelirse farklı değer sayılır; SPEC §7 kural 2–3 işler
    (eşit/yüksek güven eskisini kapatıp yenisini açar, düşük güven çelişki açar). Kullanıcı "sektör standardı" dedi:
    sürümlü veri modellerinde (SQL:2011, CMDB ilişkileri) herhangi bir alanın değişmesi yeni sürümdür.
31. **Lider kararları:** `Proposal.reason` alanı açılmaz, gerekçe `evidence`'a yazılır; `Proposal.status` değerleri
    `open|accepted|rejected`. Kanıt ve öneri içeriği de secret görünümlü değer için denetlenir (değişmez 2: anlık
    görüntüye ve git'e girerler). Tek yazmada en çok 50 fact ve 50 ilişki. `facts.yaml` doğrulayıcıları (hostname, ip,
    port-spec, enum) yeni yazmalarda uygulanır (değişmez 11). İlişki daha yüksek kaynaktan yeniden görülünce yalnız
    tazelenir, kaynağı yerinde yükselmez (yerinde değişiklik yok, karar 22–23). Çatışma sinyalleri aramada `ham` olarak
    en sona girer (değişmez 12). `kb_remember`'da `summary`/`body` alanı yok; o sınırlar REST ve `kb_propose`'da uygulanır.

### Tarayıcı (G9)
32. **`finding` çok değerlidir** (kullanıcı, 2026-11-05): aynı varlıkta birden çok dosyadaki bulgu birbirini kapatmaz.
33. **`finding` metni:** `Düz metin parola bulundu: <dosya>:<satır>; ortam değişkenine taşınmalı`. Önceki `parola:` biçimi
    secret desenine uydu (lider kararı); desene uymayan ifade seçildi.
34. **Tek taramada çok değer:** tek değerli anahtar bir ortamda birden çok değer alırsa deterministik biri (önce yol, sonra
    satır) kalır; diğerlerini listeleyen tek `conflict` sinyali açılır. Aynı secret id için iki farklı değerde ilki tutulur,
    sinyal yalnız id'yi söyler (SECRETS §4).
35. **Kapsam kuralları:** proje birden çok repoya yayılabilir (ek `HAS_REPO`); token taşıyan webhook url'leri url fact'i
    yazılmaz; okunan her dosya `allowed-roots` altında çözülür (git `core.worktree`/`gitdir` kaçıramaz).
36. **Extractor sınırları ve `DEPLOYED_ON`:** Compose yalnız `services.*.environment` düz metin secret'ını yazar (servis varlık,
    ilişki, fact üretmez). K8s ortamı namespace'in son `-` parçasından alır, `secretKeyRef` secret üretmez. Ansible
    host'u kendi ortamında `Server`; `DEPLOYED_ON` verilir, k8s namespace'i projeyi o ortama koymuşsa verilmez
    (ARCHITECTURE §6).
37. **CP5 ölçümleri:** H2 17/17 = %100. 500 dosyalı repo taraması ısınmış JVM'de 4,7 sn, yeni JVM'de 5,9 sn; SPEC hedefi
    < 5 sn. Sunucu uzun ömürlü ve tarama onun içinde koştuğu için kabul edildi. Yeniden tarama yaklaşık 2 sn.
    Sonra eklenen çok-değer indirgemesi taramayı karesel yaptı (JFR); tek indeksle doğrusala döndü, `ScanPerformanceTest`
    çağrı sayısının dosya sayısıyla doğrusal kaldığını sabitler. Süre bu makinede yük 7–13 iken yeniden ölçülemedi
    (17 sn, ilgisiz derleme koşuyordu); boş makinede CP6'da yeniden ölçülür. Değişmemiş yeniden tarama 18/18 koşuda 0
    ekledi, 0 kapattı.
45. **Tarama süresi hedefi netleşti** (kullanıcı kararı 2026-11-07): 5 sn hedefi yeniden tarama içindir; 500 dosyalık ilk
    tarama oturum açılışında arka planda çalıştığı için hedefi < 10 sn'dir. CP6 ölçümü (500 dosya, `-Xmx512m`, yük 3–4,7):
    yeniden tarama 0,6–1,1 sn; ilk tarama yeni JVM'de medyan 5,8 sn, ısınmış JVM'de 4,6–5,9 sn. Makine boşken yeniden
    ölçülür ve buraya eklenir.

### Geri yükleme (G12)
38. **Restore yerel veriyi korur** (kullanıcı kararı 2026-11-05; değişmez 8): geri yüklenen anlık görüntüde olmayan Secret
    kaydı değeriyle kalır, sinyaller kalır; bağlı oldukları kayıt yoksa yalnız o kenar düşer. Restore kaç kaydın böyle
    kaldığını raporlar, `kb check` listeler. Hiçbir parola ve sinyal kaybolmaz.
39. **Elle yazılan secret kullanıcınındır** (lider, karar 38'in ilkesi): `PUT /secrets/{id}` Secret'ın kaynağını `user`,
    partisini PUT'un kendi partisi yapar (secret değeri SPEC §7 kural 9'daki geçmişsiz istisnadır). Taramanın partisi
    geri alınınca Secret ve değeri kalır, yalnız taramanın `HAS_SECRET` kenarı düşer (`kb check`: yerel kayıt); yeniden
    tarama kullanıcının değerini ezmez, çelişki sinyali açar.
40. **Bakım girdileri ve git:** `batch` `[A-Za-z0-9._:-]{0,64}`, `at` en çok 256 karakter ve kontrol karakteri yok; aşan
    istek 400. `batch` alanı indekslidir. git çıktısı boru yerine sahibine özel (0600) geçici dosyaya yazılır ve çağrı
    sonunda silinir; böylece takılan bir git zaman aşımında kesilir. Çıktı anlık görüntü ve `fsck` metnidir, secret
    değeri değildir. Kullanılamayan anlık görüntü 400 `{error, hint}` döner. `kb` istemcisi sunucu metnindeki kontrol
    karakterlerini (sekme ve satır sonu hariç) yazdırmadan siler; `kb secret show` değeri olduğu gibi basar.

### Senkron (G13)
41. **Eşit zamanda sabit kural + uyarı** (kullanıcı kararı 2026-11-06): `kb sync` aynı kaydı iki tarafta aynı `updated_at` ile
    farklı bulursa iki bilgisayarda aynı sonucu veren sabit bir kuralla birini seçer (satırın kanonik metninin sözlük
    sırasında büyük olanı). Kaybeden sürüm git geçmişinde kalır; kayıt hakkında bir `conflict` sinyali açılır.
42. **Geri alma senkrona taşınır** (lider; yaygın "silme işareti" deseni): `kb revert --batch` anlık görüntüye küçük bir
    `RevertedBatch {id: <batch>, at}` kaydı bırakır. Birleştirme, geri alınmış bir partinin damgasını taşıyan kayıtları
    diğer bilgisayardan geri getirmez. Böylece bir bilgisayarda geri alınan parti senkronla diriltilmez. Diğer
    bilgisayarda o partinin üstüne yazılmış sonraki bir kayıt silinmez (değişmez 5, 11); varlığı kalmadıysa `kb check`
    onu `orphan-fact` olarak gösterir. Birleştirmenin açtığı `SUPERSEDES` kenarının kimliği iki bilgisayarda aynı
    çıkacak şekilde türetilir.
43. **Birleştirme sonrası tutarlılık:** iki bilgisayar aynı tek değerli anahtara farklı yeni değer açtıysa birleştirme ikisini
    de korur (kayıpsız, değişmez 11); güveni düşük olan `conflict` olur, eşit güvende yeni olan güncel kalır ve çelişki
    sinyali açılır. Anlık görüntü dosyaları geçici dosya + yeniden adlandırma ile atomik yazılır.
44. **Senkron ayrıntıları** (lider): reddedilen push en çok 5 turda yeniden fetch-birleştir-commit edilir, sonra hata.
    Senkron commit'leri bot kimliğiyle (`agentkb <agentkb@localhost>`) ve imzasız atılır; ayar yalnız o git çağrılarına
    verilir, kullanıcının git ayarına yazılmaz (otomasyon commit'lerinin yaygın biçimi; yeni makinede ek kurulum gerekmez).
    Sunucu kapanırken anlık görüntüyü yazar ve değişiklik varsa yerel commit'ler; push bir sonraki `kb sync`'e kalır.

### F3 notu
21. **Doküman yükleme (F3):** `kb doc add` sıra numaralı, kaldığı yerden sürdürülebilir yükleme kullanır (ROADMAP F3,
    CLIENTS §2). Bu, protokol ve durum takibi ekler; loopback'te paket kaybı olmamasına rağmen kullanıcı bu yolu seçti.
    Her parça 1 MiB sınırı içinde kalır (karar 17). Protokol ayrıntıları F3 başında tasarlanır.

## Sonuçlar
- (+) F2'de paylaşılan DTO, ikinci modül ve tek uygulamalı arayüz yok; sistem küçük kalır (ADR-0016).
- (+) `kb run` ve secret akışı F2 bitince çalışır; bitti tanımı F2'de sınanabilir.
- (−) `kb-client` F2'de JVM jar: açılış yavaş, native binary F3'e kadar yok.
- (−) `kb-api` gelince F2'de yazılan istemci/sunucu DTO'ları ortak modüle taşınır.
- (−) Diske bağlı sinyaller disk kaybında gider (anlık görüntüye girmedikleri için); H12 ifadesi bunu açıkça söyler.
- `CLAUDE.md` bu ADR'ye göre ayrıca güncellenmelidir (Teknoloji satırları, değişmez 8); bu ADR'nin kapsamı `docs/**`.

## Değerlendirilen alternatifler
- **`GraphStore` arayüzü:** Tek uygulama var; ikinci bir depo ölçülmedi (ADR-0002'deki B planı ayrı Neo4j süreci, aynı
  motor). Arayüz F2'de yük olur, gerekirse sonradan çıkarılır.
- **`kb-api`'yi F2'de açmak:** Paylaşılan DTO yok; modül boş kalırdı.
- **Tüm komutları sunucu jar'ına koymak:** `init` dışındakiler çalışan sunucuya erişir; ikinci süreç grafı açamaz (`store_lock`).
- **Tazelemeyi `batch` ile yazmak:** Revert eski kaydı silerdi (karar 10).
- **Sabit 7411 ile test:** Testleri kırar (karar 11).
