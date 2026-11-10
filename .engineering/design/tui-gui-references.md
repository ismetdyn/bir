# TUI ve GUI tasarım referansı (H8a/H8b, F5)

Tarih: 2026-11-11. Amaç: tekerleği yeniden icat etmemek. Bu belge karar değildir; ADR'lere dokunmaz, öneri verir.
Kural: CLI/TUI/GUI yalnız arayüzdür, her özellik mevcut `/api/v1` ucuna dayanır (ARCHITECTURE §1). Ucu olmayan özellik
"YENİ UÇ GEREKİR" diye işaretlendi (bölüm 8).

## 1. Özet ve önerilen kararlar

1. **TamboUI'de kal** (0.6.0 → 0.7.0 yükseltmesi ayrıca onay ister). Kanıt: bölüm 4. Çıkış planı (Go/Bubble Tea) gerekmedi.
2. **TUI düzeni noteapp'ten alınır:** sol liste + sağ önizleme/detay, yazdıkça süzme, alt satırda girdi, alt çubukta bağlama
   göre kısayol ipucu. CLIENTS §3 çizimi zaten bu düzen.
3. **Tek komut tablosu:** her eylem tek kayıtta (id, etiket, tuş, çalışabilir mi). Tuş, komut paleti (`:`) ve yardım (`?`)
   aynı tablodan gelir; noteapp'te "tuş ve palet aynı işi yapar" testi var. H8a'da kur, H8b ve GUI aynısını kullansın.
4. **Durum modeli saf, çizim ince:** `TuiState` (saf: tuş → durum + `Intent`) ile `View` ayrı. Testlerin çoğu modelde;
   TamboUI Pilot yalnız birkaç uçtan uca senaryoda. GUI'de aynı model/görünüm ayrımı.
5. **Onay diyaloğunun varsayılanı güvenli seçenek** (Vazgeç); `kb revert`, arşiv, çelişki kabulü için. lazygit/k9s aynı yolda.
6. **Çelişki ve öneri ekranları:** yan yana iki sütun (mevcut | bekleyen), kaynak ve tarih altta; kabul/ret tek tuş + onay.
7. **Aktivite = parti listesi + `revert`**: lazygit reflog/undo (`z`) deseni; geri alma sonucu sayıyla gösterilir.
8. **GUI'de graf:** imgui-java'nın paketlediği **ImNodes** yeterli başlangıç; ama ImNodes düğüm-editörüdür, kuvvet yerleşimi
   vermez. Yerleşim (Fruchterman–Reingold) noteapp'te yazılı (`src/app/graph_layout.*`) ve Java'ya taşınır (ADR-0021 madde 4).
9. **Obsidian'dan alınacaklar:** hızlı geçiş (fuzzy, Enter yoksa oluştur değil → bizde: yoksa "ekle" önerisi), komut paleti
   (son kullanılan önce), yerel graf (derinlik kaydırıcılı), bağlantılar paneli (gelen/giden). Hepsi `GET /entities/{id}` +
   `GET /search` ile yapılır.
10. **Üç eksik uç** (bölüm 8): şema (fact anahtarları, ilişki türleri), varlık listesi/gözatma, parti ayrıntısı. Bunlar
    olmadan "şemaya göre form" ve otomatik tamamlama sunucudaki şemayı istemciye kopyalamak olur (tek olgu tek yerde ihlali).

## 2. noteapp'ten alınacaklar

Kaynak: `/home/ismet/dev/noteapp` (C++; TUI = FTXUI v7.0.3, GUI = SDL3 + Dear ImGui v1.93.0; `SPEC.md` §3).

