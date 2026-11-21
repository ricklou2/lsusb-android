#!/usr/bin/env python3
"""把 aapt2 的 R.txt 转成 Kotlin 的 R 对象，省掉 javac。"""
import re, sys, collections

txt, out = sys.argv[1], sys.argv[2]
groups = collections.OrderedDict()
pat = re.compile(r"int (\S+) (\S+) (0x[0-9a-fA-F]+)")
for line in open(txt, encoding="utf-8"):
    m = pat.match(line.strip())
    if not m:
        continue
    typ, name, val = m.groups()
    groups.setdefault(typ, []).append((name, val))

with open(out, "w", encoding="utf-8") as f:
    f.write("package com.ricklou.lsusb\n\n")
    f.write("// 由 aapt2 的 R.txt 自动生成，勿手改\n")
    f.write("object R {\n")
    for typ, items in groups.items():
        f.write("    object %s {\n" % typ)
        for name, val in items:
            f.write("        const val %s: Int = %s\n" % (name, val))
        f.write("    }\n")
    f.write("}\n")
print("生成", out, "类型:", ", ".join(groups.keys()))
