# ADR-0015: Secret'lar grafta ama sadece bu bilgisayarda; ajanlar placeholder ile kullanır

- Durum: Kabul edildi
- Tarih: 2026-11-01
- Yerini aldığı: ADR-0007

## Bağlam
Secret'lar küçük ve sık başvurulan kayıtlar; ait oldukları sunucu ve veritabanıyla aynı yerde durmalılar (ADR-0014).
Ayrı bir depo (pass) ve GPG anahtar yönetimi istemiyoruz: ikisi de dış bağımlılık ve fazladan karmaşıklık. Ama değer
ajanın bağlamına girerse model sağlayıcısına gider; git'e girerse geçmişten kolay silinmez ve senkronlandığı her yere
kopyalanır.

## Karar
1. Her secret bir `Secret` düğümüdür ve ait olduğu varlığa `HAS_SECRET` ile bağlanır. Değer `secret_value` alanında
   şifresiz durur.
2. Ajanlar değeri görmez; ona bir placeholder ile atıf yapar: `kb:secret/<id>`. Placeholder bir ortam değişkenine
   bağlanır: `kb run --env DB_PASS=kb:secret/ornek-db/prod/app-password -- <komut>`. `kb run` değeri sadece alt sürecin
   ortamına koyar ve çıktıda maskeler. Değer komut satırına yazılmaz, çünkü süreç listesinde görünür.
3. Değer bu bilgisayardan çıkmaz: anlık görüntüye, git'e, loglara, audit'e, MCP/REST okuma çıktılarına ve hata
   mesajlarına girmez. Anlık görüntüde Secret düğümü (ad, ortam, kime ait) vardır, değeri yoktur.
4. Değeri döndüren tek uç `GET /api/v1/secrets/{id}`'dir (`user` token). Onu sadece `kb run` ve insan için
   `kb secret show` kullanır.
5. Değer `kb secret set <id>` ile terminalde maskeli girilir ya da tarayıcı config dosyasından okur. Değişince eskisi
   tutulmaz; geçmişi saklanmayan tek alan budur.
6. Başka bir bilgisayarda Secret düğümü senkronla gelir ama değeri gelmez. `kb run` çalışmadan durur ve "bu bilgisayarda
   değer yok, `kb secret set <id>` çalıştır" der.
7. Senkron ve restore yereldeki değeri korur: anlık görüntüde değer olmadığı için içeri alma onu silmez, restore da
   grafı yeniden kurarken geri yazar.

## Sonuçlar
- (+) GPG, anahtar yönetimi ve ayrı depo yok; dış bağımlılık yok.
- (+) Düz metin git'e ve GitHub'a hiç gitmez.
- (−) Değerler bilgisayarlar arasında senkronlanmaz; her bilgisayarda bir kez girilir ya da taranır.
- (−) Değer diskte şifresiz durur, bugün `.env` dosyalarının durduğu gibi. Veri dizini 0700;
  ama aynı kullanıcıyla çalışan kararlı bir süreç dosyayı okuyabilir. Bu bir sızıntı önleyicidir, kasa değildir.
- (−) Disk kaybında değerler gider. Bilgi (hangi secret var, nerede kullanılıyor) anlık görüntüde kalır; değerlerin
  asıl kaynağı (parola yöneticisi, sistem yöneticisi) zaten başka yerdedir.

## Değerlendirilen alternatifler
- GPG ile şifreleyip grafta ve anlık görüntüde tutmak (v0.3'ün ilk taslağı): senkron mümkün, ama anahtar yönetimi,
  gpg-agent ve anahtar kaybı riski getiriyor. Bırakıldı.
- Yerleşik bir anahtarla (AES) şifreleyip senkronlamak: değerler bilgisayarlar arasında taşınır, ama anahtar dosyası her
  bilgisayara kopyalanmalı. Aynı secret'lar birden fazla bilgisayarda gerçekten gerekirse eklenir.
- Düz metni anlık görüntüye de yazmak: parola git geçmişine ve GitHub'a girer. Reddedildi.
- Ayrı pass deposu + referans (ADR-0007): ikinci depo, ikinci senkron. Bırakıldı.
- İşletim sisteminin anahtarlığı (Secret Service, KWallet): graf dışında ayrı bir depo. Bırakıldı.
