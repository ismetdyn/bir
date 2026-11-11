# agentkb — Proje Kiti (v0.3)

> **About (EN):** agentkb is a local, graph-based knowledge base for coding agents (Claude Code, Codex). Knowledge lives
> in an embedded Neo4j graph and is served to agents over MCP as Markdown; git is used only for snapshots and sync;
> secret values stay in the local graph and never leave the machine; agents use them through placeholders
> (`kb:secret/<id>`) that `kb run` resolves into environment variables. The kit also contains a curator agent and
> CLI/TUI/GUI clients. Documentation is in Turkish.

Claude Code ve Codex'e ortak, yerel, ilişkisel bir "beyin" veren, kendini düzenleyen bir bilgi deposu.

## Tek bakışta

```
Ajanlar ──MCP──► agentkb sunucusu ──► gömülü Neo4j (bilginin kendisi: varlık, fact, karar, ders, secret)
Sen ──TUI/GUI──►        │ okuma: Markdown, güncel önce, geçmiş işaretli · yazma: tek yazıcı, hiçbir şey silinmez
Küratör ──MCP──►        └─ 10 dk'da bir anlık görüntü + dokümanlar ──► ~/kb (git) ──► GitHub (private, yedek + senkron)
Secret: ajan sadece placeholder görür (kb:secret/<id>); kb run değeri alt sürecin ortamına koyar
```

v0.3'te değişen: Markdown vault, satır grameri, dosya izleyici, Obsidian entegrasyonu, ayrı pass deposu ve GPG çıktı.
Bilgi doğrudan grafta duruyor (ADR-0014); secret değerleri sadece bu bilgisayarda, ajanlar placeholder ile kullanıyor
(ADR-0015); sadelik ilkesi (ADR-0016); şişmeye karşı kurallar ve doküman deposu (ADR-0017).

## İçerik

