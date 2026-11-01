# ADR-0006: Katmanlı yazma politikası — sonradan inceleme, geri alma, çelişki kuyruğu

- Durum: Kabul edildi
- Tarih: 2026-10-29
- Yerini aldığı: ADR-0004

## Bağlam
Bilgiler otomatik toplanmalı (tarayıcı, ajan çıkarımları, kullanıcı cevapları). Ama doğrulanmış bilgi sessizce
ezilmemeli, her değişiklik izlenebilir ve geri alınabilir olmalı.

## Karar
1. Her yazma bir **kaynakla** etiketlenir: `user` (4) > `user_stated` (3) > `scan` (2) > `agent` (1); `curator` sadece
   hijyen yapar ve yeni bilgi üretmez.
2. Bilgi (fact, ilişki, gözlem) doğrudan yazılır. Daha düşük güvenli kaynak daha yükseğini ezemez → **çelişki kuyruğu**.
3. Eski değerler silinmez, `## Geçmiş`'e taşınır.
4. Her yazma isteği **bir git commit'i**dir (trailer'lar: `KB-Origin`, `KB-Source`, `KB-Batch`, `KB-Request`).
   Geri alma: `kb revert <commit>` / `--batch <id>`.
5. **Normatif bilgi** (karar, kural, şema, silme/arşiv) her zaman `_inbox/` üzerinden kullanıcı onayıyla.
6. `user_stated` için kanıt (kullanıcının sözleri) son 2 saatin kullanıcı mesajlarında doğrulanır; doğrulanamazsa `agent`.
7. Vault'a yazan tek bileşen sunucunun yazıcı kuyruğudur (insan düzenlemeleri hariç); ajanlar markdown değil
   yapılandırılmış istek gönderir.

## v0.3 notu (2026-10-31)
1–3, 5 ve 6. maddeler aynen geçerli. ADR-0014 ile değişenler:
- Madde 3: eski değer `## Geçmiş` bölümüne değil, kapanmış bir fact düğümüne dönüşür (`valid_to` + `SUPERSEDES`).
- Madde 4: her yazma bir git commit'i değil, tek bir graf işlemidir. Her kayıt `source`, `observed_at` ve gerekirse
  `batch` taşır. Geri alma `kb revert --batch <id>`: partinin açtığı kayıtlar silinir, kapattıkları yeniden açılır.
  Uzun dönem geçmişi git anlık görüntüleri tutar.
- Madde 5: `_inbox/` yerine `Proposal` düğümleri; onay TUI ve GUI'den.
- Madde 7: "vault'a yazan tek bileşen" yerine "grafa yazan tek bileşen sunucunun yazma servisidir".

## Sonuçlar
- (+) Bilgi bankası kendiliğinden dolar; hiçbir şey kaybolmaz; denetim kaydı git'tir.
- (+) Kirlilik riski kaynak sırası, `unverified` durumu ve küratör ile yönetilir.
- (−) Çok sayıda küçük commit; `kb activity` ve GUI ile süzülerek okunur.
- (−) Yazma politikası tablo güdümlü kapsamlı testler gerektirir (kb-core).
