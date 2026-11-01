# Ekip kaydı — agentkb

Model: ajan frontmatter'ından gelir, dispatch'te `model` verilmez (dev-flow §4). Lider ve entegratör: ana oturum.
Java dizinleri henüz yok; sahiplik modül oluşunca geçerlidir. Örtüşen dosyada tek yazar.

| Rol | Ajan | Sahiplik | Faz |
|---|---|---|---|
| Keşif | `codebase-explorer` | salt okunur | hepsi |
| Mimari (çapraz) | `solution-architect` | salt okunur; MCP/REST/hook sözleşmeleri | F1+ |
| Mimari (Java) | `java-architect` | salt okunur; modül sınırı, yazma yolu | F1+ |
| Uygulama (Java) | `java-developer` | `pom.xml`, `mvnw`, `kb-*/src/main` (Neo4j `GraphStore` dahil) | F1+ |
| Uygulama (Python) | `python-developer` | `seed/make_seed.py` | gerektikçe |
| Uygulama (Web) | `thymeleaf-developer` | `kb-server/src/main/resources/templates` (`th:` + `hx-` aynı dosyada tek yazar) | F5 |
| Uygulama (htmx) | `htmx-developer` | fragment sözleşmesi; karışık dosyada yalnız öneri | F5 |
| Test (Java) | `java-test-engineer` | `kb-*/src/test` | F1+ |
| Test (Web) | `thymeleaf-test-engineer`, `htmx-test-engineer` | web testleri | F5 |
| Kapsam planı | `qa-coordinator` | salt okunur | F2+ |
| İnceleme | `java-reviewer`, `general-code-reviewer` | salt okunur | ilgili faz |
| İnceleme (Web) | `thymeleaf-reviewer`, `htmx-reviewer`, `accessibility-reviewer` | salt okunur | F5 |
| Güvenlik | `security-reviewer` | salt okunur; secret, token, `OriginGuard`, `CypherGuard`, `kb run` | F2+ |
| Operasyon | `devops-specialist` | `integrations/`, `curator/run-curator.sh`, GitHub Actions | F2+ |
| Doküman | `documentation-specialist` | `README.md`, `docs/` (kabul edilmiş ADR değişikliği onayla) | hepsi |

## Havuzda yok
- **Neo4j uzmanı/reviewer'ı yok.** Geçici: `java-developer` yazar, `java-reviewer` + `solution-architect` inceler. Cypher ve
  gömülü Neo4j kararlarında sürüm `doc-lookup` ile doğrulanır. Gerekirse `build_agents.py` ile eklenir.
- **TamboUI, GraalVM native-image uzmanı yok.** `java-developer` yapar.

## Hakem dosyaları
`eval/questions.yaml`, `fixtures/scanner/expected.yaml`: hiçbir ajan önersiz değiştirmez (`CLAUDE.md` → Çalışma şekli).
