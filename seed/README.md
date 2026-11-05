# seed — örnek repo ve test verisi

Bu klasör `~/kb` reposunun küçük bir örneğidir ve agentkb'nin golden test verisidir:

```
seed/
├── schema/      varsayılan şema; kb init bunu ~/kb/schema/'ya kopyalar
├── snapshot/    örnek graf, anlık görüntü biçiminde (nodes.jsonl, edges.jsonl)
├── blobs/       doküman deposu örneği (içerik adresli tek bir Markdown dosyası)
└── make_seed.py snapshot/ ve blobs/'u üreten betik: python3 seed/make_seed.py seed/snapshot
```

Seed'i elle değil `make_seed.py` ile değiştir; çıktı her çalıştırmada aynıdır. Sunucuyu bu veriyle açmak:
`java -jar kb-server/target/agentkb-server.jar --agentkb.repo=./seed`.

Secret düğümlerinde değer yok, çünkü `secret_value` anlık görüntüye hiç yazılmaz. Testler kurulumda sahte bir değeri
`PUT /api/v1/secrets/{id}` ile yazar ve bu değerin anlık görüntüde, git geçmişinde, loglarda ve araç çıktılarında
görünmediğini, `kb run`'ın ise onu alt sürece verdiğini kontrol eder.

## Bilerek konulan durumlar

| Durum | Nerede | Beklenen |
|---|---|---|
| Çelişki | `ornek-app-01` → ip `192.0.2.25` (agent, `conflict`) | `kb_read`'de ÇELİŞKİ olarak görünür; `user` kaynaklı `192.0.2.21` güncel kalır |
| Geçmiş | `ornek-app-01` → ip `192.0.2.20` (`valid_to` 2026-05-10) ← `SUPERSEDES` | "Eskiden IP neydi?" sorgusu `192.0.2.20` döner; `kb_read`'de "Geçmiş" altında; `kb_search "192.0.2.20"` sunucuyu `eski` işaretiyle ve güncel IP ile bulur (eval q16) |
| İlk ve son görülme | `ornek-app-01` → hostname (`valid_from` 2026-01-15, `observed_at` 2026-05-10) | Yeniden görülünce `observed_at` tazelenir, `valid_from` değişmez; geçmişe düşerse aralık 2026-01-15'ten başlar |
| Arşivli varlık | `ornek-app-00` (`status: archived`, alias `app00`) | Aramada bulunur; `arşiv` işaretli, güncel sonuçlardan sonra (eval q17) |
| Biten ilişki | `ornek-proje` → `DEPLOYED_ON` → `ornek-app-00` (`valid_to` 2026-05-10) | `kb_read ornek-proje`'de "Geçmiş" altında; `kb_related`'da `eski` işaretli, güncel ilişkilerden sonra (eval q18) |
| Bayat | `ornek-app-01` fact'leri 2026-05-10 tarihli | `kb_stale` (varsayılan 90 gün) listesinde |
| Port → hedef | `ornek-app-01` → port `8443/https` → `TARGET` → `ornek-proje` | `kb_read`'de "→ ornek-proje" |
| Kaynak dosya | `ornek-db` fact'leri → `FROM {line: 8}` → `application-prod.yml` | "Bu dosya değişirse ne etkilenir?" sorgusu `ornek-db`, `ornek-app-01` (port, satır 2) ve `ornek-proje` (bulgu, satır 10) döner |
| Secret | 3 `Secret` düğümü (değersiz) | `kb_read`'de ad ve placeholder; değer yazıldıktan sonra da hiçbir MCP veya Cypher çıktısında yok |
| Doküman | `doc:ded48257b6f3` (çalıştırma notu) → `ABOUT` → `ornek-proje`; dosya `blobs/de/…` | `kb_read doc:ded48257b6f3` yolu verir; örnek sorgu 9 dokümandan çıkan fact'i döner |
| Dokümandan çıkarım | `ornek-proje` → `convention` (agent, `unverified`) → `FROM` → doküman | `kb_read`'de doğrulanmamış olarak işaretli; kaynak olarak doküman görünür |
| Tarayıcı kaynaklı varlık | `ornek-db` (`status: observed`) | Hata değil; kullanıcı doğrulayınca `current` |
| Alias | `ornek-lb` ↔ `ornek-f5` | `kb_read ornek-f5` aynı kaydı döner |
| Stack'e özel karar | `karar-global-constructor-injection` → `APPLIES_TO` → `stack:java-spring` | Sadece java-spring repolarında brief'e girer |
| Her yerde geçerli karar | `karar-global-kodda-secret-yok` (`APPLIES_TO` yok) | Her brief'te |
| Proje kararı | `karar-ornek-ayri-systemd-servisleri` → `APPLIES_TO` → `ornek-proje` | Sadece `ornek-proje` brief'inde |
| Repo eşleme | `ornek-proje` → `HAS_REPO` → `ornek-grup/ornek-proje` | Bu remote'taki repoda brief `ornek-proje` döner |
| Ders | `ders-ornek-tls-yaniltici-test` → `ABOUT` → `ornek-app-01`, `topic:tls` | "TLS hatası curl -k" araması bu dersi bulur |
| Türkçe büyük İ | `ornek-yedek-01` (özette `İzmir`; id ve alias'ta geçmez) | `kb_search "izmir"` bu kaydı analizör üzerinden bulur (eval q12) |

Eşanlamlı ilişki türü (`uses` → `DEPENDS_ON`) yazma anında çevrildiği için seed'de yok; `WritePolicy` birim testinde.
Tarayıcı fixture'ı: `fixtures/scanner/ornek-proje/`, beklenen sonuç `fixtures/scanner/expected.yaml`.
