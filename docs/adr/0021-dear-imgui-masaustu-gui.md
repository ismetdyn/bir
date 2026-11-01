# ADR-0021: Web GUI yerine Dear ImGui masaüstü istemcisi

- Durum: Kabul edildi
- Tarih: 2026-11-01
- Değiştirdiği: ADR-0012'nin web GUI maddesi; ADR-0003'ün `/ui` ucu

## Bağlam
ADR-0012 sunucunun içinde Thymeleaf + htmx + Cytoscape.js ile çalışan bir web GUI öngörüyordu. Kullanıcı web GUI istemediğini,
Dear ImGui kullanmak istediğini belirtti. Sistem tek kişinin bilgisayarında çalışıyor (ADR-0020).

## Karar
1. **GUI, Dear ImGui ile ayrı bir masaüstü uygulamasıdır.** Java bağlaması `io.github.spair:imgui-java-app`; Maven Central'da son
   sürüm 1.92.7.1 (MIT, `imgui-java-natives-linux` dahil). Bağımlılık F5'te eklenir; sürüm o gün yeniden doğrulanır.
2. **Yeni modül `kb-gui`.** Sunucuyla aynı süreçte çalışmaz, `kb` CLI gibi `/api/v1` REST API'sini kullanır. Yazma politikası ortak,
   kaynak `user`. Native binary hedefi yok: JVM ile çalışır; `kb gui` başlatır.
3. **`/ui` ucu kalkar.** Thymeleaf, htmx, Cytoscape.js, Spring Security, tek kullanımlık giriş linki, oturum, CSRF ve CSP gerekmez.
4. **Graf görünümü ImNodes ile** (`imgui-java` eklentisi). Düğüm sayısı arttığında yerleşim kendimiz yaparız; F5'te ölçülür.

## F5'te doğrulanacaklar
- **Türkçe karakter:** ImGui'nin varsayılan fontu `ı`, `ğ`, `ş`, `İ` harflerini içermiyor olabilir. Bu harfleri içeren bir TTF ve
  glif aralığı yüklenir; font dosyasının lisansı ve jar'a konması F5'te karara bağlanır.
- **Wayland:** GLFW ile KDE Wayland oturumunda pencere, HiDPI ve pano davranışı.
- **`OriginGuard`:** `kb-gui` isteklerinin geçtiği.

## Sonuçlar
- (+) Sunucuda web yüzeyi, oturum ve CSRF kalmaz; saldırı yüzeyi küçülür.
- (+) Tek dil (Java) korunur; Node araç zinciri gerekmez.
- (−) GUI kurulum ister: `kb-gui` jar'ı ve native kütüphaneler. Yerel kullanımda kabul edilebilir.
- (−) ImGui'nin ekran okuyucu desteği yok; tek kullanıcılı bir araçta kabul edildi.
- (−) Graf yerleşimi hazır gelmiyor; Cytoscape.js'in verdiği otomatik yerleşim için kod yazılır.

## Değerlendirilen alternatifler
- **Web GUI (eski karar):** Kullanıcı istemiyor.
- **C++ Dear ImGui:** Doğrudan kütüphane, ama projeye ikinci dil ve derleme zinciri ekler.
