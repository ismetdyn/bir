# ADR-0009: Projeler arası kararlar: kapsam ve uygulanabilirlik

- Durum: Kabul edildi
- Tarih: 2026-10-29

## Bağlam
Bazı mimari kararlar her projede geçerli olmalı. Ama "global" kuralların çoğu aslında stack'e özgü; ayrıca bağlam
dosyalarına eklenen genel ilkeler maliyeti artırıp başarıyı artırmıyor.

## Karar
Karar kayıtları (v0.3'te `Decision` düğümleri) şu alanları taşır: `scope` (global/project), `applies_to` (ör. `{stack: [java-spring]}`), tek satırlık
`rule`, `review_by`.
`kb_brief` sadece repoda algılanan stack ile eşleşen kararların `rule` satırlarını verir. Sadece eve özgü kurallar
yazılır; ders kitabı ilkeleri yazılmaz.

## Sonuçlar
- (+) Kısa, ilgili bağlam; kurallar ölçülebilir ve süresi dolabilir.
- (−) Stack algılama (build dosyalarından) doğru olmalı; tarayıcının BuildFile extractor'ı bunu üretir.
