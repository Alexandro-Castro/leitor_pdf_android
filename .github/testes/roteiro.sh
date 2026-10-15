#!/usr/bin/env bash
# Teste no emulador: instala o app, abre um PDF de exemplo, mexe nele e tira capturas de tela.
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
toque_duplo() { adb shell "input tap $1 $2 & sleep 0.12; input tap $1 $2; wait"; }

adb logcat -G 16M > /dev/null 2>&1
adb logcat -c
# não mostrar o aviso "tela cheia" do sistema, que cobriria as capturas
adb shell settings put secure immersive_mode_confirmations confirmed
adb install -r "$APK" || { echo "ERRO: não instalou"; exit 1; }

TAM=$(adb shell wm size | tr -d '\r' | awk -F': ' '/size/ {print $2}' | tail -1)
W=${TAM%x*}; H=${TAM#*x}
CX=$((W / 2)); CY=$((H / 2))
echo "Tela: ${W}x${H}"

# PDF de exemplo na pasta Download (para o seletor) e na pasta do próprio app
adb push exemplo.pdf /sdcard/Download/exemplo.pdf > /dev/null
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
  -d file:///sdcard/Download/exemplo.pdf > /dev/null 2>&1
adb push exemplo.pdf /data/local/tmp/exemplo.pdf > /dev/null
adb shell "cat /data/local/tmp/exemplo.pdf | run-as $PKG sh -c 'mkdir -p files && cat > files/exemplo.pdf'"
ARQ="file:///data/data/$PKG/files/exemplo.pdf"

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
tela 10_paisagem 4
tela 10b_paisagem_depois 6
adb shell input swipe $((H / 2)) $((W * 3 / 4)) $((H / 2)) $((W / 4)) 300
tela 10c_paisagem_rolado 3
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
    adb shell input keyevent 4
    tela 19_inicio_final 3
  fi
fi

echo "== 15. Tema escuro do sistema"
adb shell cmd uimode night yes
sleep 2
adb shell am force-stop $PKG
adb shell am start -W -n "$ATIV" > /dev/null
tela 20_tema_escuro_inicio 3
adb shell am start -W -a android.intent.action.VIEW -d "$ARQ" -t application/pdf -n "$ATIV" > /dev/null
tela 21_tema_escuro_leitor 4
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
