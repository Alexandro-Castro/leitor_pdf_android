package com.alexandro.leitorpdf;

import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.util.LruCache;
import android.util.SparseArray;
import android.view.ActionMode;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mostra as páginas de um PDF. Dois modos:
 * <ul>
 *   <li>contínuo: páginas empilhadas na vertical, na largura da tela;</li>
 *   <li>página por página: uma página por tela, lado a lado, trocando com um deslize.</li>
 * </ul>
 * Também faz zoom, corta margens brancas, destaca resultados de busca, seleciona texto
 * e segue links (estes três só nos aparelhos com recursos de texto).
 *
 * Todo acesso ao PDF ({@link Motor}) acontece numa única linha de execução em segundo
 * plano ("trabalho"). O resto do estado vive na linha principal.
 *
 * Coordenadas: cada página tem um retângulo (px, py, pw, ph) em "coordenadas do
 * documento" com zoom 1. Na tela, um ponto do documento aparece em (doc * zoom - pos).
 * Dentro da página, os textos e links vêm em pontos do PDF (1/72 de polegada).
 */
public class PdfView extends View {

    public static final int FALHA_ACESSO = 1;
    public static final int FALHA_SENHA = 2;
    public static final int FALHA_LEITURA = 3;

    public interface Ouvinte {
        void aoAbrir(int totalPaginas, boolean temTexto);

        void aoFalhar(int motivo);

        void aoMudarPagina(int pagina, int total);

        void aoToque();

        void aoAbrirLink(Uri uri);

        /** atual: índice da ocorrência mostrada (-1 se nenhuma). */
        void aoProgressoBusca(int atual, int encontradas, boolean terminou);
    }

    /** Quem pediu uma miniatura. Os dois métodos rodam na linha principal, exceto aindaPrecisa. */
    public interface PedidoMiniatura {
        /** Chamado na linha de renderização: se devolver false, o pedido é descartado. */
        boolean aindaPrecisa();

        /** Recebe a miniatura (ou null, se foi descartada ou falhou). */
        void receber(Bitmap miniatura);
    }

    private static final float ZOOM_MAX = 5f;
    private static final float ZOOM_TOQUE_DUPLO = 2.5f;
    private static final int LADO_MAX_BITMAP = 4096;
    private static final String TAG = "LeitorPDF";

    private final boolean depurar; // registros detalhados só na versão de teste
    private final float dp;

    // ---------- Linha de renderização (campos usados só dentro de 'trabalho') ----------
    private final ExecutorService trabalho = Executors.newSingleThreadExecutor();
    private Motor motor;
    private ParcelFileDescriptor descritor;
    private File arquivoTemp;

    // Escritos na linha principal e lidos na linha de renderização.
    private volatile int geracaoAtiva;
    private volatile int pedidoDetalheAtivo;
    private volatile int idBuscaAtiva;
    private volatile int primeiraDesejada;
    private volatile int ultimaDesejada;
    private volatile boolean querCorte;

    // ---------- Estado da interface (linha principal) ----------
    private final Handler principal = new Handler(Looper.getMainLooper());
    private Ouvinte ouvinte;
    private int geracao;
    private int pedidoDetalhe;
    private int versaoLayout;
    private int total;              // número de páginas; 0 = nada carregado
    private boolean temTexto;
    private float[] largPts;        // tamanho de cada página em pontos
    private float[] altPts;
    private float[] px, py, pw, ph; // retângulo de cada página no documento (zoom 1)
    private float docLargura;
    private float docAltura;
    private int larguraLayout;      // largura usada no layout (0 = layout pendente)
    private int alturaLayout;
    private final float margem;

    private boolean modoPagina;
    private boolean cortar;         // opção "cortar margens" ligada
    private RectF corte;            // área útil das páginas, em frações (null = sem corte)
    private boolean corteCalculado;
    private float cl, ct, cr = 1f, cb = 1f; // corte em uso

    private float espacoTopo;       // altura da barra superior (pixels de tela)
    private float espacoBase;       // altura da barra inferior
    private int insetEsquerdo;      // laterais ocupadas pelo sistema (entalhe, botões)
    private int insetDireito;

    private float zoom = 1f;
    private float posX;
    private float posY;
    private float posicaoPendente;
    private boolean tocando;
    private boolean houvePinca;
    private boolean houveFling;
    private int paginaNoToque;      // modo página: página quando o dedo encostou
    private int paginaInformada = -1;
    private boolean noturno;
    private int corFundo = 0xFFDCD9D5;
    private int primeiraVisivel;
    private int ultimaVisivel;

    private final LruCache<Integer, Bitmap> cache;
    private final Set<Integer> naCache = new HashSet<>(); // consultar sem mexer na ordem do cache
    private final Set<Integer> pendentes = new HashSet<>();
    private final Set<Integer> falhas = new HashSet<>();
    private List<Detalhe> detalhes = new ArrayList<>();
    private boolean paginasReduzidas; // alguma página foi desenhada abaixo da resolução da tela

    // Links (por página) e marcadores
    private final SparseArray<List<Motor.Link>> links = new SparseArray<>();
    private Set<Integer> marcadas = Collections.emptySet();

    // Busca
    private int idBusca;
    private final List<Ocorrencia> ocorrencias = new ArrayList<>();
    private final SparseArray<List<Ocorrencia>> ocorrenciasPorPagina = new SparseArray<>();
    private int ocorrenciaAtual = -1;
    private boolean buscaTerminou = true;
    private int paginaInicioBusca;

    // Seleção de texto
    private Motor.Selecao selecao;
    private ActionMode menuSelecao;
    private boolean arrastandoSelecao;  // depois do toque longo, arrastar estende a seleção
    private int alcaArrastada;          // 0 nenhuma, 1 início, 2 fim
    private PointF ancora;              // ponta fixa da seleção (pontos da página)
    private boolean selecaoEmAndamento;
    private PointF[] selecaoPendente;   // último pedido que chegou durante outro
    private int idSelecao;

    private final Paint pintaBitmap = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pintaPagina = new Paint();
    private final Paint pintaOcorrencia = new Paint();
    private final Paint pintaOcorrenciaAtual = new Paint();
    private final Paint pintaSelecao = new Paint();
    private final Paint pintaAlca = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pintaFita = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path fita = new Path();
    private final RectF ret = new RectF();
    private final RectF ret2 = new RectF();
    private final float[] tamanhoTemp = new float[2];

    private final OverScroller rolagem;
    private final ScaleGestureDetector pinca;
    private final GestureDetector gestos;
    private ValueAnimator animacaoZoom;
    private final Runnable aoAssentar = this::assentar;

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
        final float escala;
        final float desvioX;
        final float desvioY;

