# agentkb — Mimari (v0.3)

## 1. Genel görünüm

```
 Çalışan ajanlar (Claude Code, Codex)                  Sen
   │ MCP /mcp                                           │ kb CLI/TUI ──► REST /api/v1
   │ hook'lar ─► /hooks/*                               │ kb-gui     ──► REST /api/v1
   │ kb run ─► /api/v1/secrets/{id} → alt süreç ortamı  │
   ▼                                                    ▼
┌──────────────── agentkb sunucusu (Spring Boot, 127.0.0.1:7411) ────────────────┐
│  Okuma: arama · brief · recall · ilişkiler · Cypher (salt okunur) → Markdown   │
│  Yazma: doğrula → politika → tek graf işlemi (tek yazıcı)                      │
│  Tarayıcı (deterministik) · secret değeri bu bilgisayardan çıkmaz              │
│  Gömülü Neo4j = bilgi bankasının kendisi (~/.local/share/agentkb/graph)        │
│  Anlık görüntü: 10 dk'da bir ~/kb/snapshot/*.jsonl → git → GitHub (private)    │
│  Doküman deposu: ~/kb/blobs/<sha256> (aynı git reposu)                         │
└──────────────────────────────────▲─────────────────────────────────────────────┘
                                   │ MCP /mcp/curator
                       Küratör (systemd timer → headless Claude Code)
```

İlke: **Sunucu deterministiktir ve tek yazıcıdır; LLM'ler (çalışan ajanlar ve küratör) sunucunun dışındadır.**

## 2. Modüller ve teknoloji

```
agentkb/
├── pom.xml            (parent, Java 21)
├── kb-api/            JSON sözleşmeleri (record DTO'lar) — F3'te açılır; F2'de yok (ADR-0022)
├── kb-core/           Saf Java: alan modeli, WritePolicy, Markdown görünümü, secret desenleri, şema doğrulama.
│                      Spring/Neo4j bağımlılığı YOK → hızlı birim testleri
├── kb-server/         Spring Boot: graf deposu, yazma servisi, tarayıcı, anlık görüntü/senkron, MCP, REST, hook
├── kb-client/         picocli CLI + TamboUI TUI + `kb run` + `kb secret`; REST istemcisi; GraalVM native-image
│                      (F2'de yalnız JVM jar: CLI, `kb run`, `kb secret`; TUI ve native F3)
├── kb-gui/            Dear ImGui (`imgui-java`) masaüstü GUI; `/api/v1` istemcisi; JVM ile çalışır, `kb gui` başlatır (ADR-0021)
├── curator/           PROMPT.md, mcp.json, run-curator.sh
└── seed/ fixtures/ eval/ integrations/ docs/
```

| Katman | Seçim | Not |
|---|---|---|
| Dil | Java 21 | Neo4j 2025+ Java 21 istiyor |
| Çatı | Spring Boot 4.x | Spring AI 2.x'in temeli |
| MCP | Spring AI 2.0.x (`@McpTool`) + MCP Java SDK | `/mcp` auto-config ile; `/mcp/curator` MCP Java SDK ile ikinci uç (F1 spike) |
| Taşıma | Streamable HTTP | `spring.ai.mcp.server.protocol=STREAMABLE` açıkça ayarlanmalı |
| Depo | Neo4j Community embedded | `EmbeddedNeo4jStore` (arayüz yok, ADR-0022); LadybugDB alternatif (ADR-0002) |
| Git | Sistem `git` (+ büyük dokümanlar için `git-lfs`) | Anlık görüntü ve doküman commit'i, senkron |
| TUI | TamboUI + GraalVM native | B planı: Go/Bubble Tea |
| GUI | Dear ImGui (`imgui-java`) + ImNodes (graf) | Ayrı `kb-gui` modülü, JVM; Node araç zinciri yok (ADR-0021) |
| CLI | picocli | Native-image uyumlu |
| Test | JUnit 5, AssertJ, ArchUnit, Neo4j test harness, MCP Java SDK client | |

## 3. Paket yapısı (kb-server)

