# ADR-0011: Küratör ajan — karatahta mimarisi, sunucuda zorlanan sınırlar, ölçülen partiler

- Durum: Kabul edildi
- Tarih: 2026-10-29

## Bağlam
Bilgi bankası ve kurallar kendi kendini güncellemeli ve iyileştirmeli; bu işi yapan ajan diğer ajanlarla iletişim
kurmamalı; çalışan ajanlar sadece bu yapıyı kullanmalı.

## Karar
- Küratör zamanlanmış headless bir Claude Code oturumudur; sadece `/mcp/curator` araçlarına sahiptir (shell, dosya, ağ yok).
- Çalışan ajanlarla iletişim yoktur; sinyaller ve bilgi bankası üzerinden dolaylı çalışır (karatahta).
- Yetkiler `/mcp/curator` ucunun kod sınırlarıyla sunucuda zorlanır (ADR-0020): hijyen otomatik, normatif değişiklikler öneri, değişmezler yasak.
- Değişiklikler partiler halinde yapılır; parti sonunda sunucu eval ve `kb check` çalıştırır; gerileme varsa parti geri alınır.

## Gerekçe
Sektör aynı yöne gidiyor (Claude Code Auto Dream, Codex Dreaming, Letta dreaming, ACE). Farkımız: sınırların prompt'ta
değil sunucuda olması ve her partinin ölçülmesi.

## Sonuçlar
- (+) Kendini düzenleyen ama bozulmayan bir bilgi bankası; her değişiklik görünür ve geri alınabilir.
- (−) Token maliyeti; tetikleyici eşikleriyle yönetilir. Headless bayrakları sürüme bağlı (F4'te doğrulanır).
