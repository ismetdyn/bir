# ADR-0017: Bilgi bankası büyüdükçe işlevini kaybetmez; ham dosyalar doküman deposunda

- Durum: Kabul edildi
- Tarih: 2026-11-01
- Revizyon: 2026-11-01 — "ajan sadece güncel bilgiyi görür" ve "kapanan sinyaller 30 gün sonra silinir" kaldırıldı;
  yerlerine "gizleme yok, işaretle ve sırala" ve "sinyaller silinmez" geldi (madde 3 ve 5).

## Bağlam
Bilgi bankası ajanların ikinci beyni olacak ve yıllarca büyüyecek. Sorun depolama boyutu değil: Neo4j milyonlarca
düğümü taşır, bu bilgi bankası onun çok altında kalır. Sistemi işlevsiz bırakan şey gürültüdür: tekrar eden kayıtlar,
bayat bilgi, ham konuşmalar ve uzun metinler ajanın önüne düşer, aramalar ve brief'ler dolar.

Gürültüyü bilgiyi gizleyerek ya da silerek azaltmak ise erişimi bozar: eski bir IP ile arama boş döner, arşivli bir
sunucudan geçen ilişki kopar, küratörün yanlışlıkla attığı ham bir cevap geri gelmez. Grafın gücü her şeyin düğüm ve
kenar olarak bir arada durmasıdır; zaman ve durum da bu düğüm ve kenarların alanlarıdır.

Ayrıca bilgi bankası birincil kaynaktır ve dışarıya olabildiğince az bağımlı olmalıdır. Başka yerde duran dokümanlar
(wiki sayfası, PDF, toplantı notu) zamanla silinebilir ya da değişebilir.

Grafın arkasına ikinci bir veritabanı (SQLite gibi) koymak bunların hiçbirini çözmez: aynı kayıtların ikinci kopyası
olur, onları eşit tutmak için senkron düzeneği gerekir ve ajanın önüne neyin düştüğü değişmez.

## Karar
1. **Grafa damıtılmış bilgi girer:** varlık, fact, ilişki, karar, ders. Ham konuşma, dosya kopyası ve uzun metin girmez.
   Metin sınırları: `summary` 300, metin türündeki fact 500, `body` 4.000 karakter. Aşan yazma reddedilir ve `kb doc add`
   ile doküman eklemeye yönlendirilir.
2. **Ham dosyalar doküman deposunda:** `~/kb/blobs/<ilk 2 hane>/<sha256>` altında değişmez dosyalar, aynı git reposunda.
   Grafa dosyanın kendisi değil `Document` düğümü girer. `kb doc add` önce secret taraması yapar; secret bulursa dosyayı
   reddeder. 20 MB üstü dosyalar `git lfs track` ile eklenir. Aynı içerik ikinci kez eklenmez.
3. **Gizleme yok; işaretle ve sırala:** kapanmış fact'ler, biten ilişkiler ve arşivlenmiş varlıklar aramadan,
   hatırlatmadan ve `kb_related`'dan düşmez. Önce güncel bilgi gelir, sonra geçmiş (eski değer, biten ilişki, arşivli
   varlık), en son bilgi taşıyan ham sinyaller (`qa-capture`, `imported-memory`). İşaretsiz satır günceldir; diğerleri
   `eski (… → …)`, `arşiv` ya da `ham` işaretlidir. Eski bir değer eşleşirse yanında güncel değer de yazar. Brief şu
   anın özetidir ama arşivli komşuyu düşürmez; geçmişi `kb_read`'in "Geçmiş" bölümü gösterir.
4. **Bütçe sabit:** araç çıktısı en fazla 8.000 karakter, arama en fazla 20, hatırlatma en fazla 3 sonuç. Bütçe
   sıralamadan sonra uygulanır; kesilirse sonda `(+N sonuç daha)` yazar. Bilgi bankası büyüse de ajanın bağlamı büyümez
   ve en üstte güncel bilgi durur.
