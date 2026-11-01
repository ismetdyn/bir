# Plan — F1 spike

Spec: `.engineering/archive/SPEC-f1-spike.md`. Sıralı tek hat, tek yazar → klasik dispatch; takım modu yok. Paralel yazar
yok. Tahmin: 1–2 saat; classpath çakışması çıkarsa yarım gün.

| Görev | Ajan | Scope | Depends | Kabul |
|---|---|---|---|---|
| T0 | lead | `.git` | — | `git init`, `f1-spike` dalı; commit yok |
| T1 | `java-developer` | `pom.xml`, `mvnw*`, `.mvn/**`, `kb-server/pom.xml`, `kb-server/src/main/**` | T0 | K1, K2 |
| T2 | `java-developer` | `kb-server/src/main/**` | T1 | K3 üretim kodu |
| T3 | `java-developer` | `kb-server/src/main/**` | T2 | K4 üretim kodu |
| T4 | `java-test-engineer` | `kb-server/src/test/**` | T3 | K2, K3, K5, K6 testleri; negatifler dahil |
| T5 | lead | geçici config (scratchpad) | T4 | K7 |
| T6 | lead | `docs/adr/0018-*.md` | T5 | K8 |
| T7 | `java-reviewer`, `security-reviewer` | salt okunur | T4 | CRITICAL/HIGH yok ya da düzeltildi |

Yeni bağımlılıklar (onay bu planla): `spring-boot-starter-web` (veya starter'ın getirdiği), `spring-ai-starter-mcp-server-webmvc`,
`org.neo4j:neo4j`, test için `spring-boot-starter-test`. Listede olmayan bağımlılık için durulur ve sorulur.
