# agentkb — Ürün Şartnamesi (SPEC)

> Durum: Taslak v0.3 · Tarih: 2026-11-01 · Sahibi: İsmet
> v0.2'den farklar: bilgi doğrudan grafta (ADR-0014); secret'lar grafta ama değerleri sadece bu bilgisayarda, ajanlar
> placeholder ile kullanır (ADR-0015); sadelik ilkesi (ADR-0016); şişmeye karşı kurallar (silme ve gizleme yok;
> işaretle ve sırala) ve doküman deposu (ADR-0017).
> Markdown vault, satır grameri, dosya izleyici, Obsidian entegrasyonu, ayrı pass deposu ve GPG çıkarıldı.

## 1. Problem

Claude Code ve Codex her oturuma sıfırdan başlıyor. Sunucu bilgileri, proje mimarileri, kararlar ve çıkarılmış dersler
her seferinde elle yeniden anlatılıyor. İki ajanın yerleşik hafızaları birbirini görmüyor, makineye bağlı ve
denetlenemiyor.

## 2. Çözüm özeti

**Bilgi grafta durur. Sunucu onu tek başına yazar ve ajanlara Markdown olarak sunar. Git sadece yedek ve senkron içindir.**

| Parça | Ne | Çalışma biçimi |
|---|---|---|
| **agentkb sunucusu** | Bilgi bankasının kendisi: gömülü Neo4j'deki grafı tutar, tek yazıcıdır, yazma kurallarını uygular, MCP/REST/hook uçlarını sunar. **LLM çağırmaz.** | systemd user servisi, `127.0.0.1:7411` |
| **kb istemcisi** | CLI + TUI, `kb run` ile secret kullanımı, `kb secret set/show` | GraalVM native binary |
| **Masaüstü GUI** (`kb-gui`) | Arama, düzenleme, graf görünümü, çelişkiler, öneriler, aktivite | Dear ImGui, ayrı modül; `/api/v1` kullanır (ADR-0021) |
| **Küratör ajan** | Grafın yapısını düzenler; diğer ajanlarla konuşmaz | Zamanlanmış headless Claude Code |

Mimari desen **karatahta**: ajanlar ve hook'lar grafa bilgi ve sinyal bırakır, küratör düzenler, herkes aynı grafı okur.
Ajanlar birbirleriyle konuşmaz.

## 3. Hedefler

| # | Hedef | Ölçüt |
|---|---|---|
| H1 | Ajan bilgi sormadan önce bilgi bankasına bakar | Audit'te "aramadan soru sorulan tur" oranı < %10 |
| H2 | Proje incelenirken altyapı bilgisi otomatik toplanır | Örnek repoda beklenen host/port/datasource bilgilerinin ≥ %95'i doğru ortamla yazılır |
| H3 | Kullanıcının cevapları kalıcı olur | Sorulan ve cevaplanan bilgi aynı oturumda `kb_remember` ile yazılır |
| H4 | İlişkiler sorgulanabilir | "X'e bağlı her şey" tek `kb_related` veya `kb_cypher` çağrısı |
| H5 | Bilgi yerelde, bulut sadece senkron | Sunucu sadece `127.0.0.1`'e bağlanır; senkron GitHub (private) |
| H6 | Secret değeri ajana ve bu bilgisayarın dışına çıkmaz | Taranan repodaki parola anlık görüntü, git, log ve araç çıktılarının hiçbirinde yok; ajan onu sadece placeholder ile kullanır |
| H7 | Otomatik yazmalar güvenli | Doğrulanmış bilgi sessizce ezilmez; hiçbir fact silinmez; parti tek komutla geri alınır |
| H8 | Eski bilgi taze bilgi gibi kullanılmaz | Her fact kaynağı ve `observed_at` ile görünür; `kb_stale` |
| H9 | Emekli (ADR-0019) | — |
| H10 | Bilgi bankası kendini düzenler ama bozulmaz | Küratör partisi eval skorunu düşürürse otomatik geri alınır |
| H11 | Sade | Bir kişi bütün tasarımı bir günde okuyup anlayabilir (ADR-0016) |
| H12 | Büyüdükçe işlevini ve bilgisini kaybetmez | Hiçbir kayıt silinmez ya da gizlenmez; aramada güncel bilgi önce, eski değer ve arşiv işaretli olarak sonra gelir; tek çağrının çıktısı bütçeyle sınırlı; `kb check` büyümeyi raporlar (ADR-0017) |

