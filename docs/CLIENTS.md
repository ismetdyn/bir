# İstemciler: REST API, CLI, TUI, masaüstü GUI (v0.3)

Tek API, çok istemci. Ajanlar MCP kullanır; sen CLI/TUI ve masaüstü GUI kullanırsın. Hepsi aynı yazma politikasından geçer;
senin yazdıkların `user` kaynaklıdır (en yüksek güven).

## 1. REST API v1 (`/api/v1`)

| Metot | Yol | Ne |
|---|---|---|
| GET | `/search?q=&type=&env=&limit=` | Arama (güncel önce, geçmiş işaretli) |
| GET | `/entities/{id}` | Varlık (Markdown veya JSON): güncel fact'ler, ilişkiler, secret adları, geçmiş |
| POST | `/entities` · PATCH `/entities/{id}` | Yeni varlık; alias ve özet |
| POST | `/entities/{id}/facts` | Fact ekle/değiştir (değişen eski fact kapanır) |
| POST | `/facts/{id}/close` | Fact'i kapat (silme yok) |
| POST | `/entities/{id}/relations` · POST `/relations/{id}/close` | İlişki |
| POST | `/cypher` | Salt okunur sorgu |
| GET | `/conflicts` · POST `/conflicts/{id}/resolve` | Çelişkiler (`accept-new` / `keep-old`) |
| GET | `/proposals` · POST `/proposals/{id}/accept` · POST `/proposals/{id}/reject` | Öneriler (karar, kural, arşiv, şema) |
| GET | `/signals?status=&kind=` · POST `/signals/{id}/reopen` | Sinyaller; atılanlar (hiçbir şey üretmeden kapananlar) ve yeniden açma |
| GET | `/activity?source=&batch=&since=` | Son yazmalar |
| POST | `/revert` | `{batch}` |
| GET | `/secrets?prefix=` | Secret listesi (ad, ortam, placeholder, bu bilgisayarda değeri var mı; değer yok) |
| PUT | `/secrets/{id}` | Değeri yaz (`kb secret set`); değer bu bilgisayardan çıkmaz |
| GET | `/secrets/{id}` | Değer; sadece `kb run` ve `kb secret show` kullanır |
| POST | `/documents` · GET `/documents/{id}` | Doküman ekle (secret taraması; `blobs/`'a yazar) · üst bilgi ve yol |
| POST | `/scan` | `{path}` tarama |
| GET | `/stale?days=` | Bayat liste |
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
| `kb activity [--source agent\|scan\|curator]` · `kb revert --batch <id>` | Aktivite ve geri alma |
| `kb scan <yol>` | Tarama |
| `kb doc add <dosya> [--about <id>] [--origin <url>]` · `kb doc list` | Doküman deposu (bkz. SPEC §6.7) |
| `kb secret set\|list\|rm <id>` · `kb secret show <id>` | Secret (set maskeli giriş; show sadece insan için) |
| `kb run --env AD=kb:secret/<id> [--file AD=…] -- <komut>` | Placeholder'ı ortam değişkenine çözüp komutu çalıştırır (bkz. SECRETS.md) |
| `kb sync` · `kb restore [--at <commit>]` | Anlık görüntü, senkron, geri yükleme |
| `kb stale` · `kb check` · `kb doctor` · `kb eval` | Bakım; `kb check` tutarlılık sorunlarını ve büyüme raporunu verir |
| `kb curator run [--if-needed]` · `kb import-memories` | Küratör |
| `kb tui` · `kb gui` | Arayüzler |

Çıkış kodları: 0 başarı, 1 hata/bulgu, 3 sunucuya ulaşılamıyor.

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
