# Secret Yönetimi (v0.3, ADR-0015)

## 1. İlke

Secret'lar grafta, ait oldukları sunucu veya veritabanının yanında durur. Ajanlar değeri hiç görmez; ona bir
**placeholder** ile, ortam değişkenine atıf yapar gibi başvurur. Değer **bu bilgisayardan çıkmaz.**

Değer şu yerlerin hiçbirine girmez: git anlık görüntüsü, GitHub, audit/log, MCP/REST okuma çıktıları, hata mesajları,
LLM bağlamı. Durduğu tek yer bu bilgisayardaki graftır (`~/.local/share/agentkb/graph`).

Neden:
1. Ajanın okuduğu her şey modelin bağlamına, oradan model sağlayıcısının sunucularına gider.
2. Git'e giren bir parola geçmişten kolay silinmez ve senkronlandığı her yere kopyalanır.

Şifreleme (GPG) yok: anahtar yönetimi ve anahtar kaybı riski getiriyordu (ADR-0015).

## 2. Nasıl duruyor

```
(:Database {id:'ornek-db'})-[:HAS_SECRET]->(:Secret {
    id:'ornek-db/prod/app-password', name:'app-password', env:'prod',
    secret_value:'…',              // sadece bu bilgisayarda; anlık görüntüye girmez
    source:'user', observed_at:'2026-10-31' })
```

- **Placeholder:** `kb:secret/<id>`, ör. `kb:secret/ornek-db/prod/app-password`. Ajan bunu `kb_read` çıktısında görür:
  ```
  ## Secret'lar
  - app-password @prod · kb:secret/ornek-db/prod/app-password · kullan: kb run --env DB_PASS=… -- <komut>
  ```
- **Anlık görüntü:** Secret düğümü (ad, ortam, kime ait) senkronlanır; `secret_value` yazılmaz.
- **Değiştirme:** `kb secret set` aynı `id`'nin değerini değiştirir. Placeholder aynı kalır, eski değer tutulmaz.

## 3. Placeholder nasıl kullanılır

`kb run`, placeholder'ı ortam değişkenine çevirip komutu çalıştırır:

```
kb run --env AD=kb:secret/<id> [--env AD2=...] [--file AD3=kb:secret/<id>] -- <komut> [argümanlar]
```

| Durum | Ajanın yazdığı |
|---|---|
| Parolayı ortamdan okuyan araç | `kb run --env PGPASSWORD=kb:secret/ornek-db/prod/app-password -- psql -h ornek-db -U app ORNEKPDB` |
| Uygulamayı yerelde çalıştırmak | `kb run --env SPRING_DATASOURCE_PASSWORD=kb:secret/ornek-proje/prod/spring.datasource.password -- ./mvnw spring-boot:run` |
| Değer komutun içinde gerekiyor | `kb run --env TOKEN=kb:secret/<id> -- sh -c 'curl -H "Authorization: Bearer $TOKEN" https://…'` |
| Anahtar dosyası gerekiyor | `kb run --file KEY=kb:secret/ornek-app-01/prod/deploy-ssh-key -- sh -c 'ssh -i "$KEY" deploy@ornek-app-01'` |
| Config dosyası | Dosyaya değer değil `${DB_PASSWORD}` yazılır; uygulama `kb run --env DB_PASSWORD=…` ile başlatılır |

- `kb run` değeri `GET /api/v1/secrets/{id}`'den alır ve sadece alt sürecin ortamına koyar. `--file`
  için 0600 izinli geçici bir dosya oluşturur, yolunu değişkene koyar ve çıkışta siler.
- Değer komut satırına yazılmaz; komut satırı süreç listesinde (`ps`) görünür. Bu yüzden `sh -c '…'` içinde tek tırnak
  kullanılır: değişkeni ajanın kabuğu değil, alt süreç açar.
- Alt sürecin stdout/stderr'i süzülür: değerin kendisi, base64 ve URL-encoded biçimleri `****` ile maskelenir. Çıkış kodu
  aynen döner.
- Değer bu bilgisayarda yoksa (secret başka bir bilgisayarda girilmişse) `kb run` komutu çalıştırmaz ve
  "`kb secret set <id>` çalıştır" der.