## 4. Kapsam dışı

- **Sunucu içinde LLM.** Zekâ ajanlarda ve küratörde.
- **Secret değerlerinin cihazlar arası senkronu.** Değer sadece bu bilgisayarda (bkz. SECRETS.md).
- **Ham konuşma ve dosya kopyası grafta.** Grafa damıtılmış bilgi girer; saklanması gereken ham dosyalar doküman
  deposunda (ADR-0017).
- **Kendi sürümleme sistemi, Merkle ağacı, blockchain.** Git yeterli.
- **Markdown dosyası olarak saklama ve Obsidian ile düzenleme.** Markdown sadece görünüm.
- **Oturum kaydı arşivi, kod grafiği.** Gerekirse deja-vu, Graphify.
- **Ajanlar arası koordinasyon (görev, mesajlaşma).** Ayrı proje; o proje agentkb'ye API ile bağlanır.
- **Çok kullanıcılı sunucu (v1).**

## 5. Senaryolar

| # | Senaryo | Beklenen davranış |
|---|---|---|
| S1 | Ajan `ornek-proje` reposunda oturum açar | SessionStart: brief → proje, komşular, repoya uyan kararların tek satırları, bayat uyarıları. Repo son taramadan beri değiştiyse arka planda tarama |
| S2 | Ajan yeni bir repoyu inceler | `kb_scan(path)`: sunucu config dosyalarını kendisi okur; host/IP/port/datasource `scan` kaynağıyla yazılır; secret'lar `Secret` düğümüne yazılır (değer bu bilgisayardan çıkmaz); "repoda düz metin secret" bulgusu açılır |
| S3 | Kullanıcı "ornek-app-01 hangi LB arkasında?" der | UserPromptSubmit hook'u ilgili kayıtları zaten bağlama ekler; ajan `kb_read` ile doğrular |
| S4 | Ajanın bilmediği bir bilgi gerekir | Önce `kb_search`; yoksa kullanıcıya sorar; cevabı `kb_remember(source=user_stated, evidence=...)` ile yazar |
| S5 | Ajana bir parola gerekir | Ajan sohbette istemez: "`kb secret set ornek-db/prod/app-password` çalıştır" der; sonra `kb run --env DB_PASS=kb:secret/ornek-db/prod/app-password -- ...` ile kullanır, değeri görmez |
| S6 | Yeni tarama eski bir değerle çelişir | Düşük güvenli kaynak yüksek güvenliyi ezemez → çelişki; kullanıcı TUI/GUI'den çözer |
| S7 | IP değişir | Eski fact kapanır (`valid_to`), yenisi ona bağlanır; "eskiden IP neydi?" sorgulanabilir |
| S8 | Tüm Java projeleri için bir kural | `Decision` → `APPLIES_TO` → `Stack {id:'stack:java-spring'}`; sadece Java/Spring repolarında brief'e girer |
| S9 | Emekli (ADR-0019) | — |
| S10 | Emekli (ADR-0019) | — |
| S11 | Küratör çalışır | Sinyalleri okur, tekrarları birleştirir, soru-cevapları fact'e çevirir, öneri bırakır; eval düşerse parti geri alınır |
| S12 | İkinci bilgisayar | `kb sync`: anlık görüntüler birleşir (aynı kayıtta yenisi kazanır), graf güncellenir; secret değerleri bir kez `kb secret set` ya da tarama ile girilir |
| S13 | Kullanıcı bir runbook PDF'i ekler | `kb doc add runbook.pdf --about ornek-proje`: secret taraması, dosya `~/kb/blobs/`'a, grafa `Document`; ajan dosyayı okuyup çıkardığı bilgileri `kb_remember(document=…)` ile yazar |
| S14 | Ajan bir logda eski bir IP görür | `kb_search "192.0.2.20"`: sunucu `eski (2026-01-15 → 2026-05-10)` işaretiyle ve güncel IP'siyle gelir; ajan "bilgi bankasında yok" demez |
| S15 | Küratör bir soru-cevabı yanlışlıkla atar | Sinyal silinmez; TUI/GUI'de "Atılanlar" listesinde nedeniyle görünür; yeniden açılınca küratör bir sonraki çalışmada tekrar bakar |
| S16 | Sunucu kapalı | Ajan MCP'ye ulaşamaz; `~/kb/snapshot/*.jsonl`'ü salt okunur okur (en fazla 10 dk eski, secret değeri yok); yazmaz ve bunu kullanıcıya söyler |

