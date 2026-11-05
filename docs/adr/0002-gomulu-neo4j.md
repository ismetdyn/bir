# ADR-0002: Graf motoru olarak gömülü Neo4j Community

- Durum: Kabul edildi (F1 spike ile doğrulanacak; ADR-0022 ile güncellendi: `GraphStore` arayüzü kalktı)
- Tarih: 2026-10-29

## Bağlam
Ajanların ilişkileri esnek sorgulayabilmesi için Cypher istiyoruz. Ayrı bir sunucu işletmek istemiyoruz.
Ana dil Java/Spring.

## Karar
Neo4j Community Edition'ı Spring Boot süreci içinde gömülü (embedded) çalıştır. Erişim `GraphStore` arayüzü
arkasında olsun.

## Gerekçe
- Saf Java, JNI yok; makinede paketleme kolay.
- Cypher'ın referans uygulaması; ajanlar Cypher'ı iyi yazıyor.
- Tam metin (Lucene) indeksi dahil; ayrı arama motoru gerekmiyor.

## Değerlendirilen alternatifler
- **Kùzu:** Ekim 2025'te arşivlendi. Yeni projede kullanılmaz.
- **Ladybug (Kùzu devamı):** Aktif ama Java bağlaması native (JNI); bazı paket kurulum yollarının henüz çalışmadığı belirtiliyor.
- **SQLite + recursive CTE:** Bu ölçekte yeterince hızlı, ama ajanların serbest graf sorgusu yazması zorlaşır.
- **Ayrı Neo4j sunucusu:** B planı. Classpath çakışması çıkarsa `127.0.0.1:7687` üzerinde ayrı süreç + Java driver.

## Sonuçlar
- (−) Tek süreç kilidi: CLI veritabanını açamaz, sunucunun API'sini kullanır.
- (−) GPLv3 lisansı: dağıtım kısıtı (bkz. SPEC §13).
- (−) JVM bellek kullanımı; heap ve page cache sınırlandırılır.

## v0.3 notu (2026-10-31)
ADR-0014 ile Neo4j türetilmiş indeks olmaktan çıkıp ana depo oldu. Veri dizini `~/.local/share/agentkb/graph`; yedek ve
senkron git anlık görüntüleriyle. Kùzu'nun aktif devamı LadybugDB (MIT lisanslı, Java paketi var) F1 spike'ında
ölçülen bir alternatif olarak kalır; geçişin gerekçesi hız değil, ancak lisans olabilir.
