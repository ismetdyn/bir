"""Writes agentkb seed/snapshot/{nodes,edges}.jsonl and seed/blobs/ deterministically (same output every run)."""
import hashlib
import json
import random
import sys
from datetime import datetime, timezone
from pathlib import Path

OUT = Path(sys.argv[1])
rng = random.Random(20261031)
B32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


def ulid(ts: str) -> str:
    ms = int(datetime.fromisoformat(ts.replace("Z", "+00:00")).timestamp() * 1000)
    t = "".join(B32[(ms >> (5 * i)) & 31] for i in reversed(range(10)))
    r = rng.getrandbits(80)
    return t + "".join(B32[(r >> (5 * i)) & 31] for i in reversed(range(16)))


nodes: list[dict] = []
edges: list[dict] = []


def node(id_, labels, **props):
    nodes.append({"id": id_, "labels": labels, "props": {k: v for k, v in props.items() if v is not None}})
    return id_


def edge(type_, frm, to, at, **props):
    props = {k: v for k, v in props.items() if v is not None}
    props.setdefault("updated_at", at)
    eid = ulid(at)
    edges.append({"id": eid, "type": type_, "from": frm, "to": to, "props": props})
    return eid


def entity(id_, type_label, status, summary, at, aliases=()):
    return node(id_, ["Entity", type_label], status=status, summary=summary, aliases=list(aliases), updated_at=at)


def fact(ent, key, value, env, source, observed, status="ok", valid_to=None, evidence=None,
         src_file=None, line=None, commit=None, target=None, doc=None, valid_from=None):
    # valid_from: first seen, never changes; observed_at: last seen, refreshed on re-observation.
    fid = ulid(observed)
    node(fid, ["Fact"], key=key, value=value, env=env or "default", source=source, valid_from=valid_from or observed,
         observed_at=observed, valid_to=valid_to, status=status, evidence=evidence, updated_at=valid_to or observed)
    edge("HAS_FACT", ent, fid, observed)
    if src_file:
        edge("FROM", fid, src_file, observed, line=line, commit=commit)
    if doc:
        edge("FROM", fid, doc, observed)
    if target:
        edge("TARGET", fid, target, observed)
    return fid


def rel(frm, type_, to, source, at, env=None, valid_to=None):
    return edge(type_, frm, to, at, source=source, valid_from=at, observed_at=at, env=env, valid_to=valid_to,
                updated_at=valid_to)


def document(name, mime, content, source, added, origin=None):
    data = content.encode("utf-8")
    sha = hashlib.sha256(data).hexdigest()
    rel_path = f"blobs/{sha[:2]}/{sha}"
    blob = OUT.parent / rel_path
    blob.parent.mkdir(parents=True, exist_ok=True)
    blob.write_bytes(data)
    return node(f"doc:{sha[:12]}", ["Document"], sha256=sha, name=name, mime=mime, size=len(data), path=rel_path,
                origin=origin, source=source, added_at=added, updated_at=added)


def secret(ent, env, name, source, observed, src_file=None, line=None, commit=None):
    sid = f"{ent}/{env}/{name}"
    # secret_value is never part of a snapshot (ADR-0015); tests set a fake value at setup.
    node(sid, ["Secret"], name=name, env=env, source=source, observed_at=observed, updated_at=observed)
    edge("HAS_SECRET", ent, sid, observed)
    if src_file:
        edge("FROM", sid, src_file, observed, line=line, commit=commit)
    return sid


U1 = "2026-01-15T09:00:00Z"
U2 = "2026-05-10T09:00:00Z"
U3 = "2026-10-01T09:00:00Z"
SC = "2026-10-29T10:00:00Z"
AG = "2026-10-28T15:00:00Z"
D1 = "2026-08-20T09:00:00Z"
D2 = "2026-09-12T09:00:00Z"
D3 = "2026-10-29T09:00:00Z"
DC = "2026-10-30T14:00:00Z"
COMMIT = "a1b2c3d"

# --- repo, source files, stacks, topics
repo = node("ornek-grup/ornek-proje", ["Repo"], remote="git.ornek.example/ornek-grup/ornek-proje", updated_at=SC)
f_prod = node("ornek-grup/ornek-proje:src/main/resources/application-prod.yml", ["SourceFile"],
              repo=repo, path="src/main/resources/application-prod.yml", updated_at=SC)
f_pom = node("ornek-grup/ornek-proje:pom.xml", ["SourceFile"], repo=repo, path="pom.xml", updated_at=SC)
for f in (f_prod, f_pom):
    edge("IN", f, repo, SC)
stack = node("stack:java-spring", ["Stack"], updated_at=D3)
tls = node("topic:tls", ["Topic"], updated_at=D2)

