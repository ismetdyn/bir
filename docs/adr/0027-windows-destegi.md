# ADR-0027: Windows desteği (Linux + Windows)

- Durum: Kabul edildi (2026-11-11)
- İlgili: ADR-0015 (secret'lar), ADR-0020 (yerel tek kullanıcı), ADR-0025 (istemci, native), ADR-0026

## Bağlam
Kullanıcı kararı (2026-11-11): agentkb Linux ve Windows'ta çalışır; noteapp'teki gibi çapraz platform. Yol: doğrudan Java
(Docker'da ürün değil; `kb_scan`, `kb run` ve GUI host'a erişmek zorunda). Windows işi F3 ile birlikte yapılır. Windows
testleri yerel bir Windows sanal makinesinde koşar (test ortamı ayrı karar, W0).

Etki analizi: saf Java kısmı taşınabilir. Engeller üç yerde toplanıyor: POSIX dosya izinleri (veri dizini 0700,
`kb run --file` geçici dosyası), servis katmanı (systemd, `install.sh`, bash `kb-hook.sh`, `run-curator.sh`) ve yalnız
Linux'ta koşan test altyapısı (bash, `kill`, pty, `mkfifo`).

## Karar
1. **Yollar işletim sistemine göre** (noteapp ile aynı düzen): `AGENTKB_HOME` hepsini ezer. Veri: Linux
   `$XDG_DATA_HOME/agentkb` (`~/.local/share/agentkb`), Windows `%LOCALAPPDATA%\agentkb`. Bilgi reposu: `~/kb` /
   `%USERPROFILE%\kb`. Araçlar: `~/kb-tools` / `%LOCALAPPDATA%\agentkb\tools`. Varsayılanlar tek yerde (`InitCommand`
   ile `application.yml`'deki kopya kaldırılır).
2. **Yalnız sahibine açık dizin:** Linux'ta 0700 (değişmez). Windows'ta NTFS ACL: miras kapatılır, yalnız sahip (dosya
   sahibi SID) tam yetki alır (`AclFileAttributeView`). İki platformda da aynı soyutlama (`OwnerOnly`); dosya sistemi
   ikisini de desteklemiyorsa başlamayı açık hatayla reddeder (secret değişmezi zayıflamaz).
3. **`kb run --file`:** geçici dosya aynı `OwnerOnly` ile oluşturulur. Windows'ta SIGTERM yok: temizlik
   `deleteOnExit` + kapanış kancası + bir sonraki `kb run`'da sahipsiz dosyaların silinmesi.
4. **Hook'lar `kb hook <olay>` alt komutuna taşınır.** stdin JSON'u okur, sunucuya iletir, hata olursa sessizce 0 ile
   çıkar (fail-open, bugünkü `kb-hook.sh` davranışı). bash ve curl bağımlılığı kalkar; Claude Code ve Codex iki
   platformda da aynı komutu çağırır. `kb-hook.sh` geçiş süresince ince bir sarmalayıcı olarak kalır.
5. **Servis:** Linux systemd user (değişmez). Windows'ta oturum açılışında başlayan Görev Zamanlayıcı görevleri
   (`schtasks`): sunucu, 10 dk'da bir `kb sync`, küratör. Yönetici yetkisi gerekmez.
6. **Kurulum:** Windows'ta `install.ps1`, `install.sh` ile aynı sözleşme (README'deki seçim kuralı, `--dry-run`/
   `-DryRun`, idempotent).
7. **Native:** Windows'ta `kb.exe` Windows makinesinde Mandrel + MSVC ile derlenir; o zamana kadar JVM başlatıcısı
   (`kb.cmd`).
8. **Metin:** anlık görüntü her zaman LF ve UTF-8 yazılır; bilgi reposuna `snapshot/*.jsonl text eol=lf` ve
   `blobs/** -text` içeren `.gitattributes` eklenir (Windows'ta `core.autocrlf` blob hash'ini bozmasın).
9. **Symlink politikası:** Windows'ta yeniden ayrıştırma noktaları (symlink, junction) Linux'taki symlink gibi
   reddedilir (CWE-59 kararları aynen geçerli).
10. **Testler:** `./mvnw verify` iki platformda geçer. POSIX'e özgü testlerin Windows karşılığı yazılır; karşılığı
    olmayanlar (pty, `mkfifo`) `@EnabledOnOs` ile işaretlenir ve "Windows'ta koşmaz" diye raporlanır.

## Sonuçlar
- (+) Tek kod tabanı, iki platform; bash/curl bağımlılığı kalkar.
- (+) Secret değişmezi Windows'ta da dosya sistemi izniyle korunur.
- (−) Windows testleri yerel sanal makineye bağlı; makine yoksa Windows davranışı doğrulanmamış sayılır.
- (−) Görev Zamanlayıcı ve ACL kodu yalnız Windows'ta tam test edilir.
