#!/usr/bin/env python3
"""对比 Hibernate 期望 DDL 与 pg_dump 实际 schema，输出实体↔迁移差异全量清单。

背景：control 以 ddl-auto=validate 启动（schema 真源是 Flyway 迁移），本工具用于
定位 validate 失败的全量差异（Hibernate validate 一次只报一个错，逐轮重启太慢）。

用法:
  1) 导出实体期望 DDL（只读）:
     E2E_PG_HOST=<host> ./gradlew :services:control-service:test --tests "*SchemaDiffExport*"
     → services/control-service/build/schema-expected.sql
  2) 导出实际 schema:
     ssh <host> "docker exec <pg容器> pg_dump -U oddsmaker -d oddsmaker -s -n public" > /tmp/schema-actual.sql
  3) python3 tools/schemadiff.py build/schema-expected.sql /tmp/schema-actual.sql

输出三组:
  [A] 实体有列、库没有   —— validate 必炸（缺列）
  [B1] 基类型不同        —— validate 必炸（JDBC type code 不同）
  [B2] 仅长度/精度不同   —— validate 不看长度，不炸
  [C] 库有表/列、实体无  —— validate 不管，仅列出参考
注意两个坑: float(53) 与 double precision 在 PG 等价（不炸）;
Double 字段 + columnDefinition=DECIMAL 与库 numeric 文本相同但 typecode 不同（必炸）。
"""
import re
import sys

def strip_balanced(s: str, keyword: str) -> str:
    """删除 'keyword (...)' 括号平衡的整个子串（check/generated 等尾巴）"""
    out, i, n = [], 0, len(s)
    while i < n:
        m = re.match(keyword, s[i:], re.I)
        if m:
            j = s.find('(', i + m.end())
            if j != -1:
                depth, k = 0, j
                while k < n:
                    if s[k] == '(':
                        depth += 1
                    elif s[k] == ')':
                        depth -= 1
                        if depth == 0:
                            break
                    k += 1
                i = k + 1
                continue
        out.append(s[i])
        i += 1
    return ''.join(out)

def normalize_type(t: str) -> str:
    t = t.strip().rstrip(',')
    t = re.sub(r'\s+', ' ', t)
    t = strip_balanced(t, r'check\s*')
    t = re.sub(r'\bgenerated\b.*$', '', t, flags=re.I)
    t = re.sub(r'\bnot null\b.*$', '', t, flags=re.I)
    t = re.sub(r'\bdefault\b.*$', '', t, flags=re.I)
    t = t.lower()
    t = re.sub(r'^character varying', 'varchar', t)
    t = re.sub(r'^decimal\b', 'numeric', t)
    t = re.sub(r'^timestamp\(6\)', 'timestamp', t)
    t = re.sub(r'^timestamp without time zone', 'timestamp', t)
    t = re.sub(r'^int4$', 'integer', t)
    t = re.sub(r'^int8$', 'bigint', t)
    t = re.sub(r'^bool$', 'boolean', t)
    t = re.sub(r'^serial$', 'integer', t)
    t = re.sub(r'^bigserial$', 'bigint', t)
    return t

def type_base(t: str) -> str:
    return re.sub(r'\(.*', '', t).strip()

def split_top_level(s: str):
    parts, depth, cur = [], 0, []
    for ch in s:
        if ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
        if ch == ',' and depth == 0:
            parts.append(''.join(cur))
            cur = []
        else:
            cur.append(ch)
    if cur:
        parts.append(''.join(cur))
    return parts

TABLE_CONSTRAINT = re.compile(r'^(primary key|foreign key|unique|constraint|check|exclude)\b', re.I)

