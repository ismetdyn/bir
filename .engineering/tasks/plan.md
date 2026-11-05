# Plan — F2 bilgi çekirdeği

Ürün şartnamesi: `docs/SPEC.md`, `docs/ARCHITECTURE.md`, `docs/ROADMAP.md` F2. Dal: `f2-core` (`f1-spike`'tan). Plan onaylandı 2026-11-01. Her dilim çalışır
sistem bırakır. Sıra yüksek riskten düşüğe, `OriginGuard` ilk veri aracından önce (ADR-0020).

## Dilimler
Y = `java-developer` + `java-test-engineer` (sıralı). Önce test: `CLAUDE.md` → Çalışma şekli.

| # | Amaç | Kabul (negatif dahil) | Kapsam | Bağ. | Yazar |
|---|---|---|---|---|---|
| G0 | Dokümanları kararlarla hizala (ADR-0022) | ADR-0022 yazıldı; ROADMAP, SPEC, ARCHITECTURE, CLIENTS uyumlu | `docs/**` | — | `documentation-specialist` |
| G1 | Yerel erişim sınırı | Sahte `Host`/`Origin` iki MCP ucunda 403; `server.address=0.0.0.0` açılmaz; hata gövdesinde `stackTrace` yok | `kb-server/**/security/**`, `config/**`, `application.yml` | G0 | Y |
| G2 | Script'ler token'sız | Token dosyası yokken hook sunucuya gider; `stop` reddedilir; `Authorization` başlığı yok | `integrations/kb-hook.sh`, `curator/{mcp.json,run-curator.sh}` | G1 | `devops-specialist` |
| CP1 | Lider: Claude Code ve Codex gerçekten geçiyor mu | | | | |
| G3 | Saf çekirdek ve kapılar | `WritePolicy` tablosu geçer; düşük güven yükseği ezemez; `İ/I` testleri geçer; ArchUnit ihlalde düşer | `pom.xml`, `kb-core/**`, ArchUnit testi | G1 | Y |
| G4 | Seed'i yükle, byte-aynı geri yaz (önce seed'e İzmir kaydı + q12 açılır: `python-developer`, kullanıcı onaylı) | Export `seed/snapshot` ile byte-aynı; sınır aşan kayıt reddedilmez; sahipsiz kenar açılışı net hatayla keser | `server/graph/**`, `snapshot/**`, `write/**`, `kb-core/**` | G3 | Y |
| CP2 | Determinizm ve Neo4j bellek sınırı ölçümü | | | | |
| G5 | Görünüm ve temel okuma araçları | `kb_search "192.0.2.20"` `eski` + güncel IP verir; arşiv işaretli gelir; secret değeri çıktıda yok | `kb-core/**/render/**`, `graph/KnowledgeQueries*`, `mcp/worker/**` | G4 | Y |
| G6 | Kalan okuma araçları ve eval kapısı | `CREATE`, `LOAD CSV`, `secret_value` reddedilir; `-Peval verify` ≥ 16/18; eşik altında build düşer | `graph/CypherGuard*`, `mcp/worker/**`, `eval/**`, `pom.xml` | G5 | Y |
| CP3 | Eval skoru ve analizör kararı (ADR-0018 m.6) | | | | |
| G7 | Tek yazıcı ve ajan yazması | `valid_from` değişmez; çelişki sinyal açar, `count` artar; secret görünümlü değer ve sınır aşımı reddedilir | `write/**`, `signals/**`, `mcp/worker/**` | G4, G5 | Y |
| G8 | Secret ve `kb run` | Değer alt sürece gider; düz, base64, URL hali maskelenir; değer yoksa komut çalışmaz | `secrets/**`, `api/**`, `kb-client/**` | G7 | Y |
| CP4 | `security-reviewer`: `SecretLeakIT` | | | | |
| G9 | Hook uçları, recall, brief | Secret görünümlü cevap sinyale maskeli girer; kanıt uyuşmazsa kaynak `agent`; sunucu kapalıyken boş + exit 0 | `hooks/**`, `signals/**`, `integrations/**` | G6, G7 | Y; script `devops-specialist` |
| G10 | Tarayıcı 1: SpringConfig, DotEnv, BuildFile | `allowed-roots` dışı reddedilir; `${DB_PASSWORD}` secret olmaz; iki sahte parola hiçbir çıktıda yok | `scan/**`, `mcp/worker/**` | G8, G9 | Y |
| G11 | Tarayıcı 2: Compose, K8s, Ansible | `expected.yaml` tam geçer; `merge_with_seed` geçer; compose servisi varlık olmaz | `scan/extract/**` | G10 | Y |
| CP5 | H2 ≥ %95 ve tarama süresi ölçümü | | | | |
| G12 | Revert, restore, check | Restore aynı grafı verir, secret ve sinyalleri korur; revert açtığını siler, kapattığını açar; bozuk repoda `git fsck` hatası görünür | `write/BatchRevert*`, `snapshot/**`, `api/**`, `kb-client/**` | G8, G11 | Y |
| G13 | Senkron ve timer | İki geçici repo kayıpsız birleşir; satır sayısı tutmazsa commit yok; force-push yok | `snapshot/SyncService*`, `api/**`, `kb-client/**`, `integrations/systemd/**` | G12 | Y; timer `devops-specialist` |
| G14 | Kurulum ve bitti tanımı | Boş dizinde `init` + servis açılır; `init` ikinci kez veri bozmaz; import iki kez sinyal sayısını değiştirmez | server `init`, `kb-client/**`, `integrations/**` | G13 | Y; kurulum `devops-specialist` |
| CP6 | Lider: bitti tanımı uçtan uca + `./mvnw verify` | | | | |