| Konu | noteapp'te ne var | agentkb'ye karşılığı |
|---|---|---|
| Yerleşim | `SPEC-tui.md` "Yerleşim": üstte yol + sayaç + durum, solda liste, sağda önizleme, altta mod/ipucu satırı. Seçim değişince önizleme aynı karede güncellenir | CLIENTS §3 çizimi. Üst satır: arama + filtre çipleri (`tür`, `ortam`); sağ panel: `MarkdownView` değil JSON `EntityAnswer` çizimi (bkz. §3 not) |
| Odak modeli | 4 odak: Liste, Arama, Önizleme, Editör. Harf kısayolu yalnız Liste/Önizlemede; yazı girdisinde harfler metindir; diyalog açıkken tüm tuşlar ona gider | Aynen: Liste, Arama, Detay, Form. En sık hata "yazarken kısayol tetiklenmesi"; kabul kriteri 7 bunun testi |
| Tuşlar | j/k + oklar, `gg`/`G` (bekleyen önek alt satırda söylenir), `/` ara, `Enter`, `Esc` (kademeli: önce süzgeç temizle), `q`, `?` yardım, `:` palet | CLIENTS §3 tablosundaki tuşlarla çakışmaz; `Esc` kuralı ve `g` önekini al |
| Arama davranışı | fzf gibi: yazdıkça süzülür, eşleşen karakter vurgulu, `Ctrl-P/N` arama geçmişi, `Tab` tamamlama, gölge öneri (soluk) | Süzme sunucuda (`GET /search`); geçmiş istemcide, kayıtlı dosyada. `limit` 1–20 → sayfalama yerine "N daha var" (`more`) satırı |
| Komut paleti | `:`; tek tablo (`TuiState::commands()`), `id` + etiket + tuş, çalışamayan komut soluk ve nedenli, son kullanılan önce (en çok 20, kalıcı) | Aynı. Çalışamayan nedenleri: "sunucuya ulaşılamıyor", "kayıt seçili değil", "arşivli" |
| Girdi satırı | Tek saf bileşen (`InputLine`): arama, yol, ad, palet hepsi aynı; Türkçe katlama (`I`=`ı`=`i`) | TamboUI `TextInputElement` + kendi ince sarmalayıcı. Kimlik/alias eşleşmesi `Ids.normalize()` (CLAUDE.md `toLowerCase` tuzağı) |
| Onay diyaloğu | Kalıcı silme onaylı, varsayılan "Vazgeç"; çöpe atma onaysız ama `u` geri alır | agentkb'de silme yok: arşiv onaylı, geri alınabilir (`unarchive`); `revert` onaylı, sayıyı önceden göster |
| Ayarlar ekranı | `,`/`F9`: form; Kaydet → doğrula → atomik yaz → uygula; değişiklik varken çıkışta Kaydet/At/Vazgeç sorusu; satır içi hata | agentkb'de ayar sunucuda yok (v0.3 kapsamı). GUI "Bağlantı" bölümü (port, repo yolu salt okunur) için aynı çıkış sorusu deseni. Yeni ayar mekanizması ekleme (ADR-0016) |
| Durum/olay döngüsü | `TuiState` saf; `Intent` enum'u dış işleri ister (editör aç, çık...), yürütücü yan etkiyi yapar. GUI: `GuiState` + `View`; olay `SDL_WaitEventTimeout`, boştayken kare yok; işçi sonucu `post` kuyruğuyla ana iş parçacığına | TUI: TamboUI `TuiRunner` olay döngüsü + REST çağrıları ayrı iş parçacığında (UI donmasın), sonuç kuyrukla. GUI: ImGui sürekli çizer; boşta CPU için `waitEvents` timeout (imgui-java-app'te doğrula) |
| Arayüz ve çekirdek ayrımı | `src/core` `src/search` UI'yı bilmez; `app/` iki arayüzün ortak mantığı (listeleme, harita modeli, yerleşim) | agentkb'de çekirdek sunucu. Ortak mantık (komut tablosu, süzgeç ayrıştırma, çizge yerleşimi) `kb-api`'ye değil, küçük ortak istemci paketine gerekirse (yalnız GUI+TUI paylaşıyorsa). Şimdilik TUI içinde tut; F5'te gerçek tekrar görünce çıkar |
| Test yaklaşımı | Küçük (~%80): saf durum testleri; orta: ekranı metne render eden testler + başsız GUI (`SDL_VIDEO_DRIVER=dummy`, yazılımsal çizim); büyük: tmux + gerçek terminal e2e betiği (Türkçe yazım, SIGKILL, boyut). Düzen testi: 1/1.25/1.5/2 ölçek, en küçük pencere, çakışma yok | TamboUI Pilot (bölüm 4) + saf model testleri. Elle kontrol listesi: Türkçe klavye, 60 sütun, `--no-alt-screen`. GUI: ImGui başsız çizim imgui-java'da zor; model testleri ağırlıkta, çizim elle |
| Çizim | Yalnız değişen hücre yazılır, synchronized update, bracketed paste, `Ctrl+L` yeniden çiz, `Ctrl+S`/`Ctrl+Q` XON/XOFF için IXON'un kapatıldığının e2e testi | TamboUI'de bunların hangisi var **doğrulanmadı**; H8a'da pty testiyle denenir. Önlem: `Ctrl+S` ile kaydetmeye güvenme, form kaydı `Enter`/`Ctrl+Enter` |
| FTXUI yaması | 7.0.3'e yama gerekti: her karede tüm ekran yazma, 128 bayt okuma, bracketed paste yok (`cmake/patches/ftxui-7.0.3-frame-writer.patch`) | Ders: kütüphane seçerken **yazma yolunu ve yapıştırmayı** ölç. TamboUI'nin hücre-fark yazıcısı ve Kitty klavye protokolü (0.7.0) var; bracketed paste elle denenir |
| Türkçe | "`ı` ve bilinmeyen harfler yok sayılır" (harf kısayolu birebir); font: DejaVu gömülü | TUI'de harf kısayolları ASCII; `ı/İ/ğ/ş` yazı girdisinde çalışmalı (test). GUI'de ADR-0021'deki font işi: DejaVu Sans Mono (Bitstream Vera türevi lisans) noteapp'te gömülü kullanıldı, aynı yol |
| Harita (graf) | `SPEC-gui.md` "Harita": FR yerleşimi (ızgara sürümü, itme 2k yarıçapında), başlangıç konumu anahtar özetinden (belirlenimci), düğüm sınırı 2.000, kenar çizimi ucuz çizgi, çift tıkla komşu aç/kapat, süzgeç çipleri, en kısa yol, sabitleme (pin), "Haritada bul" | GUI graf gezgini için en değerli parça; bölüm 5 ve 6 |

## 3. TUI desenleri (ekran → örnek proje → bizdeki karşılık)

