# Riskler ve Bilerek Ertelenenler (v0.3)

Her madde bir karar veya görev doğurur. "Faz" sütunu önlemin hangi fazda uygulanacağını gösterir.

## A. Teknik riskler ve önlemler

| # | Konu | Neden önemli | Önlem | Faz |
|---|---|---|---|---|
| A1 | Graf artık ana depo | Disk kaybında veri kaybı | Neo4j işlem günlüğü (çökme); 10 dk'lık git anlık görüntüsü (disk kaybı); `kb restore` testi | F2 |
| A2 | İki cihazda eşzamanlı yazma | Aynı kayıt iki yerde değişir | `id` bazlı birleştirme, yenisi kazanır; fact'ler silinmediği için bilgi kaybolmaz; iki repo ile test | F2 |
| A3 | Gömülü Neo4j tek süreç kilidi | Sunucu açıkken CLI veritabanını açamaz | CLI her zaman REST API'yi kullanır | F2 |
| A4 | Bağımlılık çakışması | Boot 4 + Spring AI 2 + Neo4j test edilmemiş kombinasyon | F1 spike; `GraphStore` arayüzü; B planı ayrı Neo4j süreci | F1 |
| A5 | İki MCP uç noktası | Spring AI auto-config tek sunucu kurar | `/mcp/curator` MCP Java SDK ile elle; spike'ta doğrula | F1 |
| A6 | Yerel HTTP ≠ güvenli | DNS rebinding ile `127.0.0.1`'e istek; MCP SDK'larında CVE'ler çıktı | Token kapsamları + Host/Origin kontrolü + UI için CSRF | F2 |
| A7 | `kb_cypher` ile sızıntı | `LOAD CSV` dosya okur; `secret_value` sorgulanabilir | `LOAD CSV` yasak; `secret_value` geçen sorgu reddedilir ve sonuçtan çıkarılır | F2 |
| A8 | Prompt injection | Kayıt veya sinyal "şunu çalıştır" diyebilir | İçerik veridir uyarısı; normatif değişiklikler onaylı; küratörde shell/ağ yok | F2–F4 |
| A9 | Türkçe arama | Varsayılan analizör `İ/ı`'yı yanlış işler | Analizör seçimi spike + eval | F1 |
| A10 | Çıktı boyutu | Bağlamı doldurur | 8.000 karakter / 200 satır / kısaltma notu | F2 |
| A11 | Otomatik yazma kirliliği ve şişme | Her şeyi yazmak aramayı bozar; graf yıllar içinde gürültüyle dolar | Grafa sadece damıtılmış bilgi; metin sınırları; sıralama (güncel önce, geçmiş işaretli sonra) ve sabit çıktı bütçesi; kaynak sırası, `unverified`, küratör hijyeni, eval kapısı; `kb check` büyüme raporu (ADR-0017) | F2–F4 |
| A12 | Ajanın "kullanıcı söyledi" uydurması | Düşük güvenli bilgi yüksek güvenli görünür | `evidence` + PromptRing doğrulaması; doğrulanamazsa `agent` | F2 |
| A13 | Ortam karışması | dev değeri prod sanılır | Fact'te ortam zorunlu (bilinmiyorsa `default`); profil/namespace'ten çıkarım | F2 |
| A14 | Tarayıcıyla keyfi dosya okuma | Ajan `~/.ssh`'ı taratabilir | `allowed-roots`; sadece bilinen dosya türleri | F2 |
| A15 | Secret maskeleme atlatma | Kararlı ajan değeri kodlayarak yazdırabilir | Audit; asıl çözüm secret'ları repodan çıkarmak | F2 |
| A16 | Secret değeri diskte şifresiz | Aynı kullanıcıyla çalışan bir süreç veri dizinini okuyabilir; disk kaybında değer gider | Veri dizini 0700; değer git'e gitmez; asıl kaynak parola yöneticisinde | F2 |
| A17 | Token'ların ajanlarca okunması | `user` token'ı ile secret değerini alma | Token dosyaları 0600 | F2 |
| A25 | Dokümanla secret sızması | Eklenen PDF/not içinde parola olabilir; ajan dokümanı okur | `kb doc add` önce secret taraması yapar, bulursa reddeder | F3 |
| A26 | Bilgiye erişememe | Eski değer, biten ilişki ya da arşivli kayıt sonuçlardan süzülürse ajan "bilgi bankasında yok" sanar ve kullanıcıya yeniden sorar | Gizleme yok: güncel önce, geçmiş ve ham sinyal işaretli sonra; eski değer eşleşince güncel değer de yazılır; eval'de eski IP, arşivli varlık ve biten ilişki soruları (q16–q18) | F2 |
| A27 | İçeri almada kayıp | Sınırdan uzun ya da şema değişmeden önce yazılmış bir kayıt senkronda reddedilirse o bilgisayarda kaybolur | Sınırlar ve şema doğrulaması sadece yeni yazmalara; içeri alma ve restore hiçbir kaydı reddetmez (test) | F2 |
| A28 | Restore'da yerel verinin silinmesi | Restore grafı boşaltır; anlık görüntüde olmayan secret değerleri ve sinyaller giderdi | Restore yerel verileri ayırıp geri yazar (test) | F2 |
| A29 | Ham bilginin kaybı | Kapanan sinyaller silinseydi küratörün yanlışlıkla attığı ham cevap ya da içe aktarılan hafıza geri gelmezdi | Sinyaller silinmez; kapanınca `outcome` ve `PRODUCED`; atılanlar listede ve yeniden açılabilir; geri alma partinin kapattığı sinyalleri yeniden açar (test) | F4 |
| A30 | Doküman erişimi | Dosya silinir, bozulur ya da git-lfs olmayan bilgisayara işaretçi olarak gelir | `kb check` dosya ve sha256 kontrolü; `kb doctor` git-lfs kontrolü; LFS gönderimi başarısızsa `kb sync` hata verir; git geçmişinden geri alınır | F3 |
| A31 | Ham sinyalde secret | Kullanıcı bir soruya parolayla cevap verirse ham sinyal aramada geri gelebilir | Hook yakalaması secret görünümlü değeri sinyale yazmadan `****` ile maskeler; küratör yapılandırmaz, `kb secret set` önerir (test) | F2 |
| A32 | Anlık görüntünün sessizce eksik yazılması | Dışa aktarma bir kaydı atlarsa yedek eksik kalır ve bu fark edilmez | `kb sync` commit'ten önce satır sayısını grafla karşılaştırır, tutmazsa commit'lemez; `kb check` `git fsck` çalıştırır (test) | F2 |
| A33 | İlk görülme tarihinin kaybı | `observed_at` tazelendikçe bilginin ne zamandan beri geçerli olduğu kaybolur; geçmiş aralığı yanlış görünür | `valid_from` bir kez yazılır, hiç değişmez (test) | F2 |
| A34 | Sunucu kapalıyken bilgisizlik | Yerleşik hafızalar kapalı; sunucu çökerse ajanlar hiçbir şey bilmez | Ajanlar `~/kb/snapshot/*.jsonl`'ü salt okunur okur (en fazla 10 dk eski, secret yok); hook'lar fail-open | F2 |
| A18 | Emekli (ADR-0019) | — | — | — |
| A19 | Emekli (ADR-0019) | — | — | — |
| A20 | Emekli (ADR-0019) | — | — | — |
| A21 | Küratör bağlam çöküşü ve Goodhart | Yeniden yazımlar ayrıntıyı aşındırır; kapıları gevşetme | Artımlı düzenleme, parti sınırı; küratör kural/kapı yazamaz | F4 |
| A22 | Headless Claude Code bayrakları | Sürüme göre değişebilir | F4 başında doğrula | F4 |
| A23 | TamboUI ve GraalVM | 0.x sürüm; native ayarları | TUI ince istemci; B planı Go/Bubble Tea | F3 |
| A24 | Neo4j GPLv3 | Dağıtım yükümlülüğü | Proje GPL-3.0-only; LadybugDB (MIT) F1'de ölçülebilir | — |