```
<kök-paket>.server
├── config/     KbProperties, Neo4jConfig, McpConfig (iki uç nokta)
├── graph/      EmbeddedNeo4jStore (`read(fn)`, `write(fn)`), KnowledgeQueries, CypherGuard, Schema (schema/*.yaml)
├── write/      WriteService (tek yazıcı kilidi), EntityResolver, BatchRevert, SnapshotImporter   — WritePolicy kb-core'da
├── scan/       ScannerService, extract/ (SpringConfig, DotEnv, Compose, K8s, Ansible, BuildFile)
├── secrets/    SecretService (değer sadece secret ucundan çıkar)
├── docs/       DocumentService (kb doc add: secret taraması, blobs/, Document düğümü)
├── snapshot/   SnapshotFormat, SnapshotExporter, SyncService (sistem git); `write` → `snapshot` tek yön, grafa yazan içeri alma `write`'ta (ArchUnit döngü kuralı)
├── signals/    PromptRing (RAM); `Signal` düğümleri: silinmez, aynı `kind`+`key` tek düğüm (`count`); `PRODUCED`, `ABOUT` (F2'de en küçük hal, `PRODUCED` F4: ADR-0022)
├── mcp/        worker/ (KbTools), curator/ (CuratorTools), SchemaResource
├── api/        REST kontrolcüleri (/api/v1)
├── hooks/      HookController (/hooks/*), BriefService, RecallService, CaptureService
├── eval/       EvalRunner (eval/questions.yaml); `-Peval` yalnız ince JUnit sarmalayıcı açar (ADR-0022)
├── security/   OriginGuard
└── audit/      AuditLog
```

ArchUnit kuralları: `mcp|api|hooks` → `write|graph|scan|snapshot|signals|docs` → `kb-core`. Grafa yazan tek sınıf
`WriteService`'tir (anlık görüntüden içeri alma ve restore dahil, onun üzerinden); `EmbeddedNeo4jStore.write` yalnız
`write` paketinden çağrılabilir. Secret değerini döndüren tek yer
`api` içindeki secret ucudur; `mcp` paketi `SecretService`'e erişemez.

## 4. Veri modeli

Düğüm ve kenarlar: SPEC §6. Fiziksel ayrıntılar:

- Her düğüm ve kenarın `id`'si vardır; senkron `id` ile birleştirir. Biçimler: varlık, karar ve derste kısa ad
  (`ornek-app-01`); fact, kenar, öneri ve sinyalde ULID; secret `<varlık>/<ortam>/<ad>`; Repo `<grup>/<ad>`;
  SourceFile `<repo>:<yol>`; Stack ve Topic `stack:<ad>`, `topic:<ad>`; Document `doc:<sha256'nın ilk 12 hanesi>`;
  Episode ULID (ADR-0023; anlık görüntüye girer).
- Kısıtlar: her etikette `id` unique. İndeks: `Fact.key` ve `batch`. `Fact.value` ve `Entity.aliases` için aralık indeksi
  yok: Neo4j 2026.10.1'de bu indeks yaklaşık 8 kB'tan uzun değerli yazmayı reddediyor, bu da kayıpsız içeri almayı
  (değişmez 11) bozar; değer ve takma ad araması tam metin indeksinden yapılır.
- Tam metin indeksi: varlık `id`/`aliases`/`summary`, fact değerleri (güncel ve kapanmış), `Decision.rule`/`body`,
  `Lesson.title`/`body`, `Document.name` ve bilgi taşıyan sinyallerin `payload`'u. Türkçe analizör seçimi F1
  spike'ında değerlendirme setiyle yapılır ("İzmir" testi).
- Okuma sorguları kayıtları süzmez, sıralar (ADR-0017). `KnowledgeQueries` her sonuca bir katman verir: 0 güncel
  (`valid_to IS NULL`, varlık arşivli değil), 1 geçmiş (kapanmış fact, biten ilişki, arşivli varlık), 2 ham (bilgi
  taşıyan sinyal). Önce katmana, sonra skora göre sıralar; bütçeyi en son uygular ve kesilen sonuçları sayar. Eski bir
  fact eşleşirse `SUPERSEDES` zincirinin başındaki güncel değeri de getirir. `kb_related`'da bir yol, bütün kenarları
  güncel ve hiçbir düğümü arşivli değilse güncel sayılır.
