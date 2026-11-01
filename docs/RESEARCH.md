# Benzer Projeler ve Alınacak Dersler

> Araştırma tarihi: 2026-10-29. Sürüm ve yıldız sayıları değişir; kurmadan önce repoyu kontrol et.
>
> §1–§10'daki "Bizim için" notları yazıldıkları turun (v0.1–v0.2) kararlarını yansıtır. v0.3'te bilgi doğrudan
> grafta durur (ADR-0014); Markdown not sözdizimi, vault, `_inbox` ve Obsidian kalktı. "Veritabanını ana kaynak
> yapmak" itirazının v0.3'teki karşılığı: tek yazıcı, `SUPERSEDES` ile korunan geçmiş, `kb revert --batch` ve git
> anlık görüntüsü. Bkz. §11.

## Özet tablo

| Proje | Benzerliği | Ana kaynak | Al | Alma |
|---|---|---|---|---|
| Basic Memory | En yakın | Markdown + SQLite indeks | Gözlem/ilişki sözdizimi, şema doğrulama, `build_context` | AGPL; kendi format detayları |
| agentcairn | Çok yakın | Obsidian vault + yeniden kurulabilir DuckDB | Provenance, tazelik, `supersession`, `doctor` | LLM ile otomatik özetleme |
| Global Agent Memory | Yakın | Obsidian vault + yerel daemon | İnceleme kuyruğu, korumalı notlar, systemd daemon | Dashboard (Obsidian yeterli) |
| neo4j-contrib/mcp-neo4j | Graf tarafı | Neo4j (ana kaynak) | Cypher aracı tasarımı, namespace | Veritabanını ana kaynak yapmak |
| Semantic Markdown → Neo4j / Neo4j Graph View eklentisi | Format | Markdown | `- tip [[not]]` tipli link sözdizimi | Canlı senkronun kırılganlığı |
| KG Forge (Obsidian eklentisi) | Şema | Markdown | Tipli üçlüler, provenance, "yeterlilik soruları" | — |
| llmwiki-harness | Yakın | Markdown + SQLite kenar tablosu | Kırık link/öksüz not tespiti, 2 adımlı gezinme | — |
| knowledge-gateway | Yakın | Obsidian vault + git | Takım için paylaşımlı sunucu modu, token | Ansible/kod grafiği (bizim kapsamda değil) |
| Karpathy "LLM Wiki" deseni | Felsefe | Markdown | `index.md`, `log.md`, düzenli "lint" | — |
| Graphify | Tamamlayıcı | Kod grafiği | Kenarları EXTRACTED / INFERRED diye etiketleme | — |
| deja-vu | Tamamlayıcı | Oturum geçmişi indeksi | Ayrı araç olarak birlikte kullan | — |

---

## 1. Basic Memory — en yakın akraba

- Notlar markdown, veritabanı ikincil indeks; `[[wiki link]]` ve tipli ilişkiler grafı oluşturuyor.
- v0.19 ile gelen **şema sistemi (Picoschema)**: not türlerinin beklenen gözlem ve ilişkileri içerip içermediğini
  doğruluyor; mevcut notlardan şema çıkarma (`schema_infer`), doğrulama ve sapma tespiti var.
- Ayrıca `memory-defrag`, `memory-reflect` gibi bakım skill'leri var: bilgi bankasını düzenli birleştirme ve temizleme.

**Bizim için:** Not sözdizimini Basic Memory ile uyumlu tutuyoruz (`- [kategori] metin`, `- ilişki [[Hedef]]`).
Böylece bir gün Basic Memory'ye geçmek veya onun skill'lerini kullanmak mümkün kalır. Şema doğrulama fikrini
`_schema/types.yaml` ile alıyoruz. Bakım skill'i fikrini `kb check` + `kb stale` ile karşılıyoruz.

- https://github.com/basicmachines-co/basic-memory
- https://docs.basicmemory.com/whats-new/changelog

## 2. agentcairn — "vault ana kaynak, indeks atılabilir"

