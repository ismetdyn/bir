#!/usr/bin/env bash
# install.sh — boş bir makinede agentkb'yi tek komutla kurar (plan.md G14).
#
#   integrations/install.sh             # kur (ikinci çalıştırma hiçbir şeyi değiştirmez)
#   integrations/install.sh --dry-run   # hiçbir şey yazmaz/çalıştırmaz; kurulumu ve ajan ayarı geçişini yazdırır
#
# Yaptığı: java ≥ 21, git ve jar'ları denetler (eksikse HOME'a dokunmadan durur); jar'ları, kb-hook.sh ve
# AGENT-RULES.md'yi ~/kb-tools/'a, kb başlatıcısını ~/.local/bin/kb'ye koyar; systemd kullanıcı birimlerini
# denetlenen java ile ~/.config/systemd/user/'a yazar; `init` çalıştırır; servisi ve senkron timer'ını açar.
# kb istemcisi native (~/kb-tools/kb) ya da istemci jar'ı + java başlatıcısıdır; seçim kuralı README "Kurulum"da.
# Hangisinin kurulacağını --dry-run yazdırır.
# ~/.claude ve ~/.codex'e yazmaz: o geçiş kullanıcı onayıyla, elle yapılır (plan.md "Kapsam dışı"; --dry-run).
# Küratör birimleri F4'te (`kb curator` F2'de yok).
#
# Ortam:
#   AGENTKB_SERVER_JAR     kurulacak sunucu jar'ı (varsayılan: bu checkout'un kb-server/target/agentkb-server.jar)
#   AGENTKB_CLIENT_NATIVE  kurulacak native kb (README "Kurulum")
#   AGENTKB_CLIENT_JAR     kurulacak istemci jar'ı, native yoksa (varsayılan: bu checkout'un kb-client/target/kb-client-*.jar)
#   Varsayılan jar yoksa checkout ./mvnw ile derlenir; native'i derlemez (README "Kurulum").
set -euo pipefail

die() { echo "ERROR: $*" >&2; exit 1; }

readonly MIN_JAVA=21
readonly SERVER_JAR_NAME=agentkb-server.jar
readonly CLIENT_JAR_NAME=agentkb-client.jar
readonly UNITS=(agentkb.service agentkb-sync.service agentkb-sync.timer)

dry_run=false
case "${1:-}" in
  "") ;;
  --dry-run) dry_run=true ;;
  *) die "unknown argument '$1' (usage: install.sh [--dry-run])" ;;
esac

: "${HOME:?HOME is required}"
integrations="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
checkout="$(dirname "$integrations")"
tools_dir="$HOME/kb-tools"
bin_dir="$HOME/.local/bin"
# Not $XDG_CONFIG_HOME: the user manager (systemd --user) does not see this shell's environment.
unit_dir="$HOME/.config/systemd/user"

# --- Ön koşullar: hiçbiri HOME'a yazmaz -------------------------------------------------------------

java_bin="$(command -v java || true)"
if [[ -z "$java_bin" ]]; then
  die "java not found on PATH; install Java $MIN_JAVA or newer (distro package such as openjdk-$MIN_JAVA-jre-headless, or mise)"
fi
java_version="$("$java_bin" -XshowSettings:properties -version 2>&1 \
  | sed -n 's/^ *java\.specification\.version = //p')"
java_major="${java_version#1.}"
java_major="${java_major%%.*}"
if ! [[ "$java_major" =~ ^[0-9]+$ ]] || (( java_major < MIN_JAVA )); then
  die "$java_bin is Java '${java_version:-unknown}'; agentkb needs Java $MIN_JAVA or newer"
fi
# systemd ExecStart= boşlukta böler; % bir specifier'dır.
if [[ "$java_bin" =~ [[:space:]] ]]; then
  die "java path '$java_bin' contains whitespace; put a java without spaces in its path first on PATH"
fi
command -v git >/dev/null || die "git not found on PATH; install it with your package manager (package: git)"
command -v systemctl >/dev/null || die "systemctl not found; agentkb runs as a systemd user service"

