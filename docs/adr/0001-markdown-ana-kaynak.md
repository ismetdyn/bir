# ADR-0001: Ana kaynak markdown dosyalarıdır, graf türetilmiş indekstir

- Durum: Yerini aldı → ADR-0014 (2026-10-31). Aşağıdaki metin tarihçe için korunuyor.
- Tarih: 2026-10-29

## Bağlam
Bilgi hem insan (Obsidian) hem ajanlar (MCP) tarafından okunacak; cihazlar arası senkronize edilecek;
değişiklikler incelenebilir ve geri alınabilir olmalı.

## Karar
Vault'taki markdown dosyaları tek doğruluk kaynağıdır. Neo4j grafı bu dosyalardan üretilir, her an silinip
yeniden kurulabilir ve hiçbir zaman senkronize edilmez.

## Sonuçlar
- (+) Git ile diff, geçmiş, geri alma; Obsidian ile doğrudan düzenleme.
- (+) Senkronizasyonda veritabanı dosyası bozulma riski yok.
- (+) Basic Memory / Obsidian ile format uyumu, araç kilitlenmesi yok.
- (−) Grafa özgü bilgiler (ör. hesaplanmış skorlar) kalıcı değil; gerekirse dosyaya yazılmalı.
- (−) Artımlı senkronizasyon dikkat ister (hash, iki geçiş, stub düğümler).

## Değerlendirilen alternatifler
- Graf veritabanı ana kaynak (mcp-neo4j-memory, Graphiti): incelenemez, git'e girmez, ajan hatası kalıcı olur.

## v2 notu (2026-10-29)
Otomatik yazmalar (ADR-0006) ve küratör (ADR-0011) eklendikten sonra bu karar daha da önemli hale geldi: her otomatik
değişikliğin git'te görünmesi ve tek komutla geri alınabilmesi, önceden onayı kaldırmamızı mümkün kılan şey.
