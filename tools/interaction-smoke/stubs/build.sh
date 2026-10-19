#!/bin/bash
# 编译两个冒烟替身插件，产物直接放进服务端 plugins/ 目录。
#
# 用法：PAPER_API=/path/to/paper-api-1.21.4.jar SERVER_DIR=/path/to/server ./build.sh
# 不设 PAPER_API 时尝试从 Gradle 缓存里找 1.21.4 的 paper-api。
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${OUT:-$HERE/build}"
JDK="${JAVA_HOME:+$JAVA_HOME/bin/}javac"

find_in_cache () { # $1 = 相对路径通配
  ls -1 $HOME/.gradle/caches/modules-2/files-2.1/$1 2>/dev/null | tail -1 || true
}

API="${PAPER_API:-}"
if [ -z "$API" ]; then
  API=$(ls -1 "$HOME"/.gradle/caches/modules-2/files-2.1/io.papermc.paper/paper-api/*/*/paper-api-*-SNAPSHOT.jar 2>/dev/null | grep '1\.21\.4' | tail -1 || true)
fi
if [ -z "$API" ] || [ ! -f "$API" ]; then
  echo "找不到 paper-api 1.21.4 的 jar；请用 PAPER_API=/path/to/paper-api.jar 指定" >&2
  exit 1
fi

# Paper API 的方法签名里出现 adventure 与 bungee-chat 的类型，编译替身插件时要一起给上
CP="$API"
for pattern in 'net.kyori/adventure-api/*/*/*.jar' 'net.md-5/bungeecord-chat/*/*/*.jar'; do
  jar=$(ls -1 $HOME/.gradle/caches/modules-2/files-2.1/$pattern 2>/dev/null | tail -1 || true)
  [ -n "$jar" ] && CP="$CP:$jar"
done

mkdir -p "$OUT"

build () { # $1 = 目录名、$2 = jar 名
  local dir="$HERE/$1"
  local classes="$OUT/$1-classes"
  rm -rf "$classes"; mkdir -p "$classes"
  "$JDK" -encoding UTF-8 -cp "$CP" -d "$classes" $(find "$dir/src" -name '*.java')
  cp "$dir/plugin.yml" "$classes/"
  (cd "$classes" && jar cf "$OUT/$2" .)
  echo "built $OUT/$2"
}

build smokehooks SmokeHooks.jar
build pepperclaim PepperClaim-stub.jar

if [ -n "${SERVER_DIR:-}" ]; then
  mkdir -p "$SERVER_DIR/plugins"
  cp "$OUT/SmokeHooks.jar" "$OUT/PepperClaim-stub.jar" "$SERVER_DIR/plugins/"
  echo "installed into $SERVER_DIR/plugins/"
fi