# Native kb: unset = this checkout's build if present; empty = jar; a given path must be executable.
if [[ -z "${AGENTKB_CLIENT_NATIVE+set}" ]]; then
  AGENTKB_CLIENT_NATIVE="$checkout/kb-client/target/kb"
  if [[ ! -f "$AGENTKB_CLIENT_NATIVE" || ! -x "$AGENTKB_CLIENT_NATIVE" ]]; then AGENTKB_CLIENT_NATIVE=""; fi
  # A checkout build older than its sources is installed anyway; say so (KbNativeIT is the hard check).
  src_dirs=()
  for module in kb-core kb-api kb-client; do
    if [[ -d "$checkout/$module/src" ]]; then src_dirs+=("$checkout/$module/src"); fi
  done
  if [[ -n "$AGENTKB_CLIENT_NATIVE" ]] && (( ${#src_dirs[@]} > 0 )); then
    newer_source="$(find "${src_dirs[@]}" -type f -newer "$AGENTKB_CLIENT_NATIVE" -print -quit)"
    if [[ -n "$newer_source" ]]; then
      echo "WARN: native kb $AGENTKB_CLIENT_NATIVE is older than $newer_source; rebuild it (README \"Kurulum\") or set AGENTKB_CLIENT_NATIVE= for the jar" >&2
    fi
  fi
elif [[ -n "$AGENTKB_CLIENT_NATIVE" && ( ! -f "$AGENTKB_CLIENT_NATIVE" || ! -x "$AGENTKB_CLIENT_NATIVE" ) ]]; then
  die "native kb not found or not executable: $AGENTKB_CLIENT_NATIVE (set AGENTKB_CLIENT_NATIVE= to install the jar)"
fi
if [[ -n "$AGENTKB_CLIENT_NATIVE" ]]; then client_kind=native; else client_kind=jar; fi
# The other variant's file, left by an earlier install; removed once this one is installed.
if [[ "$client_kind" == native ]]; then other_client="$tools_dir/$CLIENT_JAR_NAME"; else other_client="$tools_dir/kb"; fi

needs_client_jar=false
if [[ "$client_kind" == jar && -z "${AGENTKB_CLIENT_JAR:-}" ]]; then needs_client_jar=true; fi
if [[ -z "${AGENTKB_SERVER_JAR:-}" ]] || $needs_client_jar; then
  shopt -s nullglob
  client_builds=("$checkout"/kb-client/target/kb-client-*[0-9T].jar)
  shopt -u nullglob
  if [[ ! -f "$checkout/kb-server/target/$SERVER_JAR_NAME" ]] \
    || { $needs_client_jar && (( ${#client_builds[@]} == 0 )); }; then
    build=("$checkout/mvnw" -B -q -DskipTests -pl kb-server,kb-client -am package)
    if $dry_run; then
      echo "would build: ${build[*]}"
      client_builds=("$checkout/kb-client/target/kb-client-<version>.jar")
    else
      "${build[@]}" || die "build failed: ${build[*]}"
      shopt -s nullglob
      client_builds=("$checkout"/kb-client/target/kb-client-*[0-9T].jar)
      shopt -u nullglob
    fi
  fi
  if $needs_client_jar && (( ${#client_builds[@]} > 1 )); then
    die "more than one client jar in $checkout/kb-client/target; set AGENTKB_CLIENT_JAR"
  fi
  : "${AGENTKB_SERVER_JAR:=$checkout/kb-server/target/$SERVER_JAR_NAME}"
  : "${AGENTKB_CLIENT_JAR:=${client_builds[0]:-$checkout/kb-client/target/kb-client.jar}}"
fi
if ! $dry_run; then
  [[ -f "$AGENTKB_SERVER_JAR" ]] || die "server jar not found: $AGENTKB_SERVER_JAR"
  if [[ "$client_kind" == jar && ! -f "$AGENTKB_CLIENT_JAR" ]]; then
    die "client jar not found: $AGENTKB_CLIENT_JAR"
  fi
fi
for unit in "${UNITS[@]}"; do
  [[ -f "$integrations/systemd/$unit" ]] || die "unit template missing: $integrations/systemd/$unit"
done

# --- Dry-run ------------------------------------------------------------------------------------------

state() { if [[ -e "$1" ]]; then echo "exists"; else echo "missing"; fi; }

if $dry_run; then
  if [[ "$client_kind" == native ]]; then
    client_plan="copy $AGENTKB_CLIENT_NATIVE -> $tools_dir/kb
  write $bin_dir/kb (runs \$HOME/kb-tools/kb -Duser.home=\$HOME)"
  else
    client_plan="copy $AGENTKB_CLIENT_JAR -> $tools_dir/$CLIENT_JAR_NAME
  write $bin_dir/kb (runs $java_bin -Duser.home=\$HOME -jar \$HOME/kb-tools/$CLIENT_JAR_NAME)"
  fi
  cat <<EOF
DRY RUN: nothing is written and nothing is run.

Install would (kb client: $client_kind):
  copy $AGENTKB_SERVER_JAR -> $tools_dir/$SERVER_JAR_NAME
  $client_plan
  copy kb-hook.sh, AGENT-RULES.md -> $tools_dir/
  render ${UNITS[*]} -> $unit_dir/ (ExecStart java: $java_bin)
  run $java_bin -Duser.home=$HOME -jar $tools_dir/$SERVER_JAR_NAME init
  run systemctl --user daemon-reload
  run systemctl --user enable --now agentkb.service agentkb-sync.timer
  remove $other_client (the other kb client; $(state "$other_client"))

Agent settings switch: NOT done by install.sh. Apply it by hand after your approval, in this order.
Current state:
  ~/.claude/settings.json  $(state "$HOME/.claude/settings.json")
  ~/.claude/CLAUDE.md      $(state "$HOME/.claude/CLAUDE.md")
  ~/.codex/config.toml     $(state "$HOME/.codex/config.toml")
  ~/.codex/hooks.json      $(state "$HOME/.codex/hooks.json")
Merge into existing files; do not replace them. The steps (integrations/claude-code/setup.md, integrations/codex/setup.md):

EOF
  # Tek kaynak setup.md'lerdir; burada kopyası tutulmaz.
  cat "$integrations/claude-code/setup.md"
  echo
  cat "$integrations/codex/setup.md"
  exit 0
fi

# --- Kurulum ------------------------------------------------------------------------------------------

work="$(mktemp -d)"
cleanup() { rm -rf "$work"; }
trap cleanup EXIT

# put <src> <dst> <mode>: içerik ve izin aynıysa dokunmaz (idempotent); put_changed sonucu söyler.
put() {
  local src="$1" dst="$2" mode="$3"
  put_changed=false
  if [[ -f "$dst" ]] && cmp -s "$src" "$dst" && [[ "$(stat -c %a "$dst")" == "${mode#0}" ]]; then
    return 0
  fi
  install -D -m "$mode" "$src" "$dst"
  put_changed=true
}

put "$AGENTKB_SERVER_JAR" "$tools_dir/$SERVER_JAR_NAME" 0644
server_changed=$put_changed
put "$integrations/kb-hook.sh" "$tools_dir/kb-hook.sh" 0755
put "$integrations/AGENT-RULES.md" "$tools_dir/AGENT-RULES.md" 0644

# Every JVM gets -Duser.home="$HOME": on Linux the JVM takes user.home from the password file, so
# with HOME set elsewhere (sudo -E, containers, a test HOME) it would use another home than $HOME.
# The native image does the same and parses -D options before main, so it gets the same flag.
printf '#!/bin/sh\n# install.sh tarafından yazıldı; elle değiştirme, kurulumu yeniden çalıştır.\n' > "$work/kb"
if [[ "$client_kind" == native ]]; then
  put "$AGENTKB_CLIENT_NATIVE" "$tools_dir/kb" 0755
  printf 'exec "$HOME/kb-tools/kb" -Duser.home="$HOME" "$@"\n' >> "$work/kb"
else
  put "$AGENTKB_CLIENT_JAR" "$tools_dir/$CLIENT_JAR_NAME" 0644
  printf 'exec %q -Duser.home="$HOME" -jar "$HOME/kb-tools/%s" "$@"\n' \
    "$java_bin" "$CLIENT_JAR_NAME" >> "$work/kb"
fi
put "$work/kb" "$bin_dir/kb" 0755

java_unit="${java_bin//%/%%}"
for unit in "${UNITS[@]}"; do
  template="$(<"$integrations/systemd/$unit")"
  printf '%s\n' "${template//@JAVA@/"$java_unit"}" > "$work/$unit"
  put "$work/$unit" "$unit_dir/$unit" 0644
  if $put_changed; then server_changed=true; fi
done

"$java_bin" -Duser.home="$HOME" -jar "$tools_dir/$SERVER_JAR_NAME" init || die "agentkb init failed"

systemctl --user daemon-reload || die "systemctl --user failed; is a user session running? (loginctl enable-linger \"\$USER\" for headless hosts)"
if $server_changed; then
  # Çalışan eski sürüm yenisiyle değişsin; çalışmıyorsa no-op.
  systemctl --user try-restart agentkb.service
fi
systemctl --user enable --now agentkb.service
systemctl --user enable --now agentkb-sync.timer
rm -f "$other_client"

echo "agentkb installed. Server: systemctl --user status agentkb; client: $bin_dir/kb ($client_kind)"
if [[ ":${PATH}:" != *":$bin_dir:"* ]]; then
  echo "NOTE: $bin_dir is not on PATH; add it to use 'kb'." >&2
fi
echo "Agent settings switch (by hand, after approval): $integrations/install.sh --dry-run"