| Yol | Ne |
|---|---|
| `CLAUDE.md` | Claude Code'un bu projeyi geliştirirken uyacağı kurallar |
| `LICENSE` | GPL-3.0-only (gömülü Neo4j Community nedeniyle) |
| `docs/SPEC.md` | Şartname v0.3: hedefler, kapsam dışı, veri modeli, yazma politikası, güvenlik, kabul ölçütleri |
| `docs/ARCHITECTURE.md` | Modüller, yazma yolu, tarayıcı, secret'lar, anlık görüntü ve senkron, uçlar, yapılandırma |
| `docs/ROADMAP.md` | F1–F5 fazları ve bitti tanımları |
| `docs/MCP-TOOLS.md` | Çalışan ajan ve küratör araçları, açıklama taslakları, örnek Cypher |
| `docs/SECRETS.md` | Secret'lar: placeholder'lar, `kb run`, akışlar, sınırlar |
| `docs/CURATOR.md` | Küratör ajan: sinyaller, yetkiler, parti + eval kapısı |
| `docs/CLIENTS.md` | REST API, CLI komutları, TUI ekranları, masaüstü GUI |
| `docs/RESEARCH.md` | Benzer projeler ve üç tur araştırma bulguları |
| `docs/RISKS-AND-GAPS.md` | Riskler, bilerek ertelenenler, test stratejisi |
| `docs/PUBLISHING.md` | GitHub'a koymadan önce kontrol listesi |
| `docs/adr/` | 21 mimari karar kaydı (0001, 0004, 0005 ve 0007'nin yerini başkaları aldı; 0010 emekli) |
| `curator/` | Küratör prompt'u, MCP ayarı, çalıştırma script'i |
| `seed/` | Örnek repo: varsayılan şema (`seed/schema/`, `kb init` bunu `~/kb/schema/`'ya kopyalar) + örnek graf (`seed/snapshot/*.jsonl`) + örnek doküman (`seed/blobs/`) + `make_seed.py`; test durumları `seed/README.md`'de |
| `fixtures/scanner/` | Tarayıcı golden testi: örnek Spring projesi + `expected.yaml` |
| `eval/questions.yaml` | Değerlendirme seti |
| `integrations/` | Ortak ajan talimatı, `kb-hook.sh`, Claude Code/Codex ayarları, systemd birimleri |

## Kurulum

Gerekenler: Java 21+, git, systemd (user). Tek komut, tekrar çalıştırmak güvenlidir:

```bash
integrations/install.sh --dry-run   # hiçbir şey yazmaz; yapılacakları ve ajan ayarı geçişini gösterir
integrations/install.sh             # derler, ~/kb-tools ve ~/.local/bin/kb'yi kurar, kb init, servis + senkron timer
```

**`kb` istemcisinin seçimi** (her kurulum script'i bu sözleşmeye uyar). Native, jar'a tercih edilir:

| `AGENTKB_CLIENT_NATIVE` | Kurulan istemci |
|---|---|
| tanımsız | checkout'ta çalıştırılabilir `kb-client/target/kb` varsa native, yoksa jar. Native `kb-core`, `kb-api` veya `kb-client` kaynaklarından eskiyse uyarı verilir, kurulum durmaz |
| boş (`AGENTKB_CLIENT_NATIVE=`) | jar |
| yol | o native dosya; yoksa veya çalıştırılabilir değilse hata, HOME'a dokunulmaz |

Native `~/kb-tools/kb`'ye, jar `~/kb-tools/agentkb-client.jar`'a kopyalanır. `~/.local/bin/kb` ikisini de
`-Duser.home=$HOME` ile çalıştırır. Başarılı kurulumdan sonra öbür türün dosyası silinir. Native'i script
derlemez; önce (Mandrel 23.1.13, ADR-0025):

```bash
mise exec java@mandrel-23.1.13.0-Final+java21 -- ./mvnw -pl kb-client -am -Pnative package
```

Claude Code ve Codex ayarları (MCP, hook'lar, AGENT-RULES, yerleşik hafızayı kapatma) script tarafından yazılmaz;
adımlar `integrations/claude-code/setup.md` ve `integrations/codex/setup.md`'de. Önce `kb import-memories`.

## Başlarken

F1 (spike) bitti; bulgular `docs/adr/0018-f1-spike-bulgulari.md`'de. F2 (bilgi çekirdeği) geliştiriliyor.

```bash
claude
```

İlk mesaj:

> `CLAUDE.md`'yi ve `docs/` altındaki SPEC, ARCHITECTURE, ROADMAP ve ADR'leri oku. ROADMAP'teki **F2**'yi yap;
> önce testleri yaz; bitti tanımını kontrol et. Kod yazmadan önce planını göster.

Sonraki fazlar için kalıp: "ROADMAP'teki F<n>'i yap; önce testleri yaz; bitti tanımını kontrol et."

## Bu kitteki varsayılan kararlar

| Konu | Varsayılan | Nerede |
|---|---|---|
| Depo | Bilgi gömülü Neo4j'de; git sadece anlık görüntü ve senkron | ADR-0014, SPEC §6 ve §10 |
| Sürümleme | Fact ve ilişkiler silinmez, kapanır; yenisi `SUPERSEDES` ile bağlanır | ADR-0014 |
| Secret'lar | Grafta, değer sadece bu bilgisayarda; ajan placeholder + `kb run` ile kullanır | ADR-0015, SECRETS.md |
| Büyüme | Grafa damıtılmış bilgi; ham dosyalar `~/kb/blobs`; hiçbir şey silinmez ya da gizlenmez, güncel bilgi önce gelir; `kb check` büyümeyi raporlar | ADR-0017 |
| Yazma | Kaynak sırası (user › user_stated › scan › agent) + çelişki + parti geri alma | ADR-0006 |
| Config bilgisi | Deterministik tarayıcı | ADR-0008 |
| TUI / GUI | TamboUI (native) / Dear ImGui (`kb-gui`) | ADR-0012, ADR-0021 |
| Küratör | Headless Claude Code, sadece `/mcp/curator` | ADR-0011, CURATOR.md |
| Yerleşik hafızalar | Kapalı (önce içe aktarılır) | ADR-0013 |
| Sadelik | Kanıtlanmış ihtiyaç olmadan mekanizma yok | ADR-0016 |
| Yollar | İşletim sistemine göre `AgentkbPaths`'te; Linux: repo `~/kb`, veri `~/.local/share/agentkb`, araçlar `~/kb-tools`; Windows: `%USERPROFILE%\kb`, `%LOCALAPPDATA%\agentkb`; `AGENTKB_HOME` hepsini taşır; port `7411` | ARCHITECTURE §14, ADR-0027 |
| Lisans / yayın | GPL-3.0-only; kişisel ad alanı (`io.github.ismetdyn.agentkb`) | PUBLISHING.md |
