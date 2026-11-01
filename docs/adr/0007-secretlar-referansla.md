# ADR-0007: Secret'lar referansla — değerler bilgi bankasına girmez

- Durum: Yerini aldı → ADR-0015 (2026-11-01). Referans + `kb run` deseni sürüyor; değişen şey değerin pass yerine
  grafta, sadece bu bilgisayarda durması. Aşağıdaki metin tarihçe için korunuyor.
- Tarih: 2026-10-29

## Bağlam
Kullanıcı, ajanların parolaları da bilip kullanabilmesini istiyor. Bilgi bankasındaki her bilgi LLM bağlamına ve
sağlayıcıya gider; vault git'te ve senkronize olur.

## Karar
- Secret değerleri `pass` deposunda (GPG, ayrı git reposu) tutulur; bilgi bankasında sadece `kb:secret/...` referansı.
- Sunucunun `SecretStore`'unda okuma (`get`) yoktur; sadece yazma, listeleme, silme.
- Ajanlar secret'ları `kb run --env AD=<ref> -- <komut>` ile, değeri görmeden kullanır; çıktı maskelenir.
- Ajan secret istemek için sohbeti kullanmaz; kullanıcıdan `kb secret set` çalıştırmasını ister.
- Tarayıcı bulduğu secret'ları LLM'e göstermeden depoya taşır ve bulgu açar.

## Değerlendirilen alternatifler
- Düz metin (vault/graf): LLM'e ve git geçmişine sızar; reddedildi.
- Şifreli değer vault'ta (sops/age): değer yine ajan aracıyla çözülüp bağlama girer; referans + exec deseni daha iyi.
- OpenBao/Vault sunucusu: kişisel kullanım için ağır; `SecretStore` arayüzüyle ileride mümkün.

## Sonuçlar
- (+) Secret'lar ajanın bağlamına girmeden kullanılabilir; gpg-agent önbelleği doğal bir onay penceresi.
- (−) En iyi çaba savunması; kararlı kötüye kullanım maskelemeyi atlatabilir (SECRETS.md §7).
