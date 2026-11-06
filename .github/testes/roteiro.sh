#!/usr/bin/env bash
# Teste no emulador: instala o app, abre PDFs de exemplo, usa os recursos e tira capturas de tela.
# As capturas e os registros ficam na pasta "resultado".
set -u
PKG=com.alexandro.leitorpdf
ATIV="$PKG/.MainActivity"
OUT=resultado
APK=app/build/outputs/apk/debug/app-debug.apk
ACHAR=.github/testes/achar.py
mkdir -p "$OUT"

tela() { sleep "${2:-2}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "captura: $1"; }
despejar_ui() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" > /dev/null 2>&1
}
toque_id() {
  despejar_ui
  local c; c=$(python3 "$ACHAR" "$OUT/ui.xml" id "$1")
  if [ -n "$c" ]; then adb shell input tap $c; else echo "AVISO: não achei o id $1"; return 1; fi
}
toque_texto() {
  despejar_ui
  local c; c=$(python3 "$ACHAR" "$OUT/ui.xml" texto "$1")
  if [ -n "$c" ]; then adb shell input tap $c; else echo "AVISO: não achei o texto $1"; return 1; fi
}
tem_texto() { despejar_ui; [ -n "$(python3 "$ACHAR" "$OUT/ui.xml" texto "$1")" ]; }
no_leitor() { despejar_ui; [ -n "$(python3 "$ACHAR" "$OUT/ui.xml" id "$PKG:id/titulo")" ]; }
toque_duplo() { adb shell "input tap $1 $2 & sleep 0.12; input tap $1 $2; wait"; }
toque_longo() { adb shell input swipe "$1" "$2" "$1" "$2" 1200; }
# garante as barras do leitor à vista (um toque no meio as mostra ou esconde)
barras() { despejar_ui; [ -n "$(python3 "$ACHAR" "$OUT/ui.xml" id "$PKG:id/botao_mais")" ] || { adb shell input tap $CX $CY; sleep 1.5; }; }
menu() { barras; toque_id "$PKG:id/botao_mais" && sleep 1 && toque_texto "$1"; }
ir_para_pagina() {
  barras
  toque_id "$PKG:id/rotulo_pagina" && sleep 1
  adb shell input text "$1"
  adb shell input keyevent 66
}

adb logcat -G 16M > /dev/null 2>&1
adb logcat -c
# não mostrar o aviso "tela cheia" do sistema, que cobriria as capturas
adb shell settings put secure immersive_mode_confirmations confirmed

# o emulador às vezes ainda não está pronto para instalar logo depois de ligar
instalado=""
for tentativa in 1 2 3 4 5 6; do
  if adb install -r "$APK" > "$OUT/instalacao.txt" 2>&1; then instalado=1; break; fi
  echo "instalação falhou (tentativa $tentativa), tentando de novo"; sleep 15
done
[ -n "$instalado" ] || { echo "ERRO: não instalou"; cat "$OUT/instalacao.txt"; exit 1; }

