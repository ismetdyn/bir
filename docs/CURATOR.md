# Küratör Ajan

## 1. Rol

Küratör, bilgi bankasının **bahçıvanıdır**: yapıyı düzenler, ham sinyalleri yapılandırılmış bilgiye çevirir ve öneriler bırakır. **Diğer ajanlarla konuşmaz.** İletişim tamamen bilgi bankası üzerinden olur
(karatahta mimarisi):

- Çalışan ajanlar ve hook'lar → ham bilgi ve sinyal bırakır.
- Küratör → yapıyı düzenler.
- Herkes → aynı bilgi bankasını okur; düzenlenmiş bilgi önce, ham sinyaller en sonda gelir.

Faydaları: ajanlar birbirini manipüle edemez; küratör çökse sistem çalışmaya devam eder; küratörün yaptığı her şey git
geçmişinde görünür ve geri alınabilir.

Sektördeki karşılıkları: Claude Code'un Auto Dream'i (hafıza dosyalarını belirli aralıklarla birleştiren, çelişenleri
silen, tarihleri düzelten arka plan işlemi), Codex'in "Dreaming" ile hafıza konsolidasyonu, Letta'nın git tabanlı
hafıza üzerinde çalışan arka plan alt ajanları, ve akademik tarafta ACE (bağlamı "playbook" olarak evrilten
üretici/yansıtıcı/küratör döngüsü). agentkb'nin farkı: küratörün yetkileri **sunucuda** sınırlandırılır ve her partisi
ölçülür.

## 2. Sinyaller

| Sinyal | Kaynak | Tekrar anahtarı (`key`) | Örnek kullanım |
|---|---|---|---|
| `empty-search` | `kb_search`/recall sonuç bulamadı | Normalleştirilmiş sorgu | Eksik kayıtlar ("bilgi boşlukları") |
| `qa-capture` | AskUserQuestion / `request_user_input` hook yakalaması | Soru ve cevabın hash'i | `kb_remember` ile kaydedilmemiş cevapları yapılandırmak |
| `scan-finding` | Tarayıcı | Dosya, satır ve bulgu türü | Düz metin secret bulguları, çözülemeyen host'lar |
| `conflict` | Yazma politikası | Çelişen fact | Çelişki özetleri (çözmez; kullanıcıya hazırlar) |
| `imported-memory` | `kb import-memories` | İçeriğin hash'i | Claude Code auto memory / Codex Memories içeriği |

Sinyaller grafta `Signal` düğümüdür ve cihaza yereldir: anlık görüntüye girmez. Küratör her cihazda kendi sinyallerini
işler; yaptığı düzenlemeler graf üzerinden senkronlanır. **Sinyaller silinmez** (ADR-0017): kapanınca sonucu
(`outcome`) yazılır ve ürettiği fact ya da öneriye `PRODUCED` ile bağlanır. Hiçbir şey üretmeden kapanan sinyal
TUI/GUI'deki "Atılanlar" listesinde durur; kullanıcı yeniden açarsa küratör bir sonraki çalışmada tekrar bakar. Bilgi taşıyan sinyaller (`qa-capture`,
`imported-memory`) işlenmeden önce de aramada `ham` işaretiyle bulunur.

Aynı `kind` ve `key` ile gelen sinyal yeni düğüm açmaz: `count` artar, `last_at` güncellenir; sinyal hiçbir şey
üretmeden kapanmışsa yeniden açılır. Böylece sık aranıp bulunamayan konular sayıyla görünür ve `kb import-memories`
iki kez çalışsa da hafızalar çoğalmaz. Sinyal neyle ilgiliyse (çelişen fact, taranan dosya, kural) ona `ABOUT` ile
bağlanır.

Küratörün asıl işi bilgi bankasının şişmesini önlemektir: tekrarları birleştirir, ham soru-cevapları kısa fact'lere
çevirir, uzun süredir gözlenmeyen ve hiçbir şeyin bağlanmadığı varlıklar için arşiv önerir. Arşivlenen kayıt silinmez
ve gizlenmez; aramada `arşiv` işaretiyle güncel kayıtlardan sonra gelir. Her çalışmada `cur_metrics`'teki büyüme
raporuna bakar.

## 3. Yetki seviyeleri (sunucuda zorlanır)