- Metin sınırları yazarken doğrulanır: `summary` 300, metin fact 500, `body` 4.000 karakter. Sadece MCP ve REST'ten
  gelen yeni yazmalara uygulanır; anlık görüntüden içeri alma ve restore hiçbir kaydı reddetmez, kırpmaz.

## 5. Yazma yolu

```
WriteRequest {source, entity ipucu, facts[], relations[], evidence?, batch?, episode?}
  1. Doğrula: şema, anahtar sözlüğü, metin sınırları, secret görünümlü değer → RED (kb secret set ipucuyla);
     sadece MCP/REST yazmaları, içeri alma doğrulamadan geçmez
  2. Varlığı bul: id / alias / hostname / ip; yoksa oluştur (scan → observed, user → current)
  3. WritePolicy (kb-core, saf): mevcut fact'lerle karşılaştır → {tazele, ekle, kapat + ekle, çelişki}
  4. Tek graf işlemi, tek yazıcı kilidi altında; her kayda source, observed_at, updated_at, batch, episode
  5. WriteResult {eklenen, tazelenen, kapanan, çelişkiler, yeni varlıklar}
```

- **Normatif değişiklik** (karar, silme/arşiv, şema) → ajan ve küratör yalnız `Proposal` düğümü açar; onay TUI/GUI'den,
  onaylayan `user`. `user` kaynağı arşivi doğrudan yapar (ADR-0022).
- **Tazeleme** (aynı değerin yeniden görülmesi) `batch` yazmaz; yoksa `kb revert` tazelenen eski kaydı silerdi. Bir
  partinin kapattığı kayıtlar `SUPERSEDES` ile bulunur (ADR-0022).
- **Episode (ADR-0023):** `episode` oturumu ya da olayı gruplar, `batch` bir yazmayı; Episode'un partisi yoktur ve revert
  onu silmez. `user_stated` yazmada kanıt, UserPromptSubmit'in `session_id` ile PromptRing'e yazdığı mesajla eşleşirse
  kayıt o oturumun episode'una otomatik bağlanır; yoksa `episode` argümanı, o da yoksa kayıt episode'suz kalır (hata değil).
- **Yönetilen sözlük (ADR-0024):** adım 1 sözlükte olmayan anahtarı `note`/`kb_propose(kind=schema)` ipucuyla reddeder.
- **Geri alma:** `kb revert --batch <id>` partinin açtığı kayıtları siler, kapattıklarının `valid_to`'sunu kaldırır ve
  kapattığı sinyalleri yeniden açar (yoksa yapılandırılamamış soru-cevap kapalı kalır ve küratör ona bir daha bakmaz).
  Daha eski bir duruma dönmek için `kb restore --at <commit>`.

## 6. Tarayıcı

```
kb_scan(path) / kb scan <path> / SessionStart (HEAD değiştiyse)
  → allowed-roots kontrolü → git bilgisi (remote, HEAD) → dosya keşfi (.gitignore'a uyar, ama .env*'yi okur)
  → Extractor'lar → aday {varlık ipucu, anahtar, değer, ortam, dosya:satır, hassas mı}
  → hassas olanlar → Secret düğümü (değer sadece bu bilgisayarda); "düz metin secret" bulgusu (dosya git'te izleniyorsa)
  → WriteRequest(source=scan) → yazma yolu; kaynak SourceFile düğümüne FROM {line, commit} ile bağlanır
  → ScanReport {yeni varlıklar, yeni/değişen fact'ler, çelişkiler, bulgular}   // secret değeri içermez
```