TAM=$(adb shell wm size | tr -d '\r' | awk -F': ' '/size/ {print $2}' | tail -1)
W=${TAM%x*}; H=${TAM#*x}
CX=$((W / 2)); CY=$((H / 2))
DENS=$(adb shell wm density | tr -d '\r' | awk -F': ' '/density/ {print $2}' | tail -1)
echo "Tela: ${W}x${H}, densidade $DENS"

# PDFs de exemplo na pasta Download (para o seletor) e na pasta do próprio app
adb push exemplo.pdf /sdcard/Download/exemplo.pdf > /dev/null
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
  -d file:///sdcard/Download/exemplo.pdf > /dev/null 2>&1
for f in exemplo.pdf exemplo_senha.pdf; do
  adb push "$f" "/data/local/tmp/$f" > /dev/null
  adb shell "cat /data/local/tmp/$f | run-as $PKG sh -c 'mkdir -p files && cat > files/$f'"
done
ARQ="file:///data/data/$PKG/files/exemplo.pdf"
ARQ_SENHA="file:///data/data/$PKG/files/exemplo_senha.pdf"

echo "== 1. Tela inicial"
adb shell am start -W -n "$ATIV" > /dev/null
tela 01_inicio_vazio 3

echo "== 2. Abrir com (como se viesse de outro app)"
adb shell am start -W -a android.intent.action.VIEW -d "$ARQ" -t application/pdf -n "$ATIV" > /dev/null
tela 02_aberto 4

echo "== 3. Rolar"
adb shell input swipe $CX $((H * 3 / 4)) $CX $((H / 4)) 300
tela 03_rolado 2

echo "== 4. Toque: tela cheia"
adb shell input tap $CX $CY
tela 04_tela_cheia 2

echo "== 5. Rolar em tela cheia: indicador de página"
adb shell input swipe $CX $((H * 3 / 4)) $CX $((H / 3)) 300
tela 05_indicador 0.5

echo "== 6. Toque: barras voltam"
sleep 2
adb shell input tap $CX $CY
tela 06_barras 2

echo "== 7. Modo noturno"
toque_id "$PKG:id/botao_noturno"
tela 07_noturno 2

echo "== 8. Ir para a página 9"
toque_id "$PKG:id/rotulo_pagina"
tela 08_dialogo 2
adb shell input text 9
adb shell input keyevent 66
tela 09_pagina_9 3

echo "== 9. Paisagem e volta"
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
tela 10_paisagem 5
adb shell settings put system user_rotation 0
tela 11_retrato 4

echo "== 10. Voltar (fecha e volta para quem abriu)"
adb shell input keyevent 4
tela 12_voltou 3

echo "== 11. Reabrir do zero: deve voltar na página 9"
adb shell am force-stop $PKG
adb shell am start -W -a android.intent.action.VIEW -d "$ARQ" -t application/pdf -n "$ATIV" > /dev/null
tela 13_reaberto 4
toque_id "$PKG:id/botao_noturno"
sleep 1
adb shell input keyevent 4

echo "== 12. Tela inicial com recentes"
adb shell am start -W -n "$ATIV" > /dev/null
tela 14_inicio_recentes 3

echo "== 13. Seletor de arquivos do sistema"
if toque_id "$PKG:id/botao_abrir"; then
  tela 15_seletor 4
  if toque_texto "exemplo.pdf"; then
    tela 16_aberto_pelo_seletor 4
    echo "== 14. Toque duplo: zoom"
    toque_duplo $CX $CY
    tela 17_zoom 3
    toque_duplo $CX $CY
    tela 18_sem_zoom 2
  fi
fi

# Daqui em diante, o documento precisa estar aberto no leitor
if ! no_leitor; then
  adb shell am start -W -a android.intent.action.VIEW -d "$ARQ" -t application/pdf -n "$ATIV" > /dev/null
  sleep 4
fi

echo "== 15. Link interno (página 1 -> página 5)"
ir_para_pagina 1
sleep 2
despejar_ui
read -r BX1 BY1 BX2 BY2 <<< "$(python3 "$ACHAR" "$OUT/ui.xml" limites "$PKG:id/barra_topo")"
# página 1 começa logo abaixo da barra: y = base da barra + margem (6dp) + pontos * escala
ESC=$(python3 -c "d=$DENS/160; m=6*d; print(($W-2*m)/595)")
Y_INTERNO=$(python3 -c "d=$DENS/160; print(int($BY2 + 6*d + 270*$ESC))")
Y_EXTERNO=$(python3 -c "d=$DENS/160; print(int($BY2 + 6*d + 350*$ESC))")
Y_GOTO=$(python3 -c "d=$DENS/160; print(int($BY2 + 6*d + 430*$ESC))")
tela 20_pagina_1 1
adb shell input tap $CX "$Y_INTERNO"
tela 21_link_interno 3
ir_para_pagina 1
sleep 2
adb shell input tap $CX "$Y_GOTO"
tela 21b_link_goto 3

echo "== 16. Link externo"
ir_para_pagina 1
sleep 2
adb shell input tap $CX "$Y_EXTERNO"
tela 22_link_externo 4
adb shell input keyevent 4
sleep 2
if ! no_leitor; then
  adb shell am start -W -a android.intent.action.VIEW -d "$ARQ" -t application/pdf -n "$ATIV" > /dev/null
  sleep 4
fi

echo "== 17. Busca"
if toque_id "$PKG:id/botao_busca"; then
  sleep 1
  adb shell input text rainha
  tela 23_busca 5
  toque_id "$PKG:id/botao_proxima"
  tela 24_busca_proxima 2
  toque_id "$PKG:id/botao_fechar_busca"
  sleep 1
else
  echo "(sem busca neste Android)"
fi

echo "== 18. Seleção de texto (toque longo numa linha da página 2)"
ir_para_pagina 2
sleep 2
# 3ª linha do texto: linha de base a 244 pt do topo da página; meio das letras ~240 pt
X_TEXTO=$(python3 -c "d=$DENS/160; print(int(6*d + 110*$ESC))")
Y_TEXTO=$(python3 -c "d=$DENS/160; print(int($BY2 + 6*d + 240*$ESC))")
toque_longo "$X_TEXTO" "$Y_TEXTO"
tela 25_selecao 2
adb shell input tap $CX $((H * 3 / 4))   # um toque fora tira a seleção
sleep 1

echo "== 19. Menu e cortar margens"
barras
toque_id "$PKG:id/botao_mais"
tela 27_menu 1.5
toque_texto "Cortar margens"
tela 28_margens_cortadas 4

echo "== 20. Modo página por página"
menu "Página por página"
tela 29_modo_pagina 4
adb shell input swipe $((W * 4 / 5)) $CY $((W / 5)) $CY 150
tela 30_deslizou 3
adb shell input tap $((W * 9 / 10)) $CY
tela 31_toque_borda 3

echo "== 21. Marcador"
menu "Marcar esta página"
sleep 1.5
toque_id "android:id/button1"
tela 32_marcada 2
menu "Marcadores"
tela 33_lista_marcadores 1.5
adb shell input keyevent 4
sleep 1

echo "== 22. Miniaturas"
barras
toque_id "$PKG:id/botao_miniaturas"
tela 34_miniaturas 4
toque_texto "3"
tela 35_foi_para_3 3

echo "== 23. Virar com o volume"
menu "Virar página com volume"
sleep 1
adb shell input keyevent 25
tela 36_volume 3

echo "== 24. Volta às opções normais"
menu "Página por página"
sleep 1
menu "Cortar margens"
sleep 1
menu "Virar página com volume"
sleep 1
adb shell input keyevent 4

echo "== 25. PDF com senha"
adb shell am force-stop $PKG
adb shell am start -W -a android.intent.action.VIEW -d "$ARQ_SENHA" -t application/pdf -n "$ATIV" > /dev/null
tela 37_senha 4
adb shell input text 9999
adb shell input keyevent 66
tela 38_senha_errada 4
adb shell input text 1234
adb shell input keyevent 66
tela 39_senha_certa 4

echo "== 26. Tema escuro do sistema"
adb shell cmd uimode night yes
sleep 2
adb shell am force-stop $PKG
adb shell am start -W -n "$ATIV" > /dev/null
tela 40_tema_escuro_inicio 3
adb shell am start -W -a android.intent.action.VIEW -d "$ARQ" -t application/pdf -n "$ATIV" > /dev/null
tela 41_tema_escuro_leitor 4
adb shell cmd uimode night no

adb logcat -d -v time -s LeitorPDF:V AndroidRuntime:E > "$OUT/registro_app.txt"
adb logcat -d -b crash > "$OUT/travamentos.txt" 2> /dev/null
adb logcat -d -v time | tail -n 4000 > "$OUT/logcat.txt"
grep -E "AndroidRuntime|leitorpdf|PdfRenderer|FATAL" "$OUT/logcat.txt" > "$OUT/logcat_app.txt" || true
if grep -q "$PKG" "$OUT/travamentos.txt" 2> /dev/null; then
  echo "ERRO: o app travou durante o teste"
  cat "$OUT/travamentos.txt"
  exit 1
fi
echo "Teste terminado sem travamentos."
