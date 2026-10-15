package com.alexandro.leitorpdf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.UriPermission;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.DisplayCutout;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity implements PdfView.Ouvinte {

    private static final int PEDIDO_ABRIR = 1;
    private static final long TELA_LIGADA_MS = 5 * 60 * 1000L;
    private static final long INDICADOR_MS = 1200L;

    private Armazenamento armazenamento;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<View, int[]> paddingOriginal = new HashMap<>();

    private View raiz;
    private View inicio;
    private View inicioBarra;
    private View inicioConteudo;
    private View leitor;
    private View barraTopo;
    private View barraBase;
    private View carregando;
    private LinearLayout listaRecentes;
    private TextView semRecentes;
    private TextView tituloRecentes;
    private TextView titulo;
    private TextView rotuloPagina;
    private TextView indicador;
    private ImageButton botaoNoturno;
    private ImageButton[] botoesTopo;
    private SeekBar barraPaginas;
    private PdfView pdf;

    // Documento aberto
    private Uri docUri;
    private String docNome;
    private String docChave;
    private boolean docExterno;     // veio de outro app ("Abrir com" / "Compartilhar")

    private boolean barrasVisiveis = true;
    private boolean arrastandoBarra;
    private boolean noturno;
    private int margemIndicador;

    private final Runnable apagarTela = () ->
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    private final Runnable esconderIndicador = () ->
            indicador.animate().alpha(0f).setDuration(250)
                    .withEndAction(() -> indicador.setVisibility(View.GONE));

    // ======================= Ciclo de vida =======================

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);
        setContentView(R.layout.activity_main);
        armazenamento = new Armazenamento(this);
        noturno = armazenamento.noturno();

        acharViews();
        configurarJanela();
        configurarEventos();
        aplicarNoturno();

        String uriSalva = estado != null ? estado.getString("uri") : null;
        if (uriSalva != null) {
            abrirDocumento(Uri.parse(uriSalva), estado.getBoolean("externo"),
                    estado.getFloat("posicao", -1f));
        } else if (!tratarIntent(getIntent())) {
            mostrarInicio();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        tratarIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A lista pode ter mudado enquanto outra janela do app estava aberta.
        if (inicio.getVisibility() == View.VISIBLE) atualizarRecentes();
    }

    @Override
    protected void onPause() {
        super.onPause();
        salvarPosicao();
    }

    @Override
    protected void onSaveInstanceState(Bundle saida) {
        super.onSaveInstanceState(saida);
        if (docUri != null && leitor.getVisibility() == View.VISIBLE) {
            saida.putString("uri", docUri.toString());
            saida.putBoolean("externo", docExterno);
            saida.putFloat("posicao", pdf.getPosicao());
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        pdf.liberar();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (leitor.getVisibility() == View.VISIBLE) {
            fecharLeitor();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean temFoco) {
        super.onWindowFocusChanged(temFoco);
        // Depois de um diálogo, garante que as barras do sistema continuem escondidas.
        if (temFoco && leitor.getVisibility() == View.VISIBLE && !barrasVisiveis) {
            mostrarBarrasSistema(false);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent evento) {
        if (evento.getActionMasked() == MotionEvent.ACTION_DOWN
                && leitor.getVisibility() == View.VISIBLE) {
            manterTelaLigada();
        }
        return super.dispatchTouchEvent(evento);
    }

    @Override
    protected void onActivityResult(int pedido, int resultado, Intent dados) {
        super.onActivityResult(pedido, resultado, dados);
        if (pedido != PEDIDO_ABRIR || resultado != RESULT_OK || dados == null) return;
        Uri uri = dados.getData();
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            // sem permissão permanente: abre agora, mas não entra nos recentes
        }
        abrirDocumento(uri, false, -1f);
    }

    // ======================= Montagem da tela =======================

    private void acharViews() {
        raiz = findViewById(R.id.raiz);
        inicio = findViewById(R.id.inicio);
        inicioBarra = findViewById(R.id.inicio_barra);
        inicioConteudo = findViewById(R.id.inicio_conteudo);
        listaRecentes = findViewById(R.id.lista_recentes);
        semRecentes = findViewById(R.id.sem_recentes);
        tituloRecentes = findViewById(R.id.titulo_recentes);
        leitor = findViewById(R.id.leitor);
        pdf = findViewById(R.id.pdf);
        carregando = findViewById(R.id.carregando);
        barraTopo = findViewById(R.id.barra_topo);
        barraBase = findViewById(R.id.barra_base);
        titulo = findViewById(R.id.titulo);
        botaoNoturno = findViewById(R.id.botao_noturno);
        botoesTopo = new ImageButton[]{findViewById(R.id.botao_voltar),
                findViewById(R.id.botao_abrir_outro), botaoNoturno};
        rotuloPagina = findViewById(R.id.rotulo_pagina);
        barraPaginas = findViewById(R.id.barra_paginas);
        indicador = findViewById(R.id.indicador);

        for (View v : new View[]{inicioBarra, inicioConteudo, barraTopo, barraBase}) {
            paddingOriginal.put(v, new int[]{v.getPaddingLeft(), v.getPaddingTop(),
                    v.getPaddingRight(), v.getPaddingBottom()});
        }
        margemIndicador = ((FrameLayout.LayoutParams) indicador.getLayoutParams()).bottomMargin;
    }

    private void configurarEventos() {
        pdf.setOuvinte(this);
        findViewById(R.id.botao_abrir).setOnClickListener(v -> abrirSeletor());
        findViewById(R.id.botao_abrir_outro).setOnClickListener(v -> abrirSeletor());
        findViewById(R.id.botao_voltar).setOnClickListener(v -> fecharLeitor());
        botaoNoturno.setOnClickListener(v -> alternarNoturno());
        rotuloPagina.setOnClickListener(v -> perguntarPagina());

        barraPaginas.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar barra, int progresso, boolean doUsuario) {
                if (!doUsuario) return;
                mostrarRotulo(progresso);
                pdf.irParaPagina(progresso);
            }

            @Override
            public void onStartTrackingTouch(SeekBar barra) {
                arrastandoBarra = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar barra) {
                arrastandoBarra = false;
                int atual = pdf.getPaginaAtual();
                barra.setProgress(atual);
                mostrarRotulo(atual);
            }
        });

        // As barras ficam por cima do documento; o PdfView precisa saber a altura delas.
        View.OnLayoutChangeListener mediuBarras = (v, l, t, r, b, la, ta, ra, ba) ->
                pdf.setEspacos(barraTopo.getHeight(), barraBase.getHeight());
        barraTopo.addOnLayoutChangeListener(mediuBarras);
        barraBase.addOnLayoutChangeListener(mediuBarras);

        raiz.setOnApplyWindowInsetsListener((v, insets) -> {
            int[] m = margensSistema(insets);
            aplicarMargens(m[0], m[1], m[2], m[3]);
            return insets;
        });
    }

    // ======================= Abrir e fechar documentos =======================

    /** Trata "Abrir com" (VIEW) e "Compartilhar" (SEND). Devolve true se abriu algo. */
    private boolean tratarIntent(Intent intent) {
        if (intent == null) return false;
        String acao = intent.getAction();
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(acao)) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(acao)) {
            uri = extrairAnexo(intent);
        }
        if (uri == null) return false;
        if ((intent.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException e) {
                // tudo bem: só não entra nos recentes
            }
        }
        abrirDocumento(uri, true, -1f);
        return true;
    }

    @SuppressWarnings("deprecation")
    private static Uri extrairAnexo(Intent intent) {
        Parcelable anexo;
        if (Build.VERSION.SDK_INT >= 33) {
            anexo = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        } else {
            anexo = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (anexo instanceof Uri) return (Uri) anexo;
        ClipData clip = intent.getClipData();
        if (clip != null && clip.getItemCount() > 0) return clip.getItemAt(0).getUri();
        return null;
    }

    private void abrirSeletor() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        try {
            startActivityForResult(intent, PEDIDO_ABRIR);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.sem_seletor, Toast.LENGTH_LONG).show();
        }
    }

    private void abrirDocumento(Uri uri, boolean externo, float posicaoForcada) {
        salvarPosicao();
        docUri = uri;
        docExterno = externo;
        lerNomeEChave(uri);
        float posicao = posicaoForcada >= 0 ? posicaoForcada : armazenamento.posicao(docChave);

        titulo.setText(docNome);
        rotuloPagina.setText("");
        barraPaginas.setVisibility(View.INVISIBLE);
        mostrarLeitor();
        carregando.setVisibility(View.VISIBLE);
        pdf.abrir(getContentResolver(), uri, getCacheDir(), posicao);
    }

    /** Nome do arquivo e uma chave que identifica o mesmo PDF vindo de apps diferentes. */
    private void lerNomeEChave(Uri uri) {
        String nome = null;
        long tamanho = -1;
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            File arquivo = new File(uri.getPath());
            nome = arquivo.getName();
            if (arquivo.exists()) tamanho = arquivo.length();
        } else {
            String[] colunas = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            try (Cursor c = getContentResolver().query(uri, colunas, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int iNome = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int iTamanho = c.getColumnIndex(OpenableColumns.SIZE);
                    if (iNome >= 0 && !c.isNull(iNome)) nome = c.getString(iNome);
                    if (iTamanho >= 0 && !c.isNull(iTamanho)) tamanho = c.getLong(iTamanho);
                }
            } catch (Exception e) {
                // alguns apps não informam nome nem tamanho
            }
        }
        if (nome == null || nome.isEmpty()) nome = uri.getLastPathSegment();
        if (nome == null || nome.isEmpty()) nome = getString(R.string.documento);
        docNome = nome;
        docChave = tamanho > 0 ? nome + "|" + tamanho : uri.toString();
    }

    private void salvarPosicao() {
        if (docChave == null || !pdf.temDocumento()) return;
        armazenamento.salvarPosicao(docChave, pdf.getPosicao());
        armazenamento.atualizarRecente(docChave, pdf.getPaginaAtual(), pdf.getTotalPaginas());
    }

    private void fecharLeitor() {
        salvarPosicao();
        if (docExterno) {
            finish(); // volta para o app que pediu para abrir o PDF
        } else {
            mostrarInicio();
        }
    }

    private boolean podeReabrir(Uri uri) {
        if ("file".equals(uri.getScheme())) return true;
        for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(uri) && p.isReadPermission()) return true;
        }
        return false;
    }

    private void liberarPermissao(String uri) {
        if (armazenamento.usaUri(uri) || (docUri != null && docUri.toString().equals(uri))) return;
        try {
            getContentResolver().releasePersistableUriPermission(Uri.parse(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            // já não tinha permissão
        }
    }

    // ======================= Avisos do PdfView =======================

    @Override
    public void aoAbrir(int totalPaginas) {
        carregando.setVisibility(View.GONE);
        barraPaginas.setMax(Math.max(0, totalPaginas - 1));
        barraPaginas.setVisibility(totalPaginas > 1 ? View.VISIBLE : View.INVISIBLE);
        int atual = pdf.getPaginaAtual();
        barraPaginas.setProgress(atual);
        mostrarRotulo(atual);
        if (docUri != null && podeReabrir(docUri)) {
            List<String> saiu = armazenamento.registrarRecente(docUri.toString(), docNome, docChave,
                    atual, totalPaginas);
            for (String uri : saiu) liberarPermissao(uri);
        }
        manterTelaLigada();
    }

    @Override
    public void aoFalhar(int motivo) {
        carregando.setVisibility(View.GONE);
        if (isFinishing() || isDestroyed()) return;
        if (docUri != null && armazenamento.removerRecente(docUri.toString())) {
            String uri = docUri.toString();
            docUri = null;
            liberarPermissao(uri);
        }
        docChave = null;
        int mensagem;
        if (motivo == PdfView.FALHA_SENHA) {
            mensagem = R.string.erro_senha;
        } else if (motivo == PdfView.FALHA_ACESSO) {
            mensagem = R.string.erro_acesso;
        } else {
            mensagem = R.string.erro_leitura;
        }
        final boolean externo = docExterno;
        new AlertDialog.Builder(this)
                .setTitle(R.string.nao_abriu)
                .setMessage(mensagem)
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener(d -> {
                    if (externo) {
                        finish();
                    } else {
                        mostrarInicio();
                    }
                })
                .show();
    }

    @Override
    public void aoMudarPagina(int pagina, int total) {
        if (!pdf.temDocumento()) return;
        if (!arrastandoBarra) {
            barraPaginas.setProgress(pagina);
            mostrarRotulo(pagina);
        }
        if (!barrasVisiveis) mostrarIndicador(pagina, total);
    }

    @Override
    public void aoToque() {
        mostrarBarras(!barrasVisiveis, true);
    }

    // ======================= Telas =======================

    private void mostrarInicio() {
        docUri = null;
        docChave = null;
        docNome = null;
        pdf.fechar();
        handler.removeCallbacks(apagarTela);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        leitor.setVisibility(View.GONE);
        inicio.setVisibility(View.VISIBLE);
        mostrarBarras(true, false);
        atualizarIconesSistema();
        atualizarRecentes();
    }

    private void mostrarLeitor() {
        inicio.setVisibility(View.GONE);
        leitor.setVisibility(View.VISIBLE);
        mostrarBarras(true, false);
        atualizarIconesSistema();
    }

    private void atualizarRecentes() {
        listaRecentes.removeAllViews();
        List<Armazenamento.Recente> lista = armazenamento.recentes();
        semRecentes.setVisibility(lista.isEmpty() ? View.VISIBLE : View.GONE);
        tituloRecentes.setVisibility(lista.isEmpty() ? View.GONE : View.VISIBLE);
        LayoutInflater inflador = getLayoutInflater();
        long agora = System.currentTimeMillis();
        for (final Armazenamento.Recente r : lista) {
            View item = inflador.inflate(R.layout.item_recente, listaRecentes, false);
            ((TextView) item.findViewById(R.id.nome)).setText(r.nome);
            CharSequence quando = agora - r.quando < DateUtils.MINUTE_IN_MILLIS
                    ? getString(R.string.agora)
                    : DateUtils.getRelativeTimeSpanString(r.quando, agora, DateUtils.MINUTE_IN_MILLIS);
            String detalhe = r.total > 0
                    ? getString(R.string.pagina_de, r.pagina + 1, r.total) + "  ·  " + quando
                    : quando.toString();
            ((TextView) item.findViewById(R.id.detalhe)).setText(detalhe);
            ProgressBar progresso = item.findViewById(R.id.progresso);
            progresso.setProgress(r.total > 0 ? Math.round(1000f * (r.pagina + 1) / r.total) : 0);
            item.setOnClickListener(v -> abrirDocumento(Uri.parse(r.uri), false, -1f));
            item.setOnLongClickListener(v -> {
                confirmarRemocao(r);
                return true;
            });
            listaRecentes.addView(item);
        }
    }

    private void confirmarRemocao(final Armazenamento.Recente r) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.remover_da_lista)
                .setMessage(r.nome)
                .setPositiveButton(R.string.remover, (d, w) -> {
                    armazenamento.removerRecente(r.uri);
                    liberarPermissao(r.uri);
                    atualizarRecentes();
                })
                .setNegativeButton(R.string.cancelar, null)
                .show();
    }

    private void mostrarRotulo(int pagina) {
        int total = pdf.getTotalPaginas();
        rotuloPagina.setText(total > 0 ? getString(R.string.rotulo_pagina, pagina + 1, total) : "");
    }

    private void mostrarIndicador(int pagina, int total) {
        indicador.setText(getString(R.string.rotulo_pagina, pagina + 1, total));
        indicador.animate().cancel();
        indicador.setAlpha(1f);
        indicador.setVisibility(View.VISIBLE);
        handler.removeCallbacks(esconderIndicador);
        handler.postDelayed(esconderIndicador, INDICADOR_MS);
    }

    private void perguntarPagina() {
        final int total = pdf.getTotalPaginas();
        if (total <= 0) return;
        final EditText campo = new EditText(this);
        campo.setInputType(InputType.TYPE_CLASS_NUMBER);
        campo.setImeOptions(EditorInfo.IME_ACTION_GO);
        campo.setSingleLine(true);
        campo.setHint(getString(R.string.dica_pagina, total));
        FrameLayout moldura = new FrameLayout(this);
        int lado = dp(24);
        moldura.setPadding(lado, dp(8), lado, 0);
        moldura.addView(campo, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final AlertDialog dialogo = new AlertDialog.Builder(this)
                .setTitle(R.string.ir_para_pagina)
                .setView(moldura)
                .setPositiveButton(R.string.ir, (d, w) -> irParaDigitada(campo, total))
                .setNegativeButton(R.string.cancelar, null)
                .create();
        campo.setOnEditorActionListener((v, acao, evento) -> {
            if (evento == null || evento.getAction() == KeyEvent.ACTION_DOWN) {
                if (dialogo.isShowing()) {
                    irParaDigitada(campo, total);
                    dialogo.dismiss();
                }
            }
            return true;
        });
        Window janela = dialogo.getWindow();
        if (janela != null) {
            janela.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialogo.show();
        campo.requestFocus();
    }

    private void irParaDigitada(EditText campo, int total) {
        int pagina;
        try {
            pagina = Integer.parseInt(campo.getText().toString().trim());
        } catch (NumberFormatException e) {
            pagina = -1;
        }
        if (pagina < 1 || pagina > total) {
            Toast.makeText(this, getString(R.string.pagina_invalida, total), Toast.LENGTH_SHORT).show();
            return;
        }
        pdf.irParaPagina(pagina - 1);
        barraPaginas.setProgress(pagina - 1);
        mostrarRotulo(pagina - 1);
    }

    private void alternarNoturno() {
        noturno = !noturno;
        armazenamento.setNoturno(noturno);
        aplicarNoturno();
    }

    private void aplicarNoturno() {
        pdf.setNoturno(noturno);
        pdf.setCorFundo(noturno ? Color.BLACK : getColor(R.color.fundo_leitor));
        botaoNoturno.setImageResource(noturno ? R.drawable.ic_dia : R.drawable.ic_noturno);
        botaoNoturno.setContentDescription(getString(
                noturno ? R.string.desativar_noturno : R.string.ativar_noturno));

        // No modo noturno as barras do leitor também ficam escuras.
        int fundoBarra = noturno ? getColor(R.color.barra_noturna) : getColor(R.color.barra);
        int corTexto = noturno ? getColor(R.color.texto_noturno) : getColor(R.color.texto);
        barraTopo.setBackgroundColor(fundoBarra);
        barraBase.setBackgroundColor(fundoBarra);
        titulo.setTextColor(corTexto);
        rotuloPagina.setTextColor(corTexto);
        rotuloPagina.setBackgroundResource(noturno ? R.drawable.fundo_pilula_noturna : R.drawable.fundo_pilula);
        ColorStateList tinta = ColorStateList.valueOf(corTexto);
        for (ImageButton b : botoesTopo) b.setImageTintList(tinta);
        atualizarIconesSistema();
    }

    /** Ícones escuros nas barras do sistema quando o fundo atrás delas é claro. */
    private boolean iconesEscuros() {
        boolean lendo = leitor.getVisibility() == View.VISIBLE;
        return temaClaro() && !(lendo && noturno);
    }

    @SuppressWarnings("deprecation")
    private void atualizarIconesSistema() {
        Window janela = getWindow();
        boolean escuros = iconesEscuros();
        if (Build.VERSION.SDK_INT < 29) {
            janela.setNavigationBarColor(escuros ? 0xE6FFFFFF : 0xE6000000);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controle = janela.getInsetsController();
            if (controle != null) {
                int claras = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controle.setSystemBarsAppearance(escuros ? claras : 0, claras);
            }
        } else {
            boolean escondidas = leitor.getVisibility() == View.VISIBLE && !barrasVisiveis;
            mostrarBarrasSistema(!escondidas);
        }
    }

    private void manterTelaLigada() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.removeCallbacks(apagarTela);
        handler.postDelayed(apagarTela, TELA_LIGADA_MS);
    }

    // ======================= Barras =======================

    private void mostrarBarras(boolean mostrar, boolean animar) {
        barrasVisiveis = mostrar;
        mostrarBarrasSistema(mostrar);
        animarBarra(barraTopo, mostrar, -1, animar);
        animarBarra(barraBase, mostrar, 1, animar);
        if (mostrar) {
            handler.removeCallbacks(esconderIndicador);
            indicador.animate().cancel();
            indicador.setVisibility(View.GONE);
        }
    }

    private static void animarBarra(final View barra, boolean mostrar, int direcao, boolean animar) {
        barra.animate().cancel();
        float alvoY = mostrar ? 0f : direcao * barra.getHeight();
        if (!animar || barra.getHeight() == 0) {
            barra.setTranslationY(alvoY);
            barra.setAlpha(mostrar ? 1f : 0f);
            barra.setVisibility(mostrar ? View.VISIBLE : View.INVISIBLE);
            return;
        }
        barra.setVisibility(View.VISIBLE);
        if (mostrar) {
            barra.animate().translationY(0f).alpha(1f).setDuration(180);
        } else {
            barra.animate().translationY(alvoY).alpha(0f).setDuration(180)
                    .withEndAction(() -> barra.setVisibility(View.INVISIBLE));
        }
    }

    private boolean temaClaro() {
        int modo = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return modo != Configuration.UI_MODE_NIGHT_YES;
    }

    /** O app desenha por trás das barras do sistema; as margens são aplicadas à mão. */
    @SuppressWarnings("deprecation")
    private void configurarJanela() {
        Window janela = getWindow();
        janela.setStatusBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            janela.setNavigationBarColor(Color.TRANSPARENT);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams atributos = janela.getAttributes();
            atributos.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            janela.setAttributes(atributos);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            janela.setDecorFitsSystemWindows(false);
        } else {
            janela.getDecorView().setSystemUiVisibility(flagsBase());
        }
    }

    @SuppressWarnings("deprecation")
    private int flagsBase() {
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (iconesEscuros()) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        return flags;
    }

    @SuppressWarnings("deprecation")
    private void mostrarBarrasSistema(boolean mostrar) {
        Window janela = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controle = janela.getInsetsController();
            if (controle == null) return;
            if (mostrar) {
                controle.show(WindowInsets.Type.systemBars());
            } else {
                controle.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controle.hide(WindowInsets.Type.systemBars());
            }
        } else {
            int flags = flagsBase();
            if (!mostrar) {
                flags |= View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            }
            janela.getDecorView().setSystemUiVisibility(flags);
        }
    }

    /** Espaço ocupado pelas barras do sistema e pelo entalhe: esquerda, topo, direita, base. */
    @SuppressWarnings("deprecation")
    private static int[] margensSistema(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            Insets i = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            return new int[]{i.left, i.top, i.right, i.bottom};
        }
        int esquerda = insets.getSystemWindowInsetLeft();
        int topo = insets.getSystemWindowInsetTop();
        int direita = insets.getSystemWindowInsetRight();
        int base = insets.getSystemWindowInsetBottom();
        if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutout entalhe = insets.getDisplayCutout();
            if (entalhe != null) {
                esquerda = Math.max(esquerda, entalhe.getSafeInsetLeft());
                topo = Math.max(topo, entalhe.getSafeInsetTop());
                direita = Math.max(direita, entalhe.getSafeInsetRight());
                base = Math.max(base, entalhe.getSafeInsetBottom());
            }
        }
        return new int[]{esquerda, topo, direita, base};
    }

    private void aplicarMargens(int esquerda, int topo, int direita, int base) {
        somarPadding(inicioBarra, esquerda, topo, direita, 0);
        somarPadding(inicioConteudo, esquerda, 0, direita, base);
        somarPadding(barraTopo, esquerda, topo, direita, 0);
        somarPadding(barraBase, esquerda, 0, direita, base);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) indicador.getLayoutParams();
        lp.bottomMargin = margemIndicador + base;
        indicador.setLayoutParams(lp);
        pdf.setInsetsLaterais(esquerda, direita);
    }

    private void somarPadding(View v, int esquerda, int topo, int direita, int base) {
        int[] p = paddingOriginal.get(v);
        if (p == null) return;
        v.setPadding(p[0] + esquerda, p[1] + topo, p[2] + direita, p[3] + base);
    }

    private int dp(int valor) {
        return Math.round(valor * getResources().getDisplayMetrics().density);
    }
}