| Extractor | Dosyalar | Çıkardıkları |
|---|---|---|
| SpringConfig | `application*.yml/yaml/properties`, `bootstrap*` | profil → ortam; `server.port`; `spring.datasource.*` (url → host/port/db, password → secret); redis/rabbit/kafka host'ları; OAuth2 `issuer-uri`; `*.url`/`*.host`/`*.uri` |
| DotEnv | `.env`, `.env.<ortam>` | Anahtarlar; secret görünümlüler Secret düğümüne |
| Compose | `docker-compose*.yml`, `compose*.yml` | yalnız `services.*.environment` altındaki düz metin secret'lar (ortam `local`); compose servisi varlık, ilişki veya fact üretmez |
| K8s | üst düzey `kind` taşıyan dosyalar | ortam namespace'in son `-` parçasından; namespace, container imajı ve Ingress url'si proje fact'i olur; `secretKeyRef` secret üretmez; container env'inde düz metin secret görünümlü değer Secret olur |
| Ansible | `inventory*`, `hosts*`, `*.ini` | INI envanter; her host, grubunun ortamında bir `Server` |
| BuildFile | `pom.xml`, `build.gradle*` | stack (java-spring, java, kotlin…), Spring Boot ve Java sürümü |

Ortam: profil adı (`application-prod.yml` → `prod`), dosya adı soneki, k8s namespace, CI environment adı; bilinmiyorsa
`default`. Tarayıcı **asla LLM çağırmaz**.

Tarama kuralları (ADR-0022 karar 32–36):
- `DEPLOYED_ON`: bir ortamın Ansible host'u projeye bağlanır; bir k8s namespace'i projeyi o ortama koyuyorsa bağlanmaz.
- Bir proje birden çok repoya yayılabilir (ek `HAS_REPO`).
- Token taşıyan webhook url'leri url fact'i olarak yazılmaz.
- Okunan her dosya `allowed-roots` altında çözülmelidir (git `core.worktree`/`gitdir` dışarı çıkaramaz).
- Tek değerli bir anahtar bir taramada aynı ortamda birden çok değer alırsa tek değer kalır (önce yola, sonra satıra göre
  ilki) ve diğerlerini listeleyen tek bir çelişki sinyali açılır.
- `finding` çok değerlidir; metni `Düz metin parola bulundu: <dosya>:<satır>; ortam değişkenine taşınmalı`.

## 7. Secret'lar

- Değer `Secret.secret_value` alanında, sadece bu bilgisayardaki grafta durur. Veri dizini 0700. GPG ya da başka bir
  şifreleme yok (ADR-0015).
- **Yazma:** `kb secret set <id>` değeri terminalde maskeli alır, `PUT /api/v1/secrets/{id}` ile gönderir; tarayıcı
  config dosyasından okur. Değişince eskisi tutulmaz.
- **Okuma:** sadece `GET /api/v1/secrets/{id}`; onu `kb run` ve `kb secret show` kullanır. MCP, Cypher,
  UI, anlık görüntü, log ve hata mesajları değeri hiç görmez.
- **Placeholder:** `kb run --env AD=kb:secret/<id> -- <komut>` değeri alt sürecin ortamına koyar; stdout/stderr'i akış
  halinde süzer: değerin kendisi, base64 ve URL-encoded biçimleri `****` ile maskelenir; çıkış kodunu aynen döner.
  Değer bu bilgisayarda yoksa komutu çalıştırmaz. Ayrıntılar: SECRETS.md.

## 8. Anlık görüntü ve senkron

`~/kb` bir git reposudur:

```
~/kb/
├── schema/            types.yaml, relations.yaml, facts.yaml
├── blobs/             ab/ab12…ef   doküman deposu: içerik adresli, değişmez dosyalar (ADR-0017)
└── snapshot/
    ├── nodes.jsonl    {"id":..., "labels":[...], "props":{...}}            id'ye göre sıralı
    └── edges.jsonl    {"id":..., "type":..., "from":..., "to":..., "props":{...}}
```