## 6. Veri modeli

### 6.1 Düğümler

| Etiket | Ne | Başlıca alanlar |
|---|---|---|
| `Entity` + tür etiketi (`Server`, `Database`, `Service`, `Project`, `Network`, `Environment`, `Cluster`) | Sunucu, veritabanı, servis, proje… | `id` (kısa ad, ör. `ornek-app-01`), `aliases[]`, `status` (`current` \| `observed` \| `archived`), `summary` |
| `Fact` | Bir varlık hakkında tek bilgi | `id`, `key`, `value`, `env`, `source`, `valid_from`, `observed_at`, `valid_to`, `status` (`ok` \| `unverified` \| `conflict`), `evidence?`, `batch?` |
| `Decision` | Mimari karar veya kural | `id`, `rule`, `scope`, `date`, `review_by`, `status`, `body` |
| `Lesson` | Çıkarılmış ders | `id`, `title`, `body`, `date` |
| `Secret` | Secret değeri; ajan ona placeholder `kb:secret/<id>` ile atıf yapar | `id` (`<varlık>/<ortam>/<ad>`), `name`, `env`, `secret_value` (sadece bu bilgisayarda), `source`, `observed_at` |
| `SourceFile`, `Repo` | Bilginin geldiği dosya ve depo | `SourceFile {id:'<repo>:<yol>', repo, path}`, `Repo {id, remote}` |
| `Document` | Bilgi bankasının sakladığı ham dosya (PDF, runbook, not) | `id` (`doc:<sha256'nın ilk 12 hanesi>`), `sha256`, `name`, `mime`, `size`, `path` (`blobs/…`), `origin?`, `source`, `added_at` |
| `Stack`, `Topic` | Teknoloji yığını; varlık olmayan konu (TLS, ...) | `id` |
| `Proposal` | Onay bekleyen normatif değişiklik; neyi değiştirdiği `ABOUT` kenarıyla | `id`, `kind`, `content`, `evidence`, `by`, `status` |
| `Signal` | Küratör için ham sinyal; **cihaza yerel, anlık görüntüye girmez**, silinmez; aynı `kind` + `key` tek düğümdür | `id`, `kind`, `key`, `payload` (secret görünümlü değerler maskeli), `at`, `last_at`, `count`, `status` (`open` \| `done`), `outcome?` |

`body` alanları Markdown metindir. Her kayıtta ayrıca sunucunun yazdığı teknik `updated_at` vardır (senkron için).
Metin sınırları: `summary` 300, metin türündeki fact 500, `body` 4.000 karakter; aşan yazma reddedilir, uzun metin
doküman olarak eklenir (ADR-0017). Sınırlar ve şema doğrulaması sadece MCP ve REST'ten gelen yeni yazmalara uygulanır;
anlık görüntüden içeri alma ve restore hiçbir kaydı reddetmez, kırpmaz.

### 6.2 Kenarlar