        PedidoDetalhe(int pagina, RectF area, int largura, int altura,
                      float escala, float desvioX, float desvioY) {
            this.pagina = pagina;
            this.area = area;
            this.largura = largura;
            this.altura = altura;
            this.escala = escala;
            this.desvioX = desvioX;
            this.desvioY = desvioY;
        }
    }

    private static final class Ocorrencia {
        final int pagina;
        final List<RectF> areas;

        Ocorrencia(int pagina, List<RectF> areas) {
            this.pagina = pagina;
            this.areas = areas;
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
        dp = contexto.getResources().getDisplayMetrics().density;
        margem = 6 * dp;
        depurar = (contexto.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;

        int maxBytes = (int) Math.min(Runtime.getRuntime().maxMemory() / 4, Integer.MAX_VALUE);
        cache = new LruCache<Integer, Bitmap>(maxBytes) {
            @Override
            protected int sizeOf(Integer chave, Bitmap bitmap) {
                return bitmap.getByteCount();
            }

            @Override
            protected void entryRemoved(boolean expulso, Integer chave, Bitmap antigo, Bitmap novo) {
                if (novo == null) naCache.remove(chave);
            }
        };

        pintaOcorrencia.setColor(0x66FFC107);
        pintaOcorrenciaAtual.setColor(0x99FF6D00);
        pintaSelecao.setColor(0x552196F3);
        pintaAlca.setColor(0xFF2196F3);

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
                houveFling = true;
                if (modoPagina && zoom < 1.05f) {
                    // um deslize rápido para o lado vira uma página a partir de onde o dedo começou
                    int alvo = getPaginaAtual();
                    if (Math.abs(vx) > Math.abs(vy) && Math.abs(vx) > 300 * dp) {
                        alvo = vx < 0 ? paginaNoToque + 1 : paginaNoToque - 1;
                    }
                    animarParaPagina(alvo);
                    return true;
                }
                rolagem.fling((int) posX, (int) posY, (int) -vx, (int) -vy,
                        (int) minPosX(), (int) maxPosX(), (int) minPosY(), (int) maxPosY());
                postInvalidateOnAnimation();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                iniciarSelecao(e.getX(), e.getY());
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                tocou(e.getX(), e.getY());
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

    public boolean temTexto() {
        return temTexto;
    }

    public int getTotalPaginas() {
        return total;
    }

    public boolean isModoPagina() {
        return modoPagina;
    }

    public void setNoturno(boolean noturno) {
        this.noturno = noturno;
        aplicarCores();
        invalidate();
    }

    /** Filtro de cor do modo noturno (para as miniaturas), ou null. */
    public ColorFilter filtroNoturno() {
        return pintaBitmap.getColorFilter();
    }

    public void setCorFundo(int cor) {
        corFundo = cor;
        invalidate();
    }

    public void setMarcadas(Set<Integer> paginas) {
        marcadas = paginas;
        invalidate();
    }

    /** Alturas das barras que ficam por cima do documento, em pixels. */
    public void setEspacos(float topo, float base) {
        if (topo == espacoTopo && base == espacoBase) return;
        boolean temLayout = total > 0 && larguraLayout > 0;
        float posicao = getPosicao();
        espacoTopo = topo;
        espacoBase = base;
        if (!temLayout) return;
        if (modoPagina) fazerLayout(); // as páginas cabem entre as barras
        aplicarPosicao(posicao);
    }

    /** Áreas nas laterais da tela cobertas pelo sistema (entalhe da câmera, botões). */
    public void setInsetsLaterais(int esquerdo, int direito) {
        if (esquerdo == insetEsquerdo && direito == insetDireito) return;
        insetEsquerdo = esquerdo;
        insetDireito = direito;
        refazerLayoutMantendoPosicao();
    }

    public void setModoPagina(boolean pagina) {
        if (pagina == modoPagina) return;
        int atual = getPaginaAtual();
        modoPagina = pagina;
        zoom = 1f;
        posX = 0;
        posY = 0;
        if (total > 0 && larguraLayout > 0) {
            fazerLayout();
            aplicarPosicao(atual);
        } else {
            posicaoPendente = atual;
        }
    }

    public void setCortarMargens(boolean ligado) {
        if (ligado == cortar) return;
        cortar = ligado;
        querCorte = ligado;
        if (ligado && total > 0 && !corteCalculado) {
            calcularCorteDepois();
            return; // o layout é refeito quando o cálculo terminar
        }
        aplicarCorte();
    }

    /**
     * Abre um PDF e posiciona na 'posicaoInicial' (formato de getPosicao).
     * O resultado chega por Ouvinte.aoAbrir ou Ouvinte.aoFalhar.
     */
    public void abrir(final ContentResolver resolver, final Uri uri, final File pastaTemp,
                      float posicaoInicial, final String senha) {
        final int g = ++geracao;
        geracaoAtiva = g;
        limparEstado();
        posicaoPendente = Math.max(0f, posicaoInicial);
        final boolean calcularCorte = cortar;
        executar(() -> {
            fecharDocumento();
            int motivo;
            try {
                abrirMotor(resolver, uri, pastaTemp, senha);
                int n = motor.paginas();
                if (n <= 0) throw new FalhaAbertura(FALHA_LEITURA);
                if (depurar) Log.d(TAG, "motor " + motor.nome() + ", " + n + " páginas");
                final float[] larguras = new float[n];
                final float[] alturas = new float[n];
                float[] t = new float[2];
                for (int i = 0; i < n; i++) {
                    if (g != geracaoAtiva) return; // outro arquivo foi pedido nesse meio tempo
                    motor.tamanho(i, t);
                    boolean valido = t[0] > 0 && t[1] > 0;
                    larguras[i] = valido ? t[0] : 595f;
                    alturas[i] = valido ? t[1] : 842f;
                }
                final RectF areaUtil = calcularCorte ? medirMargens(larguras, alturas) : null;
                final boolean texto = motor.temTexto();
                principal.post(() -> {
                    if (g == geracao) documentoPronto(larguras, alturas, texto, calcularCorte, areaUtil);
                });
                return;
            } catch (FalhaAbertura f) {
                motivo = f.motivo;
            } catch (Exception e) {
                Log.w(TAG, "falha ao abrir", e);
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
     * Posição de leitura. No modo contínuo: índice da página na linha de referência
     * (logo abaixo da barra superior) + fração já rolada dela. No modo página: o índice.
     */
    public float getPosicao() {
        if (total == 0 || larguraLayout == 0) return posicaoPendente;
        if (modoPagina) return getPaginaAtual();
        float y = (posY + espacoTopo) / zoom;
        int i = primeiraEmY(y);
        float fracao = (y - py[i]) / ph[i];
        return i + limitar(fracao, 0f, 0.999f);
    }

    /**
     * Página considerada "atual" (começando em 0). Modo contínuo: a que ocupa mais espaço
     * entre as barras (no fim do documento, a última). Modo página: a do centro da tela.
     */
    public int getPaginaAtual() {
        if (total == 0 || larguraLayout == 0) return (int) posicaoPendente;
        if (modoPagina) {
            float centro = (posX + getWidth() / 2f) / zoom;
            return (int) limitar((float) Math.floor(centro / larguraLayout), 0, total - 1);
        }
        float max = maxPosY();
        if (max > minPosY() + 1 && posY >= max - 1) return total - 1;
        float yTopo = (posY + espacoTopo) / zoom;
        float yBase = Math.max(yTopo + 1, (posY + getHeight() - espacoBase) / zoom);
        int melhor = primeiraEmY(yTopo);
        float maiorParte = -1f;
        for (int i = melhor; i < total && py[i] < yBase; i++) {
            float parte = Math.min(py[i] + ph[i], yBase) - Math.max(py[i], yTopo);
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

    /** Avança (1) ou volta (-1) uma tela, como pelas teclas de volume. */
    public void avancar(int direcao) {
        if (total == 0 || larguraLayout == 0) return;
        if (modoPagina) {
            animarParaPagina(getPaginaAtual() + direcao);
            return;
        }
        rolagem.forceFinished(true);
        float passo = (getHeight() - espacoTopo - espacoBase) * 0.9f * direcao;
        float alvo = limitar(posY + passo, minPosY(), maxPosY());
        rolagem.startScroll((int) posX, (int) posY, 0, (int) (alvo - posY), 250);
        postInvalidateOnAnimation();
    }

    /** Fecha o documento e encerra a linha de renderização. Chamar no onDestroy. */
    public void liberar() {
        geracao++;
        geracaoAtiva = geracao;
        idBuscaAtiva = ++idBusca;
        removeCallbacks(aoAssentar);
        cancelarAnimacaoZoom();
        fecharMenuSelecao();
        executar(this::fecharDocumento);
        trabalho.shutdown();
    }

    /** Pede uma miniatura da página (sem corte de margens), com 'largura' pixels. */
    public void pedirMiniatura(final int pagina, int largura, final PedidoMiniatura pedido) {
        if (total == 0 || pagina < 0 || pagina >= total) {
            pedido.receber(null);
            return;
        }
        final int g = geracao;
        final int w = Math.max(1, largura);
        final int h = Math.max(1, Math.round(w * altPts[pagina] / largPts[pagina]));
        final float escala = w / largPts[pagina];
        executar(() -> {
            Bitmap b = null;
            if (g == geracaoAtiva && pedido.aindaPrecisa()) b = renderizar(pagina, w, h, escala, 0, 0);
            final Bitmap feito = b;
            principal.post(() -> pedido.receber(g == geracao ? feito : null));
        });
    }

    // ======================= Busca =======================

    /** Procura o termo em todas as páginas. O progresso chega por aoProgressoBusca. */
    public void buscar(String termo) {
        limparBusca();
        final String t = termo == null ? "" : termo.trim();
        if (t.isEmpty() || !temTexto || total == 0) {
            if (ouvinte != null) ouvinte.aoProgressoBusca(-1, 0, true);
            return;
        }
        final int id = ++idBusca;
        idBuscaAtiva = id;
        buscaTerminou = false;
        paginaInicioBusca = getPaginaAtual();
        buscarLote(id, t, 0, total);
        if (ouvinte != null) ouvinte.aoProgressoBusca(-1, 0, false);
    }

    /** Vai para a próxima (1) ou anterior (-1) ocorrência. */
    public void proximaOcorrencia(int direcao) {
        int n = ocorrencias.size();
        if (n == 0) return;
        int alvo = ocorrenciaAtual < 0 ? 0 : ((ocorrenciaAtual + direcao) % n + n) % n;
        mostrarOcorrencia(alvo);
        notificarBusca();
    }

    public void limparBusca() {
        idBuscaAtiva = ++idBusca;
        ocorrencias.clear();
        ocorrenciasPorPagina.clear();
        ocorrenciaAtual = -1;
        buscaTerminou = true;
        invalidate();
    }

    private void buscarLote(final int id, final String termo, final int inicio, final int n) {
        executar(() -> {
            if (id != idBuscaAtiva || motor == null) return;
            final List<Ocorrencia> achadas = new ArrayList<>();
            final int fim = Math.min(n, inicio + 6);
            for (int i = inicio; i < fim; i++) {
                if (id != idBuscaAtiva) return;
                try {
                    for (List<RectF> areas : motor.buscar(i, termo)) {
                        if (!areas.isEmpty()) achadas.add(new Ocorrencia(i, areas));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "falha na busca, página " + (i + 1), e);
                }
            }
            final boolean acabou = fim >= n;
            principal.post(() -> receberBusca(id, achadas, acabou));
            // Os lotes voltam para o fim da fila, para a busca não travar o desenho das páginas.
            if (!acabou) buscarLote(id, termo, fim, n);
        });
    }

    private void receberBusca(int id, List<Ocorrencia> achadas, boolean acabou) {
        if (id != idBusca) return;
        for (Ocorrencia o : achadas) {
            ocorrencias.add(o);
            List<Ocorrencia> daPagina = ocorrenciasPorPagina.get(o.pagina);
            if (daPagina == null) {
                daPagina = new ArrayList<>();
                ocorrenciasPorPagina.put(o.pagina, daPagina);
            }
            daPagina.add(o);
        }
        buscaTerminou = acabou;
        if (ocorrenciaAtual < 0) {
            // primeira ocorrência a partir da página em que a busca começou
            for (int k = 0; k < ocorrencias.size(); k++) {
                if (ocorrencias.get(k).pagina >= paginaInicioBusca) {
                    mostrarOcorrencia(k);
                    break;
                }
            }
            if (ocorrenciaAtual < 0 && acabou && !ocorrencias.isEmpty()) mostrarOcorrencia(0);
        }
        notificarBusca();
        invalidate();
    }

    private void notificarBusca() {
        if (ouvinte != null) ouvinte.aoProgressoBusca(ocorrenciaAtual, ocorrencias.size(), buscaTerminou);
    }

    private void mostrarOcorrencia(int k) {
        ocorrenciaAtual = k;
        Ocorrencia o = ocorrencias.get(k);
        RectF uniao = new RectF(o.areas.get(0));
        for (RectF r : o.areas) uniao.union(r);
        mostrarArea(o.pagina, uniao);
        invalidate();
    }

    /** Rola para que a área (em pontos da página) fique visível. */
    private void mostrarArea(int pagina, RectF areaPts) {
        if (larguraLayout == 0) return;
        rolagem.forceFinished(true);
        paraDoc(pagina, areaPts, ret2);
        float esq = ret2.left * zoom, dir = ret2.right * zoom;
        float cima = ret2.top * zoom, baixo = ret2.bottom * zoom;
        float visivelCima = posY + espacoTopo;
        float visivelBaixo = posY + getHeight() - espacoBase;
        if (modoPagina && pagina != getPaginaAtual()) {
            aplicarPosicao(pagina);
            visivelCima = posY + espacoTopo;
            visivelBaixo = posY + getHeight() - espacoBase;
        }
        if (cima < visivelCima || baixo > visivelBaixo) {
            float alturaUtil = getHeight() - espacoTopo - espacoBase;
            posY = limitar(cima - espacoTopo - alturaUtil * 0.3f, minPosY(), maxPosY());
        }
        if (esq < posX || dir > posX + getWidth()) {
            posX = limitar((esq + dir) / 2f - getWidth() / 2f, minPosX(), maxPosX());
        }
        invalidate();
        agendarAssentar();
    }

    // ======================= Abertura (linha de renderização) =======================

    private void abrirMotor(ContentResolver resolver, Uri uri, File pastaTemp, String senha)
            throws FalhaAbertura {
        try {
            descritor = resolver.openFileDescriptor(uri, "r");
        } catch (Exception e) {
            throw new FalhaAbertura(FALHA_ACESSO);
        }
        if (descritor == null) throw new FalhaAbertura(FALHA_ACESSO);
        try {
            motor = Motor.abrir(descritor, senha);
            return;
        } catch (SecurityException e) {
            throw new FalhaAbertura(FALHA_SENHA);
        } catch (IOException e) {
            throw new FalhaAbertura(FALHA_LEITURA);
        } catch (RuntimeException e) {
            // O app que entregou o arquivo usou um fluxo sem acesso aleatório, que o
            // leitor do Android não aceita. Copia para o cache e abre a cópia.
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
            motor = Motor.abrir(descritor, senha);
        } catch (SecurityException e) {
            throw new FalhaAbertura(FALHA_SENHA);
        } catch (Exception e) {
            throw new FalhaAbertura(FALHA_LEITURA);
        }
    }

    private void fecharDocumento() {
        if (motor != null) {
            try {
                motor.close();
            } catch (Exception ignorada) {
                // nada a fazer
            }
            motor = null;
            descritor = null; // o motor fecha o descritor junto
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

    /**
     * Desenha a página num bitmap novo. 'escala' converte pontos em pixels; 'desvio' é o
     * canto do bitmap dentro da página inteira já escalada. Roda na linha de renderização.
     */
    private Bitmap renderizar(int indice, int largura, int alturaPx,
                              float escala, float desvioX, float desvioY) {
        if (motor == null) return null;
        Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(largura, alturaPx, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError semMemoria) {
            Log.w(TAG, "sem memória para " + largura + "x" + alturaPx);
            return null;
        }
        bitmap.eraseColor(Color.WHITE); // páginas sem fundo viriam transparentes
        try {
            Matrix matriz = new Matrix();
            matriz.setScale(escala, escala);
            matriz.postTranslate(-desvioX, -desvioY);
            motor.desenhar(indice, bitmap, matriz);
        } catch (Exception e) {
            Log.w(TAG, "falha ao desenhar a página " + (indice + 1), e);
            return null;
        }
        return bitmap;
    }

    /**
     * Mede as margens brancas em algumas páginas e devolve a área com conteúdo, em
     * frações da página (a mesma para todas, para a largura não pular de uma página
     * para outra), ou null se não houver margem que valha cortar.
     */
    private RectF medirMargens(float[] larguras, float[] alturas) {
        int n = larguras.length;
        List<Integer> amostras = new ArrayList<>();
        if (n <= 12) {
            for (int i = 0; i < n; i++) amostras.add(i);
        } else {
            for (int k = 0; k < 12; k++) amostras.add(1 + (n - 2) * k / 11); // pula a capa
        }
        float l = 1f, t = 1f, r = 0f, b = 0f;
        boolean algum = false;
        int w = 160;
        for (int i : amostras) {
            int h = (int) Math.max(1, Math.min(800, Math.round(w * alturas[i] / larguras[i])));
            Bitmap bm = renderizar(i, w, h, w / larguras[i], 0, 0);
            if (bm == null) continue;
            int[] cores = new int[w * h];
            bm.getPixels(cores, 0, w, 0, 0, w, h);
            bm.recycle();
            int x0 = w, y0 = h, x1 = -1, y1 = -1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int c = cores[y * w + x];
                    int lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
                    if (lum < 225) {
                        if (x < x0) x0 = x;
                        if (x > x1) x1 = x;
                        if (y < y0) y0 = y;
                        if (y > y1) y1 = y;
                    }
                }
            }
            if (x1 < 0) continue; // página em branco
            float fl = x0 / (float) w, fr = (x1 + 1) / (float) w;
            float ft = y0 / (float) h, fb = (y1 + 1) / (float) h;
            if (fr - fl > 0.96f && fb - ft > 0.96f) continue; // imagem ou página escaneada
            l = Math.min(l, fl);
            t = Math.min(t, ft);
            r = Math.max(r, fr);
            b = Math.max(b, fb);
            algum = true;
        }
        if (!algum) return null;
        float folga = 0.02f;
        l = Math.max(0f, l - folga);
        t = Math.max(0f, t - folga);
        r = Math.min(1f, r + folga);
        b = Math.min(1f, b + folga);
        if (r - l > 0.95f && b - t > 0.95f) return null;
        if (depurar) Log.d(TAG, "margens: " + l + " " + t + " " + r + " " + b);
        return new RectF(l, t, r, b);
    }

    private void calcularCorteDepois() {
        final int g = geracao;
        final float[] larguras = largPts;
        final float[] alturas = altPts;
        executar(() -> {
            if (g != geracaoAtiva || motor == null) return;
            final RectF areaUtil = medirMargens(larguras, alturas);
            principal.post(() -> {
                if (g != geracao) return;
                corte = areaUtil;
                corteCalculado = true;
                aplicarCorte();
            });
        });
    }

    private void executar(Runnable tarefa) {
        if (!trabalho.isShutdown()) trabalho.execute(tarefa);
    }

    // ======================= Estado e layout (linha principal) =======================

    private void limparEstado() {
        total = 0;
        temTexto = false;
        largPts = null;
        altPts = null;
        px = py = pw = ph = null;
        larguraLayout = 0;
        zoom = 1f;
        posX = 0;
        posY = 0;
        posicaoPendente = 0f;
        paginaInformada = -1;
        corte = null;
        corteCalculado = false;
        rolagem.forceFinished(true);
        cancelarAnimacaoZoom();
        removeCallbacks(aoAssentar);
        cache.evictAll();
        pendentes.clear();
        falhas.clear();
        detalhes = new ArrayList<>();
        paginasReduzidas = false;
        links.clear();
        marcadas = Collections.emptySet();
        limparBusca();
        limparSelecao();
        invalidate();
    }

    private void documentoPronto(float[] larguras, float[] alturas, boolean texto,
                                 boolean corteMedido, RectF areaUtil) {
        largPts = larguras;
        altPts = alturas;
        total = larguras.length;
        temTexto = texto;
        if (corteMedido) {
            corte = areaUtil;
            corteCalculado = true;
        } else if (cortar) {
            calcularCorteDepois(); // a opção foi ligada durante a abertura
        }
        atualizarCorteEmUso();
        if (getWidth() > 0) {
            fazerLayout();
            aplicarPosicao(posicaoPendente);
        }
        if (ouvinte != null) ouvinte.aoAbrir(total, temTexto);
        invalidate();
    }

    private void atualizarCorteEmUso() {
        if (cortar && corte != null) {
            cl = corte.left;
            ct = corte.top;
            cr = corte.right;
            cb = corte.bottom;
        } else {
            cl = 0f;
            ct = 0f;
            cr = 1f;
            cb = 1f;
        }
    }

    private void aplicarCorte() {
        float antes = cl + ct + cr + cb;
        atualizarCorteEmUso();
        if (antes != cl + ct + cr + cb) refazerLayoutMantendoPosicao();
    }

    private void refazerLayoutMantendoPosicao() {
        if (total == 0 || larguraLayout == 0) return;
        float posicao = getPosicao();
        fazerLayout();
        aplicarPosicao(posicao);
    }

    /** Altura / largura da área visível da página (já com o corte). */
    private float aspecto(int i) {
        return (altPts[i] * (cb - ct)) / (largPts[i] * (cr - cl));
    }

    private void fazerLayout() {
        larguraLayout = getWidth();
        alturaLayout = getHeight();
        versaoLayout++;
        int n = total;
        px = new float[n];
        py = new float[n];
        pw = new float[n];
        ph = new float[n];
        float larguraUtil = Math.max(1f, larguraLayout - insetEsquerdo - insetDireito - 2 * margem);
        if (!modoPagina) {
            float y = margem;
            for (int i = 0; i < n; i++) {
                px[i] = insetEsquerdo + margem;
                py[i] = y;
                pw[i] = larguraUtil;
                ph[i] = larguraUtil * aspecto(i);
                y += ph[i] + margem;
            }
            docLargura = larguraLayout;
            docAltura = y;
        } else {
            float alturaUtil = Math.max(1f, alturaLayout - espacoTopo - espacoBase - 2 * margem);
            for (int i = 0; i < n; i++) {
                float a = aspecto(i);
                float w = larguraUtil;
                float h = w * a;
                if (h > alturaUtil) {
                    h = alturaUtil;
                    w = h / a;
                }
                px[i] = i * (float) larguraLayout + insetEsquerdo + margem + (larguraUtil - w) / 2f;
                py[i] = espacoTopo + margem + (alturaUtil - h) / 2f;
                pw[i] = w;
                ph[i] = h;
            }
            docLargura = n * (float) larguraLayout;
            docAltura = alturaLayout;
        }
        if (depurar) {
            Log.d(TAG, "layout " + larguraLayout + "x" + alturaLayout + (modoPagina ? " página" : " contínuo")
                    + " corte " + cl + "," + ct + "," + cr + "," + cb);
        }
        cache.evictAll();
        pendentes.clear();
        falhas.clear();
        detalhes = new ArrayList<>();
        paginasReduzidas = false;
        pedidoDetalheAtivo = ++pedidoDetalhe; // descarta recortes do layout antigo
        posX = limitar(posX, minPosX(), maxPosX());
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int larguraAntiga, int alturaAntiga) {
        super.onSizeChanged(w, h, larguraAntiga, alturaAntiga);
        if (total == 0 || w == 0) return;
        if (w != larguraLayout || (modoPagina && h != alturaLayout)) {
            // primeira medida ou rotação: refaz o layout mantendo o ponto de leitura
            float posicao = getPosicao();
            fazerLayout();
            aplicarPosicao(posicao);
        } else {
            posX = limitar(posX, minPosX(), maxPosX());
            posY = limitar(posY, minPosY(), maxPosY());
            invalidate();
            agendarAssentar();
        }
    }

    private void aplicarPosicao(float posicao) {
        int i = (int) limitar((float) Math.floor(posicao), 0, total - 1);
        if (modoPagina) {
            posX = alvoX(i);
            posY = limitar(posY, minPosY(), maxPosY());
        } else {
            float fracao = limitar(posicao - i, 0f, 1f);
            float y = py[i] + fracao * ph[i];
            if (fracao == 0f) y -= margem; // mostra o respiro acima da página
            posY = limitar(y * zoom - espacoTopo, minPosY(), maxPosY());
            posX = limitar(posX, minPosX(), maxPosX());
        }
        invalidate();
        agendarAssentar();
    }

    private float minPosX() {
        return 0f;
    }

    /** Modo página: posição horizontal que mostra a página centralizada. */
    private float alvoX(int pagina) {
        return limitar(pagina * larguraLayout * zoom + (zoom - 1f) * larguraLayout / 2f,
                minPosX(), maxPosX());
    }

    private float maxPosX() {
        return Math.max(0f, docLargura * zoom - getWidth());
    }

    private float minPosY() {
        return modoPagina ? 0f : -espacoTopo;
    }

    private float maxPosY() {
        if (modoPagina) return Math.max(0f, docAltura * zoom - getHeight());
        return Math.max(minPosY(), docAltura * zoom - getHeight() + espacoBase);
    }

    /** Modo contínuo: índice da primeira página cuja parte de baixo fica abaixo de y. */
    private int primeiraEmY(float y) {
        int baixo = 0;
        int alto = total - 1;
        while (baixo < alto) {
            int meio = (baixo + alto) >>> 1;
            if (py[meio] + ph[meio] <= y) {
                baixo = meio + 1;
            } else {
                alto = meio;
            }
        }
        return baixo;
    }

    /** Calcula primeiraVisivel/ultimaVisivel para a janela dada (coordenadas do documento). */
    private void calcularVisiveis(float x0, float y0, float x1, float y1) {
        if (modoPagina) {
            primeiraVisivel = (int) limitar((float) Math.floor(x0 / larguraLayout), 0, total - 1);
            ultimaVisivel = (int) limitar((float) Math.floor((x1 - 0.5f) / larguraLayout), 0, total - 1);
        } else {
            primeiraVisivel = primeiraEmY(y0);
            int u = primeiraVisivel;
            while (u + 1 < total && py[u + 1] < y1) u++;
            ultimaVisivel = u;
        }
    }

    /** Página sob o ponto da tela, ou -1. Preenche 'pts' com o ponto em pontos da página. */
    private int paginaNoPonto(float sx, float sy, PointF pts) {
        if (total == 0 || larguraLayout == 0) return -1;
        float xd = (sx + posX) / zoom;
        float yd = (sy + posY) / zoom;
        int i;
        if (modoPagina) {
            i = (int) Math.floor(xd / larguraLayout);
        } else {
            i = primeiraEmY(yd);
        }
        if (i < 0 || i >= total) return -1;
        if (xd < px[i] || xd > px[i] + pw[i] || yd < py[i] || yd > py[i] + ph[i]) return -1;
        float k = escalaPagina(i);
        pts.set((xd - px[i]) / k + cl * largPts[i], (yd - py[i]) / k + ct * altPts[i]);
        return i;
    }

    /** Ponto da tela convertido para pontos da página i (sem checar se está dentro). */
    private void pontoNaPagina(int i, float sx, float sy, PointF pts) {
        float xd = (sx + posX) / zoom;
        float yd = (sy + posY) / zoom;
        float k = escalaPagina(i);
        float x = (xd - px[i]) / k + cl * largPts[i];
        float y = (yd - py[i]) / k + ct * altPts[i];
        pts.set(limitar(x, 0, largPts[i]), limitar(y, 0, altPts[i]));
    }

    /** Unidades do documento por ponto da página. */
    private float escalaPagina(int i) {
        return pw[i] / (largPts[i] * (cr - cl));
    }

    /** Converte um retângulo em pontos da página para coordenadas do documento. */
    private void paraDoc(int i, RectF pts, RectF saida) {
        float k = escalaPagina(i);
        float ox = cl * largPts[i];
        float oy = ct * altPts[i];
        saida.set(px[i] + (pts.left - ox) * k, py[i] + (pts.top - oy) * k,
                px[i] + (pts.right - ox) * k, py[i] + (pts.bottom - oy) * k);
        saida.sort();
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

        float x0 = posX / zoom;
        float y0 = posY / zoom;
        float x1 = (posX + getWidth()) / zoom;
        float y1 = (posY + getHeight()) / zoom;
        calcularVisiveis(x0, y0, x1, y1);
        int primeira = primeiraVisivel;
        int ultima = ultimaVisivel;

        canvas.save();
        canvas.translate(-posX, -posY);
        canvas.scale(zoom, zoom);
        for (int i = primeira; i <= ultima; i++) {
            ret.set(px[i], py[i], px[i] + pw[i], py[i] + ph[i]);
            if (ret.right < x0 || ret.left > x1 || ret.bottom < y0 || ret.top > y1) continue;
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
            canvas.save();
            canvas.clipRect(ret);
            desenharOcorrencias(canvas, i);
            if (selecao != null && selecao.pagina == i) {
                for (RectF a : selecao.areas) {
                    paraDoc(i, a, ret2);
                    canvas.drawRect(ret2, pintaSelecao);
                }
            }
            canvas.restore();
        }
        canvas.restore();

        desenharFitas(canvas, primeira, ultima);
        desenharAlcas(canvas);

        primeiraDesejada = primeira;
        ultimaDesejada = ultima;
        for (int i = primeira; i <= ultima; i++) pedirPagina(i);
        pedirPagina(ultima + 1);
        pedirPagina(primeira - 1);

        informarPagina();
        if (menuSelecao != null) menuSelecao.invalidateContentRect();
    }

    private void desenharOcorrencias(Canvas canvas, int i) {
        List<Ocorrencia> daPagina = ocorrenciasPorPagina.get(i);
        if (daPagina == null) return;
        Ocorrencia atual = ocorrenciaAtual >= 0 ? ocorrencias.get(ocorrenciaAtual) : null;
        for (Ocorrencia o : daPagina) {
            Paint p = o == atual ? pintaOcorrenciaAtual : pintaOcorrencia;
            for (RectF a : o.areas) {
                paraDoc(i, a, ret2);
                ret2.inset(-1f, -1f);
                canvas.drawRect(ret2, p);
            }
        }
    }

    /** Fitinha no canto das páginas marcadas, sempre do mesmo tamanho na tela. */
    private void desenharFitas(Canvas canvas, int primeira, int ultima) {
        if (marcadas.isEmpty()) return;
        pintaFita.setColor(noturno ? 0xFFFF8A7A : 0xFFC0362C);
        float largura = 12 * dp;
        float altura = 22 * dp;
        for (int i = primeira; i <= ultima; i++) {
            if (!marcadas.contains(i)) continue;
            float direita = (px[i] + pw[i]) * zoom - posX - 14 * dp;
            float topo = py[i] * zoom - posY;
            fita.reset();
            fita.moveTo(direita - largura, topo);
            fita.lineTo(direita, topo);
            fita.lineTo(direita, topo + altura);
            fita.lineTo(direita - largura / 2f, topo + altura - largura / 2f);
            fita.lineTo(direita - largura, topo + altura);
            fita.close();
            canvas.drawPath(fita, pintaFita);
        }
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
        if (i < 0 || i >= total || pendentes.contains(i) || falhas.contains(i) || naCache.contains(i)) {
            return;
        }
        pendentes.add(i);

        // Cada página cabe em 1/5 do cache: as visíveis e as vizinhas (pré-carregadas)
        // ficam todas guardadas sem uma expulsar a outra.
        float largura = Math.max(1f, pw[i]);
        float alturaPx = Math.max(1f, ph[i]);
        float fator = 1f;
        long limiteBytes = cache.maxSize() / 5;
        double bytes = (double) largura * alturaPx * 4;
        if (bytes > limiteBytes) fator = (float) Math.sqrt(limiteBytes / bytes);
        float maiorLado = Math.max(largura, alturaPx) * fator;
        if (maiorLado > LADO_MAX_BITMAP) fator *= LADO_MAX_BITMAP / maiorLado;
        final int w = Math.max(1, (int) (largura * fator));
        final int h = Math.max(1, (int) (alturaPx * fator));
        if (fator < 0.999f) paginasReduzidas = true; // a nitidez vem dos recortes de detalhe
        final float escala = w / (largPts[i] * (cr - cl));
        final float desvioX = cl * largPts[i] * escala;
        final float desvioY = ct * altPts[i] * escala;
        final int g = geracao;
        final int vl = versaoLayout;
        final boolean querLinks = temTexto && links.get(i) == null;

        if (depurar) Log.d(TAG, "pedido p" + (i + 1) + " " + w + "x" + h);
        executar(() -> {
            boolean util = g == geracaoAtiva
                    && i >= primeiraDesejada - 2 && i <= ultimaDesejada + 2;
            long t0 = SystemClock.uptimeMillis();
            final Bitmap feito = util ? renderizar(i, w, h, escala, desvioX, desvioY) : null;
            List<Motor.Link> achados = null;
            if (util && querLinks && motor != null) {
                try {
                    achados = motor.links(i);
                } catch (Exception e) {
                    achados = Collections.emptyList();
                }
            }
            final List<Motor.Link> linksDaPagina = achados;
            final boolean tentou = util;
            if (depurar) {
                Log.d(TAG, "p" + (i + 1) + (util ? (feito != null ? " pronta" : " FALHOU") : " pulada")
                        + " em " + (SystemClock.uptimeMillis() - t0) + " ms");
            }
            principal.post(() -> {
                if (g != geracao) return;
                if (linksDaPagina != null) links.put(i, linksDaPagina);
                if (vl != versaoLayout) return; // o layout mudou; o pedido novo já foi feito
                pendentes.remove(i);
                if (feito != null) {
                    naCache.add(i);
                    cache.put(i, feito);
                    invalidate();
                } else if (tentou) {
                    falhas.add(i);
                }
            });
        });
    }

    /**
     * Com zoom (ou quando a página inteira teve de ser reduzida para caber na memória,
     * como na tela deitada), a página fica borrada. Quando o usuário para de mexer,
     * renderiza só a parte visível na resolução exata da tela.
     */
    private void pedirDetalhes() {
        final int id = ++pedidoDetalhe;
        pedidoDetalheAtivo = id;
        if (total == 0 || larguraLayout == 0 || (zoom < 1.05f && !paginasReduzidas)) {
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
        calcularVisiveis(x0, y0, x1, y1);

        final List<PedidoDetalhe> pedidos = new ArrayList<>();
        for (int i = primeiraVisivel; i <= ultimaVisivel; i++) {
            RectF area = new RectF(
                    Math.max(px[i], x0),
                    Math.max(py[i], y0),
                    Math.min(px[i] + pw[i], x1),
                    Math.min(py[i] + ph[i], y1));
            if (area.width() <= 0 || area.height() <= 0) continue;
            int w = (int) Math.ceil(area.width() * z);
            int h = (int) Math.ceil(area.height() * z);
            if (w > LADO_MAX_BITMAP || h > LADO_MAX_BITMAP) continue;
            float escala = pw[i] * z / (largPts[i] * (cr - cl));
            pedidos.add(new PedidoDetalhe(i, area, w, h, escala,
                    (area.left - px[i]) * z + cl * largPts[i] * escala,
                    (area.top - py[i]) * z + ct * altPts[i] * escala));
        }
        if (pedidos.isEmpty()) return;

        final int g = geracao;
        executar(() -> {
            final List<Detalhe> feitos = new ArrayList<>();
            for (PedidoDetalhe p : pedidos) {
                if (id != pedidoDetalheAtivo || g != geracaoAtiva) return;
                Bitmap b = renderizar(p.pagina, p.largura, p.altura, p.escala, p.desvioX, p.desvioY);
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

    // ======================= Seleção de texto =======================

    private void iniciarSelecao(float sx, float sy) {
        if (!temTexto) return;
        PointF pts = new PointF();
        int i = paginaNoPonto(sx, sy, pts);
        if (i < 0) return;
        fecharMenuSelecao();
        selecao = null;
        ancora = pts;
        arrastandoSelecao = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        pedirSelecao(i, pts, pts);
    }

    /** Pede ao motor a seleção entre dois pontos. Se já houver um pedido rodando, guarda o último. */
    private void pedirSelecao(final int pagina, PointF a, PointF b) {
        if (selecaoEmAndamento) {
            selecaoPendente = new PointF[]{a, b, new PointF(pagina, 0)};
            return;
        }
        selecaoEmAndamento = true;
        final PointF inicio = new PointF(a.x, a.y);
        final PointF fim = new PointF(b.x, b.y);
        final int id = idSelecao;
        final int g = geracao;
        executar(() -> {
            Motor.Selecao s = null;
            if (g == geracaoAtiva && motor != null) {
                try {
                    s = motor.selecionar(pagina, inicio, fim);
                    if (s == null && (inicio.x != fim.x || inicio.y != fim.y)) {
                        s = motor.selecionar(pagina, fim, inicio); // pontas invertidas
                    }
                } catch (Exception e) {
                    Log.w(TAG, "falha ao selecionar", e);
                }
            }
            final Motor.Selecao resultado = s;
            principal.post(() -> {
                selecaoEmAndamento = false;
                if (g != geracao || id != idSelecao) return;
                if (resultado != null) {
                    selecao = resultado;
                    invalidate();
                }
                if (selecaoPendente != null) {
                    PointF[] p = selecaoPendente;
                    selecaoPendente = null;
                    pedirSelecao((int) p[2].x, p[0], p[1]);
                } else if (!arrastandoSelecao && alcaArrastada == 0) {
                    if (selecao != null) mostrarMenuSelecao();
                }
            });
        });
    }

    private void selecionarTudo() {
        if (selecao == null) return;
        final int pagina = selecao.pagina;
        final int g = geracao;
        executar(() -> {
            Motor.Selecao s = null;
            if (g == geracaoAtiva && motor != null) {
                try {
                    s = motor.selecionarTudo(pagina);
                } catch (Exception e) {
                    Log.w(TAG, "falha ao selecionar a página", e);
                }
            }
            final Motor.Selecao resultado = s;
            principal.post(() -> {
                if (g == geracao && resultado != null) {
                    selecao = resultado;
                    invalidate();
                    if (menuSelecao != null) menuSelecao.invalidateContentRect();
                }
            });
        });
    }

    private void limparSelecao() {
        idSelecao++;
        selecaoPendente = null;
        arrastandoSelecao = false;
        alcaArrastada = 0;
        if (selecao != null) {
            selecao = null;
            invalidate();
        }
        fecharMenuSelecao();
    }

    private void fecharMenuSelecao() {
        if (menuSelecao != null) {
            ActionMode m = menuSelecao;
            menuSelecao = null;
            m.finish();
        }
    }

    private void copiarSelecao() {
        if (selecao == null) return;
        ClipboardManager area = getContext().getSystemService(ClipboardManager.class);
        if (area == null) return;
        area.setPrimaryClip(ClipData.newPlainText(getContext().getString(R.string.app_name),
                selecao.texto.trim()));
        if (Build.VERSION.SDK_INT < 33) {
            // a partir do Android 13 o próprio sistema mostra o aviso
            Toast.makeText(getContext(), R.string.texto_copiado, Toast.LENGTH_SHORT).show();
        }
    }

    private void mostrarMenuSelecao() {
        if (menuSelecao != null) {
            menuSelecao.invalidateContentRect();
            return;
        }
        menuSelecao = startActionMode(new ActionMode.Callback2() {
            @Override
            public boolean onCreateActionMode(ActionMode modo, Menu menu) {
                menu.add(Menu.NONE, android.R.id.copy, 0, android.R.string.copy);
                menu.add(Menu.NONE, android.R.id.selectAll, 1, android.R.string.selectAll);
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode modo, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode modo, MenuItem item) {
                if (item.getItemId() == android.R.id.copy) {
                    copiarSelecao();
                    limparSelecao();
                    return true;
                }
                if (item.getItemId() == android.R.id.selectAll) {
                    selecionarTudo();
                    return true;
                }
                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode modo) {
                if (menuSelecao == modo) {
                    menuSelecao = null;
                    // o menu foi fechado pelo sistema (toque fora, voltar): some a seleção
                    if (alcaArrastada == 0 && !arrastandoSelecao) limparSelecao();
                }
            }

            @Override
            public void onGetContentRect(ActionMode modo, View view, Rect saida) {
                if (selecao == null || larguraLayout == 0) {
                    saida.set(0, 0, getWidth(), getHeight() / 2);
                    return;
                }
                RectF uniao = null;
                for (RectF a : selecao.areas) {
                    paraDoc(selecao.pagina, a, ret2);
                    if (uniao == null) uniao = new RectF(ret2);
                    else uniao.union(ret2);
                }
                saida.set((int) (uniao.left * zoom - posX), (int) (uniao.top * zoom - posY),
                        (int) (uniao.right * zoom - posX), (int) (uniao.bottom * zoom - posY));
            }
        }, ActionMode.TYPE_FLOATING);
    }

    /** Pontas da seleção na tela: [x início, y início, x fim, y fim], ou null. */
    private float[] pontasSelecao() {
        if (selecao == null || selecao.areas.isEmpty() || larguraLayout == 0) return null;
        RectF primeira = selecao.areas.get(0);
        RectF ultima = selecao.areas.get(selecao.areas.size() - 1);
        paraDoc(selecao.pagina, primeira, ret2);
        float xi = ret2.left * zoom - posX;
        float yi = ret2.bottom * zoom - posY;
        paraDoc(selecao.pagina, ultima, ret2);
        float xf = ret2.right * zoom - posX;
        float yf = ret2.bottom * zoom - posY;
        return new float[]{xi, yi, xf, yf};
    }

    private void desenharAlcas(Canvas canvas) {
        float[] p = pontasSelecao();
        if (p == null) return;
        float raio = 9 * dp;
        canvas.drawRect(p[0] - dp, p[1] - 14 * dp, p[0] + dp, p[1], pintaAlca);
        canvas.drawCircle(p[0], p[1] + raio, raio, pintaAlca);
        canvas.drawRect(p[2] - dp, p[3] - 14 * dp, p[2] + dp, p[3], pintaAlca);
        canvas.drawCircle(p[2], p[3] + raio, raio, pintaAlca);
    }

    /** Se o toque começou numa alça da seleção, devolve 1 (início) ou 2 (fim). */
    private int alcaNoPonto(float x, float y) {
        float[] p = pontasSelecao();
        if (p == null) return 0;
        float raio = 9 * dp;
        float alcance = 28 * dp;
        if (Math.hypot(x - p[2], y - (p[3] + raio)) < alcance) return 2;
        if (Math.hypot(x - p[0], y - (p[1] + raio)) < alcance) return 1;
        return 0;
    }

    // ======================= Toque, rolagem e zoom =======================

    private void tocou(float x, float y) {
        if (selecao != null) {
            limparSelecao();
            return;
        }
        PointF pts = new PointF();
        int i = paginaNoPonto(x, y, pts);
        if (i >= 0) {
            Motor.Link link = linkEm(i, pts);
            if (link != null) {
                if (link.uri != null) {
                    if (ouvinte != null) ouvinte.aoAbrirLink(link.uri);
                } else if (link.destinoPagina >= 0 && link.destinoPagina < total) {
                    irParaDestino(link.destinoPagina, link.destinoY);
                }
                return;
            }
        }
        if (modoPagina && zoom < 1.05f) {
            // nas bordas, o toque vira a página, como num livro
            if (x < getWidth() * 0.22f) {
                avancar(-1);
                return;
            }
            if (x > getWidth() * 0.78f) {
                avancar(1);
                return;
            }
        }
        if (ouvinte != null) ouvinte.aoToque();
    }

    private Motor.Link linkEm(int pagina, PointF pts) {
        List<Motor.Link> daPagina = links.get(pagina);
        if (daPagina == null) return null;
        for (Motor.Link l : daPagina) {
            for (RectF a : l.areas) {
                ret2.set(a);
                ret2.sort();
                ret2.inset(-2f, -2f);
                if (ret2.contains(pts.x, pts.y)) return l;
            }
        }
        return null;
    }

    private void irParaDestino(int pagina, float yPts) {
        if (modoPagina || yPts < 0) {
            irParaPagina(pagina);
            return;
        }
        rolagem.forceFinished(true);
        float y = py[pagina] + (yPts - ct * altPts[pagina]) * escalaPagina(pagina);
        posY = limitar(y * zoom - espacoTopo - margem, minPosY(), maxPosY());
        invalidate();
        agendarAssentar();
    }

    @Override
    public boolean onTouchEvent(MotionEvent evento) {
        if (total == 0 || larguraLayout == 0) return false;
        int acao = evento.getActionMasked();

        // Arrastar uma alça da seleção, ou o dedo logo depois do toque longo
        if (acao == MotionEvent.ACTION_DOWN && selecao != null) {
            alcaArrastada = alcaNoPonto(evento.getX(), evento.getY());
            if (alcaArrastada != 0) {
                fecharMenuSelecao();
                float[] p = pontasSelecao();
                PointF fixo = new PointF();
                if (alcaArrastada == 2 && p != null) {
                    pontoNaPagina(selecao.pagina, p[0] + 1, p[1] - 2 * dp, fixo);
                } else if (p != null) {
                    pontoNaPagina(selecao.pagina, p[2] - 1, p[3] - 2 * dp, fixo);
                }
                ancora = fixo;
                return true;
            }
        }
        if (alcaArrastada != 0 || (arrastandoSelecao && acao != MotionEvent.ACTION_DOWN)) {
            if (acao == MotionEvent.ACTION_MOVE && selecao != null && ancora != null) {
                PointF dedo = new PointF();
                // o texto escolhido fica um pouco acima do dedo, para não ficar escondido
                pontoNaPagina(selecao.pagina, evento.getX(), evento.getY() - 20 * dp, dedo);
                if (alcaArrastada == 1) {
                    pedirSelecao(selecao.pagina, dedo, ancora);
                } else {
                    pedirSelecao(selecao.pagina, ancora, dedo);
                }
            } else if (acao == MotionEvent.ACTION_UP || acao == MotionEvent.ACTION_CANCEL) {
                arrastandoSelecao = false;
                alcaArrastada = 0;
                if (!selecaoEmAndamento && selecao != null) mostrarMenuSelecao();
            }
            if (arrastandoSelecao) gestos.onTouchEvent(evento); // deixa o detector terminar o gesto
            return true;
        }

        if (acao == MotionEvent.ACTION_DOWN) {
            tocando = true;
            houvePinca = false;
            houveFling = false;
            paginaNoToque = getPaginaAtual();
            cancelarAnimacaoZoom();
        }
        pinca.onTouchEvent(evento);
        gestos.onTouchEvent(evento);
        if (acao == MotionEvent.ACTION_UP || acao == MotionEvent.ACTION_CANCEL) {
            tocando = false;
            if (modoPagina && zoom < 1.05f && !houveFling && rolagem.isFinished()) {
                animarParaPagina(getPaginaAtual()); // encaixa na página mais próxima
            }
            agendarAssentar();
        }
        return true;
    }

    private void animarParaPagina(int pagina) {
        if (total == 0 || larguraLayout == 0) return;
        int alvo = (int) limitar(pagina, 0, total - 1);
        float destinoX = alvoX(alvo);
        float destinoY = limitar(posY, minPosY(), maxPosY());
        rolagem.forceFinished(true);
        rolagem.startScroll((int) posX, (int) posY, (int) (destinoX - posX), (int) (destinoY - posY), 260);
        postInvalidateOnAnimation();
    }

    @Override
    public void computeScroll() {
        if (rolagem.computeScrollOffset()) {
            posX = limitar(rolagem.getCurrX(), minPosX(), maxPosX());
            posY = limitar(rolagem.getCurrY(), minPosY(), maxPosY());
            postInvalidateOnAnimation();
            agendarAssentar();
        }
    }

    private void moverPara(float x, float y) {
        posX = limitar(x, minPosX(), maxPosX());
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
        posX = limitar(docX * zoom - focoX, minPosX(), maxPosX());
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
        if (modoPagina && zoom < 1.05f) {
            // depois de um zoom de volta para 1, a página pode ter ficado fora do encaixe
            float encaixe = alvoX(getPaginaAtual());
            if (Math.abs(posX - encaixe) > 1f) {
                animarParaPagina(getPaginaAtual());
                return;
            }
        }
        pedirDetalhes();
    }

    private static float limitar(float valor, float minimo, float maximo) {
        return Math.max(minimo, Math.min(maximo, valor));
    }
}