Bağımsız inceleme: `java-reviewer` her checkpoint'te; `security-reviewer` G1, G6, G8, G9, G10, G13.

## Kararlar (ADR-0022'ye girer)
- **Modüller:** `kb-core` G3'te, `kb-client` G8'de (JVM jar; native ve TUI F3). `kb-api` F3'e ertelenir: F2'de paylaşılan DTO yok.
- **`GraphStore` arayüzü yok:** tek uygulama; `EmbeddedNeo4jStore` `read(fn)`, `write(fn)` sunar, `write` yalnız `write` paketinden (ArchUnit).
- **Komutlar:** `init` sunucu jar'ında, Neo4j açılmadan. `revert`, `sync`, `restore`, `check`, `import-memories`, `run`, `secret` REST + ince `kb-client`; grafa ikinci süreç girmez.
- **`kb run` F2'ye çekilir;** bitti tanımı değişmez.
- **Signal en küçük hali:** `kind`, `key`, `payload`, `at`, `last_at`, `count`, `status=open`, `ABOUT`. Türler `conflict`, `qa-capture`, `imported-memory`. `outcome`, `PRODUCED`, yeniden açma, `empty-search`, `scan-finding` F4'te.
- **F2 REST:** `GET/PUT /secrets/{id}`, `POST /revert`, `POST /sync`, `POST /restore`, `GET /check`, `POST /signals/imported-memories` (yeni).
- **`kb check` F2:** sahipsiz kenar, sözlük dışı ilişki, açık çelişki, `git fsck`. Doküman bütünlüğü F3, büyüme raporu F4.
- **Eval:** üretim sınıfı `server.eval`; `-Peval` ince JUnit sarmalayıcı açar; eşik 0,85 = en az 16/18; CI gelene kadar eşik `-Peval verify` çıkış kodudur.
- **Anlık görüntü biçimi:** seed kazanır: anahtarlar özyinelemeli alfabetik, `id` sıralı, UTF-8 ham, `\n`.
- **Tazeleme `batch` yazmaz;** yazsa revert eski kaydı silerdi. Kapatılanlar `SUPERSEDES` ile bulunur.
- **`OriginGuard` portu** `request.getLocalPort()`'tan; sabit 7411 testleri kırar.
- **Çelişkiler:** `Person`/`Team` SPEC'e eklenir. F2 kapısı 18 soru + %85; "≥30 soru" v1.0 ölçütü kalır. Hook'tan `stop` ve 1,5 sn silinir (2 sn). Ajan ve küratör yalnız `Proposal`; `user` doğrudan arşivler. H12: "Sistem kaydı kendiliğinden silmez veya gizlemez; istisnalar `kb revert --batch` ve disk kaybında yerel sinyaller".

## Ortak sözleşmeler (uygulamadan önce sabit)
`core/model/*` record'lar, `core/Ids.java`, `core/policy/WritePolicy.java` (sealed `Refresh|Add|CloseAndAdd|Conflict|Promote`),
`server/write/WriteRequest`+`WriteResult`, `WriteService.importRecords` (doğrulama yok, `updated_at` korunur, tek işlem),
`core/render/MarkdownView.java`, `server/snapshot/SnapshotFormat.java`, `core/secret/SecretPatterns.java`, `/hooks/{event}?client=`.

## Yeni bağımlılıklar (Maven Central, 2026-11-01)
| Bağımlılık | Dilim | Sürüm |
|---|---|---|
| `com.tngtech.archunit:archunit-junit5` (test) | G3 | 1.5.1 |
| `com.diffplug.spotless:spotless-maven-plugin` | G3 | 3.10.4 |
| `org.jspecify:jspecify` | G3 | 1.0.1 (Boot BOM) |
| `tools.jackson.dataformat:jackson-dataformat-yaml` | G4 | Boot BOM (`jackson-bom` 3.1.7) |
| `info.picocli:picocli` | G8 | 4.7.7 |

## Riskler
| Risk | Azaltma |
|---|---|
| Export sırasında yazma, sayımı ve determinizmi bozar | Export + sayım yazıcı kilidi altında; `git fetch/push` kilit dışı |
| Secret yan kanaldan sızar (`graph/logs/query.log`, hata gövdesi, sinyal) | Tek `SecretLeakIT`, dilim dilim genişler; sorgu logu kapalı |
| Restore tutarsız kalır veya bellek sınırını aşar | Tek işlem, rollback; 10.000 kayıt < 30 sn, heap 512 MB ile ölçülür |
| Neo4j testleri yavaşlar (bugün 1 dk 45 sn) | Mantık `kb-core`'da; paylaşılan context; page cache 128 MB |
| Sınıf yolu çakışır | Her bağımlılıkta tam `verify`; override'a neden yorumu; yeni dinleyen soket yok |

## Doğrulanmamış varsayımlar (ilgili dilimde denenir)
İkinci sürecin `store_lock` ile açılamaması (F1'de ölçüldü, süreçler arası test var); Neo4j Community'de ilişki `id` uniqueness
kısıtı (G4); `readOnlyHint`'in Codex onayını kaldırması (CP1/G5); gövde limiti mekanizması (G1'de ölçülür, gerekiyorsa sorulur).

## Kapsam dışı (F2)
TUI, native-image, doküman deposu, `kb-api` (F3); küratör akışı (F4); GUI (F5). `~/.claude` ayarlarına otomatik yazma: AGENT-RULES ve
hafıza kapatma G14'te yalnız belge + dry-run üretir, geçişi kullanıcı onayıyla yapar.