| Kenar | Nereden → nereye |
|---|---|
| `HAS_FACT`, `HAS_SECRET` | Entity → Fact / Secret |
| `SUPERSEDES` | Fact → Fact, Document → Document (yeni → eski) |
| `FROM {line, commit}` | Fact / Secret → SourceFile; Fact / Decision / Lesson → Document (`line`/`commit` yok) |
| `IN`, `HAS_REPO` | SourceFile → Repo, Project → Repo |
| `TARGET` | Fact (port) → Entity ("8443/https bu proje için") |
| `APPLIES_TO` | Decision → Stack / Project |
| `ABOUT` | Decision / Lesson / Document → Entity / Topic; Proposal → değiştirdiği kayıt; Signal → ilgili kayıt (Fact, Entity, SourceFile, Decision; sinyalinki yerel) |
| `PRODUCED` | Signal → Fact / Proposal: sinyalden ne çıktı; sinyal gibi yerel, anlık görüntüye girmez |
| İlişki sözlüğü (`RUNS`, `DEPLOYED_ON`, `DEPENDS_ON`, `BEHIND`, `ROUTES_TO`, `PART_OF`, `AUTHENTICATES_WITH`, `OWNED_BY`, `RELATED`) | Entity → Entity; `{env?, source, valid_from, observed_at, valid_to}` |

İlişki türleri `schema/relations.yaml` izin listesinden gelir; eşanlamlılar (`uses` → `DEPENDS_ON`) yazarken çevrilir.

### 6.3 Sürümleme ve erişim kuralı

Fact, ilişki ve sinyaller silinmez; tek istisna `kb revert --batch`, geri alınan partinin açtığı kayıtları siler. Değer değişince eskisine `valid_to` yazılır ve yeni fact `SUPERSEDES` ile eskisine
bağlanır. Güncel bilgi, `valid_to`'su boş olandır. `valid_from` bilginin ilk görüldüğü andır ve hiç değişmez;
`observed_at` son görüldüğü andır ve aynı değer yeniden görüldükçe tazelenir. Geçmiş aralığı `valid_from → valid_to`'dur.
Varlık alanları (`aliases`, `summary`) üzerine yazılır; geçmişleri git anlık görüntülerinde durur. Birleştirilen
varlığın kimliği alias olarak kalır, eski adla arama da onu bulur. Secret değeri tek istisnadır: değişince eskisi
hiçbir yerde tutulmaz.

**Gizleme yok; işaretle ve sırala (ADR-0017).** Zaman (`valid_to`) ve durum (`status`) birer alandır. Arama, hatırlatma
ve `kb_related` onlara göre süzmez, sıralar:
1. Önce güncel bilgi; işaretsiz.
2. Sonra geçmiş: eski değer, biten ilişki, arşivli varlık; `eski (2026-01-15 → 2026-05-10)` ya da `arşiv` işaretli.
   Eski bir değer eşleşirse yanında güncel değer de yazar.
3. En son bilgi taşıyan ham sinyaller (`qa-capture`, `imported-memory`); `ham` işaretli.

Bütçe (sonuç sayısı, 8.000 karakter) sıralamadan sonra uygulanır; kesilirse sonda `(+N sonuç daha)` yazar. Böylece graf
büyüse de en üstte güncel bilgi durur ve hiçbir kayıt erişilmez hale gelmez. Brief şu anın özetidir ama arşivli
komşuyu düşürmez; geçmişi `kb_read`'in "Geçmiş" bölümü gösterir.

### 6.4 Kararlar

`Decision`: `scope` (`global` \| `project`), `APPLIES_TO` ile stack veya proje, tek satırlık `rule`, `review_by`. Brief sadece repoda
algılanan stack'e ve projeye uyan kararların `rule` satırlarını verir. Sadece eve özgü kurallar yazılır.

### 6.5 Şema

