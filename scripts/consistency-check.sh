#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════════════════
#  一致性审计脚本
#
#  用途：检查「文档声明的」与「代码实际的」是否一致。
#
#  为什么需要它：
#    项目里最容易被忽略的缺陷不是「功能错了」，而是「说的和做的不一样」——
#    比如文档说有三种登录方式，实际只有两种；README 列了 20 个接口，
#    代码里多出 3 个；版本号停在 1.0.0 而标签已经是 v3.0.0。
#    这类问题不会导致程序崩溃，但会持续消耗阅读者的信任。
#
#  用法：
#    bash scripts/consistency-check.sh
#
#  退出码：
#    0 = 全部通过
#    1 = 发现问题（可用于 CI 卡点）
# ════════════════════════════════════════════════════════════════════════════

set -u

# ---------- 定位仓库根目录 ----------
ROOT=$(git rev-parse --show-toplevel 2>/dev/null)
if [ -z "$ROOT" ]; then
  echo "错误：当前目录不在 Git 仓库中"
  exit 1
fi
cd "$ROOT" || exit 1

PROBLEMS=0
WARNINGS=0

pass() { printf '  \033[32m✅\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m⚠️  %s\033[0m\n' "$1"; PROBLEMS=$((PROBLEMS + 1)); }
warn() { printf '  \033[33m⚡ %s\033[0m\n' "$1"; WARNINGS=$((WARNINGS + 1)); }
title() { printf '\n\033[1m%s\033[0m\n' "$1"; }

printf '\033[1m══════════════════════════════════════════════════\033[0m\n'
printf '\033[1m  一致性审计  (%s)\033[0m\n' "$(basename "$ROOT")"
printf '\033[1m══════════════════════════════════════════════════\033[0m\n'

# ════════════════════════════════════════════════════════════════════════════
title "① 版本号：pom.xml vs Git 标签"
# ════════════════════════════════════════════════════════════════════════════
POM_VERSION=$(grep -m1 '^    <version>' pom.xml 2>/dev/null | sed 's/.*<version>\(.*\)<\/version>.*/\1/')
LATEST_TAG=$(git describe --tags --abbrev=0 2>/dev/null || echo "")

printf '     pom.xml 版本 : %s\n' "${POM_VERSION:-未找到}"
printf '     最新标签     : %s\n' "${LATEST_TAG:-无标签}"

if [ -z "$LATEST_TAG" ]; then
  warn "仓库还没有标签，跳过比对"
elif [ -z "$POM_VERSION" ]; then
  fail "读不到 pom.xml 的版本号"
elif [ "v$POM_VERSION" = "$LATEST_TAG" ]; then
  pass "一致"
else
  fail "不一致：pom 是 $POM_VERSION，最新标签是 $LATEST_TAG"
fi

# ════════════════════════════════════════════════════════════════════════════
title "② 产物名：README 里引用的 jar 是否与实际构建配置匹配"
# ════════════════════════════════════════════════════════════════════════════
if [ -n "$POM_VERSION" ]; then
  README_JARS=$(grep -oE 'vote-web-[0-9]+\.[0-9]+\.[0-9]+\.jar' README.md 2>/dev/null | sort -u)
  if [ -z "$README_JARS" ]; then
    warn "README 里没有引用 jar 名"
  else
    BAD=0
    for j in $README_JARS; do
      case "$j" in
        *"-$POM_VERSION.jar") ;;
        *) fail "README 引用了 $j，但 pom 版本是 $POM_VERSION"; BAD=1 ;;
      esac
    done
    [ "$BAD" -eq 0 ] && pass "README 引用的 jar 名与 pom 版本一致（$POM_VERSION）"
  fi
fi

# ════════════════════════════════════════════════════════════════════════════
title "③ 版本日期：CHANGELOG 标题日期 vs 标签实际日期"
# ════════════════════════════════════════════════════════════════════════════
if [ ! -f CHANGELOG.md ]; then
  warn "没有 CHANGELOG.md"