## B. Operasyonel

- **Veri sınıflandırması:** sunucu envanteri ve topoloji hassastır. Anlık görüntü reposu GitHub'da private.
- **Model sağlayıcısına giden veri:** ajanın okuduğu her kayıt modele gider; ne yazdığına dikkat et.
- **Yayın:** bkz. PUBLISHING.md.
- **Yeniden kurulum:** `kb init` (repoyu klonlar, son anlık görüntüyü yükler) → `systemctl --user enable --now agentkb`
  → `kb doctor`. Secret değerleri anlık görüntüde olmadığı için `kb doctor` değeri eksik secret'ları listeler; onları
  `kb secret set` ile yeniden gir.
- **Geçmişin korunması:** GitHub'da anlık görüntü reposunun ana dalı korumalı, force-push kapalı; `kb sync`
  force-push yapmaz. Yerelde yanlışlıkla yeniden yazılan bir geçmiş uzaktakini silemez.

## C. Bilerek ertelenenler (ADR-0016)

Aşağıdakiler bir hata, ölçüm ya da açık istek olmadan eklenmez:

- Kayıt bazında erişim politikası ("bu kayıt modele gitmesin")
- Ek epistemik alanlar (gözlem / çıkarım / doğrulanmış ayrımının ötesi), ayrı `verified_at`, `confidence`
- İşlem kimlikleri ve indeks tutarlılık protokolü (tek temsil olduğu için gerek kalmadı)
- Sinyal yaşam döngüsü alanları (`open`/`done`, `outcome`, `key`/`count` ve `PRODUCED` yeterli)
- Sunucu kapalıyken yazma (okumak için ajanlar anlık görüntü dosyalarını kullanır)
- Secret değerlerinin bilgisayarlar arasında senkronu (gerekirse yerleşik anahtarla şifreleme; ADR-0015)
- Kullanıma göre önem puanı (hangi kayıt ne sıklıkla işe yaradı)

## D. Test stratejisi

| Seviye | Ne | Araç |
|---|---|---|
| Birim | WritePolicy, Markdown görünümü, okuma sıralaması ve işaretleri, secret desenleri, CypherGuard, `kb run` ve yakalama maskelemesi, metin sınırları | JUnit 5, AssertJ |
| Golden | `seed/` → beklenen görünümler; `fixtures/scanner` → beklenen fact'ler | Snapshot |
| Entegrasyon | Yazma servisi + Neo4j; anlık görüntü → restore; iki repo ile senkron | Neo4j test harness, geçici git repoları |
| Sözleşme | İki MCP uç noktasının `tools/list` ve şemaları; hook uçları | MCP Java SDK client, MockMvc |
| Güvenlik | Yazma Cypher'ları, `LOAD CSV`, `secret_value`, token'sız/yanlış kapsam/yanlış Origin, sahte secret dizelerinin bütün çıktılarda ve git geçmişinde aranması, secret içeren dokümanın reddi | Negatif testler |
| Değerlendirme | `eval/questions.yaml` | `EvalRunner`, CI eşiği |