`schema/types.yaml` (varlık türleri), `schema/relations.yaml` (ilişki sözlüğü), `schema/facts.yaml` (fact anahtarları,
doğrulayıcılar, ortam eşanlamlıları). Şema anlık görüntüyle aynı git reposunda durur (`kb init` varsayılanı
`seed/schema/`'dan kopyalar); değişikliği bir `Proposal`'dır.

### 6.6 Ajana ve insana görünüm

Okuma araçları kaydı Markdown olarak render eder. Örnek (`kb_read ornek-app-01`):

```markdown
# ornek-app-01 · Server · current
Örnek uygulama sunucusu.

## Bilgiler
- hostname: ornek-app-01.ornek.example @prod · user, 2026-05-10
- ip: 192.0.2.21 @prod · user, 2026-05-10
- ip: 192.0.2.25 @prod · agent, 2026-10-28 · ÇELİŞKİ (192.0.2.21 korunuyor)
- port: 8443/https → ornek-proje @prod · scan, 2026-10-29 · ornek-proje:src/main/resources/application-prod.yml:2

## İlişkiler
- RUNS → ornek-proje · BEHIND → ornek-lb · PART_OF → ornek-prod-ortami

## Secret'lar
- deploy-ssh-key @prod · kb:secret/ornek-app-01/prod/deploy-ssh-key · kullan: kb run --file KEY=… -- <komut>

## Geçmiş
- ip: 192.0.2.20 @prod · user, 2026-01-15 → 2026-05-10
```

"Geçmiş" bölümü kapanmış fact'leri ve biten ilişkileri gösterir. Arama sonuçlarında işaretsiz satır güncel bilgidir
(`kb_search "192.0.2.20"` ve `kb_search "uygulama sunucusu"`):

```markdown
1. ornek-app-01 · Server — ip: 192.0.2.20 @prod · eski (2026-01-15 → 2026-05-10) · güncel: 192.0.2.21

1. ornek-app-01 · Server — Örnek uygulama sunucusu.
2. ornek-app-00 · Server · arşiv — Eski uygulama sunucusu; uygulamalar 2026-05'te ornek-app-01'e taşındı.
```

### 6.7 Ham dosyalar (ADR-0017)

Başka yerde silinebilecek ya da değişebilecek dosyaların (PDF, runbook, toplantı notu) kopyası bilgi bankasında durur:
- Dosya `~/kb/blobs/<sha256'nın ilk 2 hanesi>/<sha256>` altında ve değişmez. Grafa dosyanın kendisi değil, onu
  temsil eden `Document` düğümü girer.
- `kb doc add <dosya> [--about <id>] [--origin <url>]`: secret taraması yapılır, bulunursa dosya reddedilir; aynı içerik
  ikinci kez eklenmez; 20 MB üstü dosyalar `git lfs track` ile eklenir (git-lfs gerekir).
- Ajan dosyayı `kb_read doc:…` çıktısındaki yoldan kendisi okur; çıkardığı bilgileri `kb_remember(…, document=…)` ile
  yazar, bunlar `FROM` ile dokümana bağlanır.
- Yeni sürüm yeni dosyadır ve `SUPERSEDES` ile eskisine bağlanır.

## 7. Yazma politikası (ADR-0006)

Önceden onay yok; sonradan inceleme ve geri alma var. Her kayıt kaynağını taşır.

| Kaynak | Güven | Örnek | Davranış |
|---|---|---|---|
| `user` | 4 | TUI/GUI/CLI | Doğrudan yazılır |
| `user_stated` | 3 | Ajanın kullanıcıdan aldığı cevap, kanıtı doğrulandı | Doğrudan yazılır |
| `scan` | 2 | Deterministik tarayıcı | Doğrudan yazılır; yeni varlık `status: observed` |
| `agent` | 1 | Ajanın çıkarımı veya doğrulanamayan "kullanıcı söyledi" | Doğrudan yazılır, `status: unverified` |
| `curator` | — | Hijyen (birleştirme, ilişki düzeltme, yapılandırma) | Doğrudan; yeni bilgi üretemez |

Kurallar:
1. Aynı anahtar + ortam + değer → sadece `observed_at` tazelenir; `valid_from` ve yüksek güvenli kaynak korunur.
2. Farklı değer, yeni kaynağın güveni ≥ mevcut → eski fact kapanır, yeni fact `SUPERSEDES` ile bağlanır (eskinin
   `valid_to`'su yeninin `valid_from`'u olur).
3. Farklı değer, yeni kaynağın güveni < mevcut → yeni fact `status: conflict` ile yazılır, `conflict` sinyali açılır;
   mevcut değer güncel kalır.
4. Aynı tarama kaynağından (aynı repo + dosya) gelen yeni değer her zaman eskisini kapatır (config değişmiştir).
5. Çok değerli anahtarlarda (`ip`, `port`) ekleme yapılır; kapatma sadece kullanıcı veya aynı kaynağın yeniden taranmasıyla.
6. **Normatif değişiklikler** (karar ekleme/değiştirme, silme/arşiv, şema) doğrudan yazılmaz: `Proposal` → kullanıcı onayı.
7. `user_stated` kanıtı son 2 saatin kullanıcı mesajlarında (PromptRing, sadece RAM) geçmelidir; geçmezse kaynak `agent`.
8. Secret olmayan bir anahtarda secret görünümlü değer reddedilir (`kb secret set` ipucuyla).
9. Secret değeri geçmişsiz tek alandır: değişince eskisi tutulmaz. Tarayıcı kullanıcının yazdığı değeri değiştirmez;
   farklı bir değer bulursa `conflict` sinyali açar (değer sinyale girmez).
10. Metin sınırını aşan yazma reddedilir (`kb doc add` ipucuyla).

## 8. Otomatik bilgi toplama

| Yol | Tetikleyici | Ne toplar |
|---|---|---|
| **Tarayıcı** (`kb_scan`, `kb scan`) | Ajan, SessionStart (repo değiştiyse), kullanıcı | Spring `application*.yml/properties`, `.env`, docker-compose, Kubernetes, Ansible envanteri, `pom.xml`/`build.gradle`. Secret'lar `Secret` düğümüne |
| **`kb_remember`** | Ajan, öğrendiği anda | Koddan/dokümandan çıkarımlar, kullanıcı cevapları |
| **Hook yakalama** | PostToolUse: AskUserQuestion, `request_user_input` | Ham soru-cevap → sinyal (secret görünümlü değerler maskelenir); işlenene kadar aramada `ham` olarak bulunur, küratör yapılandırır |
| **Kullanıcı** | TUI/GUI | Her şey |
| **Doküman** | `kb doc add` (kullanıcı) | Ham dosyanın kopyası; ajan içinden bilgi çıkarır |

Tarayıcı sadece `agentkb.scan.allowed-roots` altını okur. Ayrıntılar: ARCHITECTURE.md §6.

## 9. Secret yönetimi (ADR-0015)

Değer `Secret` düğümünde durur ama bu bilgisayardan çıkmaz: anlık görüntüye, git'e, loglara ve araç çıktılarına
girmez. Ajanlar değeri görmez; placeholder (`kb:secret/<id>`) kullanır, `kb run` onu alt sürecin ortam değişkenine
çözer. Ajan secret bilmiyorsa sohbette istemez, kullanıcıdan `kb secret set` çalıştırmasını ister. Değerler
bilgisayarlar arasında senkronlanmaz. Ayrıntılar: SECRETS.md.

## 10. Anlık görüntü ve senkron (ADR-0014)

- 10 dakikada bir ve kapanışta graf `~/kb/snapshot/nodes.jsonl` ve `edges.jsonl` olarak yazılır: satır başına bir kayıt,
  `id`'ye göre sıralı, sabit alan sırası. Değişiklik varsa commit'lenir. Sinyaller ve secret değerleri
  (`secret_value`) yazılmaz; içeri alırken yereldeki değer korunur. `blobs/` aynı repoda durur.
- `kb sync`: uzak dalı çeker, kayıtları `id`'ye göre birleştirir (`updated_at`'i yeni olan kazanır), grafa uygular,
  yeni anlık görüntüyü uzak dalın üstüne commit'leyip gönderir. Commit'lemeden önce yazdığı satır sayısını graftaki
  kayıt sayısıyla karşılaştırır (sinyaller hariç); tutmazsa commit'lemez ve hata verir. Hiçbir zaman force-push
  yapmaz; GitHub'da ana dal korumalıdır (private `~/kb` deposu), böylece geçmiş yeniden yazılamaz.