else
  CHECKED=0
  for tag in $(git tag 2>/dev/null); do
    ver=${tag#v}
    tag_date=$(git log -1 --format=%ad --date=format:%Y-%m-%d "$tag" 2>/dev/null)
    doc_date=$(grep -oE "^## \[$ver\] - [0-9]{4}-[0-9]{2}-[0-9]{2}" CHANGELOG.md 2>/dev/null | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}$')
    [ -z "$doc_date" ] && continue
    CHECKED=$((CHECKED + 1))
    if [ "$tag_date" = "$doc_date" ]; then
      pass "$tag：标签日期 $tag_date = CHANGELOG $doc_date"
    else
      fail "$tag：标签日期是 $tag_date，CHANGELOG 写的是 $doc_date"
    fi
  done
  [ "$CHECKED" -eq 0 ] && warn "CHANGELOG 里找不到与标签对应的版本标题"
fi

# ════════════════════════════════════════════════════════════════════════════
title "④ 接口：README 声明 vs Controller 实际暴露"
# ════════════════════════════════════════════════════════════════════════════
TMP_REAL=$(mktemp)
TMP_DOC=$(mktemp)

# 实际接口 = 类级 @RequestMapping + 方法级 @XxxMapping 拼接
for f in $(find . -name "*Controller.java" -not -path "*/target/*" 2>/dev/null); do
  base=$(grep -oE '@RequestMapping\("[^"]*"\)' "$f" | head -1 | sed 's/.*("\(.*\)")/\1/')
  grep -oE '@(Get|Post|Delete|Put|Patch)Mapping' "$f" | sed 's/@//;s/Mapping//' > /dev/null
  grep -oE '@(Get|Post|Delete|Put|Patch)Mapping\("[^"]*"\)' "$f" \
    | sed 's/.*("\(.*\)")/\1/' | while read -r p; do echo "${base}${p}"; done
done | sed 's/{[^}]*}/{}/g' | sort -u > "$TMP_REAL"

# README 声明（只取表格里的路径列）
#   - 去掉查询参数（?n=10）
#   - 把占位符统一成 {}（{id} 与 {activityId} 视为同一个）
#   - 排除通配符描述（/admin/** 这类是 prose 里的范围说明，不是具体接口）
grep -oE '`/(api|admin)[^`]*`' README.md 2>/dev/null \
  | tr -d '`' | sed 's/?.*//;s/{[^}]*}/{}/g' \
  | grep -v '\*\*' | sort -u > "$TMP_DOC"

MISSING_IN_DOC=$(comm -23 "$TMP_REAL" "$TMP_DOC")
MISSING_IN_CODE=$(comm -13 "$TMP_REAL" "$TMP_DOC")

if [ -z "$MISSING_IN_DOC" ] && [ -z "$MISSING_IN_CODE" ]; then
  pass "README 的接口表与代码完全对应（共 $(wc -l < "$TMP_REAL" | tr -d ' ') 个）"
else
  [ -n "$MISSING_IN_DOC" ] && {
    fail "代码里有、README 未列出的接口："
    echo "$MISSING_IN_DOC" | sed 's/^/        /'
  }
  [ -n "$MISSING_IN_CODE" ] && {
    fail "README 列了、代码里不存在的接口："
    echo "$MISSING_IN_CODE" | sed 's/^/        /'
  }
fi
rm -f "$TMP_REAL" "$TMP_DOC"

# ════════════════════════════════════════════════════════════════════════════
title "⑤ 配置项：README 声明的环境变量 vs 配置文件实际使用"
# ════════════════════════════════════════════════════════════════════════════
TMP_ENV_CODE=$(mktemp)
TMP_ENV_DOC=$(mktemp)

grep -rhoE '\$\{[A-Z][A-Z0-9_]+[:}]' --include="*.yml" . 2>/dev/null \
  | grep -v "/target/" | sed 's/\${//;s/[:}]//' | sort -u > "$TMP_ENV_CODE"

grep -oE '`[A-Z][A-Z0-9_]{3,}`' README.md 2>/dev/null \
  | tr -d '`' | sort -u > "$TMP_ENV_DOC"

ENV_MISSING=$(comm -23 "$TMP_ENV_CODE" "$TMP_ENV_DOC" \
  | grep -vE '^(SPRING_|JAVA_HOME)' || true)

if [ -z "$ENV_MISSING" ]; then
  pass "README 的环境变量表覆盖了配置文件中用到的全部变量"
else
  fail "配置文件里用到、但 README 未说明的变量："
  echo "$ENV_MISSING" | sed 's/^/        /'
fi
rm -f "$TMP_ENV_CODE" "$TMP_ENV_DOC"

# ════════════════════════════════════════════════════════════════════════════
title "⑥ 死代码：声明了 Bean 但无人注入的组件"
# ════════════════════════════════════════════════════════════════════════════
DEAD=0
for f in $(grep -rlE '@(Service|Component|Repository)\(' --include="*.java" . 2>/dev/null | grep -v "/target/"); do
  name=$(grep -oE '@(Service|Component|Repository)\("[^"]+"\)' "$f" | head -1 | sed 's/.*("\(.*\)")/\1/')
  [ -z "$name" ] && continue
  hits=$(grep -rl "\"$name\"" --include="*.java" . 2>/dev/null | grep -v "/target/" | grep -v "^$f$" | wc -l | tr -d ' ')
  if [ "$hits" -eq 0 ]; then
    fail "Bean \"$name\" 无任何注入点（死代码）：${f#./}"
    DEAD=$((DEAD + 1))
  fi
done
[ "$DEAD" -eq 0 ] && pass "未发现无人注入的 Bean"

# ════════════════════════════════════════════════════════════════════════════
title "⑦ 文档内部链接"
# ════════════════════════════════════════════════════════════════════════════
BAD_LINKS=0
for f in $(find . -name "*.md" -not -path "*/target/*" -not -path "*/.git/*" 2>/dev/null); do
  dir=$(dirname "$f")
  grep -oE '\]\([^)h#][^)]*\)' "$f" 2>/dev/null | sed 's/](\(.*\))/\1/' | while read -r link; do
    target=$(echo "$link" | cut -d'#' -f1)
    [ -z "$target" ] && continue
    if [ ! -e "$dir/$target" ]; then echo "  ${f#./} → $link"; fi
  done
done > /tmp/_bad_links.txt
BAD_LINKS=$(wc -l < /tmp/_bad_links.txt | tr -d ' ')
if [ "$BAD_LINKS" -eq 0 ]; then
  pass "所有文档内部链接均有效"
else
  fail "失效的文档链接："
  cat /tmp/_bad_links.txt
fi
rm -f /tmp/_bad_links.txt

# ════════════════════════════════════════════════════════════════════════════
title "⑧ 仓库卫生"
# ════════════════════════════════════════════════════════════════════════════
[ -f LICENSE ] && pass "LICENSE 存在" || fail "缺少 LICENSE（公开仓库默认『保留所有权利』，别人无法合法使用）"
[ -f .gitattributes ] && pass ".gitattributes 存在" || warn "缺少 .gitattributes（跨平台换行符可能不一致）"
[ -f .gitignore ] && pass ".gitignore 存在" || fail "缺少 .gitignore"

JUNK=$(git ls-files 2>/dev/null | grep -iE '\.log$|/target/|\.idea/|\.iml$|^app\.' || true)
if [ -z "$JUNK" ]; then
  pass "没有被误提交的构建产物或日志"
else
  fail "被 git 跟踪的垃圾文件："
  echo "$JUNK" | sed 's/^/        /'
fi

# ════════════════════════════════════════════════════════════════════════════
title "⑨ 测试数量：README 声明 vs 实际"
# ════════════════════════════════════════════════════════════════════════════
ACTUAL_TESTS=$(grep -rh "@Test" --include="*.java" . 2>/dev/null | grep -v "/target/" | wc -l | tr -d ' ')
TEST_CLAIMS=$(grep -oE '[0-9]+ 个(单元测试|用例|测试)' README.md CHANGELOG.md 2>/dev/null \
  | grep -oE '[0-9]+' | sort -un | tail -1)
printf '     实际 @Test 数量 : %s\n' "$ACTUAL_TESTS"
printf '     文档声明的最大值: %s\n' "${TEST_CLAIMS:-无}"
if [ -z "$TEST_CLAIMS" ]; then
  warn "文档里没有测试数量的声明"
elif [ "$TEST_CLAIMS" -eq "$ACTUAL_TESTS" ]; then
  pass "一致"
else
  warn "文档声明 $TEST_CLAIMS 个，实际 $ACTUAL_TESTS 个（若文档是按版本分节描述的，可能属正常）"
fi

# ════════════════════════════════════════════════════════════════════════════
printf '\n\033[1m══════════════════════════════════════════════════\033[0m\n'
if [ "$PROBLEMS" -eq 0 ] && [ "$WARNINGS" -eq 0 ]; then
  printf '\033[32m\033[1m  审计通过：未发现不一致\033[0m\n'
elif [ "$PROBLEMS" -eq 0 ]; then
  printf '\033[33m  审计完成：%d 个提示（无阻断问题）\033[0m\n' "$WARNINGS"
else
  printf '\033[31m  审计完成：发现 %d 个不一致，%d 个提示\033[0m\n' "$PROBLEMS" "$WARNINGS"
fi
printf '\033[1m══════════════════════════════════════════════════\033[0m\n\n'

[ "$PROBLEMS" -eq 0 ] && exit 0 || exit 1
