# ADR-0020: Token v2'ye kalır, yalnız tarayıcı kontrolü ve loopback zorunlu

- Durum: Kabul edildi
- Tarih: 2026-11-01
- Değiştirdiği: ADR-0003 (uçların token sütunu), ADR-0011 ve ADR-0015'in token dayanakları, `CLAUDE.md` değişmez 4

## Bağlam
SPEC her uç için ayrı token kapsamı (`agent`, `curator`, `hook`, `user`) öngörüyordu. Sistem tek kişinin bilgisayarında çalışır;
kullananlar kullanıcı ile onun Claude Code ve Codex ajanlarıdır. Ajanlar kullanıcıyla aynı Linux hesabındadır: token
dosyalarını da veri dizinini de okuyabilirler. Token bu ortamda ajanı durdurmaz, ama kurulum ve işletme yükü getirir.

Tek gerçek dış tehdit tarayıcıdır: açık bir web sitesi DNS rebinding ile `127.0.0.1:7411`'e istek atabilir. Sunucunun loopback'te
dinlemesi bunu engellemez, çünkü istek aynı bilgisayardan çıkar. F1'de sahte `Host` ve `Origin` ile iki uçta `initialize` HTTP
200 döndü (ADR-0018). `kb sync`'in uzak deposu da güvenilmezdir: içeri alınan satırlar kayıpsız alınır, ama sunucu yazarken
uzaktan gelen dosyaları bağlantı (symlink) olarak asla izlemez.

## Karar
1. **Token ve kapsamlar v2'ye kalır.** `~/.config/agentkb/tokens/`, `TokenScopeFilter` ve `Authorization` başlığı yok.
2. **`OriginGuard` F2'nin ilk işidir, veri aracından önce gelir.** Servlet `Filter`, `/*`: `Origin` başlığı varsa ya da `Host`
   `127.0.0.1:7411` veya `localhost:7411` değilse 403. `/mcp/curator` DispatcherServlet dışında olduğu için interceptor yetmez.
   İstemciler (Claude Code, Codex, `kb`) gerçekten geçiyor mu, F2'de denenir.
3. **Loopback zorunlu.** `server.address` loopback değilse sunucu açılmayı reddeder. Token olmadığı için ağa açılmak bilgi
   bankasını tümüyle açar.
4. **Uç ayrımı ve kod sınırları kalır.** `/mcp`, `/mcp/curator`, `/hooks/*`, `/api/v1/**` ayrı durur. Küratör sınırları (parti,
   yazma sayısı, yasak işlemler) ve Cypher kısıtları kodda kalır.

## Sonuçlar
- (−) "Ajan secret değerini görmez" zorlanan bir sınır değil, kural olur: `kb run` ve placeholder akışı çalışır, ama ajan isterse
  `GET /api/v1/secrets/{id}` çağırabilir. Kullanıcı ajanlarına güvendiği için kabul edildi. Değerin MCP, Cypher, log, anlık
  görüntü ve git'e girmemesi kuralları değişmez.
- (−) Bu bilgisayarda çalışan her program sunucuyu okuyup yazabilir.
- (+) Kurulum sadeleşir; istemci ayarlarında başlık ve ortam değişkeni gerekmez.
- `integrations/kb-hook.sh`, `curator/mcp.json`, `curator/run-curator.sh` token'sız hale F2'de getirilir.

## v2 koşulu
Başka bir kullanıcı veya makine erişecekse ya da ajanların secret değerine erişimi gerçekten engellenecekse token gelir. O durumda
ajanın ayrı bir işletim sistemi kullanıcısında çalışması da gerekir; yoksa token dosyası yine okunur.

## Değerlendirilen alternatifler
- **Token + `OriginGuard` (eski karar):** Aynı hesaptaki ajana karşı koruma sağlamıyor; sistemi kısıtlıyor.
- **Hiçbir kontrol yok:** Tarayıcıdaki bir site bilgi bankasını ve secret değerlerini okuyabilir. Kontrol ajanları kısıtlamadığı
  için bu riske değmez.
