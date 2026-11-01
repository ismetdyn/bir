# agentkb — Mimari (v0.3)

## 1. Genel görünüm

```
 Çalışan ajanlar (Claude Code, Codex)                  Sen
   │ MCP /mcp (agent token)                             │ kb CLI/TUI ──► REST /api/v1 (user token)
   │ hook'lar ─► /hooks/* (hook token)                  │ Web GUI    ──► /ui (oturum + CSRF)
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
                                   │ MCP /mcp/curator (curator token)
                       Küratör (systemd timer → headless Claude Code)
```

İlke: **Sunucu deterministiktir ve tek yazıcıdır; LLM'ler (çalışan ajanlar ve küratör) sunucunun dışındadır.**

## 2. Modüller ve teknoloji

```
agentkb/
├── pom.xml            (parent, Java 21)
├── kb-api/            JSON sözleşmeleri (record DTO'lar)
├── kb-core/           Saf Java: alan modeli, WritePolicy, Markdown görünümü, secret desenleri, şema doğrulama.
│                      Spring/Neo4j bağımlılığı YOK → hızlı birim testleri
├── kb-server/         Spring Boot: graf deposu, yazma servisi, tarayıcı, anlık görüntü/senkron, MCP, REST, hook, UI
├── kb-client/         picocli CLI + TamboUI TUI + `kb run` + `kb secret`; REST istemcisi; GraalVM native-image
├── curator/           PROMPT.md, mcp.json, run-curator.sh
└── seed/ fixtures/ eval/ integrations/ docs/
```

| Katman | Seçim | Not |
|---|---|---|
| Dil | Java 21 | Neo4j 2025+ Java 21 istiyor |
| Çatı | Spring Boot 4.x | Spring AI 2.x'in temeli |
| MCP | Spring AI 2.0.x (`@McpTool`) + MCP Java SDK | `/mcp` auto-config ile; `/mcp/curator` MCP Java SDK ile ikinci uç (F1 spike) |
| Taşıma | Streamable HTTP | `spring.ai.mcp.server.protocol=STREAMABLE` açıkça ayarlanmalı |
| Depo | Neo4j Community embedded | `GraphStore` arayüzü arkasında; LadybugDB F1'de ölçülen alternatif (ADR-0002) |
| Git | Sistem `git` (+ büyük dokümanlar için `git-lfs`) | Anlık görüntü ve doküman commit'i, senkron |
| TUI | TamboUI + GraalVM native | B planı: Go/Bubble Tea |
| Web GUI | Thymeleaf + htmx + Cytoscape.js (jar içinde, CDN yok) | Node araç zinciri yok |
| CLI | picocli | Native-image uyumlu |
| Test | JUnit 5, AssertJ, ArchUnit, Neo4j test harness, MCP Java SDK client | |

## 3. Paket yapısı (kb-server)

```
<kök-paket>.server
├── config/     KbProperties, Neo4jConfig, McpConfig (iki uç nokta)
├── graph/      GraphStore, EmbeddedNeo4jStore, KnowledgeQueries, CypherGuard, Schema (schema/*.yaml)
├── write/      WriteService (tek yazıcı kilidi), EntityResolver, BatchRevert   — WritePolicy kb-core'da
├── scan/       ScannerService, extract/ (SpringConfig, DotEnv, Compose, K8s, GitlabCi, Ansible, BuildFile)
├── secrets/    SecretService (değer sadece secret ucundan çıkar)
├── docs/       DocumentService (kb doc add: secret taraması, blobs/, Document düğümü)
├── snapshot/   SnapshotExporter, SnapshotImporter, SyncService (sistem git)
├── signals/    PromptRing (RAM); `Signal` düğümleri: silinmez, aynı `kind`+`key` tek düğüm (`count`); `PRODUCED`, `ABOUT`
├── mcp/        worker/ (KbTools), curator/ (CuratorTools), SchemaResource
├── api/        REST kontrolcüleri (/api/v1)
├── hooks/      HookController (/hooks/*), BriefService, RecallService, CaptureService
├── ui/         UiController, şablonlar, statik dosyalar
├── eval/       EvalRunner (eval/questions.yaml)
├── security/   TokenScopeFilter, OriginGuard, OneTimeLoginService
└── audit/      AuditLog
```

ArchUnit kuralları: `mcp|api|hooks|ui` → `write|graph|scan|snapshot|signals|docs` → `kb-core`. Grafa yazan tek sınıf
`WriteService`'tir (anlık görüntüden içeri alma ve restore dahil, onun üzerinden). Secret değerini döndüren tek yer
`api` içindeki secret ucudur; `mcp` paketi `SecretService`'e erişemez.

