# ADR-0026: Geç kaydedilen secret değerini okumada maskeleme

- Durum: Kabul edildi
- Tarih: 2026-11-11
- Değiştirdiği: docs/SECRETS.md §5–§6; ADR-0022 karar 24 (maske `****`, normalize biçimler de maskelenir). ADR-0015 ve
  değişmez 2'yi bozmaz, tamamlar.

## Bağlam
Yazma yolu, bir Secret'ta saklı değeri içeren metni yalnız değer **saklandıktan sonra** reddeder. Bir değer önce düz metin
olarak (fact, özet, alias, öneri) yazılıp sonra `PUT /api/v1/secrets/{id}` ile saklanırsa grafta durur ve Cypher dışındaki
her okuma (REST, MCP, hook) onu açık gösteriyordu (güvenlik incelemesi, Sev-3). Düzeltme her okuma cevabına tek bir
maskeleme adımı koydu (`graph.ReadRows` → `graph.SecretMask`). İnceleme bunun iki yan etkisini buldu: kısa değerler
(`postgres`) her yerde maskelenince sıradan kelimeler de gidiyordu, ve değer zaten anlık görüntüde ve git geçmişindeyse
kullanıcı bunu bilmiyordu. Sektör yaklaşımı (CI log maskeleme, secret tarayıcıları): kısa ve iyi bilinen yer tutucu
değerler gerçek secret gibi ele alınmaz.

## Karar
A. **Okuma maskesi ve yazma reddi aynı kuralla, uzunluğa göre çalışır** (`graph.SecretMask`). En az 8 karakterlik
   saklı değer her yerde eşleşir. 4–7 karakterlik değer rakam ya da sembol içeriyorsa yine her yerde eşleşir (F3:
   `pa55w`, `?password%3Dpa55w` içinde); yalnız harften oluşuyorsa yalnız bütün bir sözcük olarak (iki yanı metnin
   başı/sonu ya da harf/rakam olmayan bir karakter): saklı `pgadmin`, `pgadmin-db` içinde maskelenir ve reddedilir,
   `pgadmin4` içinde ikisi de olmaz. 4 karakterden kısa değer secret sayılmaz, hiç eşleşmez (sınır tek yerde:
   `Placeholders.MIN_SECRET_CHARS`). Saklı değer yazıldığı haliyle ve normalize biçimiyle (`Ids.normalize`; alias ve
   kimlikler böyle durur) aranır; yazma reddi ve PUT notu metni de hem yazıldığı hem normalize biçimiyle dener (F2:
   saklı `Kb7Qz-Prod-Pw` varken `KB7QZ-PROD-PW-db` varlığı reddedilir). Okumada düğümden doğrudan gelen dizi özellikleri
   (`e.aliases`) de maskelenir. Maske `****`. Kanca yakalaması (hook capture) da aynı kuralla maskeler. Tarayıcı iyi bilinen yer tutucuları (`postgres`, `password`, `changeme`,
   `secret`, `admin`, `root`, `123456`, `example`, `test`, …; liste tek yerde: `kb-core` `secret.Placeholders`) ve 4
   karakterden kısa değerleri, ayrıca tek karakter sınıfından oluşan (yalnız harf, her alfabede; yalnız rakam; yalnız
   sembol) düşük entropili değerleri (F1) saklamaz: Secret düğümü ve adı kalır, değeri boş kalır; tarama cevabı bunu
   `<dosya>:<satır>: placeholder, not stored` diye bildirir. Böylece `.env`'deki `PASSWORD=not` ya da
   `DB_PASSWORD=keycloak` hiçbir şey saklamaz; "do not" ve "keycloak" ne maskelenir ne reddedilir. Kullanıcının `PUT`
   ile sakladığı değere bu kapı uygulanmaz.
D. **Arama tekilleştirmesi ham kimlikle yapılır.** Maskelenmiş kimlikleri yalnız saklı değerin içinde ayrışan iki kayıt
   iki sonuç olarak kalır. Sunucunun ürettiği kimlik ve zaman sütunları (`id` (ULID), `batch`, `rec`, `at`, zamanlar,
   `source`, `status`, `sha256`) maskelenmez. Varlık kimliği yalnız SEARCH ve STALE sorgularında `id` sütununda, maskesiz
   bir tekilleştirme anahtarı olarak döner ve hiçbir cevapta gösterilmez; gösterilen kimlik aynı satırın maskeli
   `record` sütunundandır. Başka her sorguda varlık kimliği maskelenen bir sütunda (`entity`, `record`, `secret`,
   `project`, `decision`) döner.
E. **`PUT /api/v1/secrets/{id}` değerin zaten kaç kayıtta geçtiğini söyler** (F5: yazma reddiyle aynı eşleşme; değer
   yazıldığı ve normalize biçimiyle, kayıt metni de iki biçimiyle; 4 karakterden kısa değer sayılmaz; bitişik yazılmış
   kopya da sayılır). Hiçbir kayıtta yoksa cevap bugünkü gibi
   `204`. Varsa `200` ve düz metin: değer saklandı, N kayıtta düz metin olarak vardı, büyük olasılıkla anlık görüntüde ve
   git geçmişinde de var; değiştir (rotate); yüklenmiş dokümanlara bakılmadı. Cevap değeri asla içermez. Okuma maskesi geçmişi silmez; sızan değer ele
   geçmiş sayılır.

## Sonuçlar
- 4–7 karakterlik bir parola bir sözcüğün içinde geçiyorsa okumada görünür ve yazma onu reddetmez; bütün sözcük olarak
  geçtiği yerde maskelenir ve reddedilir. 4 karakterden kısa değer (kullanıcı `PUT` ile saklasa bile) hiçbir yerde
  maskelenmez ve reddedilmez.
- Doküman yüklemesinin taraması (`SecretScan`) saklı değeri hâlâ düz içerme ile arar, ama yalnız 4 karakter ve
  üstündeki değerleri (F4): `42` saklıyken içinde 42 geçen doküman kabul edilir. 4–7 karakterlik harf değerlerde orada
  kural okumadan sıkıdır.
- Düşük entropi kapısı yalnız taranan değere uygulanır (F1); kullanıcı zayıf bir değeri `PUT` ile saklarsa o değer
  sıkı kuralla maskelenir ve reddedilir.
- `kb_cypher` ile değeri yeniden biçimlendiren bir sorgu (`toUpper`, `split`, `substring`) maskeyi atlatır; bu kabul
  edilen sınırdır (ADR-0022 karar 24, ADR-0020: aynı süreç değeri `GET /api/v1/secrets/{id}` ile zaten alabilir).
- Anlık görüntü maskelenmez: kullanıcının metni yazıldığı gibi kalır (değişmez 8). E'deki uyarı bu yüzden var.
- `kb secret set` 200 cevabındaki notu kontrol karakterleri temizlenmiş olarak stderr'e yazar ve başarıyla çıkar; 204
  değişmedi.
