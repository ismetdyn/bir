# GitHub'da Yayınlama Kontrol Listesi

Kişisel proje (GitHub kullanıcısı `ismetdyn`). Karar: ADR-0019. GitHub'a koymadan önce bu listeyi izle.

## Repo ayrımı

- [ ] **Araç reposu** public olabilir. **Anlık görüntü reposu** `~/kb` private kalır; secret değerleri anlık görüntüye
      girmese de sunucu envanteri ve topoloji hassastır.
- [ ] Fixture'lar ve örnekler sadece belgeleme için ayrılmış değerleri kullanır: IP'ler RFC 5737 (`192.0.2.0/24`,
      `198.51.100.0/24`, `203.0.113.0/24`), alan adları RFC 2606 (`.example`, `.test`).
- [ ] Fixture'lardaki sahte secret satırlarında `# gitleaks:allow`.

## Kod ve isimlendirme

- [ ] Paket kökü: `io.github.ismetdyn.agentkb`.
- [ ] Kod, yorumlar, commit mesajları İngilizce.
- [ ] README İngilizce (Türkçe dokümanlar `docs/tr/` altına taşınabilir).
- [ ] Şema (varlık türleri, ilişki/fact sözlükleri) yapılandırılabilir; kişiye özgü varsayılan yok.

## Temizlik

- [ ] `gitleaks git` ile **tüm geçmiş** taranır (sadece son commit değil).

## Lisans ve yönetişim

- [x] `LICENSE` (kökte): GPL-3.0-only, çünkü gömülü Neo4j Community GPLv3.
- [ ] Kaynak dosyalarında SPDX başlığı (`SPDX-License-Identifier: GPL-3.0-only`).
- [ ] Üçüncü taraf lisans listesi (CycloneDX SBOM'dan üretilebilir).
- [ ] `SECURITY.md` (açık bildirme yolu), `CONTRIBUTING.md` (opsiyonel).
- [ ] CI: GitHub Actions'ta build, test, gitleaks.
