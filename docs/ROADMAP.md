# agentkb — Yol Haritası (v0.3)

Her faz kendi başına kullanılabilir bir sonuç bırakır. Claude Code ile her fazı ayrı dal + pull request olarak ilerlet;
"Bitti tanımı" sağlanmadan sonrakine geçme. Faz başında maddeleri görev listesine dök. Sadelik ilkesi (ADR-0016) her
fazda geçerli: listede olmayan mekanizmayı ekleme, gerekiyorsa önce sor.

F0 ve F6 emekliye ayrıldı (ADR-0019).

```
F1 spike ──► F2 bilgi çekirdeği ──► F3 kb istemcisi (CLI/TUI)
                                └─► F4 küratör ──► F5 web GUI
```

---

## F1 — Spike

- [x] Spring Boot 4 + Spring AI 2.0.x MCP (STREAMABLE) + gömülü Neo4j aynı uygulamada açılıyor; `/mcp` (auto-config)
      ve `/mcp/curator` (MCP Java SDK ile elle) iki uç olarak çalışıyor; Claude Code ve Codex bağlanabiliyor.
- [x] Tam metin analizörleri listelendi; Türkçe "İzmir" testi.
- Opsiyonel LadybugDB ölçümü yapılmadı.

**Bitti tanımı:** Bulgular sıradaki boş ADR numarasıyla yazıldı. → ADR-0018; F2'ye girenler orada.

---

## F2 — Bilgi çekirdeği

- [ ] Maven çoklu modül iskeleti: `kb-api`, `kb-core`, `kb-server`, `kb-client`
- [ ] Graf modeli (SPEC §6), şema yükleyici (`schema/*.yaml`), kısıtlar ve tam metin indeksi
- [ ] `WritePolicy` (kb-core, saf, tablo güdümlü testler): kaynak sırası, tazeleme (`valid_from` değişmez), kapat +
      ekle, çelişki
- [ ] `WriteService` (tek yazıcı) + `EntityResolver` + `kb revert --batch`
- [ ] Markdown görünümü (SPEC §6.6) ve okuma araçları: `kb_search`, `kb_read`, `kb_related`, `kb_schema`, `kb_cypher`
      + `CypherGuard` (`secret_value` engeli dahil), `kb_stale`, `kb_brief`
- [ ] Okuma kuralı: hiçbir kayıt gizlenmiyor; güncel önce, eski değer, biten ilişki ve arşiv işaretli sonra; eski değer
      eşleşince güncel değer de yazılıyor; metin sınırları (`summary` 300, metin fact 500, `body` 4.000) yazarken
      doğrulanıyor (ADR-0017)
- [ ] `kb_remember` + PromptRing kanıt doğrulaması; secret görünümlü değer reddi; `kb_propose` → `Proposal`
      (hedefe `ABOUT`)
- [ ] Secret'lar: `Secret` düğümü, `GET/PUT /api/v1/secrets/{id}`, veri dizini 0700; `secret_value` anlık görüntüye,
      loga ve araç çıktılarına girmiyor (test)
- [ ] Tarayıcı: önce SpringConfig, DotEnv, BuildFile; sonra Compose, K8s, GitlabCi, Ansible; `kb_scan` + allowed-roots;
      `fixtures/scanner` golden testi (sahte secret dizeleri hiçbir çıktıda yok)
- [ ] Anlık görüntü + `kb sync` + `kb restore`; iki geçici repo ile senkron testi; systemd timer; commit öncesi satır
      sayısı kontrolü; force-push yok; `kb check`'te `git fsck`
- [ ] Kayıpsızlık testleri: restore ve senkron yereldeki secret değerlerini ve sinyalleri koruyor; sınırdan uzun eski
      kayıt içeri alınırken reddedilmiyor; eski IP ve arşivli varlık aramada işaretli olarak çıkıyor (eval q16–q18)
- [ ] Hook uçları (`/hooks/session-start`, `/user-prompt-submit`, `/post-tool-use`) + `integrations/kb-hook.sh`;
      yakalamada secret maskeleme (test)