# --- entities
entity("ornek-prod-ortami", "Environment", "current", "Örnek prod ortamı.", U3)
entity("ornek-lb", "Network", "current", "Sunucuların önündeki load balancer + WAF (örnek).", U3, ["ornek-f5"])
entity("ornek-app-01", "Server", "current", "Örnek uygulama sunucusu.", AG, ["app01"])
entity("ornek-db", "Database", "observed", "Tarayıcının oluşturduğu örnek kayıt; kullanıcı doğrulamadı.", SC,
       ["ornek-db.ornek.example"])
entity("ornek-proje", "Project", "current", "Örnek Spring Boot projesi.", SC)
edge("HAS_REPO", "ornek-proje", repo, SC)

# --- ornek-app-01: conflict, history, stale (2026-05-10), port → target
fact("ornek-app-01", "hostname", "ornek-app-01.ornek.example", "prod", "user", U2, valid_from=U1)  # seen again
old_ip = fact("ornek-app-01", "ip", "192.0.2.20", "prod", "user", U1, valid_to=U2)
new_ip = fact("ornek-app-01", "ip", "192.0.2.21", "prod", "user", U2)
edge("SUPERSEDES", new_ip, old_ip, U2)
fact("ornek-app-01", "ip", "192.0.2.25", "prod", "agent", AG, status="conflict", evidence="ornek-proje README.md")
fact("ornek-app-01", "os", "Rocky Linux 9", None, "user", U2)
fact("ornek-app-01", "port", "8443/https", "prod", "scan", SC, src_file=f_prod, line=2, commit=COMMIT,
     target="ornek-proje")
fact("ornek-app-01", "role", "Spring Boot uygulamalarını systemd servisi olarak çalıştırır", None, "user", U2)
fact("ornek-app-01", "constraint", "Yeni uygulamalar ayrı port ve ayrı systemd servisi ile izole edilir", None, "user", U2)
fact("ornek-app-01", "access", "Erişim bastion üzerinden", None, "user", U2)
secret("ornek-app-01", "prod", "deploy-ssh-key", "user", U2)

# --- ornek-lb
fact("ornek-lb", "hostname", "ornek-lb.ornek.example", "prod", "user", U3)
fact("ornek-lb", "ip", "192.0.2.5", "prod", "user", U3)
fact("ornek-lb", "role", "Dış trafik bu katmandan geçer", None, "user", U3)
fact("ornek-lb", "constraint", "Arkasındaki servislerde gerçek istemci IP'si için X-Forwarded-For başlığına bakılmalı",
     None, "user", U3)
secret("ornek-lb", "prod", "panel-admin", "user", U3)

# --- ornek-db (from scan)
for key, value in (("engine", "oracle"), ("hostname", "ornek-db.ornek.example"), ("port", "1521"),
                   ("db_name", "ORNEKPDB")):
    fact("ornek-db", key, value, "prod", "scan", SC, src_file=f_prod, line=8, commit=COMMIT)

# --- ornek-proje
fact("ornek-proje", "stack", "java-spring", None, "scan", SC, src_file=f_pom, line=8, commit=COMMIT)
fact("ornek-proje", "java_version", "21", None, "scan", SC, src_file=f_pom, line=16, commit=COMMIT)
fact("ornek-proje", "url", "https://ornek.example", "prod", "user", U3)
fact("ornek-proje", "build", "./mvnw verify", None, "user", U3)
fact("ornek-proje", "convention", "REST uçları /api/v1 altında", None, "user", U3)
fact("ornek-proje", "finding", "Düz metin parola bulundu: src/main/resources/application-prod.yml:10; ortam değişkenine taşınmalı",
     "prod", "scan", SC, src_file=f_prod, line=10, commit=COMMIT)
secret("ornek-proje", "prod", "spring.datasource.password", "scan", SC, src_file=f_prod, line=10, commit=COMMIT)

# --- a document kept by the knowledge base; the agent extracted one (unverified) fact from it
runbook = document("ornek-proje-calistirma.md", "text/markdown",
                   "# ornek-proje çalıştırma notu\n\n"
                   "- Servis: `ornek-proje.service` (systemd), ornek-app-01 üzerinde.\n"
                   "- Yeniden başlatma: `sudo systemctl restart ornek-proje`\n"
                   "- Loglar: `journalctl -u ornek-proje -f`\n"
                   "- Sağlık kontrolü: `https://ornek.example/actuator/health`\n",
                   "user", DC, origin="wiki.ornek.example/ornek-proje/calistirma")
edge("ABOUT", runbook, "ornek-proje", DC)
fact("ornek-proje", "convention", "Yeniden başlatma: sudo systemctl restart ornek-proje; loglar: journalctl -u ornek-proje",
     None, "agent", DC, status="unverified", doc=runbook)

# --- relations between entities
rel("ornek-app-01", "RUNS", "ornek-proje", "user", U2)
rel("ornek-app-01", "BEHIND", "ornek-lb", "user", U2)
rel("ornek-app-01", "PART_OF", "ornek-prod-ortami", "user", U2)
rel("ornek-lb", "PART_OF", "ornek-prod-ortami", "user", U3)
rel("ornek-db", "PART_OF", "ornek-prod-ortami", "scan", SC)
rel("ornek-proje", "DEPLOYED_ON", "ornek-app-01", "user", U2, env="prod")
rel("ornek-proje", "DEPENDS_ON", "ornek-db", "scan", SC, env="prod")