## 4. Veri modeli

Düğüm ve kenarlar: SPEC §6. Fiziksel ayrıntılar:

- Her düğüm ve kenarın `id`'si vardır; senkron `id` ile birleştirir. Biçimler: varlık, karar ve derste kısa ad
  (`ornek-app-01`); fact, kenar, öneri ve sinyalde ULID; secret `<varlık>/<ortam>/<ad>`; Repo `<grup>/<ad>`;
  SourceFile `<repo>:<yol>`; Stack ve Topic `stack:<ad>`, `topic:<ad>`; Document `doc:<sha256'nın ilk 12 hanesi>`.
- Kısıtlar: her etikette `id` unique. İndeksler: `Fact.key`, `Fact.value`, `Entity.aliases`.
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
WriteRequest {source, entity ipucu, facts[], relations[], evidence?, batch?}
  1. Doğrula: şema, anahtar sözlüğü, metin sınırları, secret görünümlü değer → RED (kb secret set ipucuyla);
     sadece MCP/REST yazmaları, içeri alma doğrulamadan geçmez
  2. Varlığı bul: id / alias / hostname / ip; yoksa oluştur (scan → observed, user → current)
  3. WritePolicy (kb-core, saf): mevcut fact'lerle karşılaştır → {tazele, ekle, kapat + ekle, çelişki}
  4. Tek graf işlemi, tek yazıcı kilidi altında; her kayda source, observed_at, updated_at, batch
  5. WriteResult {eklenen, tazelenen, kapanan, çelişkiler, yeni varlıklar}
