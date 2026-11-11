# İstemciler: REST API, CLI, TUI, masaüstü GUI (v0.3)

Tek API, çok istemci. Ajanlar MCP kullanır; sen CLI/TUI ve masaüstü GUI kullanırsın. Hepsi aynı yazma politikasından geçer;
senin yazdıkların `user` kaynaklıdır (en yüksek güven).

## 1. REST API v1 (`/api/v1`)

F2'de yalnız şu alt küme çıkar (ADR-0022): `GET/PUT /secrets/{id}`, `POST /revert`, `POST /sync`, `POST /restore`,
`GET /check`, `POST /signals/imported-memories` (yeni; tabloda yok). Kalanı F3'tedir (`/signals*` ve `/curator/*` F4'e kalır: ADR-0025 karar 14); DTO'lar F3'te `kb-api` modülüne girer.

| Metot | Yol | Ne |
|---|---|---|
| GET | `/search?q=&type=&env=&limit=` | Arama (güncel önce, geçmiş işaretli); `limit` 1–20, varsayılan 8 (ADR-0017); yanıtta `more` kesilen sayıyı verir |
| GET | `/entities/{id}` | Varlık: `Accept`'te kalitesi 0'dan büyük ve JSON'unkinden düşük olmayan `text/markdown` → `MarkdownView`, değilse JSON (fact ve ilişki id'leriyle): güncel fact'ler, ilişkiler, secret adları, geçmiş. Bilinmeyen id 404 |
| GET | `/entities?type=&limit=` | Gözatma: aramasız bütün varlıklar (`id`, `type`, `status`, tek satır `summary`, `updatedAt`), id sırasıyla, arşivliler sonda ve işaretli (değişmez 12); `type` tür anahtarı ya da etiketi, büyük/küçük harf fark etmez (`/search` gibi); `limit` varsayılan 50, en çok 500; `more` kesilen sayı |
| GET | `/schema` | Şema, `kb_schema` ile aynı kaynak (`schema/*.yaml`): varlık türleri (`key`, `label`, `operational`), fact anahtarları (`multi`: metin anahtarı hep çok değerli; `validator`, `allowed` enum değerleri, `kind`, `target`), ilişki türleri (`type`, `description`, `synonyms`). İstemci şemayı kopyalamaz |
| POST | `/entities` `{id,type,facts?,relations?}` · PATCH `/entities/{id}` `{aliases?,summary?}` | Yeni varlık (fact'siz de olur: `kb add <tür> <id>`; yeni 201, bilinen 200); alias (en çok 20) ve özet |
| POST | `/entities/{id}/facts` `{facts}` | Fact ekle/değiştir (değişen eski fact kapanır) |
| POST | `/facts/{id}/close` | Fact'i kapat (silme yok) |
| POST | `/entities/{id}/relations` `{relations}` · POST `/relations/{id}/close` | İlişki |
| POST | `/entities/{id}/archive` · POST `/entities/{id}/unarchive` | Arşivle, arşivden çıkar (geri alınabilir; tekrarı ipuçlu ret) |
| POST | `/cypher` `{query}` | Salt okunur sorgu (`CypherGuard`; `kb_cypher` gibi en çok 200 satır ve 8.000 karakter, `truncated`; çalışırken hata veren ya da 5 sn'yi aşan sorgu 400) |
| GET | `/conflicts?limit=` · POST `/conflicts/{id}/resolve` `{resolution}` | Çelişkiler (`accept-new` / `keep-old`) |
| GET | `/proposals?limit=` · POST `/proposals/{id}/accept` · POST `/proposals/{id}/reject` `{note?}` | Öneriler (karar, kural, arşiv, şema); açıklar önce |
| GET | `/signals?status=&kind=` · POST `/signals/{id}/reopen` | Sinyaller; atılanlar (hiçbir şey üretmeden kapananlar) ve yeniden açma |
| GET | `/activity?source=&batch=&since=&limit=` | Son yazmalar, parti başına bir satır, yeniden eskiye; `limit` varsayılan 50, en çok 500 |
| GET | `/activity/{batch}?limit=` | Tek parti: aktivite satırı, açtığı/kapattığı/değiştirdiği kayıtlar (`changes`: `change`, `kind`, `id`, `entity`, `key`, `env`, `/search` parçası gibi kesilen tek satır `value`, `source`; secret değeri hiç yok), `revertible` ve `blockedBy` (önce geri alınacak sonraki partiler; şema kabulü hiç geri alınmaz); `limit` varsayılan 50, en çok 500; bilinmeyen ya da geri alınmış parti 404 `{error, hint}` |
| POST | `/revert` | `{batch}` |
| GET | `/secrets?prefix=&limit=` | Secret listesi (ad, ortam, placeholder, bu bilgisayarda değeri var mı; değer yok); `limit` varsayılan 50, en çok 500 |
| PUT | `/secrets/{id}` | Değeri yaz (`kb secret set`); değer bu bilgisayardan çıkmaz |
| GET | `/secrets/{id}` | Değer; sadece `kb run` ve `kb secret show` kullanır |
| PUT · PATCH · DELETE | `/uploads/{sha}` | Sürdürülebilir yükleme (ADR-0025 karar 1): `PUT` + `Upload-Length` başlatır/offset verir, `PATCH` (`application/offset+octet-stream`, ≤ 1 MiB) `Upload-Offset`'ten devam eder, `DELETE` iptal eder |
| POST | `/documents` `{sha256,name,about?,origin?,replaces?}` | Yüklemeyi tamamla: hash ve secret taraması, `blobs/`'a yazar, `Document` düğümü |
| GET | `/documents?limit=` · `/documents/{id}` | Doküman listesi (en yeni önce, `limit` varsayılan 50, en çok 500; `{documents, more}`) · üst bilgi, blob'un mutlak yolu ve `about` (bilinmeyen id → 404 `{error, hint}`) |
| POST | `/scan` | `{path}` tarama (yalnız `allowed-roots` altı). Yazma kuralının istisnası: kaynak `scan`, kendi `kind=scan` Episode'u (ADR-0023 #3; ADR-0025 karar 9), çünkü USER kaynaklı tarama yüksek güvenli değerleri ezebilirdi |
| GET | `/stale?days=&limit=` | Bayat liste |
| POST | `/sync` · POST `/restore` · GET `/doctor` · GET `/eval` · GET `/check` | Bakım. `/doctor` → `{ok, checks: [{name, ok, hint}]}`: `git`, `git-lfs`, `repo`, `lfs-filter`, `data-dir`; ipucu yalnız başarısızda, yol vermez |
| GET | `/curator/should-run` · GET `/curator/runs` | Küratör |

## 2. CLI (`kb`)

| Komut | Ne |
|---|---|
| `kb init` | Dizinler (veri dizini 0700), `~/kb` reposu (oluştur ya da klonla), şema |
| `kb ? <sorgu>` | Hızlı kontrol: var mı, tek satırda ana bilgiler |
| `kb search <sorgu> [--type] [--env] [--limit]` (takma ad `kb find`) · `kb show <id> [--json]` · `kb cypher <sorgu>` | Arama, kayıt detayı (sunucunun Markdown'ı olduğu gibi; `--json` fact ve ilişki id'leriyle JSON görünümü), salt okunur sorgu (sütunlar sekmeyle ayrılır; hücredeki `\`, sekme ve satır sonu `\\`, `\t`, `\n` yazılır; kesilme notu stderr'e) |
| `kb add <tür> <id>` · `kb edit <id> [--alias <ad>]… \| [--clear-aliases] [--summary <metin>]` · `kb archive <id>` · `kb unarchive <id>` | Varlık (`--alias` verilirse listenin yerini alır, `--clear-aliases` listeyi boşaltır; ikisi birlikte verilemez) |
| `kb fact set <id> <anahtar> <değer> [--env]` · `kb fact close <factId>` | Fact |
| `kb rel add <id> <tür> <hedef>` · `kb rel close <relId>` | İlişki |
| `kb conflicts` · `kb resolve <id> accept-new\|keep-old` | Çelişkiler |
| `kb proposals` · `kb accept <id>` · `kb reject <id> -m "..."` | Öneriler |
| `kb activity [--source agent\|scan\|curator] [--batch <id>] [--since <zaman>] [--limit <n>]` · `kb revert --batch <id>` | Aktivite ve geri alma. Partinin açtığı ve hâlâ ona ait Secret değeriyle silinir, çıktı sayısını verir. `kb secret set` ile değer yazılan Secret kullanıcıya geçer ve geri almada kalır (ADR-0022 karar 39) |
| `kb scan <yol>` | Tarama |
| `kb doc add <dosya> [--name <ad>] [--about <id>] [--origin <url>] [--replaces <docId>]` · `kb doc list [--limit <n>]` · `kb doc show <docId>` | Doküman deposu (bkz. SPEC §6.7). Yükleme byte offset'li ve sürdürülebilir; yeniden çalıştırma sunucunun bildirdiği offset'ten devam eder, istemci durum tutmaz. Protokol: ADR-0025 karar 1. İstemci sha256'yı akışla hesaplar, en çok 1 MiB'lık parçalar gönderir; yükleme sırasında değişen dosya 1 ile reddedilir (kısalırsa istemci, içerik değişirse sunucunun 422'si). `PUT`'tan sonra bağlantı koparsa ya da `PATCH`/`POST` zaman aşımına uğrarsa çıkış 3 ve "connection lost during upload" mesajı; aynı komut devam ettirir. `POST /documents` dosya boyuyla büyüyen daha uzun bir süre bekler (sunucu tüm dosyayı hash'ler ve tarar). Yalnız normal dosya kabul edilir (FIFO, aygıt, dizin 1 ile reddedilir); dosya adı çıktıda kontrol karakterlerinden arındırılır. Sunucunun `Upload-Offset`'i 0'ın altında, dosya boyunun üstünde ya da `PATCH` sonrası ilerlemiyorsa 1 ile durur, başka istek atılmaz. `--name` verilmezse dosya adı gider; `--about` tek id alır, ek konu için komut yeniden çalıştırılır (aynı içerik yeni `ABOUT` kenarı açar). `--replaces` yeni dokümanı eskisine `SUPERSEDES` ile bağlar. Sıkıştırılmış dosyada secret taraması yapılamaz, komut uyarır (zip/docx/xlsx, gzip, PDF, 7z, xz, zstd, bzip2 imzası; ekleme başarılıysa stderr'e tek satır, çıkış kodu değişmez) |
| `kb secret set <id>` · `kb secret show <id>` · `kb secret list [--prefix <id>] [--limit <n>]` | Secret (set maskeli giriş; show sadece insan için; list id, placeholder ve bu bilgisayarda değer olup olmadığını verir, değeri asla). F2'de set ve show çıktı (`GET/PUT /secrets/{id}`); `kb secret list` (`GET /secrets?prefix=`) F3 H6'da. `rm` için uç ve silme politikası tanımlı değil (SPEC değişmez 5); tanımlanana kadar yok |
| `kb run --env AD=kb:secret/<id> [--file AD=…] -- <komut>` | Placeholder'ı ortam değişkenine çözüp komutu çalıştırır (bkz. SECRETS.md) |
| `kb sync` · `kb restore [--at <commit>]` | Anlık görüntü, senkron, geri yükleme. Anlık görüntü satırındaki `secret_value` ve `props.id` yüklenmez (değişmez 2); çıktı yok sayılan değer sayısını verir |
| `kb stale` · `kb check` · `kb doctor` · `kb eval` | Bakım; `kb check` tutarlılık sorunlarını ve büyüme raporunu verir (F2: yalnız tutarlılık + `git fsck`; F3: `missing-blob`, `blob-hash-mismatch`). `kb doctor` (`GET /doctor`): git, git-lfs, repo ve LFS `--local` filtresi, veri dizini 0700; her kontrol `ok`/`fail` (+ ipucu) satırı, biri başarısızsa çıkış 1 |
| `kb curator run [--if-needed]` · `kb import-memories` | Küratör |
| `kb tui` · `kb gui` | Arayüzler. `kb tui` TamboUI 0.6.0 (F3; `--no-alt-screen` destekler); `kb gui` F5 |

`kb --version` Maven `project.version`'ını yazar (`kb <sürüm>`).

Çıkış kodları: 0 başarı, 1 hata/bulgu/sunucu reddi ya da hatalı komut satırı, 3 sunucuya ulaşılamıyor.

`-` ile başlayan bir değer (negatif sayı hariç) seçenek sanılır ve komut 1 ile reddedilir; önüne `--` koy:
`kb fact set <id> flags -- -x`. `kb run` komut adından sonrasını zaten komuta verir. `.` ve `..` id olarak reddedilir.

F2'de `kb-client` JVM jar'dır; native-image ve TUI F3'tedir. `kb init` sunucu jar'ında, Neo4j açılmadan çalışır; `revert`,
`sync`, `restore`, `check`, `import-memories`, `run`, `secret` REST üzerinden gider. (ADR-0022).

## 3. TUI (TamboUI)

Neden TamboUI: Ratatui'den esinlenen bir Java TUI kütüphanesi; GraalVM ile anında açılan native binary üretiyor.
Risk: henüz 0.x sürümü. TUI ince bir istemci olduğu için gerekirse Go/Bubble Tea'ye geçmek ucuz.

```
┌ Ara: ornek prod__________________________ [tür: tümü] [ortam: prod] ┐
│ ● ornek-app-01   server    current   2026-05-10                     │
│ ● ornek-db       database  observed  2026-10-29 (scan)              │
│ ○ ders-ornek-tls-yaniltici-test  lesson                             │
├ ornek-app-01 ───────────────────────────────────────────────────────┤
│ Bilgi   hostname  ornek-app-01.ornek.example     user  2026-05-10   │
│         ip @prod  192.0.2.21                      user  2026-05-10   │
│         ip @prod  192.0.2.25  ÇELİŞKİ             agent 2026-10-28   │
│ Secret  deploy-ssh-key @prod · kb:secret/ornek-app-01/prod/deploy-… │
│ İlişki  BEHIND → ornek-lb · RUNS → ornek-proje                       │
└ / ara  ↵ aç  e düzenle  a fact  r ilişki  d arşiv  c çelişki  p öneri  l aktivite  s bayat  q çık ┘
```

| Ekran | Kısayol | İşlev |
|---|---|---|
| Arama + detay | `/`, `↵` | Yazarken arama, filtreler, detay paneli |
| Düzenleme | `e`, `a`, `r` | Şemaya göre form; ilişki türü ve hedef için otomatik tamamlama |
| Arşiv | `d` | Onaylı; geri alınabilir |
| Çelişkiler | `c` | Yan yana değer, kaynak, tarih; kabul/ret |
| Öneriler | `p` | Fark görünümü; kabul/ret |
| Aktivite | `l` | Kaynağa göre filtre; partiyi geri alma |
| Bayat | `s` | Doğrulanacaklar; "doğruladım" ile `observed_at` tazelenir |
| Sinyaller | `g` | Açık sinyaller ve "Atılanlar" (hiçbir şey üretmeden kapananlar, nedeniyle); yeniden açınca küratör tekrar bakar |

## 4. Masaüstü GUI (`kb-gui`)

Dear ImGui (`imgui-java`), ayrı `kb-gui` modülü; `kb gui` başlatır, `/api/v1` kullanır (ADR-0021). Ekranlar: TUI'nin
bütün ekranları, graf gezgini (ImNodes; komşuluk genişletme, ilişki türü filtresi), salt okunur Cypher konsolu, secret
listesi (sadece adlar), küratör çalışmaları ve eval geçmişi.

GUI secret değerini hiçbir zaman göstermez.
