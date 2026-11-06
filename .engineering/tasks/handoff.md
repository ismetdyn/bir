# Devir — F2 (2026-11-05)

Oturum `agentkb` kökünde. Dal `f2-core`, hiçbir şey commit edilmedi (kullanıcı istemedi). Tek gerçek kaynak `todo.md`.

## Çalışan (kanıt `todo.md`'de)
- G0–G6, CP1–CP3 bitti. Son tam koşu: `./mvnw -B clean verify` → exit 0 (kb-core 536, kb-server 648), `-Peval` 18/18, `quality_gate.py` → exit 0.
- Codex 0.161.0 okuma araçlarını ayarsız çağırıyor (`destructiveHint: false`, ADR-0022 karar 28).

## Sürüyor
- G0–G9, CP1–CP4 bitti (verify exit 0; kb-core 3101, kb-server 901 + `SecretLeakIT`, kb-client 38; eval 18/18). F2 bitti. F3 planı onaylandı (kullanıcı 2026-11-07: plan, TamboUI 0.6.0, picocli-codegen, istemcide Jackson, Json.java silme, Mandrel). Dal `f3-client`. Mandrel kuruldu (`mise exec java@mandrel-23.1.13.0-Final+java21 -- …`). `sudo dnf install libstdc++-static git-lfs` kullanıcıda bekliyor.
H0, H1, CP1 bitti ve commit edildi (son: `5a779fa`; native kb 40,7 MB, istemcide Jackson yok). Workflow `f3-h2-h3-h4-cp2` (run `wf_73a87398-47f`): H2 kb-api + tipli istemci → H3 yükleme staging → H4 doküman tamamlama + blob + sync → CP2 güvenlik/java incelemesi. Bitince her dilimi ayrı commit et.
  Kesilirse: `Workflow({scriptPath: ~/.claude/projects/-home-ismetdyn-dev-bir-agentkb/93d3cf6a-5cff-4d66-b473-571fc9d941c7/workflows/scripts/f3-h2-h3-h4-cp2-wf_73a87398-47f.js, resumeFromRunId: "wf_73a87398-47f"})`.

## Kullanıcı kararları (bu turda)
- `/mcp` gövde sınırı önce 16 MiB, CP2 ölçümünden sonra 1 MiB + `-XX:+ExitOnOutOfMemoryError` (kullanıcı "en optimize seçeneği seç" dedi); F3'te sıra numaralı, sürdürülebilir doküman yüklemesi (ADR-0022 karar 17, 21).
- Log maskeleme: satır kalır, yük `*****` (karar 19). Ad yansıması kabul (karar 20).
- Aynı değer yüksek kaynaktan → kaynak yükselir; bekleyen çelişki yeniden yazılınca güncel olur (SPEC §7 kural 1, karar 22–23). 2026-11-05: bunlar yerinde değil yeni sürümle yapılır (SQL:2011/Datomic gibi), revert tam geri alsın diye.
- `kb_cypher` ile heap tükenmesi kabul edilen risk (lider, karar 25).
- `finding` çok değerli (kullanıcı, 2026-11-05); bulgu metni `Düz metin parola bulundu: <dosya>:<satır>; ortam değişkenine taşınmalı` (lider; `parola:` biçimi secret deseni sayılıyordu).
- Restore yerel veriyi korur (kullanıcı, karar 38).
- Ana amaç: ajan kullanıcıdan önce agentkb'ye sorar; her konuda her bilgi (SPEC §1). Wikidata modeli + kayıt kaynağı (ADR-0023/0024, 2026-11-06).
- Her iş bitince commit (kullanıcı, 2026-11-05).
- Senkron eşitliği: sabit kural + çelişki sinyali (kullanıcı, karar 41).
- Tarama hedefi: yeniden tarama < 5 sn, ilk tarama arka planda < 10 sn (kullanıcı, karar 45).
- `user_stated` kanıtı değeri içermeli ve mesajda birebir geçmeli; port hedefi değerin parçası ("sektör standardı") (karar 29–30).

## Denenip başarısız olan
- Bash ile dosya yazmak hook'u atlatıyor; alt ajanlar Write/Edit kullanır.
- `pkill -f <desen>` kendi kabuğunu da öldürür (exit 144); PID ile durdur.
- Kalite kapısı (`quality_gate.py`) test sınıflarında `TooManyMethods`/`CognitiveComplexity` yakalar; düzeltme test sahibine gider.

## Kalan
- `todo.md`: G4'ten devam; G7 ve G12 altında G3'ten açık maddeler var.
- Commit sırası (kullanıcı isterse): Spotless biçim değişikliği ayrı commit; refactor ile feat ayrı.
- Hatırlatma: F2–F5 bitince `/home/ismetdyn/dev/bir/agentguard/`.