- **Yazma:** `kb sync` her çalıştığında (systemd timer, 10 dakikada bir) ve sunucu kapanırken; ayrı bir zamanlayıcı
  yok. Biçim sabittir (anahtarlar özyinelemeli alfabetik, `id` sıralı, UTF-8, `\n`; ADR-0022), böylece git diff okunur. `Signal` düğümleri, onlara bağlı
  kenarlar (`PRODUCED`, `ABOUT`) ve `secret_value` alanı yazılmaz; içeri alırken yereldeki secret değeri korunur.
  Yeni dokümanlar aynı commit'e girer. Değişiklik yoksa commit yok. Commit'ten önce yazılan satır sayısı graftaki
  kayıt sayısıyla (sinyaller hariç) karşılaştırılır; tutmazsa commit yapılmaz ve `kb sync` hata verir.
- **Senkron (`kb sync`):** `git fetch` → uzak anlık görüntüyle `id`'ye göre birleştir (`updated_at`'i yeni olan
  kazanır) → birleşimi grafa uygula → anlık görüntüyü yaz → uzak dalın üstüne commit → `git push`. Ağ hatasında yerel
  commit bekler, sonraki çalışmada tekrar denenir. Force-push hiç yapılmaz; GitHub'da ana dal korumalıdır.
- **Restore:** `kb restore [--at <commit>]` grafı boşaltıp anlık görüntüden yeniden kurar. Anlık görüntüde olmayan yerel
  veriler (secret değerleri, sinyaller) önce ayrılır, kurulumdan sonra geri yazılır; restore onları silmez (test).
- **İlk kurulum:** `kb init` repoyu oluşturur veya klonlar, `schema/`'yı yerleştirir; graf boşsa son anlık görüntü
  yüklenir. `init` sunucu jar'ında, Neo4j açılmadan çalışır; `sync`, `restore`, `revert`, `check` REST + `kb-client`
  ile (ADR-0022).
- **Sunucu kapalıyken:** ajanlar `~/kb/snapshot/*.jsonl`'ü salt okunur okuyabilir (en fazla 10 dk eski, secret değeri
  yok; AGENT-RULES). Yazma sadece sunucudan.

## 9. API yüzeyleri ve kapsamlar

| Uç | Oturum | Kim | İzinli işlemler |
|---|---|---|---|
| `/mcp` | yok | Claude Code, Codex | Okuma araçları, `kb_remember`, `kb_scan`, `kb_propose` |
| `/mcp/curator` | yok | Küratör | Okuma, sinyal okuma, hijyen yazma, öneri, parti kontrolü |
| `/hooks/*` | yok | `kb-hook.sh` | brief, recall, capture |
| `/api/v1/**` | yok | `kb` CLI/TUI, `kb-gui` | CRUD, çelişki çözme, öneri onayı, sinyal yeniden açma, revert, restore, senkron, tarama, secret yazma |
| `/api/v1/secrets/{id}` (GET) | yok | `kb run`, `kb secret show` | Secret değerini döner; başka hiçbir uç döndürmez |
| `/actuator/health` | yok | systemd, `kb doctor` | Detaysız sağlık |

Token yok (ADR-0020); tüm uçlar `OriginGuard` arkasındadır (§10).

## 10. Ağ güvenliği

- `OriginGuard` (servlet `Filter`, `/*`): `Origin` başlığı varsa 403; `Host` ∈ {`127.0.0.1:<port>`, `localhost:<port>`};
  `<port>` `request.getLocalPort()`'tan okunur (ADR-0022), varsayılan 7411.
- Loopback zorunlu: `server.address` loopback değilse sunucu açılmayı reddeder (ADR-0020).
- Sunucu dışarıya HTTP çağrısı yapmaz; tek çıkış `kb sync`'in git'idir.
- İstek gövdesi en çok 1 MiB, aşılırsa 413. Sunucu JVM'i `OutOfMemoryError`'da çıkar, systemd yeniden başlatır. MCP HTTP hatalarında (≥ 400) gövde boştur (`McpErrorBodyFilter`); aktarım
  logger'ları yükü `*****` yazar. Gerekçe ve kabul edilen ad yansıması: ADR-0022 karar 17–20.

## 11. Hook entegrasyonu