- `kb restore [--at <commit>]`: grafı bir anlık görüntüden yeniden kurar; anlık görüntüde olmayan yerel veriler
  (secret değerleri, sinyaller) korunur.
- Sunucu kapalıyken ajanlar anlık görüntüyü salt okunur dosya olarak okuyabilir (en fazla 10 dk eski; AGENT-RULES).

## 11. Fonksiyonel gereksinimler

### 11.1 Arayüzler
- **MCP — çalışan ajanlar** (`/mcp`): `kb_brief`, `kb_search`, `kb_read`, `kb_related`, `kb_cypher`,
  `kb_schema`, `kb_stale`, `kb_remember`, `kb_scan`, `kb_propose`. Bkz. MCP-TOOLS.md.
- **MCP — küratör** (`/mcp/curator`): okuma araçları + `cur_*` araçları. Bkz. CURATOR.md.
- **REST** (`/api/v1`): CLI, TUI ve masaüstü GUI. **Hook uçları** (`/hooks/*`).
- Okuma araçları Markdown döner, en fazla 8.000 karakter. Hiçbir kaydı gizlemez: güncel bilgi önce, geçmiş ve ham
  sinyaller işaretli olarak sonra gelir (§6.3). Secret değeri hiçbir okuma aracından dönmez; secret'lar
  placeholder'larıyla görünür.

