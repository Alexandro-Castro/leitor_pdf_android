package com.alexandro.leitorpdf;

import android.animation.ValueAnimator;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mostra as páginas de um PDF em rolagem contínua, com zoom por pinça e toque duplo.
 *
 * Usa só o PdfRenderer do próprio Android. Todo acesso ao PdfRenderer acontece numa
 * única linha de execução em segundo plano ("trabalho"), porque ele não aceita uso
 * simultâneo. O restante do estado vive na linha principal.
 *
 * Coordenadas: as páginas são posicionadas em "coordenadas do documento" com zoom 1
 * (largura da tela). Um ponto do documento aparece na tela em (doc * zoom - pos).
 * A posição vertical pode ser negativa até -espacoTopo, para que a primeira página
 * comece abaixo da barra superior.
 */
public class PdfView extends View {

    public static final int FALHA_ACESSO = 1;
    public static final int FALHA_SENHA = 2;
    public static final int FALHA_LEITURA = 3;

    public interface Ouvinte {
        void aoAbrir(int totalPaginas);

        void aoFalhar(int motivo);

        void aoMudarPagina(int pagina, int total);

        void aoToque();
    }

    private static final float ZOOM_MAX = 5f;
    private static final float ZOOM_TOQUE_DUPLO = 2.5f;
    private static final int LADO_MAX_BITMAP = 4096;
    private static final String TAG = "LeitorPDF";

    /** Registros detalhados só na versão de teste (debug). */
    private final boolean depurar;

    // ---------- Linha de renderização (campos usados só dentro de 'trabalho') ----------
    private final ExecutorService trabalho = Executors.newSingleThreadExecutor();
    private PdfRenderer renderer;
    private ParcelFileDescriptor descritor;
    private File arquivoTemp;

    // Escritos na linha principal e lidos na linha de renderização.
    private volatile int geracaoAtiva;
    private volatile int pedidoDetalheAtivo;
    private volatile int primeiraDesejada;
    private volatile int ultimaDesejada;

    // ---------- Estado da interface (linha principal) ----------
    private final Handler principal = new Handler(Looper.getMainLooper());
    private Ouvinte ouvinte;
    private int geracao;
    private int pedidoDetalhe;
    private int total;              // número de páginas; 0 = nada carregado
    private float[] proporcao;      // altura / largura de cada página
    private float[] topo;           // topo de cada página (coordenadas do documento)
    private float[] altura;         // altura de cada página (coordenadas do documento)
    private float alturaTotal;
    private int larguraLayout;      // largura usada no layout (0 = layout pendente)
    private float esquerdaPagina;
    private float larguraPagina;
    private final float margem;

    private float espacoTopo;       // altura da barra superior (pixels de tela)
    private float espacoBase;       // altura da barra inferior (pixels de tela)
    private int insetEsquerdo;      // áreas laterais ocupadas pelo sistema (entalhe, botões)
    private int insetDireito;

    private float zoom = 1f;
    private float posX;
    private float posY;
    private float posicaoPendente;
    private boolean tocando;
    private boolean houvePinca;
    private int paginaInformada = -1;
    private boolean noturno;
    private int corFundo = 0xFFDCD9D5;

    private final LruCache<Integer, Bitmap> cache;
    private final Set<Integer> pendentes = new HashSet<>();
    private final Set<Integer> falhas = new HashSet<>();
    private List<Detalhe> detalhes = new ArrayList<>();

    private final Paint pintaBitmap = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pintaPagina = new Paint();
    private final RectF ret = new RectF();

    private final OverScroller rolagem;
    private final ScaleGestureDetector pinca;
    private final GestureDetector gestos;
    private ValueAnimator animacaoZoom;
    private final Runnable aoAssentar = this::assentar;

    /** Recorte em alta resolução da parte visível de uma página (usado com zoom). */
    private static final class Detalhe {
        final int pagina;
        final RectF area;
        final Bitmap bitmap;

        Detalhe(int pagina, RectF area, Bitmap bitmap) {
            this.pagina = pagina;
            this.area = area;
            this.bitmap = bitmap;
        }
    }