- [ ] Token kapsamları + `OriginGuard`; `kb init` (geçici olarak sunucu jar'ında); systemd servisi
- [ ] `EvalRunner` + `eval/questions.yaml`; `seed/` ile yüklenen örnek veride CI eşiği
- [ ] AGENT-RULES devreye (sunucu kapalıyken anlık görüntüden salt okunur okuma dahil); yerleşik hafızaları kapatma
      (önce `kb import-memories`)

**Bitti tanımı:** Yeni bir repoda oturum açınca bilgiler otomatik toplanıyor; ajan bildiğini sormuyor, bilmediğini sorup
kaydediyor; parola gereken bir komutu `kb run` ile değeri görmeden çalıştırıyor; "eskiden IP neydi?" cevaplanıyor ve
eski IP ile arama sunucuyu buluyor; anlık görüntüden restore aynı grafı veriyor.

---

## F3 — kb istemcisi: CLI + TUI

- [ ] REST API v1 (bkz. CLIENTS.md)
- [ ] `kb-client`: picocli komutları, REST istemcisi, `kb run` (placeholder → ortam değişkeni, akış süzgeci + maskeleme
      testleri, değer bu bilgisayarda yoksa net hata), `kb secret`
- [ ] `kb check` doküman bütünlüğü (dosya var mı, sha256 tutuyor mu); `kb doctor` git-lfs kontrolü
- [ ] Doküman deposu: `POST /api/v1/documents`, `kb doc add` (secret taraması, içerik adresli `blobs/`, 20 MB üstü
      `git lfs track`), `Document` düğümü, `kb_read doc:…`, `kb_remember(document=…)` (SPEC §6.7)
- [ ] TamboUI TUI: arama, detay, fact/ilişki düzenleme, arşiv, çelişkiler, öneriler, aktivite + geri alma, bayat liste
- [ ] GraalVM native-image (`kb` < 50 MB, anında açılış)

**Bitti tanımı:** Bilgi bankasındaki her işlemi TUI'den yapabiliyorsun.

---

## F4 — Küratör v1

- [ ] Sinyaller (`Signal` düğümleri, silinmez): boş aramalar, ham soru-cevaplar, tarama bulguları, çelişkiler, içe
      aktarılan hafızalar; kapanınca `outcome` ve `PRODUCED`; bilgi taşıyanlar işlenmeden önce aramada `ham`; TUI'de
      "Atılanlar" ekranı ve yeniden açma; aynı `kind` + `key` tek düğüm (`count`, `last_at`); ilgili kayda `ABOUT`
- [ ] `/mcp/curator` araçları (bkz. CURATOR.md) ve sunucuda zorlanan sınırlar
- [ ] Parti: `cur_begin_batch` → yazmalar parti kimliğiyle → `cur_end_batch` → eval + `kb check` → gerilediyse geri alma
      (partinin kapattığı sinyaller yeniden açılır; test)
- [ ] `run-curator.sh`; `agentkb-curator.timer`; `/api/v1/curator/should-run`
- [ ] Şişmeye karşı: kullanılmayan varlıklar için arşiv önerisi; `kb check` büyüme raporu (`cur_metrics`'e girer)
      (ADR-0017)

**Bitti tanımı:** Kasıtlı olarak kötüleştirilmiş bir partinin otomatik geri alındığı test var.

---

## F5 — Web GUI

- [ ] `kb ui` (tek kullanımlık giriş linki) + oturum + CSRF + CSP
- [ ] Sayfalar: arama, kayıt detayı/düzenleme, graf gezgini (Cytoscape.js), salt okunur Cypher konsolu, çelişkiler,
      öneriler, aktivite + geri alma, secret listesi (değer yok), küratör çalışmaları ve eval geçmişi

**Bitti tanımı:** Graf üzerinde gezinerek etki analizi yapabiliyorsun; çelişkileri ve önerileri GUI'den çözebiliyorsun.

---

## Sürekli

- ArchUnit katman testleri, Spotless, JSpecify
- GitHub Actions: build, test, eval, gitleaks
- Conventional commits; önemli kararda ADR

## Sonrası (ihtiyaç ölçülünce)

| Fikir | Ne zaman |
|---|---|
| Anlamsal arama (yerel embedding + Neo4j vektör indeksi) | Eval'de tam metin aramanın kaçırdığı soru oranı > %15 |
| Secret değerlerinin bilgisayarlar arası senkronu (yerleşik anahtarla şifreleme) | Aynı secret'lar ikinci bir bilgisayarda gerçekten gerekince |
| Kullanıma göre önem puanı | Eval'de arama sıralaması sorun olunca |
| Takım kullanımı | Ekip arkadaşı kullanmak isteyince |
| Salt okunur Markdown dışa aktarımı (Obsidian'da göz atmak için) | İstenirse |
| agentguard (ajan işinin kalitesi) ayrı proje | F2–F5 bitince kullanıcıya hatırlat |