| Otomatik (geri alınabilir) | Öneri (kullanıcı onayı) | Asla |
|---|---|---|
| Tekrar varlıkları birleştirme, alias ekleme | Yeni/değişen karar ve kural | Karar/kural/şema yazmak |
| Yanlış ilişkiyi düzeltme, ilişki türünü normalleştirme | Kural güncelleme/emekliye ayırma | `user` kaynaklı fact kapatmak |
| Ham soru-cevabı yapılandırma (`cur_structure_signal`) | | Secret okumak/yazmak |
| Bayatlık işaretleme | Kayıt silme/arşivleme (kullanılmayan varlıklar için arşiv önerisi) | hook, kendi ayarlarına dokunmak |
| Sinyalleri kapatma | Şema sözlüğüne yeni anahtar/ilişki türü | Parti dışında yazmak; çalışan ajanlarla iletişim; shell/ağ |

Prompt ikna edilebilir, kod edilemez: bu sınırlar `curator` ucunun kod sınırlarıdır (ADR-0020).

## 4. Çalışma döngüsü

```
systemd timer (saatlik) → kb curator run --if-needed
  → sunucu: gerekli mi? (≥24 saat VE (≥5 oturum VEYA ≥50 sinyal))
  → run-curator.sh → headless Claude Code (sadece /mcp/curator araçları)
       1. cur_metrics, cur_signals ile durumu oku
       2. cur_begin_batch(goal)
       3. Küçük, artımlı değişiklikler (en fazla 50 yazma)
       4. Normatif konular için cur_propose
       5. cur_end_batch → sunucu eval + kb check çalıştırır
            gerileme (eval skoru > 0,02 düştü VEYA check sorunları arttı) → parti geri alınır
            (partinin açtığı kayıtlar silinir, kapattıkları ve kapattığı sinyaller yeniden açılır)
  → özet: ~/.local/state/agentkb/curator/runs.jsonl ; TUI/GUI "Aktivite" ekranı
```

## 5. Değerlendirme kapısı

| Ölçüt | Kural |
|---|---|
| Eval skoru (`eval/questions.yaml`) | Parti sonrası skor ≥ parti öncesi − 0,02 |
| `kb check` sorun sayısı (sahipsiz kenar, sözlük dışı ilişki, açık çelişki) | Artamaz |
| Yazma sayısı | Parti başına ≤ 50 (bağlam çöküşüne karşı) |

## 6. Çalıştırma

`curator/run-curator.sh` özetle:

```bash
KB_HOOKS=off \
claude -p "$(cat ~/kb-tools/curator/PROMPT.md)" \
  --mcp-config ~/kb-tools/curator/mcp.json --strict-mcp-config \
  --allowedTools "mcp__kbc" \
  --disallowedTools "Bash,Edit,Write,Read,Glob,Grep,WebFetch,WebSearch,Task,NotebookEdit" \
  --max-turns 60 --output-format json
```

`KB_HOOKS=off`: küratör oturumu kullanıcının hook'larını tetiklemez (oturum sayacını şişirmez,
recall ile bağlamı kirletmez). F4 başında doğrula: bu bayrakların güncel Claude Code sürümündeki adları ve davranışı
(sunucunun tüm araçları için `mcp__kbc` mi `mcp__kbc__*` mı), `--mcp-config` dosyasında ortam değişkeni genişletmesi.
Alternatif: aynı prompt ve MCP ayarıyla `codex exec`.

## 7. İlk kurulum

1. `kb import-memories`: `~/.claude/projects/*/memory/` ve `~/.codex/memories/` içeriği `imported-memory` sinyali olur.
2. Küratörün ilk partileri bunları yapılandırır; sen öneri listesindekileri onaylarsın.
3. Sonra yerleşik hafızaları kapat (tek beyin).

## 8. Riskler ve önlemler

| Risk | Önlem |
|---|---|
| **Bağlam çöküşü:** yinelenen yeniden yazımlar ayrıntıyı aşındırır (ACE yazarlarının gösterdiği) | Sadece artımlı düzenleme; parti başına yazma sınırı; bilgi silinmez, kapanır |
| **Goodhart:** kapı başarısızlıklarını azaltmak için kuralları gevşetmek | Kurallar ve kapılar küratörün yazamadığı alanda; sadece öneri |
| **Yanlış bilginin yayılması** | Küratör sinyalsiz bilgi üretemez; her fact kaynağıyla |
| **Prompt injection** (sinyallerde talimat) | Sinyaller veri; küratörün shell/ağ/dosya aracı yok; normatif değişiklik onaylı |
| **Kişiye aşırı uyum** (kullanıcının yanlış tercihlerini kural yapmak) | Kural önerileri kanıt ister (sinyaller) |
| **Maliyet** | Tetikleyici eşikleri; `--max-turns`; parti başına yazma sınırı |
| **Kayıp parti** (küratör yarıda kalır) | Açık parti 30 dk sonra otomatik kapanır ve değerlendirilir |

## 9. Prompt

İlk sürüm: `curator/PROMPT.md`. Prompt değişiklikleri de sürümlenir ve eval ile ölçülür.
