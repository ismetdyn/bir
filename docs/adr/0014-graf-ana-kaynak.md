# ADR-0014: Graf ana kaynaktır; git anlık görüntü ve senkron içindir

- Durum: Kabul edildi
- Tarih: 2026-10-31
- Yerini aldığı: ADR-0001, ADR-0005

## Bağlam
Bilgi bankasını sürekli kullanacak olanlar ajanlar. Bilgi sık güncellenir ve çoğu küçük, sık başvurulan kayıttır:
sunucu bilgisi, karar, ders, secret. Markdown'ı ana kaynak tutmak iki temsil (dosya + graf), bir satır grameri, satır
bazlı bir dosya yazıcısı, bir dosya izleyici ve ikisi arasında bir tutarlılık protokolü gerektiriyordu. Bu karmaşıklık
insan düzenlemesi (Obsidian) içindi; ana kullanıcı ajan olunca gerekçesi zayıfladı.

## Karar
1. Bilgi doğrudan gömülü Neo4j'de tutulur. Varlıklar, fact'ler, kararlar, dersler ve secret'lar düğüm; aralarındaki
   bağlar kenardır.
2. Sunucu tek yazıcıdır. Her yazma tek bir graf işlemidir ve anında bütün okuyuculara görünür.
3. Fact ve ilişkiler silinmez: değişince eskisi kapanır (`valid_to`), yenisi `SUPERSEDES` ile eskisine bağlanır.
4. Okuma sonuçları ajanlara ve insanlara Markdown olarak render edilir. Markdown bir görünümdür, saklama biçimi değildir.
5. Git sadece anlık görüntü içindir. 10 dakikada bir ve kapanışta graf `~/kb/snapshot/` altına sıralı JSONL olarak
   yazılır, commit'lenir ve GitHub ile senkronlanır. Graf herhangi bir anlık görüntüden yeniden kurulur (`kb restore`).
6. Senkronda aynı kimlikli kayıtlardan yenisi kazanır. Fact'ler zaten silinmediği için kaybolan bilgi olmaz.

## Sonuçlar
- (+) Tek temsil: satır grameri, ayrıştırıcı, dosya yazıcısı, izleyici ve indeks tutarlılığı sorunu yok.
- (+) Okuma ve yazma milisaniyeler içinde; sık güncelleme commit gürültüsü üretmez.
- (+) Geçmiş grafın içinde sorgulanır ("eskiden IP neydi?").
- (−) Obsidian ile doğrudan düzenleme yok; insan arayüzü TUI ve masaüstü GUI.
- (−) Veritabanı artık atılabilir bir indeks değil. Veri dizini `~/.local/share/agentkb/graph`; çökmeye karşı Neo4j'nin
  işlem günlüğü, disk kaybına karşı git anlık görüntüleri (en fazla 10 dakikalık kayıp).

## Değerlendirilen alternatifler
- Markdown ana kaynak + türetilmiş graf (ADR-0001): insan düzenlemesi için iyi, ajan öncelikli kullanım için gereksiz
  karmaşık.
- Kendi binary nesne deposu + Merkle ağacı + git benzeri sürümleme: git'in yaptığını yeniden yazmak. Reddedildi.
- Her yazmada git commit: sık yazmada gürültü ve gecikme; anlık görüntü yeterli.
