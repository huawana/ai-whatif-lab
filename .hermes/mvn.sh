#!/usr/bin/env bash
# 本机没有装 Maven CLI，这里直接调用 IntelliJ IDEA 自带的 Maven 3.9.9。
#
# 用法: bash .hermes/mvn.sh <maven 参数...>   例如 bash .hermes/mvn.sh -B test
#
# 【为什么要在脚本里自己切目录】Maven 用「当前工作目录」找 pom.xml。
# 从 .hermes/ 目录里调用（例如验证脚本内部）会报
#   "there is no POM in this directory (...\.hermes)"
# —— 实测踩过，表现为「打包失败」而项目其实完好。
# 所以脚本自己切到项目根目录，调用方在哪个目录都无所谓。
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(cd "$HERE/.." && pwd)"
cd "$PROJ" || exit 3

# java 是原生程序，不认 MSYS 形式的 /c/... 路径，必须转成 Windows 路径
PROJ_WIN="$(cygpath -w "$PROJ" 2>/dev/null || printf '%s' "$PROJ")"

# IDEA 自带的 Maven 路径。IDEA 升级后这个路径会变，
# 所以支持用环境变量 IDEA_MAVEN_HOME 覆盖，并在找不到时给出明确提示。
M2W="${IDEA_MAVEN_HOME:-C:\\Program Files\\JetBrains\\IntelliJ IDEA 2024.3.5\\plugins\\maven\\lib\\maven3}"
if [ ! -f "$M2W\\boot\\plexus-classworlds-2.8.0.jar" ]; then
  echo "找不到 IDEA 自带的 Maven：$M2W"
  echo "请设置环境变量 IDEA_MAVEN_HOME 指向 <IDEA安装目录>/plugins/maven/lib/maven3"
  echo "（或直接安装 Maven CLI 并用 mvn 命令）"
  exit 3
fi

exec java -Dfile.encoding=UTF-8 \
  -classpath "$M2W\\boot\\plexus-classworlds-2.8.0.jar" \
  "-Dclassworlds.conf=$M2W\\bin\\m2.conf" \
  "-Dmaven.home=$M2W" \
  "-Dmaven.multiModuleProjectDirectory=$PROJ_WIN" \
  org.codehaus.plexus.classworlds.launcher.Launcher "$@" 2>&1