    private static final class PedidoDetalhe {
        final int pagina;
        final RectF area;
        final int largura;
        final int altura;
        final float larguraPaginaPx;
        final float desvioX;
        final float desvioY;

        PedidoDetalhe(int pagina, RectF area, int largura, int altura,
                      float larguraPaginaPx, float desvioX, float desvioY) {
            this.pagina = pagina;
            this.area = area;
            this.largura = largura;
            this.altura = altura;
            this.larguraPaginaPx = larguraPaginaPx;
            this.desvioX = desvioX;
            this.desvioY = desvioY;
        }
    }

    private static final class FalhaAbertura extends Exception {
        final int motivo;

        FalhaAbertura(int motivo) {
            this.motivo = motivo;
        }
    }

    public PdfView(Context contexto) {
        this(contexto, null);
    }

    public PdfView(Context contexto, AttributeSet atributos) {
        super(contexto, atributos);
        margem = 6 * contexto.getResources().getDisplayMetrics().density;
        depurar = (contexto.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;

        int maxBytes = (int) Math.min(Runtime.getRuntime().maxMemory() / 4, Integer.MAX_VALUE);
        cache = new LruCache<Integer, Bitmap>(maxBytes) {
            @Override
            protected int sizeOf(Integer chave, Bitmap bitmap) {
                return bitmap.getByteCount();
            }
        };
        if (depurar) {
            Log.d(TAG, "memória máx " + (Runtime.getRuntime().maxMemory() >> 20) + " MB, cache "
                    + (maxBytes >> 20) + " MB");
        }

        rolagem = new OverScroller(contexto);

        pinca = new ScaleGestureDetector(contexto, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                houvePinca = true;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                zoomEm(zoom * detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
        pinca.setQuickScaleEnabled(false);

        gestos = new GestureDetector(contexto, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                rolagem.forceFinished(true);
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                moverPara(posX + dx, posY + dy);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (houvePinca) return false;
                rolagem.fling((int) posX, (int) posY, (int) -vx, (int) -vy,
                        0, (int) maxPosX(), (int) minPosY(), (int) maxPosY());
                postInvalidateOnAnimation();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (ouvinte != null) ouvinte.aoToque();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                animarZoom(zoom > 1.1f ? 1f : ZOOM_TOQUE_DUPLO, e.getX(), e.getY());
                return true;
            }
        });

        aplicarCores();
    }

    // ======================= API pública =======================

    public void setOuvinte(Ouvinte ouvinte) {
        this.ouvinte = ouvinte;
    }

    public boolean temDocumento() {
        return total > 0;
    }

    public int getTotalPaginas() {
        return total;
    }

    public void setNoturno(boolean noturno) {
        this.noturno = noturno;
        aplicarCores();
        invalidate();
    }

    public void setCorFundo(int cor) {
        corFundo = cor;
        invalidate();
    }

    /** Alturas das barras que ficam por cima do documento, em pixels. */
    public void setEspacos(float topo, float base) {
        if (topo == espacoTopo && base == espacoBase) return;
        boolean temLayout = total > 0 && larguraLayout > 0;
        float posicao = getPosicao();
        espacoTopo = topo;
        espacoBase = base;
        if (temLayout) aplicarPosicao(posicao);
    }

    /** Áreas nas laterais da tela cobertas pelo sistema (entalhe da câmera, botões). */
    public void setInsetsLaterais(int esquerdo, int direito) {
        if (esquerdo == insetEsquerdo && direito == insetDireito) return;
        insetEsquerdo = esquerdo;
        insetDireito = direito;
        if (total > 0 && larguraLayout > 0) {
            float posicao = getPosicao();
            fazerLayout();
            aplicarPosicao(posicao);
        }
    }

    /**
     * Abre um PDF e posiciona na 'posicaoInicial' (formato de getPosicao).
     * O resultado chega por Ouvinte.aoAbrir ou Ouvinte.aoFalhar.
     */
    public void abrir(final ContentResolver resolver, final Uri uri, final File pastaTemp,
                      float posicaoInicial) {
        final int g = ++geracao;
        geracaoAtiva = g;
        limparEstado();
        posicaoPendente = Math.max(0f, posicaoInicial);
        executar(() -> {
            fecharDocumento();
            int motivo;
            try {
                abrirRenderer(resolver, uri, pastaTemp);
                int n = renderer.getPageCount();
                if (n <= 0) throw new FalhaAbertura(FALHA_LEITURA);
                final float[] prop = new float[n];
                for (int i = 0; i < n; i++) {
                    if (g != geracaoAtiva) return; // outro arquivo foi pedido nesse meio tempo
                    try (PdfRenderer.Page p = renderer.openPage(i)) {
                        prop[i] = p.getWidth() > 0 && p.getHeight() > 0
                                ? (float) p.getHeight() / p.getWidth() : 1.414f;
                    }
                }
                principal.post(() -> {
                    if (g == geracao) documentoPronto(prop);
                });
                return;
            } catch (FalhaAbertura f) {
                motivo = f.motivo;
            } catch (Exception e) {
                motivo = FALHA_LEITURA;
            }
            fecharDocumento();
            final int m = motivo;
            principal.post(() -> {
                if (g == geracao && ouvinte != null) ouvinte.aoFalhar(m);
            });
        });
    }

    /** Fecha o documento atual (a view fica vazia). */
    public void fechar() {
        geracao++;
        geracaoAtiva = geracao;
        limparEstado();
        executar(this::fecharDocumento);
    }

    /**
     * Posição de leitura: índice da página na linha de referência (logo abaixo da barra
     * superior) + fração já rolada dessa página.
     */
    public float getPosicao() {
        if (total == 0 || larguraLayout == 0) return posicaoPendente;
        float y = (posY + espacoTopo) / zoom;
        int i = primeiraVisivelEm(y);
        float fracao = (y - topo[i]) / altura[i];
        return i + limitar(fracao, 0f, 0.999f);
    }

    /**
     * Página considerada "atual" (começando em 0): a que ocupa mais espaço na área
     * visível entre as barras. No empate, vale a de cima. No fim do documento, a última.
     */
    public int getPaginaAtual() {
        if (total == 0 || larguraLayout == 0) return (int) posicaoPendente;
        float max = maxPosY();
        if (max > minPosY() + 1 && posY >= max - 1) return total - 1; // chegou ao fim
        float yTopo = (posY + espacoTopo) / zoom;
        float yBase = Math.max(yTopo + 1, (posY + getHeight() - espacoBase) / zoom);
        int melhor = primeiraVisivelEm(yTopo);
        float maiorParte = -1f;
        for (int i = melhor; i < total && topo[i] < yBase; i++) {
            float parte = Math.min(topo[i] + altura[i], yBase) - Math.max(topo[i], yTopo);
            if (parte > maiorParte + 1f) {
                maiorParte = parte;
                melhor = i;
            }
        }
        return melhor;
    }

    /** Vai para a página 'indice' (começando em 0). */
    public void irParaPagina(int indice) {
        if (total == 0 || larguraLayout == 0) return;
        rolagem.forceFinished(true);
        aplicarPosicao(limitar(indice, 0, total - 1));
    }

    /** Fecha o documento e encerra a linha de renderização. Chamar no onDestroy. */
    public void liberar() {
        geracao++;
        geracaoAtiva = geracao;
        removeCallbacks(aoAssentar);
        cancelarAnimacaoZoom();
        executar(this::fecharDocumento);
        trabalho.shutdown();
    }

    // ======================= Abertura (linha de renderização) =======================

    private void abrirRenderer(ContentResolver resolver, Uri uri, File pastaTemp) throws FalhaAbertura {
        try {
            descritor = resolver.openFileDescriptor(uri, "r");
        } catch (Exception e) {
            throw new FalhaAbertura(FALHA_ACESSO);
        }
        if (descritor == null) throw new FalhaAbertura(FALHA_ACESSO);
        try {
            renderer = new PdfRenderer(descritor);
            return;
        } catch (SecurityException e) {
            throw new FalhaAbertura(FALHA_SENHA);
        } catch (IOException e) {
            throw new FalhaAbertura(FALHA_LEITURA);
        } catch (RuntimeException e) {
            // O app que entregou o arquivo usou um fluxo sem acesso aleatório, que o
            // PdfRenderer não aceita. Copia para o cache e abre a cópia.
            fecharDocumento();
        }
        try {
            arquivoTemp = File.createTempFile("doc", ".pdf", pastaTemp);
            try (InputStream entrada = resolver.openInputStream(uri);
                 OutputStream saida = new FileOutputStream(arquivoTemp)) {
                if (entrada == null) throw new IOException("sem fluxo de leitura");
                byte[] buffer = new byte[64 * 1024];
                int lidos;
                while ((lidos = entrada.read(buffer)) > 0) {
                    saida.write(buffer, 0, lidos);
                }
            }
            descritor = ParcelFileDescriptor.open(arquivoTemp, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (Exception e) {
            throw new FalhaAbertura(FALHA_ACESSO);
        }
        try {
            renderer = new PdfRenderer(descritor);
        } catch (SecurityException e) {
            throw new FalhaAbertura(FALHA_SENHA);
        } catch (Exception e) {
            throw new FalhaAbertura(FALHA_LEITURA);
        }
    }

    private void fecharDocumento() {
        if (renderer != null) {
            try {
                renderer.close();
            } catch (Exception ignorada) {
                // nada a fazer
            }
            renderer = null;
        }
        if (descritor != null) {
            try {
                descritor.close();
            } catch (IOException ignorada) {
                // nada a fazer
            }
            descritor = null;
        }
        if (arquivoTemp != null) {
            //noinspection ResultOfMethodCallIgnored
            arquivoTemp.delete();
            arquivoTemp = null;
        }
    }

    /** Desenha uma página (ou um pedaço dela) num bitmap novo. Roda na linha de renderização. */
    private Bitmap renderizar(int indice, int largura, int alturaPx,
                              float larguraPaginaPx, float desvioX, float desvioY) {
        if (renderer == null) return null;
        Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(largura, alturaPx, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError semMemoria) {
            Log.w(TAG, "sem memória para " + largura + "x" + alturaPx);
            return null;
        }
        bitmap.eraseColor(Color.WHITE); // páginas sem fundo viriam transparentes
        try (PdfRenderer.Page pagina = renderer.openPage(indice)) {
            float escala = larguraPaginaPx / pagina.getWidth();
            Matrix matriz = new Matrix();
            matriz.setScale(escala, escala);
            matriz.postTranslate(-desvioX, -desvioY);
            pagina.render(bitmap, null, matriz, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        } catch (Exception e) {
            Log.w(TAG, "falha ao desenhar a página " + (indice + 1), e);
            return null;
        }
        return bitmap;
    }

    private void executar(Runnable tarefa) {
        if (!trabalho.isShutdown()) trabalho.execute(tarefa);
    }

    // ======================= Estado e layout (linha principal) =======================

    private void limparEstado() {
        total = 0;
        proporcao = null;
        topo = null;
        altura = null;
        larguraLayout = 0;
        zoom = 1f;
        posX = 0;
        posY = 0;
        posicaoPendente = 0f;
        paginaInformada = -1;
        rolagem.forceFinished(true);
        cancelarAnimacaoZoom();
        removeCallbacks(aoAssentar);
        cache.evictAll();
        pendentes.clear();
        falhas.clear();
        detalhes = new ArrayList<>();
        invalidate();
    }

    private void documentoPronto(float[] prop) {
        proporcao = prop;
        total = prop.length;
        if (getWidth() > 0) {
            fazerLayout();
            aplicarPosicao(posicaoPendente);
        }
        if (ouvinte != null) ouvinte.aoAbrir(total);
        invalidate();
    }

    private void fazerLayout() {
        larguraLayout = getWidth();
        if (depurar) {
            Log.d(TAG, "layout " + getWidth() + "x" + getHeight() + " insets " + insetEsquerdo
                    + "/" + insetDireito);
        }
        esquerdaPagina = insetEsquerdo + margem;
        larguraPagina = Math.max(1f, larguraLayout - insetEsquerdo - insetDireito - 2 * margem);
        topo = new float[total];
        altura = new float[total];
        float y = margem;
        for (int i = 0; i < total; i++) {
            topo[i] = y;
            altura[i] = larguraPagina * proporcao[i];
            y += altura[i] + margem;
        }
        alturaTotal = y;
        cache.evictAll();
        pendentes.clear();
        falhas.clear();
        detalhes = new ArrayList<>();
        pedidoDetalheAtivo = ++pedidoDetalhe; // descarta recortes do layout antigo
        posX = limitar(posX, 0, maxPosX());
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int larguraAntiga, int alturaAntiga) {
        super.onSizeChanged(w, h, larguraAntiga, alturaAntiga);
        if (total == 0 || w == 0) return;
        if (w != larguraLayout) {
            // primeira medida ou rotação: refaz o layout mantendo o ponto de leitura
            float posicao = getPosicao();
            fazerLayout();
            aplicarPosicao(posicao);
        } else {
            posX = limitar(posX, 0, maxPosX());
            posY = limitar(posY, minPosY(), maxPosY());
            invalidate();
            agendarAssentar();
        }
    }

    private void aplicarPosicao(float posicao) {
        int i = (int) limitar((float) Math.floor(posicao), 0, total - 1);
        float fracao = limitar(posicao - i, 0f, 1f);
        float y = topo[i] + fracao * altura[i];
        if (fracao == 0f) y -= margem; // mostra o respiro acima da página
        posY = limitar(y * zoom - espacoTopo, minPosY(), maxPosY());
        posX = limitar(posX, 0, maxPosX());
        invalidate();
        agendarAssentar();
    }

    private float maxPosX() {
        return Math.max(0f, larguraLayout * zoom - getWidth());
    }

    private float minPosY() {
        return -espacoTopo;
    }

    private float maxPosY() {
        return Math.max(minPosY(), alturaTotal * zoom - getHeight() + espacoBase);
    }

    /** Índice da primeira página cuja parte de baixo fica abaixo de y. */
    private int primeiraVisivelEm(float y) {
        int baixo = 0;
        int alto = total - 1;
        while (baixo < alto) {
            int meio = (baixo + alto) >>> 1;
            if (topo[meio] + altura[meio] <= y) {
                baixo = meio + 1;
            } else {
                alto = meio;
            }
        }
        return baixo;
    }

    private void aplicarCores() {
        if (noturno) {
            // Inverte o claro/escuro, gira o matiz 180° para as cores continuarem
            // parecidas (vermelho segue avermelhado) e suaviza o contraste.
            ColorMatrix m = new ColorMatrix(new float[]{
                    -1, 0, 0, 0, 255,
                    0, -1, 0, 0, 255,
                    0, 0, -1, 0, 255,
                    0, 0, 0, 1, 0});
            m.postConcat(new ColorMatrix(new float[]{
                    -0.574f, 1.430f, 0.144f, 0, 0,
                    0.426f, 0.430f, 0.144f, 0, 0,
                    0.426f, 1.430f, -0.856f, 0, 0,
                    0, 0, 0, 1, 0}));
            float s = 0.86f;
            float o = 24f;
            m.postConcat(new ColorMatrix(new float[]{
                    s, 0, 0, 0, o,
                    0, s, 0, 0, o,
                    0, 0, s, 0, o,
                    0, 0, 0, 1, 0}));
            pintaBitmap.setColorFilter(new ColorMatrixColorFilter(m));
            pintaPagina.setColor(Color.rgb(24, 24, 24));
        } else {
            pintaBitmap.setColorFilter(null);
            pintaPagina.setColor(Color.WHITE);
        }
    }

    // ======================= Desenho =======================

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(corFundo);
        if (total == 0 || larguraLayout == 0) return;

        float y0 = posY / zoom;
        float y1 = (posY + getHeight()) / zoom;
        int primeira = primeiraVisivelEm(y0);
        int ultima = primeira;

        canvas.save();
        canvas.translate(-posX, -posY);
        canvas.scale(zoom, zoom);
        for (int i = primeira; i < total && topo[i] < y1; i++) {
            ultima = i;
            ret.set(esquerdaPagina, topo[i], esquerdaPagina + larguraPagina, topo[i] + altura[i]);
            Bitmap bitmap = cache.get(i);
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null, ret, pintaBitmap);
            } else {
                canvas.drawRect(ret, pintaPagina);
            }
            for (int k = 0; k < detalhes.size(); k++) {
                Detalhe d = detalhes.get(k);
                if (d.pagina == i) canvas.drawBitmap(d.bitmap, null, d.area, pintaBitmap);
            }
        }
        canvas.restore();

        primeiraDesejada = primeira;
        ultimaDesejada = ultima;
        for (int i = primeira; i <= ultima; i++) pedirPagina(i);
        pedirPagina(ultima + 1);
        pedirPagina(primeira - 1);

        informarPagina();
    }

    private void informarPagina() {
        final int atual = getPaginaAtual();
        if (atual == paginaInformada) return;
        paginaInformada = atual;
        final int t = total;
        post(() -> {
            if (ouvinte != null && total == t) ouvinte.aoMudarPagina(atual, t);
        });
    }

    /** Pede a renderização de uma página inteira na resolução da tela (zoom 1). */
    private void pedirPagina(final int i) {
        if (i < 0 || i >= total || pendentes.contains(i) || falhas.contains(i) || cache.get(i) != null) {
            return;
        }
        pendentes.add(i);

        float largura = Math.max(1f, larguraPagina);
        float alturaPx = Math.max(1f, larguraPagina * proporcao[i]);
        float fator = 1f;
        long limiteBytes = cache.maxSize() / 3;
        double bytes = (double) largura * alturaPx * 4;
        if (bytes > limiteBytes) fator = (float) Math.sqrt(limiteBytes / bytes);
        float maiorLado = Math.max(largura, alturaPx) * fator;
        if (maiorLado > LADO_MAX_BITMAP) fator *= LADO_MAX_BITMAP / maiorLado;
        final int w = Math.max(1, (int) (largura * fator));
        final int h = Math.max(1, (int) (alturaPx * fator));
        final int g = geracao;
        final int lw = larguraLayout;
        final float lp = larguraPagina;

        if (depurar) Log.d(TAG, "pedido p" + (i + 1) + " " + w + "x" + h);
        executar(() -> {
            boolean util = g == geracaoAtiva
                    && i >= primeiraDesejada - 2 && i <= ultimaDesejada + 2;
            long t0 = SystemClock.uptimeMillis();
            final Bitmap feito = util ? renderizar(i, w, h, w, 0, 0) : null;
            final boolean tentou = util;
            if (depurar) {
                Log.d(TAG, "p" + (i + 1) + (util ? (feito != null ? " pronta" : " FALHOU") : " pulada")
                        + " em " + (SystemClock.uptimeMillis() - t0) + " ms");
            }
            principal.post(() -> {
                if (g != geracao || lw != larguraLayout || lp != larguraPagina) {
                    if (depurar) Log.d(TAG, "p" + (i + 1) + " descartada (layout mudou)");
                    return;
                }
                pendentes.remove(i);
                if (feito != null) {
                    cache.put(i, feito);
                    invalidate();
                } else if (tentou) {
                    falhas.add(i);
                }
            });
        });
    }

    /**
     * Com zoom, a página inteira em resolução de tela fica borrada. Quando o usuário
     * para de mexer, renderiza só a parte visível na resolução exata do zoom atual.
     */
    private void pedirDetalhes() {
        final int id = ++pedidoDetalhe;
        pedidoDetalheAtivo = id;
        if (total == 0 || larguraLayout == 0 || zoom < 1.05f) {
            if (!detalhes.isEmpty()) {
                detalhes = new ArrayList<>();
                invalidate();
            }
            return;
        }
        final float z = zoom;
        float x0 = posX / z;
        float y0 = posY / z;
        float x1 = (posX + getWidth()) / z;
        float y1 = (posY + getHeight()) / z;

        final List<PedidoDetalhe> pedidos = new ArrayList<>();
        for (int i = primeiraVisivelEm(y0); i < total && topo[i] < y1; i++) {
            RectF area = new RectF(
                    Math.max(esquerdaPagina, x0),
                    Math.max(topo[i], y0),
                    Math.min(esquerdaPagina + larguraPagina, x1),
                    Math.min(topo[i] + altura[i], y1));
            if (area.width() <= 0 || area.height() <= 0) continue;
            int w = (int) Math.ceil(area.width() * z);
            int h = (int) Math.ceil(area.height() * z);
            if (w > LADO_MAX_BITMAP || h > LADO_MAX_BITMAP) continue;
            pedidos.add(new PedidoDetalhe(i, area, w, h, larguraPagina * z,
                    (area.left - esquerdaPagina) * z, (area.top - topo[i]) * z));
        }
        if (pedidos.isEmpty()) return;

        final int g = geracao;
        executar(() -> {
            final List<Detalhe> feitos = new ArrayList<>();
            for (PedidoDetalhe p : pedidos) {
                if (id != pedidoDetalheAtivo || g != geracaoAtiva) return;
                Bitmap b = renderizar(p.pagina, p.largura, p.altura,
                        p.larguraPaginaPx, p.desvioX, p.desvioY);
                if (b != null) feitos.add(new Detalhe(p.pagina, p.area, b));
            }
            principal.post(() -> {
                if (id == pedidoDetalhe && g == geracao) {
                    detalhes = feitos;
                    invalidate();
                }
            });
        });
    }

    // ======================= Toque, rolagem e zoom =======================

    @Override
    public boolean onTouchEvent(MotionEvent evento) {
        if (total == 0 || larguraLayout == 0) return false;
        int acao = evento.getActionMasked();
        if (acao == MotionEvent.ACTION_DOWN) {
            tocando = true;
            houvePinca = false;
            cancelarAnimacaoZoom();
        }
        pinca.onTouchEvent(evento);
        gestos.onTouchEvent(evento);
        if (acao == MotionEvent.ACTION_UP || acao == MotionEvent.ACTION_CANCEL) {
            tocando = false;
            agendarAssentar();
        }
        return true;
    }

    @Override
    public void computeScroll() {
        if (rolagem.computeScrollOffset()) {
            posX = limitar(rolagem.getCurrX(), 0, maxPosX());
            posY = limitar(rolagem.getCurrY(), minPosY(), maxPosY());
            postInvalidateOnAnimation();
            agendarAssentar();
        }
    }

    private void moverPara(float x, float y) {
        posX = limitar(x, 0, maxPosX());
        posY = limitar(y, minPosY(), maxPosY());
        invalidate();
        agendarAssentar();
    }

    private void zoomEm(float novoZoom, float focoX, float focoY) {
        if (total == 0 || larguraLayout == 0) return;
        novoZoom = limitar(novoZoom, 1f, ZOOM_MAX);
        float docX = (posX + focoX) / zoom;
        float docY = (posY + focoY) / zoom;
        zoom = novoZoom;
        posX = limitar(docX * zoom - focoX, 0, maxPosX());
        posY = limitar(docY * zoom - focoY, minPosY(), maxPosY());
        invalidate();
        agendarAssentar();
    }

    private void animarZoom(float alvo, final float focoX, final float focoY) {
        cancelarAnimacaoZoom();
        rolagem.forceFinished(true);
        animacaoZoom = ValueAnimator.ofFloat(zoom, alvo);
        animacaoZoom.setDuration(220);
        animacaoZoom.addUpdateListener(a -> zoomEm((Float) a.getAnimatedValue(), focoX, focoY));
        animacaoZoom.start();
    }

    private void cancelarAnimacaoZoom() {
        if (animacaoZoom != null) {
            animacaoZoom.cancel();
            animacaoZoom = null;
        }
    }

    private void agendarAssentar() {
        removeCallbacks(aoAssentar);
        postDelayed(aoAssentar, 150);
    }

    /** Chamado quando a tela para de se mexer. */
    private void assentar() {
        if (tocando) return; // o fim do toque agenda de novo
        if (!rolagem.isFinished() || (animacaoZoom != null && animacaoZoom.isRunning())) {
            agendarAssentar();
            return;
        }
        pedirDetalhes();
    }

    private static float limitar(float valor, float minimo, float maximo) {
        return Math.max(minimo, Math.min(maximo, valor));
    }
}
