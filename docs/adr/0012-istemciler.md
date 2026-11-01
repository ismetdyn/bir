# ADR-0012: İstemciler — REST API, native CLI/TUI (TamboUI), sunucu içi web GUI (htmx)

- Durum: Kabul edildi
- Tarih: 2026-10-29

## Bağlam
Kullanıcı bilginin var olup olmadığına hızlıca bakmak, bilgi ekleyip silip güncellemek istiyor; hem terminal hem GUI.

## Karar
- Tüm insan istemcileri `/api/v1` REST API'sini kullanır; yazma politikası ortak, kaynak `user`.
- CLI + TUI: tek `kb` binary'si; picocli + TamboUI; GraalVM native-image (gömülü Neo4j taşımadığı için uygun).
- Web GUI: sunucunun içinde, Thymeleaf + htmx + Cytoscape.js; CDN yok; tek kullanımlık giriş linki, CSRF, CSP.
- Obsidian (Bases dahil) ara çözüm ve tamamlayıcı olarak kalır. **v0.3 (ADR-0014):** vault olmadığı için Obsidian
  çıkarıldı; insan arayüzü TUI ve web GUI.

## Değerlendirilen alternatifler
- Go/Bubble Tea TUI: olgun; ama ikinci dil. TamboUI başarısız olursa B planı.
- Vaadin: Java ile UI; ağır ve Spring Boot 4 uyumu ayrıca doğrulanmalı.
- React SPA: Node araç zinciri gerektirir.

## Sonuçlar
- (+) Tek dil (Java), anında açılan TUI, kurulum gerektirmeyen GUI.
- (−) TamboUI 0.x; native-image ayarları zaman alabilir.