Bu desen AWS'nin AI kodlama ajanları için önerdiğiyle aynıdır: ajan secret'a adıyla atıf yapar, bir sarmalayıcı değeri
alt sürece verir, bir hook doğrudan okumayı engeller. AWS de bunun kesin bir güvenlik sınırı değil, en yaygın sızıntı
yolunu kapatan bir savunma olduğunu belirtir.

## 4. Akışlar

| Durum | Ne olur |
|---|---|
| Tarayıcı repoda secret bulur | Değer `Secret` düğümüne yazılır, placeholder hazır olur. Dosya git'te izleniyorsa "repoda düz metin secret var, ortam değişkenine taşı" bulgusu açılır. Değer hiçbir çıktıya girmez |
| Tarayıcı aynı secret'ı yeniden bulur | Kendisi yazdıysa değeri günceller. Kullanıcının `kb secret set` ile yazdığına dokunmaz; değer farklıysa `conflict` sinyali açar |
| Kullanıcı secret ekler | `kb secret set <id>`: değer terminalde maskeli alınır, `PUT /api/v1/secrets/{id}` ile yazılır |
| Ajanın secret'a ihtiyacı var | `kb_read` ile placeholder'ı bulur, `kb run --env …=kb:secret/<id> -- …` ile kullanır |
| Ajan secret'ı bilmiyor | Sohbette **istemez**; "terminalde `kb secret set <önerilen-id>` çalıştır" der |
| İnsan değeri görmek ister | `kb secret show <id>` |
| Ajan bir dosyada secret gördü | Değeri `kb_remember`'a vermez (reddedilir); `kb_scan` çağırır |
| Kullanıcı bir soruya secret ile cevap verir | Hook yakalaması değeri sinyale yazmadan `****` ile maskeler; küratör onu yapılandırmaz, `kb secret set` önerir |
| Başka bir bilgisayar | Secret düğümü senkronla gelir, değeri gelmez; orada bir kez `kb secret set` ya da `kb_scan` |

## 5. Koruma katmanları

| Katman | Ne yapar |
|---|---|
| Sunucu | Değeri MCP, Cypher, UI ve anlık görüntüye vermez; tek çıkışı `GET /api/v1/secrets/{id}`. Ajanın değeri görmemesi kuraldır, zorlanan bir sınır değil (ADR-0020). `kb_remember` secret görünümlü değeri reddeder; hook yakalaması sinyale yazmadan maskeler |
| `kb_cypher` | `secret_value` geçen sorguyu reddeder, sonuçlardaki Secret düğümlerinden bu alanı çıkarır |
| Anlık görüntü | `secret_value` yazılmaz (test: sahte değer `~/kb` altında ve git geçmişinde aranır) |
| Veri dizini | `~/.local/share/agentkb` 0700 |
| `kb run` | Değer sadece alt süreçte; çıktı maskeleme |

## 6. Sınırlar

- Değer diskte şifresiz durur, bugün `.env` dosyalarının durduğu gibi. Veri dizini 0700; ama
  aynı kullanıcıyla çalışan kararlı bir süreç dosyayı okuyabilir. Bu bir sızıntı önleyicidir, kasa değildir.
- Ajan bir dosyayı okuduğu anda içerik modele gitmiştir; geri alınamaz. Asıl çözüm secret'ları repolardan çıkarmaktır
  (ortam değişkenleri).
- Kararlı ve kötü niyetli bir ajan `kb run` maskelemesini atlatabilir (ör. değeri başka bir kodlamayla yazdırarak).
  Önlem: `kb run` kullanımını audit'te izle.
- Değerler bilgisayarlar arasında senkronlanmaz. Aynı secret'lar birden fazla bilgisayarda gerçekten gerekirse, yerleşik
  bir anahtarla şifreleyip senkronlamak eklenebilir (ADR-0015, değerlendirilen alternatifler).
- Disk kaybında değerler gider. Hangi secret'ın olduğu ve nerede kullanıldığı anlık görüntüde kalır; değerlerin asıl
  kaynağı (parola yöneticisi, sistem yöneticisi) zaten başka yerdedir.
