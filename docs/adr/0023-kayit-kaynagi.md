# ADR-0023: Kayıt kaynağı (Episode)

- Durum: Kabul edildi
- Tarih: 2026-11-06
- Değiştirdiği: SPEC §6.1–§6.2 ve §6.6; MCP-TOOLS (`kb_remember`, `kb_propose`, `kb_scan`, `kb_brief`, `kb_read`); ARCHITECTURE §5 ve §11; `integrations/AGENT-RULES.md`. ADR-0017'yi bozmaz (konuşma içeriği grafa girmez).

## Bağlam
Kullanıcı 2026-11-06'da Graphiti'den bir fikri almak istedi: her bilginin hangi konuşmadan ya da olaydan geldiğini
kaydetmek. Graphiti'de her bilgi, kendisini üreten bir "episode"a bağlanır ve geçersiz kılma zaman damgasıyla yapılır
([özel varlık ve kenar türleri](https://help.getzep.com/graphiti/core-concepts/custom-entity-and-edge-types)). Bizim
modelimiz zamanlı yönüyle (`valid_from`/`valid_to`, `SUPERSEDES`) buna zaten yakın.

Bugün bir kaydın `source` alanı kaynağın **türünü** (`user_stated`, `scan`, …) söylüyor, `batch` bir yazmayı gruplayıp geri
almaya yarıyor. Hangi oturumda, hangi istemciden öğrenildiği kayıtlı değil. "Bu IP'yi kim, ne zaman söyledi?" ve "bu oturumun
yazdığı her şey" sorulamıyor.

## Karar
1. **Yeni düğüm `Episode`:** `id` (ULID), `kind` (`session` | `scan` | `user` | `import`), `client` (`claude` | `codex` |
   `kb` | `curator` | `scan`), `session_id?`, `transcript_path?`, `started_at`, `last_at`. **İçerik yok:** istem veya cevap
   metni saklanmaz (ADR-0017). Oturumun deposu biliniyorsa isteğe bağlı `Episode -[IN]-> Repo`.
2. **Her yazma açtığı her kayda (düğüm ve kenar) `episode` özelliğini yazar**, `batch`'in yanına. `batch` = bir yazma;
   `episode` = oturum ya da olay, içinde çok parti olabilir. Episode'un partisi yoktur; `kb revert --batch` Episode'u
   hiç silmez.
3. **Sunucu episode'u nasıl öğrenir:**
   - SessionStart hook'u `(client, session_id)` için Episode'u açar ya da `last_at`'ini tazeler; brief'te episode kimliğini
     taşıyan tek satır olur.
   - `kb_remember` isteğe bağlı `source.episode`, `kb_propose` ve `kb_scan` isteğe bağlı `episode` argümanı alır (kayıt
     kaynağı `source`'un parçasıdır); AGENT-RULES ajana onu geçirmesini söyler.
   - `user_stated` yazmanın kanıtı, UserPromptSubmit'in `session_id` ile PromptRing'e yazdığı bir mesajla eşleşirse kayıt o
     oturumun episode'una **otomatik** bağlanır; bu argümandan önce gelir.
   - Oturumsuz `kb_scan` kendi `kind=scan` Episode'unu açar; kullanıcının REST yazmaları `kind=user` alır.
   - Bilinmeyen ya da verilmeyen episode hata değildir; kayıt episode'suz kalır.
4. **Anlık görüntü:** Episode düğümleri ve `episode` özelliği anlık görüntüye girer (köken senkronla taşınır). `Signal` gibi
   yerel değildir.
5. **Okuma:** `kb_read` bir fact'in satırında ya da geçmişinde kaynak episode'u gösterir (istemci, tarih, yerelse
   transcript yolu).
6. **Doğrula (uygulama diliminde):** Claude Code ve Codex MCP isteklerinde oturum kimliği gönderiyor mu? Gönderiyorsa
   argümansız bağlanır. Sonuç bu ADR'ye not düşülür; ölçülene kadar varsayım yok.
   **Ölçüldü (2026-11-06, Claude Code 2.1.318, Codex 0.161.0):** İki istemci de HTTP başlığında oturum kimliği
   göndermiyor (`mcp-session-id` sunucunun verdiği taşıma kimliğidir). Codex `tools/call` isteğinin `params._meta`
   alanında `sessionId` gönderiyor; hook'ların aldığı `session_id` ile aynı, bu yüzden Codex yazmaları argümansız
   bağlanır. Claude Code `_meta`'da yalnız `claudecode/toolUseId` gönderiyor; bu değer PreToolUse hook'unun
   `tool_use_id`'si ile aynı. Claude yazmaları bugün `episode` argümanı ve kanıt eşleşmesiyle bağlanır; PreToolUse
   üzerinden `toolUseId` → oturum eşlemesi yeni bir hook olayı gerektirdiği için ayrı karar olarak bekler.

7. **Bilinen sınır (ADR-0020 güven modeli):** ajan yazısını var olan herhangi bir Episode ile damgalayabilir (başka
   oturumun, kullanıcının ya da taramanın). Bu yalnız gösterilen kaynağı etkiler; güveni ve `source`'u değiştirmez
   (WritePolicy kaynağa bakar). Kanıt eşleşmesi ve Codex `_meta` bağlantısı argümandan önce gelir.

## Sonuçlar
- (+) "Kim, hangi oturumda söyledi" ve "bu oturum ne yazdı" sorgulanır; çelişki çözerken kaynak oturuma dönülür.
- (+) `batch` ve geri alma semantiği değişmez.
- (−) Her kayıt bir özellik daha taşır; Episode sayısı oturum sayısıyla büyür. Silinmez (H12); büyüme `kb check` raporuna girer.
- (−) Ajan `episode` argümanını geçirmezse bağlama yalnız hook yolundan olur; bu yüzden kimliği brief'e yazıyoruz.
- (−) `transcript_path` yerel yoldur ve anlık görüntüyle başka makineye gider; orada dosya yoktur.

## Değerlendirilen alternatifler
- **Yalnız `source` + `batch` kalsın:** oturum bilgisi yok (Bağlam).
- **Konuşma metnini Episode'a koymak (Graphiti'deki gibi):** ADR-0017 ve secret kuralı (değişmez 2) ile çelişir.
- **`batch`'i genişletmek:** geri alma birimi ile köken birimi karışır; parti geri alınabilir, oturum alınamaz.
- **Episode'u yerel (anlık görüntüsüz) tutmak:** ikinci makinede köken kaybolur.