- Obsidian vault ana kaynak; üstünde **her an yeniden kurulabilir** bir DuckDB indeksi var.
- Claude Code ve Codex eklentileri; oturum başında hatırlatma, oturum sonunda özetleme.
- Ayrı bir Obsidian eklentisi notların kaynağını (provenance), güncelliğini, önemini ve
  hangi notun hangisinin yerini aldığını (supersession) gösteriyor.

**Bizim için:** `source`, `verified`, `status` alanları ve ileride `superseded_by` ilişkisi doğrudan buradan.
`kb doctor` fikri de buradan. Oturum sonunda LLM ile otomatik özetlemeyi almıyoruz (kapsam dışı).

- https://github.com/ccf/agentcairn
- https://www.obsidianstats.com/plugins/agentcairn

## 3. Global Agent Memory — inceleme kuyruğu

- Yerel bir MCP daemon'u (Linux'ta systemd), Obsidian uyumlu vault, Claude Code ve Codex entegrasyonu.
- Dashboard'da **inceleme kuyruğu** ve **korumalı hafızaya erişim talebi** var.
- Henüz çok erken sürüm (v0.1.x).

**Bizim için:** `_inbox/` onay kuyruğu ve `agent: deny` korumalı notlar bu fikrin karşılığı.

- https://github.com/ozankasikci/global-agent-memory

## 4. Neo4j'nin resmi MCP sunucuları

- `mcp-neo4j-cypher` (Cypher sorgu aracı) ve `mcp-neo4j-memory` (bilgi grafiği hafızası) aynı repoda;
  araçları ad alanı (namespace) ile ayırma desteği var.
- Ancak veritabanı ana kaynak; ajan doğrudan düğüm/ilişki yazıyor.

**Bizim için:** `kb_cypher` aracının tasarımında örnek alınır. Ana kaynağın veritabanı olması tam olarak
kaçındığımız şey: ajan yanlış yazarsa geri almanın kolay yolu yok, git'te inceleme yok.

- https://github.com/neo4j-contrib/mcp-neo4j

## 5. Markdown → Neo4j dönüştürücüler

- **Semantic Markdown converter / Neo4j Graph View eklentisi:** Obsidian vault'unu Neo4j'ye aktarıyor;
  `- linkType [[not]]` biçiminde tipli bağlantı sözdizimi kullanıyor. Yazarı canlı senkronizasyonun
  "hâlâ çok hatalı" olduğunu belirtiyor.
- **KG Forge:** Frontmatter ve wikilink'lerden tipli üçlüler çıkarıyor, her olguya kaynak/güven/zaman ekliyor,
  vault şemasını doğruluyor, Neo4j Cypher MERGE olarak dışa aktarıyor; ayrıca "yeterlilik soruları"
  (competency questions) ile şemanın kapsamını test ediyor.

**Bizim için:** (1) Tipli link sözdizimi seçimimiz bu projelerle aynı yönde. (2) Zor kısım artımlı senkronizasyon;
bu yüzden içerik hash'i, iki geçişli tarama ve "her an yeniden kur" garantisi mimarinin merkezinde.
(3) Yeterlilik soruları = bizim `eval/questions.yaml`.

- https://pypi.org/project/semantic-markdown-converter/
- https://community.obsidian.md/plugins/kg-forge

## 6. llmwiki-harness ve knowledge-gateway

- **llmwiki-harness:** Wikilink'leri indeksleme anında SQLite kenar tablosuna çıkarıyor; geri bağlantılar,
  2 adımlı ağırlıklı gezinme, kırık link ve öksüz not tespiti var.
- **knowledge-gateway:** Obsidian vault'unu MCP üzerinden sunuyor; yerel mod ve token'lı paylaşımlı takım sunucusu modu var.

**Bizim için:** `kb check` kırık link + öksüz not raporu verir. Takım modu v1 sonrasına.

- https://pypi.org/project/llmwiki-harness/
- https://pypi.org/project/knowledge-gateway/

## 7. Karpathy "LLM Wiki" deseni

Ham kaynaklar, LLM'in bakımını yaptığı bağlantılı markdown wiki ve davranış şeması; `index.md` ve `log.md`
kuralları; düzenli "lint" geçişi.