```

- **Normatif değişiklik** (karar, silme/arşiv, şema) → `Proposal` düğümü; onay TUI/GUI'den, onaylayan `user`.
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
| Compose | `docker-compose*.yml`, `compose*.yml` | servisler, imajlar, portlar, `depends_on` → ilişki |
| K8s | `kind: Deployment/Service/Ingress/ConfigMap` | servisler, portlar, ingress host'ları, namespace, imaj; `Secret` nesnelerinin sadece adı |
| GitlabCi | `.gitlab-ci.yml` | `environment` adları/URL'leri, deploy script'lerindeki `ssh user@host` |
| Ansible | `inventory*`, `hosts*`, `*.ini` | host'lar, gruplar, `ansible_host` |
| BuildFile | `pom.xml`, `build.gradle*` | stack (java-spring, java, kotlin…), Spring Boot ve Java sürümü |

Ortam: profil adı (`application-prod.yml` → `prod`), dosya adı soneki, k8s namespace, CI environment adı; bilinmiyorsa
`default`. Tarayıcı **asla LLM çağırmaz**.

## 7. Secret'lar

- Değer `Secret.secret_value` alanında, sadece bu bilgisayardaki grafta durur. Veri dizini 0700. GPG ya da başka bir
  şifreleme yok (ADR-0015).
- **Yazma:** `kb secret set <id>` değeri terminalde maskeli alır, `PUT /api/v1/secrets/{id}` ile gönderir; tarayıcı
  config dosyasından okur. Değişince eskisi tutulmaz.
- **Okuma:** sadece `GET /api/v1/secrets/{id}` (`user` token); onu `kb run` ve `kb secret show` kullanır. MCP, Cypher,
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
  yok. Sabit alan sırası ve sıralı satırlar sayesinde git diff okunur. `Signal` düğümleri, onlara bağlı
  kenarlar (`PRODUCED`, `ABOUT`) ve `secret_value` alanı yazılmaz; içeri alırken yereldeki secret değeri korunur.
  Yeni dokümanlar aynı commit'e girer. Değişiklik yoksa commit yok. Commit'ten önce yazılan satır sayısı graftaki
  kayıt sayısıyla (sinyaller hariç) karşılaştırılır; tutmazsa commit yapılmaz ve `kb sync` hata verir.
- **Senkron (`kb sync`):** `git fetch` → uzak anlık görüntüyle `id`'ye göre birleştir (`updated_at`'i yeni olan
  kazanır) → birleşimi grafa uygula → anlık görüntüyü yaz → uzak dalın üstüne commit → `git push`. Ağ hatasında yerel
  commit bekler, sonraki çalışmada tekrar denenir. Force-push hiç yapılmaz; GitHub'da ana dal korumalıdır.
- **Restore:** `kb restore [--at <commit>]` grafı boşaltıp anlık görüntüden yeniden kurar. Anlık görüntüde olmayan yerel
  veriler (secret değerleri, sinyaller) önce ayrılır, kurulumdan sonra geri yazılır; restore onları silmez (test).
- **İlk kurulum:** `kb init` repoyu oluşturur veya klonlar, `schema/`'yı yerleştirir; graf boşsa son anlık görüntü
  yüklenir.
- **Sunucu kapalıyken:** ajanlar `~/kb/snapshot/*.jsonl`'ü salt okunur okuyabilir (en fazla 10 dk eski, secret değeri
  yok; AGENT-RULES). Yazma sadece sunucudan.

## 9. API yüzeyleri ve kapsamlar

| Uç | Token/oturum | Kim | İzinli işlemler |
|---|---|---|---|
| `/mcp` | `agent` | Claude Code, Codex | Okuma araçları, `kb_remember`, `kb_scan`, `kb_propose` |
| `/mcp/curator` | `curator` | Küratör | Okuma, sinyal okuma, hijyen yazma, öneri, parti kontrolü |
| `/hooks/*` | `hook` | `kb-hook.sh` | brief, recall, capture |
| `/api/v1/**` | `user` | `kb` CLI/TUI | CRUD, çelişki çözme, öneri onayı, sinyal yeniden açma, revert, restore, senkron, tarama, secret yazma |
| `/api/v1/secrets/{id}` (GET) | `user` | `kb run`, `kb secret show` | Secret değerini döner; başka hiçbir uç döndürmez |
| `/ui/**` | oturum (tek kullanımlık link) | Tarayıcı | `/api/v1` ile aynı, secret ucu hariç |
| `/actuator/health` | yok | systemd, `kb doctor` | Detaysız sağlık |

Token'lar `kb init` ile üretilir: `~/.config/agentkb/tokens/{agent,curator,hook,user}` (0600).

## 10. Ağ güvenliği

- `OriginGuard`: `/mcp*`, `/hooks/*`, `/api/*` için `Origin` başlığı varsa 403; `Host` ∈ {`127.0.0.1:7411`, `localhost:7411`}.
- `/ui`: `Origin` = `http://127.0.0.1:7411`; `SameSite=Strict`, `HttpOnly` çerez; Spring Security CSRF; CSP
  `default-src 'self'`; giriş linki 60 sn geçerli, tek kullanımlık.
- Sunucu dışarıya HTTP çağrısı yapmaz; tek çıkış `kb sync`'in git'idir.

## 11. Hook entegrasyonu

| Aile | Script | Mantık nerede | Hata davranışı |
|---|---|---|---|
| KB hook'ları | `integrations/kb-hook.sh <olay> <istemci>` | Sunucuda (`/hooks/*`) — script sadece taşıyıcı | 2 sn zaman aşımı, fail-open |

KB hook olayları: `SessionStart` → brief (+ gerekiyorsa arka planda tarama), `UserPromptSubmit` → recall + PromptRing,
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
- **`kb doctor`:** sunucu, graf, son anlık görüntü ve senkron zamanı, token ve veri dizini izinleri, bu bilgisayarda
  değeri olmayan secret'lar, büyük doküman varsa git-lfs kurulumu, iki ajanın MCP ve hook yapılandırması, son küratör çalışması ve eval skoru. LFS gönderimi başarısız olursa `kb sync` hata verir.
- **`kb check`:** tutarlılık sorunları (sahipsiz kenar, sözlük dışı ilişki, açık çelişki) ve büyüme raporu: varlıklar
  (duruma göre), güncel ve kapalı fact'ler, kararlar, dersler, dokümanlar (toplam boyut), açık, işlenmiş ve atılan
  sinyaller, bayat oranı; her biri için bir önceki `kb check`'e göre fark (ADR-0017). Doküman bütünlüğü: her
  `Document`'ın dosyası `blobs/`'ta var mı ve sha256'sı tutuyor mu. Repo bütünlüğü: `~/kb` için `git fsck`.

## 14. Yapılandırma

```yaml
agentkb:
  repo: ${HOME}/kb                          # anlık görüntü + şema (git)
  data-dir: ${HOME}/.local/share/agentkb    # graph/ burada (0700); asla senkronlanmaz; secret değerleri burada
  state-dir: ${HOME}/.local/state/agentkb   # audit, küratör özetleri
  tokens-dir: ${HOME}/.config/agentkb/tokens
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
