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
| POST | `/revert` | `{batch}` |
| GET | `/secrets?prefix=&limit=` | Secret listesi (ad, ortam, placeholder, bu bilgisayarda değeri var mı; değer yok); `limit` varsayılan 50, en çok 500 |
| PUT | `/secrets/{id}` | Değeri yaz (`kb secret set`); değer bu bilgisayardan çıkmaz |
| GET | `/secrets/{id}` | Değer; sadece `kb run` ve `kb secret show` kullanır |
| PUT · PATCH · DELETE | `/uploads/{sha}` | Sürdürülebilir yükleme (ADR-0025 karar 1): `PUT` + `Upload-Length` başlatır/offset verir, `PATCH` (`application/offset+octet-stream`, ≤ 1 MiB) `Upload-Offset`'ten devam eder, `DELETE` iptal eder |
| POST | `/documents` `{sha256,name,about?,origin?,replaces?}` | Yüklemeyi tamamla: hash ve secret taraması, `blobs/`'a yazar, `Document` düğümü |
| GET | `/documents` · `/documents/{id}` | Doküman listesi · üst bilgi ve yol |
| POST | `/scan` | `{path}` tarama (yalnız `allowed-roots` altı). Yazma kuralının istisnası: kaynak `scan`, kendi `kind=scan` Episode'u (ADR-0023 #3; ADR-0025 karar 9), çünkü USER kaynaklı tarama yüksek güvenli değerleri ezebilirdi |
| GET | `/stale?days=&limit=` | Bayat liste |
| POST | `/sync` · POST `/restore` · GET `/doctor` · GET `/eval` · GET `/check` | Bakım |
| GET | `/curator/should-run` · GET `/curator/runs` | Küratör |

## 2. CLI (`kb`)

| Komut | Ne |
|---|---|
| `kb init` | Dizinler (veri dizini 0700), `~/kb` reposu (oluştur ya da klonla), şema |
| `kb ? <sorgu>` | Hızlı kontrol: var mı, tek satırda ana bilgiler |
| `kb find <sorgu>` · `kb show <id>` | Arama, kayıt detayı (Markdown) |
| `kb add <tür> <id>` · `kb edit <id>` · `kb archive <id>` | Varlık |
| `kb fact set <id> <anahtar> <değer> [--env]` · `kb fact close <factId>` | Fact |
| `kb rel add <id> <tür> <hedef>` · `kb rel close <relId>` | İlişki |
| `kb conflicts` · `kb resolve <id> --accept-new\|--keep-old` | Çelişkiler |
| `kb proposals` · `kb accept <id>` · `kb reject <id> -m "..."` | Öneriler |
| `kb activity [--source agent\|scan\|curator]` · `kb revert --batch <id>` | Aktivite ve geri alma. Partinin açtığı ve hâlâ ona ait Secret değeriyle silinir, çıktı sayısını verir. `kb secret set` ile değer yazılan Secret kullanıcıya geçer ve geri almada kalır (ADR-0022 karar 39) |
| `kb scan <yol>` | Tarama |
| `kb doc add <dosya> [--about <id>] [--origin <url>] [--replaces <docId>]` · `kb doc list` | Doküman deposu (bkz. SPEC §6.7). Yükleme byte offset'li ve sürdürülebilir; yeniden çalıştırma kaldığı yerden devam eder, istemci durum tutmaz. Protokol: ADR-0025 karar 1. `--replaces` yeni dokümanı eskisine `SUPERSEDES` ile bağlar. Sıkıştırılmış dosyada secret taraması yapılamaz, komut uyarır |
| `kb secret set <id>` · `kb secret show <id>` | Secret (set maskeli giriş; show sadece insan için). F2'de yalnız bu ikisi çıkar (`GET/PUT /secrets/{id}`). `kb secret list` `GET /secrets?prefix=` ister, F3'tedir. `rm` için uç ve silme politikası tanımlı değil (SPEC değişmez 5); tanımlanana kadar yok |
| `kb run --env AD=kb:secret/<id> [--file AD=…] -- <komut>` | Placeholder'ı ortam değişkenine çözüp komutu çalıştırır (bkz. SECRETS.md) |
| `kb sync` · `kb restore [--at <commit>]` | Anlık görüntü, senkron, geri yükleme. Anlık görüntü satırındaki `secret_value` ve `props.id` yüklenmez (değişmez 2); çıktı yok sayılan değer sayısını verir |
| `kb stale` · `kb check` · `kb doctor` · `kb eval` | Bakım; `kb check` tutarlılık sorunlarını ve büyüme raporunu verir (F2: yalnız tutarlılık + `git fsck`; F3: `missing-blob`, `blob-hash-mismatch`). `kb doctor` (`GET /doctor`): git, git-lfs, repo ve LFS `--local` filtresi, veri dizini 0700 |
| `kb curator run [--if-needed]` · `kb import-memories` | Küratör |
| `kb tui` · `kb gui` | Arayüzler. `kb tui` TamboUI 0.6.0 (F3; `--no-alt-screen` destekler); `kb gui` F5 |

Çıkış kodları: 0 başarı, 1 hata/bulgu, 3 sunucuya ulaşılamıyor.

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