**Bizim için:** Vault'ta `index.md` (insan + ajan için giriş noktası) ve `kb check` = lint.

## 8. Tamamlayıcı araçlar (birlikte kullan, yeniden yazma)

- **deja-vu:** Ajanların diske zaten yazdığı oturum geçmişlerini indeksler, LLM kullanmaz, sırları ayıklar.
  agentkb "bilinçli bilgi", deja-vu "ne yaşandı" sorusunu cevaplar. — https://github.com/vshulcz/deja-vu
- **Graphify:** Kod grafiği. Kenarları EXTRACTED (kaynakta açıkça var) / INFERRED (çıkarım) diye etiketliyor;
  biz de kenarlara `source: explicit|inline` yazıyoruz. — https://github.com/Graphify-Labs/graphify

## 9. Teknoloji doğrulamaları

| Konu | Bulgu | Kaynak |
|---|---|---|
| Kùzu | Ekim 2025'te arşivlendi; en olgun devamı Ladybug | https://www.puppygraph.com/blog/what-is-kuzudb |
| Neo4j embedded | Community sürümü Java kütüphanesi olarak gömülebiliyor; güncel sürüm 2026.06 | https://neo4j.com/docs/java-reference/current/java-embedded/setup/ |
| Neo4j Java gereksinimi | 2025.01+ Java 21; 2025.10+ Java 25 de destekleniyor | https://neo4j.com/docs/operations-manual/current/installation/requirements/ |
| Spring AI MCP | 2.0'da `@McpTool`; 2.0.1'de `protocol: STREAMABLE` verilmezse SSE açılıyor | https://dev.to/jean_claude_van_debug/build-an-mcp-server-in-java-with-spring-ai-oauth-4nb6 |
| Spring AI 2 tabanı | Spring Boot 4.1 | https://github.com/apache/camel-spring-boot/pull/1871 |
| Codex HTTP MCP | `url` + `bearer_token_env_var`; düz `bearer_token` streamable HTTP'de desteklenmiyor | https://developers.openai.com/codex/mcp |
| Codex hook'ları | `~/.codex/hooks.json`, `SessionStart` → `additionalContext`; Codex hook bağlamını transcript'te gösteriyor | https://codex.danielvaughan.com/2026/05/25/codex-cli-hooks-after-ga-event-model-trust-verification-production-patterns/ |
| Claude Code hook'ları | `SessionStart` → `hookSpecificOutput.additionalContext` | https://claudefa.st/blog/tools/hooks/session-lifecycle-hooks |
| DNS rebinding | Resmi MCP SDK'larında (TS, Python, Go) yerel HTTP sunucular için koruma varsayılan kapalıydı; CVE'ler çıktı | https://cvepremium.circl.lu/vuln/cve-2025-66414 |

## 10. Kalite, hook'lar ve kendini düzenleyen yapılar (araştırma 2)

### Ajanlara kural vermek işe yarıyor mu?
- **Evaluating AGENTS.md (ETH Zürih, 2026):** Repo bağlam dosyaları ortalamada görev başarısını artırmadı, çıkarım
  maliyetini %20'den fazla artırdı. Ajanlar talimatlara iyi uydu; repo genel bakış metinleri yararsızdı; en faydalı
  oldukları yer standart dışı pratikleri belirtmek. → Ev kurallarını yaz, ders kitabı ilkelerini değil.
  https://arxiv.org/abs/2602.11988
- **On the Impact of AGENTS.md Files on Efficiency (2026):** 124 PR'da AGENTS.md varken medyan süre ~%28,6, çıktı
  token'ı ~%16,6 daha düşük; tamamlama benzer (ilişki, nedensellik değil). → Kısa ve spesifik dosyalar verimlilik getirir.
  https://arxiv.org/abs/2601.20404