5. **Küratör toplar, sinyaller silinmez:** küratör tekrarları birleştirir, ham soru-cevapları fact'e çevirir,
   kullanılmayan varlıklar için arşiv önerir. Kapanan sinyalin sonucu (`outcome`) yazılır ve ürettiği fact ya da öneriye
   `PRODUCED` ile bağlanır. Hiçbir şey üretmeden kapanan sinyal TUI/GUI'de "Atılanlar" listesinde durur ve yeniden
   açılabilir.6. **Büyüme ölçülür:** `kb check` sayıları ve bir önceki çalışmaya göre farkı raporlar: varlıklar (duruma göre), güncel
   ve kapalı fact'ler, kararlar, dersler, dokümanlar (toplam boyut), açık, işlenmiş ve atılan sinyaller, çelişkiler,
   bayat oranı.
7. **Hiçbir kural bilgi kaybettirmez:**
   - Silme ve gizleme yok (madde 3 ve 5). Üzerine yazılan varlık alanlarının (`summary`, `aliases`) eski hali git anlık
     görüntü geçmişinde durur; birleştirilen varlığın kimliği alias olarak kalır, eski adla arama da bulur.
   - Sınırlar ve şema doğrulaması sadece yeni yazmalara uygulanır; senkron ve restore hiçbir kaydı reddetmez, kırpmaz.
   - Parti geri alınınca partinin kapattığı sinyaller yeniden açılır.
   - `kb check` her dokümanın dosyasını ve sha256'sını doğrular; `kb doctor` git-lfs'i kontrol eder, LFS gönderimi
     başarısızsa `kb sync` hata verir.
   - Ham sinyaller aramada döndüğü için hook yakalaması secret görünümlü değerleri sinyale yazmadan maskeler.

## Sonuçlar
- (+) Ajanın gördüğü şey toplam boyutla büyümez; en üstte güncel bilgi durur, geçmiş aynı sonuçta işaretli olarak gelir.
- (+) Her şey düğüm ve kenar: zaman ve durum birer alan; okumalar süzmez, sadece sıralar ve işaretler.
- (+) Dokümanların kopyası bilgi bankasında; dışarıdaki asıl kopya silinse de kaybolmaz.
- (+) Tek depo, tek yazıcı ve tek senkron korunur.
- (−) Sınırlar bazen uzun bir açıklamayı dokümana bölmeyi gerektirir.
- (−) Büyük dosyalar için git-lfs ve GitHub'da LFS desteği gerekir.
- (−) Yerel graf sinyallerle yavaşça büyür. Sinyaller küçüktür ve aramada en sona gelir; `kb check` sayar.
- (−) Bilerek kabul edilen kayıp: sinyaller sadece o bilgisayarda durur; disk kaybında henüz işlenmemiş ham bilgi gider
  (işlenmiş olanların ürettiği bilgi anlık görüntüde).

## Değerlendirilen alternatifler
- Grafın arkasında SQLite ya da kendi byte deposu: aynı bilginin ikinci kopyası ve senkron düzeneği. Reddedildi.
- Dokümanı sadece dış yoluyla referanslamak: dış kaynak silinince bilgi bankası kanıtını kaybeder. Reddedildi.
- Eski kayıtları silerek küçültmek: geçmiş kaybolur. Reddedildi.
- Varsayılan sonuçlarda sadece güncel bilgi (bu ADR'nin ilk hali): eski değerle arama boş döner, arşivli düğümden geçen
  zincir kopar, ajan bilgi bankasında olan bir şeye "yok" der. "Gizlenen eşleşme sayısını söyle" yaması da ajanın bu
  satırı fark edip ikinci kez aramasına dayanıyordu. Reddedildi.
- Kapanan sinyalleri 30 gün sonra silmek (ilk hali): küratörün yanlışlıkla attığı ham cevap ve içe aktarılan hafıza geri
  dönülmez gider. Reddedildi.
- Sinyalleri de anlık görüntüye yazmak: disk kaybına karşı korur, ama ham ve süzülmemiş metin git geçmişine ve
  GitHub'a girer. Reddedildi; damıtılmış bilgi zaten anlık görüntüde.
- Kullanım sayacına göre önem puanı: yeni bir mekanizma; ölçüm ihtiyaç gösterirse eklenir (ADR-0016).
