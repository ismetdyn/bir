# ADR-0008: Config bilgilerini LLM değil deterministik tarayıcı çıkarır

- Durum: Kabul edildi
- Tarih: 2026-10-29

## Bağlam
Proje incelenirken sunucu adı, IP, port, datasource gibi bilgiler otomatik toplanmalı.

## Karar
Spring config, `.env`, docker-compose, Kubernetes manifestleri, Ansible envanteri ve build dosyaları
sunucudaki deterministik extractor'larla okunur (`kb_scan`). Ajan sadece tarayıcının anlayamadığını (`kb_remember`)
ekler. Sunucu LLM çağırmaz.

## Gerekçe
- Yapısal dosyalarda ayrıştırıcı hatasız, ucuz ve tekrarlanabilir; ortam (profil/namespace) ayrımını kaçırmaz.
- **Secret'lar LLM'e hiç gitmez:** dosyayı ajan değil sunucu okur.
- Kaynak kesin: `repo@commit:dosya:satır`.

## v0.3 notu (2026-10-31)
Tarayıcı artık doğrudan grafa yazar. Kaynak dosya bir `SourceFile` düğümüdür; fact'ler ona `FROM {line, commit}` ile
bağlanır. Bulunan secret'lar `Secret` düğümüne yazılır; değer bu bilgisayardan çıkmaz (ADR-0015).

## Sonuçlar
- (+) Yüksek doğruluk, sıfır token, secret güvenliği.
- (−) Her yeni format için extractor yazmak gerekir; bilinmeyen formatlar ajan + `kb_remember` ile.
- Tarama sadece `allowed-roots` altında.
