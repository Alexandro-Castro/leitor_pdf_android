#!/usr/bin/env python3
"""Cria uma cópia do PDF protegida pela senha 1234 (precisa do pikepdf)."""
import sys

import pikepdf

origem, destino = sys.argv[1:3]
with pikepdf.open(origem) as pdf:
    pdf.save(destino, encryption=pikepdf.Encryption(owner="dono-teste", user="1234", R=4))
