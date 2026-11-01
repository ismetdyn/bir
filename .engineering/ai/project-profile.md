# Proje profili — agentkb

Tarih: 2026-11-01. Kaynak: `codebase-explorer` taraması + ana oturumda çalıştırılan komutlar.
Bağlayıcı kaynak `docs/SPEC.md` ve `docs/adr/`; bu dosya yalnız durumu özetler.

## Durum
Güncelleme 2026-11-01: F1 bitti, `f1-spike` dalında commit'siz. `kb-server` çalışıyor, `./mvnw -B clean verify` exit 0
(28 test). Sürümler ve bulgular `docs/adr/0018-f1-spike-bulgulari.md`'de; aşağıdaki "planlı" ve `unknown` satırları F1 öncesi
durumdur. `kb-api`, `kb-core`, `kb-client` F2'de. Kapsam ve GitHub: `docs/adr/0019-kisisel-proje-ve-kapsam.md`; agentguard
bu projede değil. Sıradaki faz: F2.

```yaml
project_profile:
  backend:
    present:
      language: python            # yalnız seed/make_seed.py; çalıştı: 3.14.8
    planned:                      # dokümanda beyan, doğrulanmadı (CLAUDE.md:21-23)
      language: java 21           # makinede: OpenJDK 21.0.12.1
      framework: spring-boot 4.x, spring-ai 2.0.x, mcp-java-sdk (sürüm unknown)
      modules: [kb-api, kb-core, kb-server, kb-client]
  frontend:
    planned: thymeleaf + htmx + cytoscape.js (F5); picocli + TamboUI 0.x + GraalVM native-image (F3)
  data:
    engines: [neo4j-community-embedded]   # planlı; sürüm unknown
    migrations: yok; şema seed/schema/*.yaml
    seed: seed/snapshot (45 düğüm, 59 kenar)
  identity:
    provider: yok                 # token kapsamları: agent, curator, hook, user (SPEC §13)
  delivery:
    ci: yok                       # planlı GitHub Actions; .gitlab-ci.yml yalnız fixture içinde
  evidence:
    files: [CLAUDE.md:20-40, docs/ARCHITECTURE.md:42-53, docs/ROADMAP.md]
  unknowns:
    - Neo4j, MCP Java SDK, Jackson, picocli, TamboUI, Maven, Thymeleaf, htmx, Cytoscape sürümleri
    - Spring Boot 4 + Spring AI 2.0 + gömülü Neo4j birlikte çalışıyor mu (F1 spike'ın sorusu)
    - Paket kökü io.github.<kullanıcı> boş (CLAUDE.md:27)
    - Codex hook alanları ve headless bayraklar (CLAUDE.md "dene ve yaz")
```

## Komut doğrulaması

| Komut | Durum | Kanıt |
|---|---|---|
| `python3 seed/make_seed.py <dizin>` | doğrulandı | exit 0; `nodes.jsonl`, `edges.jsonl` `cmp` ile `seed/snapshot/` ile aynı |
| `./mvnw -B clean verify` | doğrulandı | exit 0; 28 test; 1 dk 45 sn |
| `./mvnw -pl kb-core test`, `-Peval verify`, `-pl kb-client -Pnative package` | doğrulanmadı: modül ve profil yok (F2/F3) | — |
| `java -jar kb-server/target/agentkb-server.jar --agentkb.data-dir=<dizin>` | doğrulandı | açıldı, iki uç yanıt verdi. `CLAUDE.md:37`'deki `--agentkb.repo` henüz yok (F2) |
| `npx @modelcontextprotocol/inspector ...` | doğrulanmadı: sunucu yok | — |

## Makine eksikleri
`mvn`, `native-image`, `git-lfs`, `gh`, `glab` kurulu değil (`command -v`, `git lfs version`). `mvn` yerine wrapper üretimi
F1'de çözülür; `git-lfs` F3'te, `native-image` F3'te gerekir.

## Git tuzağı
`fixtures/scanner/ornek-proje/.env` kendi `.gitignore`'u yüzünden `git add -A`'ya girmez, ama tarayıcı golden testi ona
bağlı (`fixtures/scanner/expected.yaml:41`). İlk commit'te `git add -f` gerekir; yoksa temiz klonda test kırılır.

## Doküman çelişkileri (henüz çözülmedi)
Çözmeden kod yazılırsa teste yansıyanlar önce. "Teyit" = ana oturumda dosyadan okundu; diğerleri tarama bulgusu.

| # | Konu | A | B | Teyit |
|---|---|---|---|---|
| 1 | Maven iskeleti hangi fazda | README.md:75-79 F1 | ROADMAP.md:42 F2 | evet |
| 2 | F2, sonraki faz bileşenlerine bağlı (`kb revert/sync/restore/import-memories`, `Signal`) | ROADMAP.md:47,58,66 | ROADMAP.md:78,92 (F3, F4) | evet |
| 3 | Revert siler, "Silme yok" der; istisna yazılı değil | ARCHITECTURE.md:112, ADR-0006:27, CURATOR.md:75 | CLAUDE.md:52, SPEC.md:40,120 | kısmi |
| 4 | `PRODUCED` sabit kenar listesinde yok | seed/schema/relations.yaml:4 | SPEC.md:113, MCP-TOOLS.md:113, CURATOR.md:36 | kısmi |
| 5 | `Person`/`Team` türleri | SPEC.md:86 (yok) | types.yaml:15-16, relations.yaml:34 | hayır |
| 6 | `Decision.date` | make_seed.py:174 | SPEC.md:88 (yok) | hayır |
| 7 | Eval: 18 soru (q12 skip), `kb_related` işareti ölçülmüyor | questions.yaml:85,120-125 | SPEC.md:316 (≥30), :332-334 | hayır |
| 8 | Hook sözleşmesi: `stop` olayı, 1,5 sn | kb-hook.sh:17,24 | ARCHITECTURE.md:206,209, ROADMAP.md:62 | hayır |
| 9 | Arşiv yetkisi: onaylı mı doğrudan mı | SPEC.md:215, ARCHITECTURE.md:111 | SPEC.md:201, CLIENTS.md:38,78 | hayır |
| 10 | ADR-0016 güncel değil (`valid_from`, sinyal alanları, sunucu kapalıyken okuma) | adr/0016:15-18 | SPEC.md:78,87,95, CLAUDE.md:53 | kısmi |
| 11 | ADR-0006 "denetim kaydı git, küçük commit'ler" | adr/0006:33,35, CURATOR.md:13-14 | adr/0014:19-20,35 | hayır |
| 12 | Eski kavram/atıf: dosya izleyici, pass, ölü § numaraları, "frontmatter" | adr/0003:22,28, adr/0005:21-23, adr/0007:26, adr/0002:27, .gitignore:15 | README.md:22, adr/0014:10, SPEC §13-14 | hayır |
| 13 | H12 "hiçbir kayıt silinmez" koşulsuz; disk kaybında sinyal gider | SPEC.md:45,289 | adr/0017:62, SPEC.md:95 | hayır |