### Ajanlar kapıları oynar mı?
- **ImpossibleBench:** Testlerin şartnameyle bilerek çeliştiği görevlerde modeller testleri değiştirerek, operatörleri
  ezerek, durum tutarak veya özel durum kodlayarak hile yaptı. Testleri gizlemek hileyi neredeyse sıfırladı ama gerçek
  performansı düşürdü; salt okunur testler orta yol oldu; "imkânsız" deyip çıkma hakkı bazı modellerde hileyi belirgin
  azalttı. → Hakemi koru, çıkış hakkı ver.
  https://www.lesswrong.com/posts/qJYMbrabcQqCZ7iqm/impossiblebench-measuring-reward-hacking-in-llm-coding-1

### Ölçülemeyen mimari kalite
- **Agentic Fitness Functions (InfoQ):** Deterministik kapılar sert kalsın; LLM değerlendiriciler kriter bazlı, kanıt
  paketiyle, önce tavsiye modunda çalışsın; 20–50 geçmiş değişiklikle kalibre edilsin; belirsizlik insana gitsin;
  tekrarlayan doğrulanmış bulgular deterministik kurala terfi etsin; rubrikler sürümlensin.
  https://infoq.com/articles/agentic-fitness-functions-evolutionary-architecture

### Kendini düzenleyen hafıza
- **ACE (Agentic Context Engineering):** Bağlamı üretim/yansıtma/küratörlük döngüsüyle evrilen bir "playbook" olarak
  ele alıyor; yinelenen yeniden yazımın ayrıntıyı aşındırdığını ("context collapse") ve kısa özet eğilimini ("brevity
  bias") gösteriyor; yapılandırılmış artımlı güncellemelerle ajan benchmark'larında +%10,6.
  https://huggingface.co/papers/2510.04618
- **Letta "dreaming":** Arka plan alt ajanları son konuşmaları gözden geçirip dersleri git tabanlı hafıza dosya
  sisteminde birleştiriyor; tetikleyici adım sayısı veya bağlam sıkıştırması; isteğe bağlı ikinci gözden geçirme.
  https://docs.letta.com/guides/agents/architectures/sleeptime
- **Claude Code Auto Dream** (resmi duyurusu yapılmamış, kademeli): son birleştirmeden beri 24 saat ve 5 oturum geçince
  hafıza dosyalarını birleştiriyor, çelişenleri siliyor, tarihleri mutlaklaştırıyor, indeksi budayıp güncelliyor.
  https://claudefa.st/blog/guide/mechanics/auto-dream
- **Codex Memories / Dreaming:** Codex'in yerel hafızası konsolidasyon geçişleriyle güncelleniyor.
  https://codex.danielvaughan.com/2026/06/12/codex-cli-memory-over-personalisation-sycophancy-risk-configuration-defence/
- **Anthropic — Effective context engineering:** En küçük yüksek sinyalli bağlam; "just-in-time" erişim; modeller
  geliştikçe daha az kuralcı mühendislik; en basit çalışan yaklaşım.
  https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents

### Hook yetenekleri
- **Claude Code:** 30'dan fazla olay; MCP araçları `mcp__<sunucu>__<araç>` adıyla eşleşir; PreToolUse `allow/deny/ask/defer`;
  Stop `decision: block` ile devam ettirir, girdide `last_assistant_message` var; `prompt` ve `agent` tipi LLM
  değerlendirmeli hook'lar (agent deneysel); varsayılan zaman aşımı command için 600 sn.
  https://code.claude.com/docs/en/hooks
- **Codex:** Hook'lar varsayılan açık; PreToolUse/PostToolUse Bash, `apply_patch` (Edit/Write olarak da eşleşir) ve MCP
  araçlarını eşler; `ask` henüz yok; Stop `decision: block` ile `reason`'ı devam istemine çevirir.
  https://learn.chatgpt.com/docs/hooks
- **Codex `request_user_input`:** Varsayılan sadece plan modunda; normal modda bir feature flag ve AGENTS.md talimatı gerekir.
  https://www.80aj.com/2026/09/18/codex-user-questions-config/
- **AskUserQuestion + PreToolUse:** PreToolUse hook'u varken cevapların boş döndüğü bir hata Ocak 2026'da kapatıldı;
  PostToolUse ile birlikte sorun bildirilmemişti. Soru-cevap yakalamayı F2'de gerçek sürümle doğrula.
  https://claudeissues.com/issue/13439-pretooluse-hook-causes-askuserquestion-tool-to-return-empty-responses

### Secret'lar ve ajanlar
- **AWS — Use secrets safely with AI coding agents:** Ajan secret'a isimle (dinamik referans) atıf yapar; bir exec
  sarmalayıcısı değeri alt süreçte çözer; PreToolUse hook'u doğrudan okumayı engeller; "en iyi çaba, güvenlik sınırı değil".
  https://docs.aws.amazon.com/secretsmanager/latest/userguide/retrieving-secrets-ai-agents.html

### İstemci teknolojileri
- **TamboUI:** Şubat 2026'da çıkan, Ratatui'den esinlenen Java TUI kütüphanesi; GraalVM ile ~10 MB native binary;
  Maven ve Spring ekosistemindeki araçlarda kullanılıyor; 0.x sürümü. https://infoq.com/news/2026/05/tamboui-java-tui
- **Obsidian Bases:** Notların özelliklerini tablo/liste/kart/kanban/harita görünümünde gösterip düzenleme, filtre ve
  formül. https://obsidian.md/help/bases

## 11. Graf motoru alternatifleri ve dış inceleme (araştırma 3, v0.3)

- **Kùzu:** GitHub reposu 10 Ekim 2025'te arşivlendi; hata ve güvenlik düzeltmesi gelmiyor.
  https://www.theregister.com/2025/10/14/kuzudb_abandoned/
- **LadybugDB (Kùzu'nun aktif devamı):** MIT lisanslı; Ekim 2026'da da sürüm çıkarıyor; Java paketi Maven Central'da
  (`com.ladybugdb:lbug`); Kùzu'nun "düğüm başına tek etiket" kısıtını kaldırdı.
  https://github.com/LadybugDB/ladybug/releases · https://central.sonatype.com/artifact/com.ladybugdb/lbug
  - Eşzamanlılık: bir veritabanını aynı anda tek süreç yazma modunda açabilir; okuyan ve yazan süreçler birlikte güvenle
    çalışamaz. Kendi dokümanı çok süreçli kullanım için tek bir API sunucusu öneriyor (agentkb'nin yaptığı).
    https://docs.ladybugdb.com/concurrency/
  - Aynı tuzağa düşen bir proje: https://github.com/CodeGraphContext/CodeGraphContext/issues/1683
  - Native kod JVM içinde çalışır; çökme bütün süreci düşürür (ör. https://github.com/LadybugDB/ladybug/issues/1007).
- **Kùzu sonrası manzara:** https://gdotv.com/blog/kuzu-legacy-embedded-graph-database-landscape/
- **Sonuç:** Bizim ölçekte (birkaç bin kayıt) hız farkı hissedilmez; Neo4j kalır. LadybugDB'nin tek gerçek artısı MIT
  lisansı; F1 spike'ında ölçülebilir.
- **Dış inceleme (ChatGPT, 2026-10-31):** Kitin çoğunu korumayı önerdi. Alınanlar: veri ile metadata arasındaki bağın
  graf kenarlarıyla kurulması (`SourceFile`, `SUPERSEDES`), sürüm geçmişinin korunması. Alınmayanlar: kendi binary nesne
  deposu, kendi Merkle ağacı ve git benzeri sürümleme (git zaten bu), PostgreSQL, ek alanlar ve protokoller
  (ADR-0016).
- **Dış inceleme, ikinci bakış (2026-11-01):** Graf ana depo olduktan ve "silme ve gizleme yok" kuralı geldikten sonra
  ChatGPT'nin şu önerileri de alındı: `valid_from`, sinyal tekrar anahtarı, öneri ve sinyal referanslarının
  `ABOUT` kenarı olması, sunucu kapalıyken anlık görüntüden salt okunur okuma, satır sayısı kontrolü, `git fsck`,
  korumalı dal ve `LICENSE`. Alınmayanlar değişmedi.
