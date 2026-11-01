# ADR-0005: Cihazlar arası senkronizasyon GitHub üzerinden git ile

- Durum: Yerini aldı → ADR-0014 (2026-10-31). GitHub (private) ve git ile senkron kararı sürüyor; artık senkronlanan şey
  vault değil, grafın anlık görüntüsü. Aşağıdaki metin tarihçe için korunuyor.
- Tarih: 2026-10-29

## Bağlam
Bilgi yerelde saklanmalı; bulut sadece senkronizasyon için. İçerik hassas.

## Karar
Vault GitHub'da private bir repodur. Senkronizasyon `git pull/push` (isteğe bağlı Obsidian git eklentisi ile
otomatik). agentkb git'e karışmaz; sadece dosya değişikliklerini izler.

## Değerlendirilen alternatifler
- Obsidian Sync / Basic Memory Cloud: üçüncü taraf bulut; hassas veri için uygun değil.
- Self-hosted LiveSync (CouchDB, uçtan uca şifreli): gerçek zamanlı ve mobil gerekirse ikinci seçenek.
- Syncthing: sunucusuz P2P; GitHub'a erişim yoksa seçenek.

## Sonuçlar
- İndeks dizini (`~/.cache/agentkb`) asla vault içinde değildir ve asla senkronize edilmez.
- v2: Senkronizasyon `kb sync` ile sunucunun yazıcı kuyruğunda yapılır (otomatik yazmalarla çakışmaması için);
  systemd timer ile periyodik. Secret deposu (`~/.password-store`) kendi git reposuyla (`pass git`) ayrıca senkronize
  olur; sinyaller cihaza yereldir ve senkronize edilmez.