def parse_columns(sql: str, kind: str):
    """返回 {table: {column: type}}，kind 用于选择解析方式"""
    tables = {}
    if kind == 'expected':
        # hibernate: create table t (col type, ...);  （可能带 schema.）
        for m in re.finditer(r'create table (?:if not exists )?([\w.]+)\s*\((.*?)\);', sql, re.S | re.I):
            table = m.group(1).split('.')[-1].lower().strip('"')
            cols = {}
            for piece in split_top_level(m.group(2)):
                piece = piece.strip()
                if not piece or TABLE_CONSTRAINT.match(piece):
                    continue
                # 列名 类型 [not null] ...
                cm = re.match(r'"?(\w+)"?\s+(.+)', piece, re.S)
                if cm:
                    col = cm.group(1).lower()
                    raw = cm.group(2)
                    raw = re.sub(r'\bnot null\b.*$', '', raw, flags=re.I)
                    raw = re.sub(r'\bdefault\b.*$', '', raw, flags=re.I)
                    cols[col] = normalize_type(raw)
            tables[table] = cols
    else:
        # pg_dump: CREATE TABLE public.t (\n  col type NOT NULL,\n ...);
        for m in re.finditer(r'CREATE TABLE (?:IF NOT EXISTS )?([\w.]+)\s*\((.*?)\n\);', sql, re.S):
            table = m.group(1).split('.')[-1].lower().strip('"')
            cols = {}
            for piece in split_top_level(m.group(2)):
                piece = piece.strip()
                if not piece or TABLE_CONSTRAINT.match(piece):
                    continue
                cm = re.match(r'"?(\w+)"?\s+(.+)', piece, re.S)
                if cm:
                    col = cm.group(1).lower()
                    raw = cm.group(2)
                    raw = re.sub(r'\bDEFAULT\b.*$', '', raw, flags=re.S)
                    raw = re.sub(r'\bNOT NULL\b.*$', '', raw, flags=re.I)
                    raw = re.sub(r'\bGENERATED .*$', '', raw, flags=re.I)
                    cols[col] = normalize_type(raw)
            tables[table] = cols
    return tables

expected = parse_columns(open(sys.argv[1]).read(), 'expected')
actual = parse_columns(open(sys.argv[2]).read(), 'actual')

only_e_tables = sorted(set(expected) - set(actual))
only_a_tables = sorted(set(actual) - set(expected))

print(f"实体表 {len(expected)} 张, 库表 {len(actual)} 张")
if only_e_tables:
    print("\n[A0] 实体有表、库没有（validate 必炸）:")
    for t in only_e_tables:
        print(f"  {t}")
if only_a_tables:
    print("\n[C0] 库有表、实体没有（validate 不管，参考）:")
    for t in only_a_tables:
        print(f"  {t} ({len(actual[t])} 列)")

nA = nB = 0
print("\n[A] 缺列（实体有、库没有）:")
for t in sorted(set(expected) & set(actual)):
    missing = sorted(set(expected[t]) - set(actual[t]))
    for c in missing:
        nA += 1
        print(f"  {t}.{c}  期望 {expected[t][c]}")
if nA == 0:
    print("  (无)")

nB2 = 0
print("\n[B1] 基类型不同（validate 必炸）:")
for t in sorted(set(expected) & set(actual)):
    for c in sorted(set(expected[t]) & set(actual[t])):
        e, a = expected[t][c], actual[t][c]
        if type_base(e) != type_base(a):
            nB += 1
            print(f"  {t}.{c}  期望 {e}  |  库 {a}")
if nB == 0:
    print("  (无)")

print("\n[B2] 仅长度/精度不同（validate 不看长度，不炸; 数值类列注意 DOUBLE 字段+columnDefinition 的假同）:")
for t in sorted(set(expected) & set(actual)):
    for c in sorted(set(expected[t]) & set(actual[t])):
        e, a = expected[t][c], actual[t][c]
        if e != a and type_base(e) == type_base(a):
            nB2 += 1
            print(f"  {t}.{c}  期望 {e}  |  库 {a}")
if nB2 == 0:
    print("  (无)")

print(f"\n=== 汇总: 缺列 {nA}, 基类型差异 {nB}, 长度/精度差异 {nB2} ===")
