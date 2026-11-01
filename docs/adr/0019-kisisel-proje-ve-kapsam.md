# ADR-0019: Kişisel proje, GitHub ve yalnız bilgi deposu kapsamı

- Durum: Kabul edildi
- Tarih: 2026-11-01
- Yerini aldığı: ADR-0010 (kalite katmanı); ADR-0005, ADR-0014, ADR-0015 ve ADR-0017'nin uzak depo ifadeleri

## Bağlam
Kit, kişisel olmayan bir ortama göre yazılmıştı: dahili git sunucusu, ortama özgü örnekler, yayın kısıtları. Yanında ajanların
işini denetleyen bir kalite katmanı da taşıyordu. Kullanıcı projenin kişisel olduğunu ve kapsamının ajanlar için bilgi deposu
olduğunu belirtti.

## Karar
1. **Kişisel proje.** GitHub kullanıcısı `ismetdyn`, commit e-postası `ismetdayann@outlook.com`, paket kökü
   `io.github.ismetdyn.agentkb`. Depoda gerçek kuruluş adı, iç adres veya ortama özgü örnek bulunmaz. Örnekler RFC 2606
   (`.example`) ve RFC 5737 değerlerini kullanır.
2. **GitHub.** Araç reposu, anlık görüntü reposu (`~/kb`, private) ve CI (GitHub Actions) GitHub'dadır.
3. **Kapsam: ajanlar için bilgi deposu.** Ajan işinin kalitesi kapsam dışıdır. agentguard, `.agentguard.toml`, `QUALITY.md`,
   F0, F6, `kb_deviation` ve sapma sinyalleri bu projeden çıkar. Ayrı proje olarak ele alınacak; dosyalar depo dışında durur.
4. **Sohbet geçmişi depoda tutulmaz.** Gerçek ad ve adresler içeriyordu; kararların bağlayıcı hali SPEC ve ADR'lerdir.

Değişmeyenler: küratör ve `eval/` bilgi bankasının kendi kalitesi içindir, kapsamda kalır. `AGENT-RULES` yalnız bilgi bankasının
kullanımını anlatır.

## Sonuçlar
- Faz numaraları değişmez: F1–F5 kalır, F0 ve F6 emekliye ayrılır.
- Git geçmişi yeniden yazıldı; bu bilgileri içeren commit'ler silindi. Henüz uzak depo yoktu.
- Tarayıcının GitLab CI extractor'ı ayrı bir karardır: F2'de tarayıcıdan önce sorulur.

## Değerlendirilen alternatifler
- **agentguard'ı `guard/` altında bağımsız tutmak:** Bilgi bankasının iş mantığı dışında; kapsamı bulandırır.
- **Sohbet geçmişini temizleyip tutmak:** 1.788 satırı elle ayıklamak hataya açık; değeri SPEC ve ADR'lerde zaten var.