### 11.2 "Önce bilgi bankası" akışı
1. UserPromptSubmit hook'u her kullanıcı mesajında arar; skor eşiğini geçen en fazla 3 sonucu bağlama ekler.
2. Ajan talimatı: bilgi sormadan önce `kb_search`.
3. Cevap gelince `kb_remember` (kaynak `user_stated`, kanıtla).
4. Hook yakalama yedek ağdır.

### 11.3 Emekli (ADR-0019)

### 11.4 Küratör
Zamanlanmış headless Claude Code; sadece `/mcp/curator`; değişiklikler parti halinde; parti sonunda eval; gerilerse
parti geri alınır. Bkz. CURATOR.md.

### 11.5 Yerleşik hafızalar
`kb import-memories` ile içeri alınır (sinyal), sonra Claude Code auto memory ve Codex Memories kapatılır (ADR-0013).

## 12. Fonksiyonel olmayan gereksinimler

| Konu | Gereksinim |
|---|---|
| Performans | `kb_search` p95 < 100 ms; brief ve recall < 300 ms; 10.000 kayıtlık anlık görüntüden restore < 30 sn |
| Tarama | 500 dosyalık Spring repo < 5 sn |
| Kaynak | Sunucu heap ≤ 512 MB; Neo4j page cache 128 MB; `kb` native binary < 50 MB |
| Ağ | Sadece `127.0.0.1`; dışarıya tek çıkış `kb sync`'in git'i |
| Dayanıklılık | Çökmeye karşı Neo4j işlem günlüğü; disk kaybına karşı anlık görüntü (en fazla 10 dk kayıp) |
| Denetim | Her kayıt `source` ve `observed_at` taşır; her araç çağrısı `audit.jsonl`'e (içerik değil) |
| Hook | KB hook'ları 2 sn'de biter, hata durumunda boş döner (fail-open) |

## 13. Güvenlik gereksinimleri

1. **Bağlanma:** sadece `127.0.0.1`.
2. **Token ve kapsamlar:** v2'ye kaldı (ADR-0020). Uçlar ayrı durur; küratör sınırları ve Cypher kısıtları kodda kalır.
3. **DNS rebinding:** `OriginGuard` (servlet `Filter`, `/*`): `Origin` başlığı varsa ret; `Host` izin listesi.
   `server.address` loopback değilse sunucu açılmayı reddeder (ADR-0020).
