# Leitor PDF

Leitor de PDF para Android, só para uso pessoal: sem anúncios, sem contas e sem acesso à internet. O app não pede nenhuma permissão, e lê apenas os PDFs que você escolhe ou manda abrir.

## Instalar no celular

1. No celular, abra **[este link](https://github.com/Alexandro-Castro/leitor_pdf_android/releases/latest/download/LeitorPDF.apk)**. Ele baixa sempre a versão mais nova.
2. Abra o arquivo `LeitorPDF.apk` baixado.
3. Se o Android pedir, permita **"Instalar apps desconhecidos"** para o navegador ou para o app de arquivos, e toque em **Instalar**.

Para atualizar, repita os mesmos passos. A versão nova é instalada por cima da antiga, e os recentes e as páginas salvas continuam lá.

## O que ele faz

- **Rolagem contínua** pelas páginas, com **zoom** de pinça ou de toque duplo. Com zoom, a página é redesenhada em alta resolução.
- **Toque na página** para esconder ou mostrar as barras (tela cheia).
- **Barra de páginas** embaixo: arraste para pular, ou toque no número (ex.: `12 / 140`) para digitar a página.
- **Modo noturno**: fundo escuro e texto claro, mantendo as cores das imagens parecidas.
- **Lembra onde você parou** em cada PDF, mesmo quando ele é aberto por outro app.
- **Recentes** na tela inicial, com o progresso de leitura. Para remover um item da lista, toque nele e segure.
- Aparece em **"Abrir com"** e em **"Compartilhar"** nos outros apps (WhatsApp, e-mail, gerenciador de arquivos, Downloads).
- A tela não apaga enquanto você lê. Depois de 5 minutos sem tocar na tela, ela volta a apagar normalmente.
- Segue o tema claro ou escuro do sistema.

Limitações atuais: não abre PDFs protegidos por senha, e ainda não tem busca de texto.

Os PDFs abertos pelo botão **Abrir PDF** entram nos recentes. Os abertos por outros apps também têm a página lembrada, mas o Android só dá acesso temporário a eles, por isso não entram na lista.

## Como as versões são geradas

Não é preciso instalar nada no computador. Cada alteração no ramo `main` dispara o GitHub Actions (aba **Actions**), que compila o app e publica o APK em **Releases**.

- `.github/workflows/gerar-apk.yml`: compila e publica o APK.
- `.github/workflows/testar-emulador.yml`: instala o app em emuladores (Android 8 e Android 15), abre um PDF de exemplo, interage com ele e tira capturas de tela. Roda quando há envio para o ramo `teste`, ou manualmente na aba Actions. As capturas e os registros ficam nos artefatos da execução e no ramo `resultados-teste`.

Para compilar no computador, abra a pasta no Android Studio, ou rode `./gradlew assembleRelease`.

### Sobre a chave de assinatura

O Android só aceita uma atualização por cima da versão instalada se as duas tiverem a mesma assinatura. Por isso a chave fica no próprio repositório (`assinatura/leitor.jks`). Como o repositório é público, essa chave também é. Se preferir que só você tenha acesso a ela, torne o repositório privado em **Settings → General → Danger Zone → Change visibility**. O resto continua funcionando igual. Com o repositório privado, você precisa estar logado no GitHub para baixar o APK.

## Estrutura

```
app/src/main/java/com/alexandro/leitorpdf/
  MainActivity.java   telas, barras, recentes, "Abrir com"
  PdfView.java        desenho das páginas, rolagem, zoom, modo noturno
  Armazenamento.java  preferências, páginas salvas e lista de recentes
```

Usa apenas o leitor de PDF que já vem no Android (`PdfRenderer`), sem nenhuma biblioteca de terceiros. Requer Android 8.0 ou mais novo.
