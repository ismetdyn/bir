# ADR-0013: Tek beyin — yerleşik ajan hafızaları kapatılır

- Durum: Kabul edildi
- Tarih: 2026-10-29

## Bağlam
Çalışan ajanlar sadece küratörün yönettiği bilgi bankasını kaynak olarak kullanmalı. Claude Code auto memory ve Codex
Memories ayrı, makineye bağlı ve birbirini görmeyen hafızalar.

## Karar
1. `kb import-memories` ile mevcut yerleşik hafıza içeriği `imported-memory` sinyali olarak alınır.
2. Küratör bunları yapılandırır; kullanıcı önerileri onaylar.
3. Claude Code auto memory kapatılır; Codex Memories açılmaz (varsayılan kapalı).
4. Kalıcı bilginin tek yeri agentkb'dir; repo içi kurallar AGENTS.md/CLAUDE.md'de kalır.

## Sonuçlar
- (+) Çelişen iki hafıza yok; her bilgi kaynağıyla ve denetlenebilir.
- (+) İçe aktarılan hafızalar küratör işleyene kadar da aramada `ham` olarak bulunur; yerleşik hafızayı kapatmak
  erişimi kesmez (ADR-0017).
- (−) agentkb kapalıyken ajanlar yazamaz ve hook'lar bağlam eklemez (fail-open; oturumlar yine açılır). Okumak için
  `~/kb/snapshot/*.jsonl`'ü salt okunur kullanırlar; en fazla 10 dakika eskidir (AGENT-RULES).