| Aile | Script | Mantık nerede | Hata davranışı |
|---|---|---|---|
| KB hook'ları | `integrations/kb-hook.sh <olay> <istemci>` | Sunucuda (`/hooks/*`) — script sadece taşıyıcı | 2 sn zaman aşımı, fail-open |

KB hook olayları: `SessionStart` → `(istemci, session_id)` için Episode'u açar ya da tazeler (ADR-0023), brief (episode
kimliği satırıyla; + gerekiyorsa arka planda tarama), `UserPromptSubmit` → recall + PromptRing (`session_id` ile),
`PostToolUse` (AskUserQuestion / `request_user_input`) → ham soru-cevap sinyali; `CaptureService` secret görünümlü
değerleri yazmadan önce `****` ile maskeler.

## 12. Küratör çalıştırma

`agentkb-curator.timer` saatte bir `kb curator run --if-needed` çalıştırır. Sunucu "gerekli mi?" kararını verir (son
çalışmadan beri ≥ 24 saat ve ≥ 5 oturum veya ≥ 50 açık sinyal). Gerekliyse `curator/run-curator.sh` headless Claude
Code'u sadece `/mcp/curator` araçlarıyla başlatır. Ayrıntılar: CURATOR.md.

## 13. Gözlemlenebilirlik

- **Aktivite:** graftaki `source`, `batch` ve `updated_at` alanlarından; TUI ve GUI'de "Aktivite" ekranı.
- **`audit.jsonl`:** `{ts, scope, client, tool, durationMs, resultCount, rejected?, reason?}`; içerik, sorgu metni,
  secret yok.
- **`kb doctor`:** sunucu, graf, son anlık görüntü ve senkron zamanı, veri dizini izinleri, bu bilgisayarda
  değeri olmayan secret'lar, büyük doküman varsa git-lfs kurulumu, iki ajanın MCP ve hook yapılandırması, son küratör çalışması ve eval skoru. LFS gönderimi başarısız olursa `kb sync` hata verir.
- **`kb check`** (F2: sahipsiz kenar, sözlük dışı ilişki, açık çelişki, yinelenen `id`, yerel kayıt, sahipsiz fact, `git fsck`;
  doküman bütünlüğü F3, büyüme raporu
  F4; ADR-0022): tutarlılık sorunları (sahipsiz kenar, sözlük dışı ilişki, açık çelişki) ve büyüme raporu: varlıklar
  (duruma göre), güncel ve kapalı fact'ler, kararlar, dersler, dokümanlar (toplam boyut), açık, işlenmiş ve atılan
  sinyaller, bayat oranı; her biri için bir önceki `kb check`'e göre fark (ADR-0017). Doküman bütünlüğü: her
  `Document`'ın dosyası `blobs/`'ta var mı ve sha256'sı tutuyor mu. Repo bütünlüğü: `~/kb` için `git fsck`.

## 14. Yapılandırma

```yaml
agentkb:
  repo: ${HOME}/kb                          # anlık görüntü + şema (git)
  data-dir: ${HOME}/.local/share/agentkb    # graph/ burada (0700); asla senkronlanmaz; secret değerleri burada
  state-dir: ${HOME}/.local/state/agentkb   # audit, küratör özetleri
  scan:
    allowed-roots: [${HOME}/projects, ${HOME}/git]
    internal-domains: [ornek.example]       # bu alanlardaki host'lar için varlık id'si = ilk DNS etiketi
    max-file-bytes: 1048576
  recall:
    max-hits: 3
    min-score: 1.5
  cypher:
    timeout-ms: 5000
    max-rows: 200
  output:
    max-chars: 8000
  limits:
    summary-chars: 300
    text-fact-chars: 500
    body-chars: 4000
  documents:
    lfs-threshold-mb: 20
  stale-days: 90
  curator:
    min-hours-between-runs: 24
    min-sessions: 5
    min-signals: 50
    max-writes-per-batch: 50
    eval-tolerance: 0.02
server:
  address: 127.0.0.1
  port: 7411
spring:
  ai:
    mcp:
      server:
        name: agentkb
        protocol: STREAMABLE
```