4. **Secret:** değer anlık görüntü, git, log, audit, MCP/REST okuma çıktısı ve hata mesajlarına girmez; sadece bu
   bilgisayardaki grafta durur. Değeri sadece tek uç döndürür. `kb_remember` secret görünümlü değeri
   reddeder; hook yakalaması onu sinyale yazmadan maskeler. Veri dizini 0700.
5. **`kb_cypher`:** EXPLAIN ile READ_ONLY kontrolü; `LOAD CSV` ve yönetim prosedürleri yasak; `secret_value` geçen
   sorgu reddedilir ve sonuçlardan bu alan çıkarılır; 5 sn, 200 satır.
6. **Tarama:** sadece izinli kökler.
7. **Prompt injection:** kayıt içerikleri ve sinyaller veridir; normatif değişiklikler sadece onayla; küratörün shell,
   dosya ve ağ erişimi yok.

## 14. Lisans ve yayın

Neo4j Community gömülü olduğu için lisans **GPL-3.0-only**. Araç reposu ile anlık görüntü reposu ayrıdır; anlık
görüntü reposu asla public olmaz. Kontrol listesi: PUBLISHING.md.

## 15. Kabul ölçütleri (v1.0)

- [ ] Değerlendirme seti (`eval/questions.yaml`, ≥ 30 soru): ilk 3'te doğru kayıt ≥ %85.
- [ ] Tarayıcı fixture'ı: beklenen bilgilerin ≥ %95'i; sahte secret dizeleri hiçbir çıktıda (anlık görüntü, git, log,
      MCP) yok.
- [ ] Çelişki: düşük güvenli kaynak yüksek güvenliyi ezemiyor (test).
- [ ] Sürüm: değer değişince eski fact kapanıyor, "eskiden ne idi" sorgusu çalışıyor (test).
- [ ] `valid_from`: aynı değer yeniden görülünce değişmiyor; eski değerin aralığı `valid_from → valid_to` (test).
- [ ] Sinyal tekrarı: aynı `kind` + `key` ikinci kez gelince yeni düğüm açılmıyor, `count` artıyor;
      `kb import-memories` iki kez çalışınca sinyal sayısı değişmiyor (test).
- [ ] `kb sync` satır sayısı graftakiyle tutmayan bir anlık görüntüyü commit'lemiyor (test).
- [ ] Anlık görüntü → restore aynı grafı veriyor; iki cihaz senkronunda hiçbir kayıt kaybolmuyor (test).
- [ ] Kayıpsızlık: restore ve senkrondan sonra yereldeki secret değerleri ve sinyaller duruyor; sınırdan uzun eski bir
      kayıt içeri alınırken reddedilmiyor; geri alınan partinin kapattığı sinyaller yeniden açılıyor; kapanan sinyal
      silinmiyor, hiçbir şey üretmediyse "Atılanlar"da görünüyor ve yeniden açılabiliyor (test).
- [ ] `kb run`: değer alt sürece gidiyor; çıktıda düz, base64 ve URL-encoded hali maskeleniyor (test).
- [ ] Sahte secret değeri anlık görüntüde, git geçmişinde, loglarda, sinyallerde, MCP ve Cypher çıktılarında yok (test).
- [ ] Erişim ve şişme: eski IP ile arama sunucuyu `eski` işaretiyle ve güncel değerle buluyor; arşivli varlık aramada
      ve `kb_related`'da `arşiv` işaretli; güncel sonuçlar her zaman önce; sınırı aşan metin reddediliyor; `kb check`
      büyüme raporu veriyor (test, eval q16–q18).
- [ ] `kb doc add`: secret içeren dosya reddediliyor; aynı dosya ikinci kez eklenmiyor (test).
- [ ] Yanlış `Host` veya `Origin` ile gelen istekler ve loopback dışı açılış reddediliyor (test).
- [ ] TUI: arama, görüntüleme, ekleme/düzenleme, arşivleme, çelişki çözme, öneri onayı.
- [ ] Küratör: eval'i düşüren parti otomatik geri alındı (test).
- [ ] Sunucu kapalıyken iki ajanda da oturum açılıyor.
