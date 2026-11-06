#!/usr/bin/env python3
"""Acha um elemento na tela (despejo do uiautomator).

Uso:
  achar.py arquivo.xml id <resource-id>      -> centro "x y"
  achar.py arquivo.xml texto <texto>         -> centro "x y"
  achar.py arquivo.xml limites <resource-id> -> "x1 y1 x2 y2"
"""
import re
import sys
import xml.etree.ElementTree as ET

arquivo, modo, valor = sys.argv[1:4]
try:
    raiz = ET.parse(arquivo).getroot()
except Exception:
    sys.exit(0)
for no in raiz.iter("node"):
    alvo = no.get("text") if modo == "texto" else no.get("resource-id")
    if alvo == valor:
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", no.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            if modo == "limites":
                print(x1, y1, x2, y2)
            else:
                print((x1 + x2) // 2, (y1 + y2) // 2)
            break
