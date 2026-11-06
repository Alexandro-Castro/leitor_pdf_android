#!/usr/bin/env python3
"""Gera um PDF de exemplo com 12 páginas para o teste no emulador (sem bibliotecas externas).

Tem páginas em retrato e uma em paisagem, faixas coloridas (para conferir o modo noturno),
texto com acentos, margens largas (para o corte de margens) e, na página 1, um link interno
(para a página 5) e um externo.
"""
import sys

FRASES = [
    "O rato roeu a roupa do rei de Roma, e a rainha resolveu remendar.",
    "Três pratos de trigo para três tigres tristes, sem pressa nenhuma.",
    "A aranha arranha a rã; a rã arranha a aranha no jardim de manhã.",
    "Quem com ferro fere, com ferro será ferido, já dizia o ditado.",
    "Água mole em pedra dura tanto bate até que fura, devagar.",
    "Ação, atenção, coração: acentos e cedilha devem aparecer certos.",
]


def esc(texto):
    return texto.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def conteudo(n, w, h):
    cores = [(0.80, 0.15, 0.15), (0.15, 0.35, 0.80), (0.10, 0.55, 0.30)]
    r, g, b = cores[n % 3]
    linhas = [
        f"{r} {g} {b} rg 50 {h - 150} {w - 100} 56 re f",
        f"BT /F2 38 Tf 1 1 1 rg 66 {h - 136} Td ({esc('Página %d' % n)}) Tj ET",
        "0 0 0 rg",
    ]
    y = h - 200
    if n == 1:
        # caixas dos links (as áreas clicáveis são definidas em main)
        linhas += [
            f"0.90 0.93 1 rg 50 {h - 300} {w - 100} 60 re f",
            f"BT /F2 18 Tf 0.1 0.25 0.7 rg 64 {h - 278} Td ({esc('Toque aqui: ir para a página 5')}) Tj ET",
            f"0.92 1 0.92 rg 50 {h - 380} {w - 100} 60 re f",
            f"BT /F2 18 Tf 0.1 0.45 0.2 rg 64 {h - 358} Td ({esc('Link externo: example.com')}) Tj ET",
            "0 0 0 rg",
        ]
        y = h - 420
    i = n
    while y > 110:
        linhas.append(f"BT /F1 13 Tf 50 {y} Td ({esc(FRASES[i % len(FRASES)])}) Tj ET")
        y -= 22
        i += 1
    linhas.append(f"BT /F1 11 Tf 0.4 0.4 0.4 rg {w / 2 - 18:.0f} 40 Td ({esc('- %d -' % n)}) Tj ET")
    return "\n".join(linhas).encode("cp1252")


def main(destino):
    objetos = {
        1: b"<< /Type /Catalog /Pages 2 0 R >>",
        3: b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
        4: b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>",
    }
    paginas = []
    proximo = 5
    for n in range(1, 13):
        w, h = (842, 595) if n == 11 else (595, 842)
        dados = conteudo(n, w, h)
        id_pag, id_cont = proximo, proximo + 1
        proximo += 2
        anotacoes = ""
        if n == 1:
            anotacoes = f" /Annots [{proximo} 0 R {proximo + 1} 0 R]"
            pagina5 = 5 + 2 * 4  # objeto da página 5
            objetos[proximo] = (
                f"<< /Type /Annot /Subtype /Link /Rect [50 {h - 300} {w - 50} {h - 240}] "
                f"/Border [0 0 0] /Dest [{pagina5} 0 R /XYZ 0 842 0] >>").encode()
            objetos[proximo + 1] = (
                f"<< /Type /Annot /Subtype /Link /Rect [50 {h - 380} {w - 50} {h - 320}] "
                f"/Border [0 0 0] /A << /S /URI /URI (https://example.com/) >> >>").encode()
            proximo += 2
        objetos[id_pag] = (
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {w} {h}] "
            f"/Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents {id_cont} 0 R{anotacoes} >>"
        ).encode()
        objetos[id_cont] = b"<< /Length %d >>\nstream\n" % len(dados) + dados + b"\nendstream"
        paginas.append(id_pag)
    filhos = " ".join(f"{p} 0 R" for p in paginas)
    objetos[2] = f"<< /Type /Pages /Kids [{filhos}] /Count {len(paginas)} >>".encode()

    saida = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    posicoes = {}
    for i in sorted(objetos):
        posicoes[i] = len(saida)
        saida += f"{i} 0 obj\n".encode() + objetos[i] + b"\nendobj\n"
    inicio_xref = len(saida)
    total = max(objetos) + 1
    saida += f"xref\n0 {total}\n".encode() + b"0000000000 65535 f \n"
    for i in range(1, total):
        saida += f"{posicoes[i]:010d} 00000 n \n".encode()
    saida += f"trailer\n<< /Size {total} /Root 1 0 R >>\nstartxref\n{inicio_xref}\n%%EOF\n".encode()
    with open(destino, "wb") as f:
        f.write(saida)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "exemplo.pdf")
