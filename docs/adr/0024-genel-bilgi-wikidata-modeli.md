# ADR-0024: Genel bilgi — Wikidata modeli

- Durum: Kabul edildi
- Tarih: 2026-11-06
- Değiştirdiği: SPEC §1 (ana amaç), §6.1, §6.6, §7; `schema/types.yaml` ve `schema/facts.yaml` (uygulama diliminde); MCP-TOOLS; `integrations/AGENT-RULES.md`; ROADMAP F2 ve F4. ADR-0016'yı (sadelik) bozmaz: yeni mekanizma yok, mevcut fact/ilişki modeli genişler.

## Bağlam
Ana amaç (SPEC §1): ajan bir bilgiye ihtiyaç duyunca kullanıcıya sormadan önce agentkb'ye sorar. Bilgi bankası yalnız altyapı
ve secret için değil, herhangi bir konudaki herhangi bir bilgi için kullanılacak (kullanıcı, 2026-11-06). Bugünkü model
operasyonel varlık türlerine ve sınırlı bir fact anahtar sözlüğüne göre kurulu; sözlükte olmayan bilgi reddedilir, yani
kaybolur.

Serbestlik ile güvenilirlik arasındaki dengeyi [Wikidata veri modeli](https://www.mediawiki.org/wiki/Wikibase/DataModel/Primer)
iyi kuruyor: her şey bir kayıt olabilir, ama her ifade kaynak ve rütbe taşır, okuyan güvenilir olanı önce görür.
Graphiti de türleri ihtiyaca göre tanımlatır
([özel türler](https://help.getzep.com/graphiti/core-concepts/custom-entity-and-edge-types)). Bilgi grafı kalitesi
yazısı ([arXiv 2507.22305](https://arxiv.org/abs/2507.22305)) şema doğrulamasının (SHACL) tutarlılığı sağladığını, ama
serbest üretilen varlıkların çakışma ve tekrar sorunu çıkardığını bildiriyor.

## Karar
1. **`topic` varlık türüdür:** `types.yaml`'a `topic` eklenir (etiket `Topic`, `operational: false`). Bugünkü sabit `Topic`
   etiketi bununla birleşir; her konu fact ve ilişki taşıyabilir. Mevcut `ABOUT` kenarları aynen çalışır. Kimlik biçimi
   `topic:<ad>` kalır (seed'deki `topic:tls`); önek, aynı adı taşıyan başka bir varlıkla çakışmayı önler.
2. **Fact anahtarı `note`:** `kind: text`, `multi: true`, en çok 500 karakter. Sözlük anahtarı olmayan kısa her ifade
   buraya yazılır; kaybolmaz.
3. **Yönetilen sözlük:** bilinmeyen anahtar ipuçlu hatayla reddedilir: "`note` olarak yaz ya da `kb_propose(kind=schema)` ile
   anahtarı öner." Kullanıcı onaylarsa anahtar `schema/facts.yaml`'a girer. (Şema değişikliği zaten normatiftir: SPEC §7
   kural 6.)
4. **Okurken güven:** `kb_read` ve `kb_search` her ifadenin kaynağını ve durumunu gösterir (`doğrulanmış`, `doğrulanmamış`,
   `çelişki`), Wikidata rütbeleri gibi sıralar: güncel ve doğrulanmış önce, doğrulanmamış işaretli, çelişki uyarılı, geçmiş
   sonra. Hiçbir şey gizlenmez (değişmez 12). Durum yeni alan değildir: `status` (`ok` | `unverified` | `conflict`) ve
   `source` (güven sırası SPEC §7) zaten var; değişen, okuma çıktısında görünmeleridir.
5. **AGENT-RULES:** doğrulanmamış ya da çelişkili bilgiye dayanan, geri alınamaz bir işlemden önce kullanıcıya teyit ettir.
6. **F4 küratör:** tekrar eden `note`'ları onaylı anahtara yükseltmeyi önerir; aynı konunun iki adla açılmış kayıtlarını
   birleştirir (`cur_merge_entities`; yukarıdaki çakışma sorunu). Her ikisi küratörün mevcut sınırları içindedir; şema
   değişikliği `Proposal` kalır.
7. **Eval:** genel bilgi soruları `eval/questions.yaml`'a eklenmeli. Dosya hakem olduğu için bu bir **öneridir**,
   bu ADR'nin değişikliği değildir; kullanıcı onayı bekler.

## Sonuçlar
- (+) Ajan her konuda önce agentkb'ye bakabilir; sözlüğe sığmayan bilgi `note` olarak saklanır.
- (+) Sözlük kullanıcı onayıyla büyür; sorgulanabilir yapı (anahtar-değer) gürültüye dönmez.
- (−) `note` serbest metindir: sorgulanabilirliği zayıf, tekrar üretmeye açık. F4 yükseltmesine kadar böyle kalır.
- (−) Topic'in fixed etiketten varlık türüne geçmesi anlık görüntüde eski `Topic` düğümlerini etkilemez (aynı etiket), ama
  `Entity` etiketi eksik olan eski kayıtlar için uygulama diliminde göç gerekebilir. Ölçülmedi.
- (−) Okuma çıktısı satır başına biraz uzar; bütçe (8.000 karakter) sıralamadan sonra uygulanır, davranışı değişmez.

## Değerlendirilen alternatifler
- **Serbest anahtar (sözlüksüz):** aynı kavram için `ip`, `ip_address`, `addr` çoğalır; sorgu ve doğrulayıcılar bozulur.
- **Kapalı sözlük (bugünkü hali):** genel bilgi reddedilir, ajan kullanıcıya sorar; ana amaçla çelişir.
- **Tüm anahtarları ajan eklesin:** normatif değişiklik kuralını (SPEC §7 kural 6) aşar.
- **Ayrı `Note` düğümü:** ikinci varlık-dışı kayıt türü; mevcut fact modeli aynı işi görür (ADR-0016).
