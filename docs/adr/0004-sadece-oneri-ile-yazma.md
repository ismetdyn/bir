# ADR-0004: Ajanlar sadece öneri bırakır, onay insandadır

- Durum: **Yerini aldı → ADR-0006** (2026-10-29)
- Tarih: 2026-10-29

## Bağlam
Ajanlar yanlış bilgi yazabilir, prompt injection ile yönlendirilebilir, sır sızdırabilir.

## Karar (artık geçerli değil)
MCP üzerinden tek yazma aracı `kb_propose` olacak ve sadece `_inbox/`'a yazacaktı.

## Neden değişti
Kullanıcı, proje incelenirken toplanan bilgilerin ve sorduğu soruların cevaplarının otomatik eklenmesini istedi. Her
bilgi için önceden onay, bilgi bankasının dolmasını engeller. Önceden onay yerine **sonradan inceleme + geri alma**
modeline geçildi (ADR-0006). Normatif bilgi (karar, kural, şema, silme) için onay kuralı ADR-0006'da korunuyor.
