# ADR-0016: Sadelik ilkesi — ihtiyaç kanıtlanmadan mekanizma eklenmez

- Durum: Kabul edildi
- Tarih: 2026-10-31

## Bağlam
Tasarım ilerledikçe öneriler çoğaldı: ek zaman alanları, işlem kimlikleri, erişim politikaları, sinyal yaşam döngüsü,
kendi sürümleme sistemi. Her biri tek başına makul; toplamı bir kişinin anlayıp sürdüremeyeceği bir sistem. Sistemi
sürekli kullanacak olanlar ajanlar, ama sahibi bir insan ve onu anlayıp gerektiğinde düzeltebilmeli.

## Karar
1. Bir mekanizma ancak gerçek bir hata, bir ölçüm ya da kullanıcının açık isteği gerektirdiğinde eklenir.
2. Var olan araçlar yeniden yazılmaz: sürümleme ve senkron için git, sorgu için Cypher.
3. Bir veri tek yerde durur (graf); türetilmiş kopya tutulmaz.
4. Fact'in anlamlı iki zaman alanı vardır: `observed_at` (ne zaman görüldü) ve `valid_to` (ne zaman kapandı). Bunun
   dışında her kayıtta senkron için sunucunun yazdığı teknik bir `updated_at` bulunur.
5. Bilerek ertelenenler: erişim politikası alanları, ek epistemik alanlar, işlem kimlikleri, sinyal yaşam döngüsü
   alanları, sunucu kapalıyken dosyadan okuma, secret değerlerinin cihazlar arası senkronu, kullanıma göre önem puanı.
   İhtiyaç doğarsa ayrı bir ADR ile eklenir.

## Sonuçlar
- (+) Bir kişinin baştan sona okuyup anlayabileceği bir sistem.
- (−) Bazı uç durumlar ilk sürümde karşılanmaz; RISKS-AND-GAPS.md'de izlenir.
