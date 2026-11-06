# Todo — F2 bilgi çekirdeği

Plan: `.engineering/tasks/plan.md`. Plan onaylandı 2026-11-01; devir: `.engineering/tasks/handoff.md`. Geçmiş: F1 (ADR-0018), kapsam temizliği (ADR-0019).

- [x] G0 Dokümanları kararlarla hizala (ADR-0022)
  Scope: docs/**
  Kanıt: `ls docs/adr/0022*` → exit 0; `git diff --stat -- docs` → 6 dosya; iki bağımsız kontrol: 1 MEDIUM düzeltildi (`kb secret` F2'de set|show), kalanı LOW. `CLAUDE.md` 3 satır hizalandı.
- [x] G1 `OriginGuard`, loopback reddi, hata gövdesinde iç detay yok
  Scope: kb-server/**
  Depends: G0
  Kanıt: `./mvnw -B clean verify` → exit 0, 332 test, 0 skip; korumalar tek tek kapatıldı → `OriginGuard` 98, `LoopbackAddressCheck` 11, `McpErrorBodyFilter` 25, `RequestBodyLimitFilter` 15 test düştü; `Http11Processor` maskesi → 2/2 düştü; `quality_gate.py` → exit 0. Gerçek jar, `-Xmx256m`, 500 MB → 413, OOM yok. `java-reviewer` + `security-reviewer`: yalnız LOW. Açık: `/mcp/curator` kaçan istisna logu doğrulanmadı (G3 başında denenir).
- [x] G2 Script'ler token'sız
  Scope: integrations/kb-hook.sh, curator/mcp.json, curator/run-curator.sh
  Depends: G1
  Kanıt: `python3 -I scratchpad/hook_probe.py integrations/kb-hook.sh` → failures=0, exit 0 (stub sunucu: Authorization yok, stop reddedilir, sunucu kapalı boş + exit 0, 2 sn zaman aşımı); `bash -n` iki script → exit 0; `json.tool curator/mcp.json` → exit 0; token grep → eşleşme yok (exit 1). Gerçek sunucuya bağlantı CP1'de.
- [x] CP1 Claude Code ve Codex gerçek bağlantı
  Kanıt: `claude -p --mcp-config` (2.1.316) → exit 0, `pong`/`cur_pong`; `codex exec` (0.161.0, araç başına `approval_mode`) → exit 0, `pong`/`cur_pong`; `curl` sahte `Host`/`Origin` iki uçta → 403; `kb-hook.sh session-start` gerçek sunucuda (uç henüz yok) → exit 0.
- [x] G3 `kb-core`, `WritePolicy`, `Ids`, ArchUnit, Spotless
  Scope: pom.xml, kb-core/**, kb-server/src/test/**
  Depends: G1
  Kanıt: `./mvnw -B clean verify` → exit 0, kb-core 492 + kb-server 363 test, 0 skip; Spotless verify'da; `quality_gate.py` → exit 0; `WritePolicy` 291 vaka, 25 mutasyonun 24'ü öldü (kalan eşdeğer). `/mcp/curator` log sızıntısı gerçekti: RED → `curatorMcpEndpoint` maskelendi. Lider kararı: kaynak `agent` kalan çözülmüş değer `unverified` (SPEC §7 tablosu).
- [x] G4 Seed'e İzmir + q12 açma; seed yükleme ve byte-aynı export
  Scope: kb-server/**, kb-core/**, seed/**, eval/questions.yaml
  Depends: G3
  Kanıt: `./mvnw -B clean verify` → exit 0, kb-core 505 + kb-server 397, 0 skip; `quality_gate.py` → exit 0; seed export `cmp` ile byte-aynı (tek JVM, yeniden açılış, gerçek jar); `make_seed.py` iki koşu `cmp` aynı, 45 düğüm / 57 kenar. İnceleme (`java-reviewer` + `solution-architect`) 8 MEDIUM: 6'sı testle düzeltildi (`closedByRescan`, `GraphSchema`, fulltext drift, `Times.format`, env), 2'si aşağıdaki dilimlere.
- [x] CP2 Determinizm + Neo4j bellek ölçümü
  Kanıt: seed export 4 yoldan `cmp` aynı; 10.035 düğüm + 12.711 kenar içeri alma 3,6 sn, heap tepe 265 MB, OOM yok (`-Xmx512m`); 16 MiB × 8+ eşzamanlı → OOM ve kilitlenme ölçüldü → kullanıcı kararı 1 MiB + `-XX:+ExitOnOutOfMemoryError`; yeniden ölçüm 200 × 1 MiB × 4 tur → hepsi 200, OOM 0, RSS tepe 1,07 GB; `write`↔`snapshot` döngüsü kırıldı + ArchUnit döngü kuralı; `./mvnw -B clean verify` → exit 0 (505 + 400); `quality_gate.py` → exit 0; `java-reviewer` yalnız LOW (doküman, düzeltildi).
- [x] G5 Markdown görünümü + temel okuma araçları
  Scope: kb-core/**, kb-server/**
  Depends: G4
  Kanıt: `./mvnw -B clean verify` → exit 0 (kb-core 528, kb-server 456); önce RED: 23 görünüm satırı derleme RED, 56 MCP testi "Tool not found"; `kb_search "192.0.2.20"` SPEC satırını birebir verir (q16), arşiv işaretli sonra (q17), İzmir 4 yazımla (q12), alias'lar aranıyor; secret değeri hiçbir çıktıda yok; `quality_gate.py` → exit 0. İnceleme CP3'te.
- [x] G6 `CypherGuard`, kalan okuma araçları, `EvalRunner`
  Kanıt: `./mvnw -B clean verify` → exit 0 (kb-core 536, kb-server 648); `./mvnw -B -Peval verify` → exit 0, 18/18 (skor 1,0), kırık beklenti kapıyı düşürüyor (test); `CypherGuardTest` 113 satır, düzeltmeler kapatılınca 11 düşer; `quality_gate.py` → exit 0.
  Açık (G4'ten): `schema/*.yaml` yükleyici (ilişki izin listesi CypherGuard'a gerekir). Alias araması G5'te testle doğrulandı.
  Scope: kb-server/**, pom.xml
  Depends: G5
- [x] CP3 Eval skoru + analizör kararı
  Kanıt: eval 18/18 → analizör `standard-folding` kalır (ADR-0022 karar 27). `security-reviewer` iki tur: CR yorum, Unicode boşluk, anahtar kelime değişken adı, `debug.log` sorgu metni bulgularının hepsi RED → düzeltildi; kalan LOW'lar kayıtlı (karar 24–25). Codex 0.161.0 `codex exec` ayarsız `kb_search` → exit 0, doğru çıktı (karar 28); Claude Code `claude -p` → exit 0.
- [x] G7 `WriteService`, `kb_remember`, `kb_propose`, Signal
  Kanıt: `./mvnw -B clean verify` → exit 0 (kb-core 3065, kb-server 815); `-Peval` 18/18; `quality_gate.py` → exit 0. Politika 2400 hücreli özellik testi: mevcut fact'te yalnız `observed_at` ileri veya kapanış (`valid_to ≥ valid_from`); partiyi geri alma hazırlık testi grafı birebir geri veriyor; `java-reviewer` + `security-reviewer` iki tur, MEDIUM'ların hepsi RED → düzeltildi (port hedefi, kanıt parça eşleşmesi, `Basic` biçimleri). Açık küçükler G8 başında.
  Açık (G3'ten): daha eski `valid_from` ile kapatmada `valid_to < valid_from` olabiliyor (önce test). Kural 4 yeniden tarama G4'te `closedByRescan` ile çözüldü.
  Açık (G4'ten, lider kararı): yazma yolu her etiket ve kenar türünde var olan `id`'yi reddeder (uygulama düzeyi kontrol, anlık görüntü biçimi değişmez); yeni ilişki türü ilk yazmadan önce `GraphSchema.ensure`.
  Scope: kb-server/**, kb-core/**
  Depends: G4, G5
- [x] G8 Secret, `kb run`, `kb-client`
  Kanıt: `./mvnw -B clean verify` → exit 0 (kb-core 3101, kb-server 834, `SecretLeakIT` 1, kb-client 22); `-Peval` 18/18; `quality_gate.py` → exit 0. Uçtan uca: `kb secret set` → exit 0; `kb run` düz + base64 iki satırı `****` verdi, çıkış kodu taşındı (5); değer yoksa komut çalışmadı (exit 1). `SecretLeakIT` mutasyonu (export süzgeci kaldırıldı) → `snapshot` + `git history` yakalandı.
  Scope: kb-server/**, kb-client/**, pom.xml
  Depends: G7
- [x] CP4 `security-reviewer` + `SecretLeakIT`
  Kanıt: HIGH (`kb_remember` saklı secret değerini kabul ediyordu, 10 yolda göründü) → reddediliyor; MEDIUM'lar RED → düzeltildi (NUL'lu değer stderr'e açık basılıyordu; SIGTERM'de `/tmp` secret dosyası kalıyordu). Kalan LOW'lar G9 başında veya F3'te (stdout yönlendirilince `kb secret set` yankısı → F3 TUI ile).
- [x] G9 Hook uçları, recall, brief
  Kanıt: `./mvnw -B clean verify` → exit 0 (kb-core 3101, kb-server 901, `SecretLeakIT` 1, kb-client 38); `-Peval` 18/18; `quality_gate.py` → exit 0. Gerçek `claude -p` (2.1.318) ile 3 hook payload'ı ölçüldü, `additionalContext` bağlama giriyor; Codex biçimleri v0.161.0 kaynağından (ölçülmedi). Uçtan uca `kb-hook.sh` + jar: brief 3 kural, arşivli komşu işaretli sonda. İnceleme: hook'un loopback dışına ve proxy'ye veri taşıması, Türkçe kesme ekli recall, kesilip birleşmeden önce maskelenmeyen kayıtlı secret, brief'te veri başlığı yok → RED → düzeltildi.
  Açık (LOW, G10 başında): PromptRing kayıt boyutu ve 2 sa sonra boşaltma; tek capture isteğinde soru sayısı sınırı.
  Scope: kb-server/**, integrations/**
  Depends: G6, G7
- [x] G10 Tarayıcı: SpringConfig, DotEnv, BuildFile
  Kanıt: `./mvnw -B clean verify` → exit 0 (kb-core 3111, kb-server 987 + `SecretLeakIT`, kb-client 38); `allowed-roots` dışı, `..`, symlink → `{error, hint}`; iki sahte parola yalnız `secret_value`'da (`SecretLeakIT` tarama raporu dahil); `finding` çok değerli (kullanıcı), kapanma kuralı `WritePolicy`'de.
  Scope: kb-server/**
  Depends: G8, G9
- [x] G11 Tarayıcı: Compose, K8s, Ansible
  Kanıt: golden `expected.yaml` 40/40, `merge_with_seed` 6/6; `security-reviewer` + `java-reviewer` MEDIUM'ları RED → düzeltildi (URL userinfo/query, silinmiş dosyanın bulgusu, aynı adlı iki parola, iki dosyada aynı değer). Kalanlar CP5 kapanışında.
  Scope: kb-server/**
  Depends: G10
- [x] CP5 H2 ≥ %95 + tarama süresi
  Kanıt: H2 17/17 = %100 (fixture, satırı birebir 23/23); 500 dosya değişmemiş yeniden tarama 18/18 koşuda added=0 closed=0; `ScanPerformanceTest` (çağrı sayısı doğrusal); `./mvnw -B clean verify` → exit 0 (kb-core 3168, kb-server 1007, `SecretLeakIT` 1, kb-client 38); `-Peval` 18/18; `quality_gate.py` → exit 0. Süre: yük 7–13'te ölçülemedi (17 sn); boş makinede CP6'da yeniden ölçülür (ADR-0022 karar 37).
- [x] G12 Revert, restore, check
  Kanıt: `./mvnw -B clean verify` → exit 0 (kb-core 3168, kb-server 1054 + `SecretLeakIT`, kb-client 51); `-Peval` 18/18; `quality_gate.py` → exit 0; 10.000 düğüm + 8.000 kenar restore 1,9 sn (`-Xmx512m`, sınır 30 sn); uçtan uca `kb check`/`kb revert`/`kb restore` gerçek jar ile. İnceleme: anlık görüntü satırıyla `secret_value` sokulabiliyordu → RED → içeri alma süzgeci.
  Çözüldü (G7'de uygulanır): yükseltme ve çelişki çözme yeni sürüm açar (kullanıcı kararı 2026-11-05, ADR-0022 karar 22–23); revert açtığını siler, kapattığını açar.
  Karar (kullanıcı 2026-11-05, ADR-0022 karar 38): restore'da eski görüntüde olmayan Secret değeriyle kalır, sinyal kalır, hedefi yoksa kenar düşer, sayı raporlanır. `kb check` yinelenen `id` de raporlar.
  Scope: kb-server/**, kb-client/**
  Depends: G8, G11
- [x] G12f G12 düzeltmeleri + sıraya bağlı test taraması
  Kanıt: 20 RED test → yeşil; sıra taraması 7 sıra (6 rastgele + ters alfabetik), tek sıraya bağlı test `CypherToolTest` → `restoreSeed()` kuralı; temiz worktree: varsayılan `clean verify` exit 0 (kb-core 3168, kb-server 1067, `SecretLeakIT` 1, kb-client 58), 3 rastgele + ters alfabetik exit 0; yükte düşen saat ölçümlü test varsayılan build'den çıkarıldı (süre CP6'da). `-Peval` 18/18; `quality_gate.py` → exit 0.
  Scope: kb-server/**, kb-client/**
  Depends: G12
- [x] G12b Kayıt kaynağı (ADR-0023, `Episode`) + genel bilgi (ADR-0024, Wikidata modeli: `topic` türü, `note`, yönetilen sözlük, okumada güven işareti)
  Scope: kb-core/**, kb-server/**, seed/schema/**, integrations/**
  Depends: G12f, ADR-0023/0024 dokümanları
  Doğrula: Claude Code/Codex MCP isteklerinde oturum kimliği gidiyor mu. Eval'e genel bilgi soruları kullanıcıya önerilir (hakem dosyası).
  Kanıt: ölçüm (Claude Code 2.1.318, Codex 0.161.0): Codex `_meta.sessionId` gönderiyor, Claude yalnız `toolUseId` → ADR-0023 karar 6. `./mvnw -B clean verify` → exit 0 (kb-core 3168, kb-server 1101, `SecretLeakIT` 1, kb-client 58); `-Peval` 18/18; `quality_gate.py` → exit 0, yeni suppress yok. Uçtan uca: brief `Episode:` satırı → `kb_remember` → `kb_read` `episode: claude, …`. İnceleme MEDIUM'ları (eski brief ipucu, sınırsız `session_id`) RED → düzeltildi.
  Açık (LOW, G14/CP6): SessionStart'ta Episode yazımı düşerse brief de kayboluyor (hook fail-open, oturum bloklanmaz); `transcript_path` secret desen denetimi yok; Claude için PreToolUse ile `toolUseId` → oturum eşlemesi ayrı karar.
- [x] G13 Senkron + timer
  Kanıt: `./mvnw -B clean verify` → exit 0 (kb-core 3168, kb-server 1117, `SecretLeakIT` 1, kb-client 62); `-Peval` → exit 0; `quality_gate.py` → exit 0, suppress yok. İki makine + bare remote: kayıpsız birleşme, eşitlik kuralı, RevertedBatch, satır sayısı reddi, force-push yok, atomik yazım, kapanışta yerel commit; `SecretLeakIT` uzak geçmişi temiz. İnceleme MEDIUM'ları (birleştirmede rastgele kenar kimliği, geri alınan partinin üstündeki kayıt) RED → düzeltildi.
  Açık (LOW, G14 başında): senkron commit'i bütün index'i alıyor (yalnız `snapshot/` almalı); `+` ile başlayan dal adı zorlanmış refspec'e dönebilir; ilişkisiz geçmiş için ipucu.
  Açık (CP2'den): restore ve `SyncService` grafa yazacaksa `write`'ta durur ya da `Snapshot` record'ları taşınır; yoksa döngü kuralı düşer. RSS ≈ 2 × Xmx (1,07 GB): servis dosyasına `MemoryMax` konacaksa buna göre.
  Açık (G4'ten): aynı saniyede eşit `updated_at` için birleştirme kuralı (SPEC §10 eki, kullanıcıya sorulacak); anlık görüntü yazımı geçici dosya + rename ile atomik.
  Scope: kb-server/**, kb-client/**, integrations/systemd/**
  Depends: G12
- [x] G14 `kb init`, kurulum, AGENT-RULES dry-run
  Kanıt: `./mvnw -B clean verify` → exit 0 (kb-core 3168, kb-server 1159 + IT 2 `SecretLeakIT`/`InstallInitIT`, kb-client 67 + `KbJarIT`); `-Peval` → exit 0; `quality_gate.py` → exit 0. `InstallInitIT` gerçek java + jar ile geçici HOME'da kurar, gerçek `~/kb` ve `~/.local/share/agentkb` dokunulmadı. İnceleme: istemci jar'ı çalışmıyordu (HIGH), `user.home` farkı, arama satırında satır sonu ile sahte sonuç → RED → düzeltildi. G13 LOW'ları (`+` dal adıyla force-push, index'in tamamını commit'leme) düzeltildi.
  Açık (LOW): içe aktarılan hafıza başına metin sınırı yok (ADR-0017); CP6'da.
  Açık (G4'ten): `snapshot/` olmayan repo "boş" mu "bozuk" mu; `agentkb.repo` varsayılanı testlerde gerçek `~/kb`'yi açmasın.
  Scope: kb-server/**, kb-client/**, integrations/**
  Depends: G13
- [x] CP6 Bitti tanımı uçtan uca + `./mvnw verify`
  Kanıt: temiz worktree, global git kimliği olmayan HOME: `./mvnw -B -o clean verify` iki kez → exit 0 (kb-core 3168, kb-server 1171 + IT 2, kb-client 67 + IT 1); `-Peval` → exit 0, 18/18. Gerçek Claude Code ile bitti tanımı: (1) oturum açılışında brief + arka plan taraması 14 bilgi ekledi; (2) "eskiden IP" KB'den cevaplandı, eski IP ile arama sunucuyu buluyor; (3) bilinmeyeni sordu, cevabı `owner: Mehmet Kaya · user_stated · episode` olarak birebir kaydetti (AGENT-RULES düzeltmesinden sonra); (4) `kb run` değeri transkript/log/git'te 0 kez, çıktı `****`; (5) sync → restore → export `cmp` aynı, secret korunuyor. Codex `kb_search` onaysız. Tarama süresi: ADR-0022 karar 45 (kullanıcı). Model harcaması ≈ 0,15 USD.
  Açık (LOW): sunucu kapanışı açık MCP akışları yüzünden ~31 sn sürüyor (systemd 90 sn içinde); makine boşken tarama süresini yeniden ölç.

## F3 — kb istemcisi (plan: `.engineering/tasks/plan.md`, onaylı)
- [x] H0 ADR-0025 + belge hizası
  Kanıt: `8f2b359`; ADR-0025 + ROADMAP/CLIENTS/SPEC §6.7/ARCHITECTURE/MCP-TOOLS/CLAUDE.md hizalandı.
- [x] H1 Native + TamboUI spike
  Kanıt: `mise exec java@mandrel-23.1.13.0-Final+java21 -- ./mvnw -B -pl kb-client -Pnative verify` → exit 0, KbNativeIT 14/14 (boyut 40.701.048 bayt, `--help` medyan 5 ms, `kb tui` + `--no-alt-screen` + `q`, SIGINT/SIGTERM'de `--file` silinir; bayraksız RED görüldü), JVM≡native JSON 5/5. `./mvnw -B clean verify` → exit 0 (kb-client 82 + 15). `libstdc++-static` gerekmedi.
- [x] CP1 TamboUI / Jackson go-no-go
  Kanıt: TamboUI go (native testler yeşil). Jackson no-go: native 55,8 MB > 50 MB → `Json.java` kalır (ADR-0025 karar 6). İnceleme: native testler `-Pnative`'de artık zorunlu (atlanmaz); `{"error":7}` regresyonu testle yakalandı.
- [x] H2 `kb-api` + tipli istemci
  Kanıt: `./mvnw -B clean verify` → exit 0 (kb-core 3168, kb-server 1197, kb-client 219); `quality_gate.py` → exit 0; sözleşme dosyaları `fixtures/api/*.json` hem sunucu hem istemci testinde; native 40,8 MB, KbNativeIT 14/14. Lider kararı: 4 F2 istemci testinin sahte cevapları tamamlandı (yeni "eksik alan = exit 1" kuralı).
- [x] H3 Yükleme staging'i (sunucu) · [x] H4 Tamamlama + blob deposu + senkron
  Kanıt: `55e74e2` (refactor: `WriteService.maintenance()`), `a1c7eb9`; `./mvnw -B clean verify` → exit 0 (kb-server 1280); git-lfs 3.7.1 kurulunca `DocumentLfsTest`/`SyncBlobsTest` 0 skip.
- [x] CP2 Güvenlik incelemesi (doküman)
  Kanıt: `bca2d72`, `83aa419`. security-reviewer 2 tur + java-reviewer: symlink (CWE-59), kayıtlı secret değeri, UTF-16, blob hash, LFS sırası, `.gitattributes` union + belge deposu ayrı commit, sync/tamamlama ortak monitör. `clean verify` exit 0 (kb-core 3179, kb-server 1294), gate exit 0. Not: aynı saklı-değer sorgusunun 3. kopyası `graph/CypherRows.java:26` (paket yönü engeli) — H6'da ele al.
- [ ] H5 Yeni yazma işlemleri (kapat, arşiv, çelişki çöz, öneri kabul)
- [ ] CP3 İnceleme
- [ ] H6 Kalan REST + CLI
- [ ] H7 Doküman okuma, `kb doctor`, bütünlük
- [ ] CP4 İnceleme
- [ ] H8a TUI 1 · H8b TUI 2
- [ ] H9 Native dağıtım + kurulum
- [ ] CP5 Bitti tanımı: her işlem TUI'den

## Bekleyen (kullanıcı kararıyla ertelendi, 2026-11-07)
- [ ] Kullanıcının gerçek Claude Code ve Codex ayarlarına geçiş: MCP sunucusu, hook'lar, AGENT-RULES, yerleşik hafızayı kapatma (önce `kb import-memories`). Adımlar `integrations/claude-code/setup.md`, `integrations/codex/setup.md`; önizleme `integrations/install.sh --dry-run`. Geçişte Codex hook'larını gerçek ayarla ölç (bugün yalnız kaynak koddan). Kullanıcı "şimdilik kalsın" dedi.

## Tüm iş bitince (F2–F5 tamam) kullanıcıya hatırlat
- [ ] agentguard'ı ayrı proje olarak ele al: `/home/ismetdyn/dev/bir/agentguard/` (kod, testler, `QUALITY.md`, ADR-0010).
  Ajan işinin kalitesi bu projenin konusu değil.