Notlar: lazygit, k9s, fzf, gitui, btop, zk, nb sayfaları 2026-11-11'de okundu; kaynak URL'ler tabloda.
`GET /entities/{id}` iki biçim verir (JSON/Markdown). TUI JSON `EntityAnswer` kullanmalı: fact/ilişki id'leri düzenleme
için gerekli (`facts[].id`, `relations[].id`, `outgoing` ile gelen/giden ayrımı).

| Ekran / özellik | Örnek ve ne alınır | agentkb karşılığı | Uç |
|---|---|---|---|
| Arama + önizleme listesi | **fzf**: `--preview` seçili satır için panel, `--preview-window` konum, `change-preview-window` ile aç/kapa, `--bind change:reload` ile her tuşta yeniden sorgu, genişletilmiş sözdizimi (`'tam`, `^önek`, `!hariç`) — https://github.com/junegunn/fzf. **zk** de fzf'i not seçicide kullanıyor — https://zk-org.github.io/zk/ | Sol: `SearchAnswer.hits` (tier işareti: güncel/geçmiş/ham; arşivli işareti); sağ: seçili kaydın detayı. Her tuşta sorgu yerine 150 ms debounce; en son isteğin cevabı geçerli (eski cevap atılır) | `GET /search` |
| Arama filtreleri | **k9s** `/` filtre, `:pod /fred` komut+filtre, `/-f` bulanık — https://k9scli.io/topics/commands/. **noteapp** `tür:`/`in:` argümanları | `[tür]` `[ortam]` çipleri + `tür:server env:prod` yazımı. Tür/ortam parametreleri uçta var | `GET /search?type=&env=` |
| Detay görünümü | **lazygit**: sol yığın paneller, sağda ana pencere; `+`/`_` odaktaki paneli yarım/tam ekran; sayı tuşuyla panele atla — https://github.com/jesseduffield/lazygit/blob/master/docs/Config.md | CLIENTS §3 detay: Bilgi / Secret / İlişki bölümleri. Geçmiş fact'ler (kapalı) soluk, `ÇELİŞKİ` kırmızı. Secret yalnız ad + placeholder | `GET /entities/{id}` |
| Gelen ilişkiler (geri bağlantı) | **Obsidian/Foam** backlinks (bölüm 5) | `relations[].outgoing=false` satırları "Gelen" başlığında; Enter ilgili kayda gider, `Backspace` geri (gezinme yığını) | `GET /entities/{id}` |
| Satır içi düzenleme formu | **gitui/lazygit** küçük popup girdileri; **noteapp** `n`/`F2` altta tek satırlık girdi, hata satırda kalır | `e` özet+alias, `a` fact (anahtar, değer, ortam), `r` ilişki (tür, hedef). Tek satırlık girdi + Tab tamamlama; doğrulama reddi sunucudan gelir ve **olduğu gibi** gösterilir (H8a kabul: "reddi göster"). TamboUI `FormElement`/`TextInputElement` var | `PATCH /entities/{id}`, `POST …/facts`, `POST …/relations` |
| İlişki hedefi tamamlama | **noteapp** `Tab`: tek aday doğrudan yazılır, çoksa fzf gibi liste; gölge öneri (`→` alır) | Hedef: `/search` ile; tür: şema listesi (**YENİ UÇ**, bölüm 8) | `GET /search`; şema ucu yok |
| Onay diyaloğu | **lazygit**: riskli işlemde uyarı; **k9s**: `ctrl-d` TAB+Enter onayı, `--readonly` bayrağı — https://k9scli.io/topics/commands/. **noteapp**: varsayılan "Vazgeç" | Arşiv (`d`), `revert`, çelişki kabulü: `[Vazgeç] [Onayla]`, varsayılan Vazgeç, sonuç tek satır bildirim. `kb tui --readonly` düşünülebilir (ürün sorusu, bölüm 9) | `POST …/archive`, `/revert`, `/conflicts/{id}/resolve` |
| Çelişki yan yana | **lazygit** birleştirme çakışması: iki taraf, seçimle çöz; **gitui** diff görünümü — https://github.com/extrawurst/gitui | İki sütun: sol "Güncel" (`current[]`), sağ "Bekleyen" (`value`, `source`, `validFrom`). `←/→` seç, `Enter` = `accept-new`/`keep-old`. Eksik: güncel değerin kaynağı/tarihi yok (`current` yalnız metin) — bölüm 8 | `GET /conflicts`, `POST /conflicts/{id}/resolve` |
| Öneri farkı | **gitui** hunk/diff, **lazygit** diff paneli | `ProposalsAnswer.content` düz metin; kind'a göre başlık (karar/kural/arşiv/şema). Gerçek "fark" için eski değer gerekir: yalnız `rule-change`/`schema` türlerinde anlamlı; kind=archive için hedef kaydın özeti. `a`/`x` kabul/ret (ret notu girdisi) | `GET /proposals`, `POST …/accept`, `…/reject` |
| Aktivite günlüğü + geri alma | **lazygit** komut günlüğü (yapılanların listesi) ve `z`/`Z` geri al/yinele, reflog paneli | Parti listesi (`ActivityAnswer.Batch`: saat, kaynaklar, açılan/kapanan/değişen sayısı, dokunulan ≤10 kayıt). `source` filtre çipi (agent/scan/curator/user). `r` = `revert` onaylı. Yinele yok (silme yok kuralı; revert da geri alınamaz mı: ürün sorusu) | `GET /activity`, `POST /revert` |
| Bayat liste | **k9s** tablo + sütun sıralama; tek tuş eylem (`d`,`e`,`l`) | `s`: tablo (kayıt, anahtar, yaş); `v` "doğruladım" = aynı değeri USER olarak yaz (ADR-0022 #22) | `GET /stale`, `POST …/facts` |
| Yardım alt çubuğu | **lazygit** alt satır ipuçları + `?` menü; **noteapp** odağa göre değişen ipucu, 60 sütunda kelime ortasında kesilmez; **gitui** bağlamsal yardım | CLIENTS §3 çizimindeki alt çubuk bağlama göre değişir (liste/detay/form/diyalog). `?` = tüm tuşlar (komut tablosundan üretilir) | — |
| Komut paleti | **k9s** `:` komut kipi (ad + kısaltma); **Obsidian** palet (bölüm 5); **noteapp** palet | `:` — "çelişkiler", "aktivite", "ara <metin>", "secret listesi". Başka ekrana geçiş ve eylem aynı tablodan | — |
| Secret listesi | **k9s** tablo; ama değer asla | Yalnız ad, ortam, placeholder, "bu bilgisayarda değeri var" işareti. Kopyala: placeholder panoya (`kb:secret/<id>`), değer asla | `GET /secrets?prefix=` |
| Mouse, tema, yerleşim | **btop**: tıklanabilir düğmeler, tema dosyası, kutu ön ayarları — https://github.com/aristocratos/btop | Mouse: tıklama=seç, çift tıklama=aç (noteapp 400 ms). Tema: TamboUI TCSS var; ilk sürümde tek koyu/açık otomatik. Ön ayar yok | — |
| Yeniden bağlanma | k9s: bağlantı kopunca ekranda durum | Üst çubukta sunucu durumu (`●`/`○`); çıkış kodu 3 ile aynı mesaj: "sunucuya ulaşılamıyor", yeniden dene `r` | `GET /check` (hafif ping için) |

Bilerek alınmayanlar: sekmeler (noteapp çoklu not sekmesi; bizde tek kayıt + geri yığını yeter), gömülü editör
(fact'ler kısa değer; çok satırlı metin ADR-0017'ye aykırı), dil sunucusu, sözdizimi vurgusu, PDF görüntüleme.

## 4. Java TUI kütüphane karşılaştırması

Doğrulama tarihi 2026-11-11. "Aktivite" için GitHub API ve Maven Central meta verisi kullanıldı.

| | **TamboUI** | **Lanterna** | **Jexer** | **JLine 3 (doğrudan)** |
|---|---|---|---|---|
| Sürüm | **0.7.0** (2026-11-09); 0.6.0 (2026-10-12); 0.5.0 (2026-09-14). Maven Central'da 0.2.0–0.7.0 — https://repo1.maven.org/maven2/dev/tamboui/tamboui-bom/maven-metadata.xml | 3.2.0-alpha1 (2026-03-15) — https://repo1.maven.org/maven2/com/googlecode/lanterna/lanterna/maven-metadata.xml | 2.0.0, `io.gitlab.autumnmeowmeow:jexer` — https://jexer.sourceforge.io/downloads.html | JLine 3 (terminal katmanı; TamboUI'nin jline3 backend'i buna dayanır) |
| Aktivite | Çok yüksek: son push 2026-11-10, ~3–4 haftada bir sürüm, 697 yıldız, 163 açık issue — https://github.com/tamboui/tamboui | Düşük/orta (alpha) | Düşük/orta, tek yazar | Yüksek |
| Olgunluk | 0.x, API değişir (ADR-0025 karar 8 sürüm sabitliyor) | Eski ve kararlı; modern widget yok | Pencere/menü odaklı (Turbo Vision tarzı), kendi pencere yöneticisi | Kütüphane değil terminal/satır editörü; widget yok |
| Widget | Block, Paragraph, List, Table, Tabs, Gauge, Sparkline, Chart, Canvas, Calendar, Scrollbar, TextInput; Toolkit: **DialogElement, FormElement, FormFieldElement, ListElement, TableElement, TabsElement, TextInputElement, TextAreaElement, TreeElement**, Dock/Grid/Flow/Stack düzenleri — https://github.com/tamboui/tamboui/tree/main/tamboui-toolkit/src/main/java/dev/tamboui/toolkit/elements | Pencere, buton, liste, tablo, giriş | Zengin (pencere, tablo, ağaç, editör, menü) | Yok |
| Native-image | Resmi hedef (README + sitede "native image docs"); bizim CP1'de ölçüldü: native `kb` 40,7 MB, açılış medyan 5 ms (ADR-0025) | Doğrulanamadı (kaynak bulunamadı) | Doğrulanamadı | Belgeli (GraalVM 23.1+) |
| Test desteği | **Pilot** (`TuiPilot`, `ToolkitPilot`), başsız `TestBackend`; Central'da `-test-fixtures.jar` yayımlı (tamboui-tui ve tamboui-toolkit 0.7.0) | Yok | Yok | Yok |
| Klavye | 0.7.0: Kitty klavye protokolü (Ctrl+Super vb. değiştirici), anlamsal tuş koşulları (`isTyping()`), sözcük bazlı düzenleme — https://github.com/tamboui/tamboui/releases/tag/v0.7.0 | Klasik | Klasik | Ham |
| Lisans | MIT | LGPL-3 (bkz. proje sayfası) | MIT (arama sonucu, doğrulanmadı) | BSD |
| Arkadaşlar | picocli entegrasyonu (`tamboui-picocli`, zaten kullanılıyor), CSS (TCSS), markdown, resim | — | — | — |

Dış sinyal: JBang bağımlılık arama TUI'sini TamboUI'ye taşıdı (draft PR, jar +33 KB; ölçümler CLI çatısına ait, TUI'ye değil) —
https://github.com/jbangdev/jbang/pull/2465; genel haber: https://infoq.com/news/2026/05/tamboui-java-tui.
Dikkat: TamboUI README'si "yalnız snapshot" diyor ama Central'da sürümler var; README eski kalmış, güvenme.

**noteapp'in FTXUI seçimi kararı değiştirmez.** FTXUI C++; Java'da karşılığı yok. Yalnız ders: FTXUI 7.0.3'e tam-kare yazma,
128 bayt okuma ve bracketed paste için yama gerekti. TamboUI için aynı üç noktayı H8a'nın ilk günü pty ile ölç.

**Öneri: TamboUI'de kal.** Gerekçe: (a) Toolkit'te ihtiyacımız olan her şey hazır (Dialog, Form, List, Table, Tree, TextInput);
(b) başsız Pilot test altyapısı Central'da; (c) native-image CP1'de ölçüldü; (d) aktif ve hızlı sürüm; (e) TamboUI importu
yalnız `client.tui`'de (ArchUnit) olduğu için çıkış maliyeti zaten düşük. Riskler: 0.x API kırılması (sürüm sabit),
163 açık issue (H8a'da kullanacağımız widget'lar için issue taraması yap).
Aday iş: 0.6.0 → 0.7.0 (Kitty klavye: `Ctrl+Enter` ve Alt kombinasyonlarının terminalde güvenilir ayrışması, sözcük
düzenleme). Bağımlılık yükseltme onay gerektirir; H8a başlamadan karar ver, ortasında bump yapma.
Lanterna ve Jexer'e geçiş için gerekçe yok: test desteği yok, widget modeli bizim "ince istemci" ihtiyacımıza fazla
pencere-yönetici odaklı, native-image kanıtı yok.

## 5. GUI desenleri (ekran → örnek → bizdeki karşılık)

Obsidian kapalı kaynaktır; dokümantasyonu ve eklenti API'si açık. Logseq AGPL-3.0, Foam MIT, Neo4j Browser GPL-3.0
(Bloom ticari). Kaynak kodu kopyalamak yerine **desen** alınır; GPL/AGPL koddan satır alma.

| agentkb ekranı | Örnek ve alınacak desen | Kaynak | Karşılık ve uç |
|---|---|---|---|
| Arama (hızlı geçiş) | **Obsidian Quick Switcher**: bulanık eşleşme (harfler sırayla), takma ad eşleşir, `Ctrl+Enter` yeni sekmede aç, eşleşme yoksa `Enter` oluşturur | https://obsidian.md/help/plugins/quick-switcher | `Ctrl+K`: üstte modal arama. Takma ad zaten arama kapsamında. Eşleşme yoksa "Oluştur: <ad>" satırı → `POST /entities` (tür seçimi gerekir: Obsidian'daki "tek Enter" burada tür sorar). `GET /search`, `POST /entities` |
| Komut paleti | **Obsidian**: bulanık, son kullanılan önce, sabitlenmiş komutlar; filtrelerken kısa ad üstte | https://obsidian.md/help/plugins/command-palette | `Ctrl+Shift+P`; TUI ile aynı komut kimlikleri. İstemci yerel |
| Kayıt detayı | **Foam/Obsidian** backlinks paneli: gelen bağlantılar + bağlam önizleme; **Obsidian**: "bağlantılı / bağlantısız anma", bağlamı genişlet, sırala, süz | https://obsidian.md/help/plugins/backlinks, https://github.com/foambubble/foam | Detay sağ panel: Bilgi (fact tablosu, kapalı olanlar soluk), Gelen / Giden ilişkiler, Secret adları, Geçmiş. "Bağlantısız anma" bizde yok (metin gövdesi yok). `GET /entities/{id}` |
| Kayıt düzenleme | **Logseq** satır içi düzenleme (blok tıklayınca metin alanı); noteapp "Ayarlar" formu | https://github.com/logseq/logseq (AGPL: yalnız desen) | Fact satırına çift tıkla → değer alanı, `Enter` kaydet, `Esc` vazgeç. Reddedilen yazma sunucu mesajıyla satırda. `POST …/facts`, `POST /facts/{id}/close`, `PATCH /entities/{id}` |
| Graf gezgini | **Obsidian Graph**: arama süzgeci, gruplar (aramaya renk), ok yönü, düğüm boyutu, çizgi kalınlığı, kuvvet ayarları (merkez/itme/bağ/mesafe), **yerel graf derinlik kaydırıcısı**, yetim süzgeci — https://obsidian.md/help/plugins/graph. **Neo4j Bloom**: arama çubuğu, kart listesi, legend (renk=tür), sahne etkileşimi (genişlet/gizle) — https://neo4j.com/docs/bloom-user-guide/current/bloom-visual-tour/. **noteapp Harita**: çift tıkla komşu aç/kapat, süzgeç çipleri, en kısa yol, sabitle | linkler yanda | Yerel graf: seçili kayıt + derinlik N (varsayılan 2). Çift tıkla komşuları aç (`GET /entities/{id}` ile bir adım; `outgoing` iki yönü verir). Renk=tür, kesikli çizgi=biten ilişki (`validTo`; "gizleme yok" kuralı: biten ilişki çizilir ama soluk), arşivli düğüm soluk. Düğüm üst sınırı 2.000 (noteapp), ilk sürümde 300. Tüm-graf görünümü: **YENİ UÇ** (bölüm 8) |
| Salt okunur Cypher konsolu | **Neo4j Browser**: sonuç çerçeveleri (graf/tablo/ham), sorgu geçmişi (yerel depoda), kayıtlı sorgular klasörlü — https://neo4j.com/docs/browser-manual/current/visual-tour/ | https://neo4j.com/docs/browser-manual/current/visual-tour/ | Tek satır → çok satırlı kutu, `Ctrl+Enter` çalıştır, tablo; `truncated` uyarısı, 200 satır sınırı; geçmiş/kayıtlı sorgu istemci dosyasında. `CypherGuard` reddini olduğu gibi göster. `POST /cypher` (hücreler metin: graf çerçevesi için kullanma) |
| Çelişkiler | **Git birleştirme araçları** (lazygit/gitui) yan yana | bölüm 3 | İki sütun kart; `Güncel kabul et / Bekleyeni kabul et`. `GET /conflicts`, `POST …/resolve` |
| Öneriler | Diff paneli | bölüm 3 | Kart listesi (açık önce), kind rozeti; ret notu alanı. `GET /proposals`, `POST …/accept\|reject` |
| Aktivite + geri alma | **lazygit** komut günlüğü + undo | bölüm 3 | Zaman çizelgesi: parti satırı, kaynak rozeti, "N açıldı / M kapandı"; seçince dokunulan kayıtlar bağlantı; `Geri al` onaylı. `GET /activity`, `POST /revert` |
| Secret listesi | Tarayıcı parola yöneticisi listesi (ad, yer), değer yok | — | Tablo; `placeholder` kopyala düğmesi; değer sütunu yok (GUI hiçbir zaman göstermez, CLIENTS §4). `GET /secrets` |
| Küratör çalışmaları + eval geçmişi | **Neo4j Browser** geçmiş çekmecesi | — | Salt okunur tablo. `GET /curator/runs` (F4), `GET /eval` (CLIENTS tablosunda var; sunucuda henüz kontrolcü yok) |
| Bayat | k9s tablo | bölüm 3 | Tablo + "Doğruladım" düğmesi. `GET /stale` |

Bilerek alınmayanlar: Obsidian "bağlantısız anma" (gövde metni yok), canvas, eklenti sistemi, çoklu sekmeli düzen (tek kayıt +
geri/ileri yığını), Logseq blok/outliner modeli ve Datalog sorguları (Cypher zaten var), Bloom "yakın doğal dil" arama
(sunucu LLM çağırmaz, değişmez 3).

## 6. imgui-java ve ImNodes durumu

- **Sürüm:** `io.github.spair:imgui-java-app` en son **1.92.7.1** (Maven Central: 1.88.0, 1.89.0, 1.90.0, 1.92.0, 1.92.7.1) —
  https://repo1.maven.org/maven2/io/github/spair/imgui-java-app/maven-metadata.xml. GitHub sürümü v1.92.7.1 = 2026-07-27;
  son push 2026-11-09; Dear ImGui 1.92.7 (docking dalı), MIT — https://github.com/SpaiR/imgui-java. ADR-0021 ile tutarlı.
- **Paketlenen eklentiler** (`imgui-binding/src/main/java/imgui/extension/`): **imnodes**, **nodeditor** (imgui-node-editor),
  imguizmo, implot, imguicolortextedit, imguifiledialog, memedit, imguiknobs. Örnekler `example/src/main/java/ExampleImNodes.java`
  ve `ExampleImGuiNodeEditor.java`. ImNodes ayrı bağımlılık gerektirmez (aynı `imgui-java-binding`). ImNodes sürümü örnekte
  `Nelarius/imnodes@857cc86` olarak sabit.
- **ImNodes mi, imgui-node-editor mü?** İkisi de **düğüm-editörü**: sürükle-bırak, pin/bağlantı çizme, minimap
  (`ImNodesMiniMapLocation` var). Otomatik yerleşim ikisinde de yok. Bizim ihtiyaç salt okunur graf: düğüm ve kenar çizimi,
  konum bizden. ImNodes (2.503 yıldız, son push 2026-10-17) daha basit ve ADR'deki seçim; imgui-node-editor (4.547 yıldız,
  son push 2026-10-26) daha zengin (gruplar, akış animasyonu) ama bağlantı çizgisi/etiket kurulumu ağır. **Öneri: ImNodes ile
  başla; yetmezse karar ver.** Çok düğümde (500+) ikisi de her karede tüm düğümü çizer; sınır koy.
- **Alternatif (düğüm kutusu istemiyorsak):** ImGui `ImDrawList` ile elle daire/çizgi çizmek noteapp'in Harita yolu
  (SDL_RenderLine). Düğümler küçük daire, kenarlar çizgi, etiket yalnız yakında. Kutu-pin tasarımı graf için görsel olarak ağır;
  bu yol büyük grafta daha hızlı ve daha okunur. imgui-java `ImDrawList` yeterince açık. **Bunu ImNodes'a eşit aday say; F5'in ilk
  spike'ında ikisini 300 düğümle dene.**
- **Doğrulanacaklar (ADR-0021 zaten yazıyor):** Wayland (imgui-java-app GLFW/LWJGL3 kullanır; README Wayland'dan söz etmiyor),
  HiDPI, pano, Türkçe font glif aralığı, `-ft` (FreeType) native'leri (`imgui-java-natives-linux-ft` Central'da var;
  FreeType metin kalitesi için gerekebilir). SDL arka ucu da var (`MainSdl.java`) ama README "tek pencere" diyor.
- **Yerleşim:** yok; FR yerleşimini noteapp'ten Java'ya taşı (belirlenimci başlangıç konumu = kimlik özeti, kare başına 1 adım,
  durunca kare üretme).

## 7. Önerilen uygulama sırası (arayüze yansıyan)

1. H8a öncesi: komut tablosu + saf `TuiState`/`Intent` iskeleti, pty testi (yapıştırma, `Ctrl+S`, boyut değişimi, Türkçe giriş).
2. H8a: arama+detay (Pilot ile), formlar (şema ucu gelene kadar kullanıcı anahtar yazar; ucu bekleme).
3. H8b: çelişki, öneri, aktivite, bayat.
4. F5: arama+detay → düzenleme → çelişki/öneri/aktivite → secret listesi → graf (en son, spike ile).

## 8. Uç eşlemesi ve eksikler

Mevcut `kb-server` kontrolcülerinde bulunanlar: `search`, `entities/{id}` (GET/POST/PATCH), `…/facts`, `…/relations`,
`facts/{id}/close`, `relations/{id}/close`, `archive`/`unarchive`, `conflicts` (+`resolve`), `proposals` (+`accept`/`reject`),
`activity`, `revert`, `cypher`, `stale`, `secrets`, `documents`, `scan`, `sync`, `restore`, `check`.
CLIENTS §1 tablosunda olup henüz kontrolcüsü görünmeyenler: `/doctor`, `/eval`, `/signals`, `/curator/*` (F4), `/documents` GET.

| Özellik | Ucu | Durum |
|---|---|---|
| Arama, filtre çipleri, hızlı geçiş | `GET /search?q=&type=&env=&limit=` | var |
| Detay, gelen/giden ilişki, geçmiş | `GET /entities/{id}` (JSON) | var |
| Ekle/düzenle/kapat/arşivle | `POST/PATCH /entities…`, `POST …/facts`, `…/relations`, `…/close`, `…/archive` | var |
| Çelişki, öneri | `GET/POST /conflicts…`, `/proposals…` | var |
| Aktivite, geri alma | `GET /activity`, `POST /revert` | var |
| Bayat + "doğruladım" | `GET /stale`, `POST …/facts` (aynı değer) | var |
| Secret listesi | `GET /secrets?prefix=` | var |
| Cypher konsolu | `POST /cypher` | var (hücreler metin) |
| Yerel graf (komşu genişletme) | `GET /entities/{id}` (kayıt başına, N+1) | var; derinlik 2'de ≤ ~30 istek, loopback'te kabul. Gerekirse toplu uç |
| Komut paleti, `?` yardım, geçmiş, tema | istemci yerel | uç gerekmez |
| Sunucu durumu | `GET /check` | var (hafif ping ayrı değil) |
| **Şemaya göre form** (fact anahtarları + türler, ilişki türleri, tür listesi) | — | **YENİ UÇ GEREKİR** (`GET /schema`; kaynak `schema/facts.yaml`, `relations.yaml`) |
| **Gözatma** (aramasız tür/ortam listesi, "tüm graf" genel bakışı, yetim kayıtlar) | — | **YENİ UÇ GEREKİR** ya da `/search` boş sorgu davranışı tanımlanmalı (SPEC'te boş sorgu yok mu: doğrulanmadı); alternatif `/cypher` ile ama hücreler metin olduğu için ayrıştırma kırılgan |
| **Çelişkide güncel değerin kaynağı/tarihi** | `ConflictsAnswer.Conflict.current` yalnız `List<String>` | **DTO zenginleştirme** (`current` → {value, source, validFrom}) |
| **Parti ayrıntısı** (partinin hangi kaydı açtığı/kapattığı) | `ActivityAnswer.Batch` yalnız sayı + ≤10 kayıt adı | **YENİ UÇ** (`GET /activity/{batch}`) ya da `revert` onayında yalnız sayıyla yetin |
| **Öneride eski/yeni fark** | `Proposal.content` düz metin | Yalnız metin farkı mümkün; yapısal fark için DTO alanı gerekir |
| Küratör çalışmaları, sinyaller, eval geçmişi | `/curator/runs`, `/signals`, `/eval` | F4; GUI ekranı bunlar gelene kadar boş |
| Tüm graf toplu çekme (GUI keşfi) | — | **YENİ UÇ** (`GET /graph?center=&depth=`); yoksa yalnız komşu genişletme |

Yeni uçlar ADR-0016 (sadelik) ve değişmez 1–12 açısından: hepsi salt okuma; `/schema` secret içermez; `/graph` silinmiş/gizli
kayıt süzmez (değişmez 12). Eklemeden önce kullanıcı onayı ve `docs/CLIENTS.md` §1 güncellemesi gerekir.

## 9. Açık sorular (ürün davranışı, sade dille)

1. **Düzenleme formunda fact anahtarını sen mi yazarsın, listeden mi seçersin?** Listeden seçmek için sunucunun bize şema
   listesini vermesi gerekir (yeni bir uç). Şimdilik yazarak mı başlayalım?
2. **Bir partiyi geri aldıktan sonra "geri almayı geri al" olsun mu?** Silme yok kuralı var; geri alma sonucu kayıtlar
   yeniden açılır ama ikinci bir geri alma tanımlı değil. Olmasın diyorum (en basit); onay verir misin?
3. **Aramada eşleşme yokken Enter yeni kayıt açsın mı?** Obsidian ve noteapp açıyor. Burada kayıt türü ve kimlik gerekir;
   önerim: tür sorusu gelsin, yanlışlıkla kayıt oluşmasın.
4. **`kb tui --readonly` olsun mu?** k9s'te var; yalnız bakmak isteyince yazma tuşları kapanır. İstersen küçük iş.
5. **Graf gezgininde biten ilişkiler (kapanmış) çizilsin mi?** "Gizleme yok" kuralı çizmeyi söylüyor; önerim: çizilsin
   ama soluk ve kesikli, bir düğmeyle gizlenebilsin (varsayılan görünür).
6. **Arşivli kayıtlar aramada hangi sırada gelsin?** Kural: güncel önce, arşivli sonra, işaretli. TUI'de ayrı bir sekme
   yerine aynı listede soluk satır olarak göstermeyi öneriyorum.
7. **Graf görünümü ilk sürümde yalnız "seçili kayıt çevresi" mi olsun, "tüm graf" ayrı bir iş mi?** Tüm graf yeni uç ister.
8. **TamboUI 0.7.0'a geçelim mi?** Kitty klavye protokolü (Ctrl+Enter vb. güvenilir) ve sözcük düzenleme geliyor; bedeli: bir
   sürüm kayması, H8a öncesi yapılırsa ucuz.
9. **GUI'de Türkçe font:** DejaVu Sans Mono (noteapp gömülü kullanıyor) jar'a konabilir mi, yoksa sistem fontu mu aransın?
   (ADR-0021 "F5'te doğrulanacaklar").

## Kaynaklar

- noteapp: `/home/ismet/dev/noteapp/{SPEC.md,SPEC-tui.md,SPEC-gui.md,cmake/patches/ftxui-7.0.3-frame-writer.patch}`
- TamboUI: https://github.com/tamboui/tamboui · https://tamboui.dev · https://github.com/tamboui/tamboui/releases/tag/v0.7.0 ·
  https://repo1.maven.org/maven2/dev/tamboui/ · pilot planı `docs/pilot-reorganization-plan-v3.md`
- Java TUI: https://repo1.maven.org/maven2/com/googlecode/lanterna/lanterna/maven-metadata.xml · https://jexer.sourceforge.io/downloads.html
- lazygit https://github.com/jesseduffield/lazygit/blob/master/docs/Config.md · k9s https://k9scli.io/topics/commands/ ·
  fzf https://github.com/junegunn/fzf · gitui https://github.com/extrawurst/gitui · btop https://github.com/aristocratos/btop ·
  zk https://zk-org.github.io/zk/ · nb https://github.com/xwmx/nb
- Obsidian https://obsidian.md/help/plugins/{graph,quick-switcher,backlinks,command-palette} · Foam https://github.com/foambubble/foam ·
  Logseq https://github.com/logseq/logseq · Neo4j Browser https://neo4j.com/docs/browser-manual/current/visual-tour/ ·
  Bloom https://neo4j.com/docs/bloom-user-guide/current/bloom-visual-tour/
- imgui-java https://github.com/SpaiR/imgui-java · https://repo1.maven.org/maven2/io/github/spair/ ·
  ImNodes https://github.com/Nelarius/imnodes · imgui-node-editor https://github.com/thedmd/imgui-node-editor

Doğrulanamayanlar: Lanterna/Jexer native-image; Jexer ve Lanterna lisansları (Lanterna LGPL, Jexer MIT: arama sonucu/önceki
bilgi); TamboUI'de bracketed paste ve XON/XOFF davranışı; imgui-java-app'in Wayland'da çalışması; nb'nin backlink/çakışma
özellikleri (sayfa ayrıntı vermedi); `/search` boş sorgu davranışı.