# --- decisions
node("karar-global-constructor-injection", ["Decision"], status="accepted", date="2026-10-29", scope="global",
     rule="Spring bean'lerinde field injection değil constructor injection kullanılır.",
     review_by="2027-04-30", updated_at=D3,
     body="## Bağlam\nField injection test edilebilirliği ve değişmezliği zayıflatıyor.\n\n"
          "## Karar\nConstructor injection; tek constructor'da `@Autowired` yazılmaz.")
edge("APPLIES_TO", "karar-global-constructor-injection", stack, D3)

node("karar-global-kodda-secret-yok", ["Decision"], status="accepted", date="2026-10-29", scope="global",
     rule="Kod ve config dosyalarına secret yazılmaz; ${ORTAM_DEGISKENI} kullanılır, değer bilgi bankasında durur ve kb run ile verilir.",
     review_by="2027-10-29", updated_at=D3,
     body="Değişmez: gerekçeyle bile çiğnenemez.")

node("karar-ornek-ayri-systemd-servisleri", ["Decision"], status="accepted", date="2026-08-20", scope="project",
     rule="Her uygulama ayrı port ve ayrı systemd servisi ile izole edilir; ortak proxy ayarı mevcut uygulamaları etkilememeli.",
     review_by="2027-02-20", updated_at=D1,
     body="## Bağlam\nAynı test sunucusunda birden fazla uygulama çalışıyor; bir dağıtım diğerlerini etkiliyordu.\n\n"
          "## Değerlendirilen alternatifler\n- Tek uygulama sunucusunda çoklu WAR: izolasyon zayıf.\n"
          "- Konteyner: ileride k3s'e geçişte değerlendirilecek.")
edge("APPLIES_TO", "karar-ornek-ayri-systemd-servisleri", "ornek-proje", D1)
edge("ABOUT", "karar-ornek-ayri-systemd-servisleri", "ornek-app-01", D1)

# --- lesson
node("ders-ornek-tls-yaniltici-test", ["Lesson"], title="curl -k ile başarılı test TLS hatasını gizledi",
     date="2026-09-12", updated_at=D2,
     body="## Belirti\n`curl -k` ile bağlantı testi başarılı görünüyordu ama uygulama TLS hatası veriyordu.\n\n"
          "## Kök neden\n`-k` sertifika doğrulamasını kapattığı için test asıl sorunu (eksik ara sertifika) gizliyordu.\n\n"
          "## Çözüm\nTesti `-k` olmadan, özel CA paketi ile tekrarla; truststore'a ara sertifikayı ekle.\n\n"
          "## Nasıl doğrulandı\n`openssl s_client -showcerts` ile zincirin tam geldiği görüldü.\n\n"
          "## Tuzak\n\"Bağlantı kuruldu\" sonucu, sertifika zincirinin doğru olduğu anlamına gelmez.")
edge("ABOUT", "ders-ornek-tls-yaniltici-test", "ornek-app-01", D2)
edge("ABOUT", "ders-ornek-tls-yaniltici-test", tls, D2)

# --- an archived server and an ended relation: still found by search, marked as history (ADR-0017).
# Added last so that the ids generated above stay the same.
entity("ornek-app-00", "Server", "archived",
       "Eski uygulama sunucusu; uygulamalar 2026-05'te ornek-app-01'e taşındı.", U2, ["app00"])
fact("ornek-app-00", "hostname", "ornek-app-00.ornek.example", "prod", "user", U1)
rel("ornek-proje", "DEPLOYED_ON", "ornek-app-00", "user", U1, env="prod", valid_to=U2)

# --- Turkish dotted capital İ (eval q12): "izmir" must match only through the analyzer, so the id and aliases
# do not contain it. An entity draws no ULID, so the ids generated above stay the same.
entity("ornek-yedek-01", "Server", "current", "İzmir veri merkezindeki yedek sunucu.", U3)

# --- checks and output
ids = [n["id"] for n in nodes]
assert len(ids) == len(set(ids)), "duplicate node id"
known = set(ids)
for e in edges:
    assert e["from"] in known and e["to"] in known, e
OUT.mkdir(parents=True, exist_ok=True)
with open(OUT / "nodes.jsonl", "w", encoding="utf-8") as fh:
    for n in sorted(nodes, key=lambda n: n["id"]):
        fh.write(json.dumps(n, ensure_ascii=False, sort_keys=True) + "\n")
with open(OUT / "edges.jsonl", "w", encoding="utf-8") as fh:
    for e in sorted(edges, key=lambda e: e["id"]):
        fh.write(json.dumps(e, ensure_ascii=False, sort_keys=True) + "\n")
print(f"{len(nodes)} nodes, {len(edges)} edges -> {OUT}")
