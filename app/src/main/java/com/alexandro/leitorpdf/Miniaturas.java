package com.alexandro.leitorpdf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorFilter;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;

/** Grade com as miniaturas de todas as páginas. */
final class Miniaturas extends BaseAdapter implements AbsListView.OnScrollListener {

    private final Context contexto;
    private final PdfView pdf;
    private final GridView grade;
    private final LruCache<Integer, Bitmap> cache;
    private final Set<Integer> pedidas = new HashSet<>();
    private int total;
    private int atual = -1;
    private ColorFilter filtro;
    private int geracao;

    // Lidos pela linha de renderização para descartar pedidos que saíram da tela.
    private volatile int primeiraVisivel;
    private volatile int ultimaVisivel = 30;

    Miniaturas(Context contexto, PdfView pdf, GridView grade) {
        this.contexto = contexto;
        this.pdf = pdf;
        this.grade = grade;
        int maxBytes = (int) Math.min(Runtime.getRuntime().maxMemory() / 16, Integer.MAX_VALUE);
        cache = new LruCache<Integer, Bitmap>(maxBytes) {
            @Override
            protected int sizeOf(Integer chave, Bitmap bitmap) {
                return bitmap.getByteCount();
            }
        };
        grade.setAdapter(this);
        grade.setOnScrollListener(this);
    }

    /** Prepara a grade para o documento aberto e marca a página atual. */
    void mostrar(int totalPaginas, int paginaAtual, ColorFilter filtroNoturno) {
        if (totalPaginas != total) {
            esquecer();
            total = totalPaginas;
        }
        atual = paginaAtual;
        filtro = filtroNoturno;
        notifyDataSetChanged();
        grade.setSelection(Math.max(0, paginaAtual - 2));
    }

    /** Descarta as miniaturas (ao trocar de documento). */
    void esquecer() {
        geracao++;
        cache.evictAll();
        pedidas.clear();
        total = 0;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return total;
    }

    @Override
    public Object getItem(int posicao) {
        return posicao;
    }

    @Override
    public long getItemId(int posicao) {
        return posicao;
    }

    @Override
    public View getView(int posicao, View reaproveitada, ViewGroup pai) {
        View item = reaproveitada != null ? reaproveitada
                : LayoutInflater.from(contexto).inflate(R.layout.item_miniatura, pai, false);
        ImageView imagem = item.findViewById(R.id.imagem);
        TextView numero = item.findViewById(R.id.numero);
        int largura = grade.getColumnWidth();
        if (largura > 0) {
            ViewGroup.LayoutParams lp = imagem.getLayoutParams();
            int altura = Math.round(largura * 1.38f);
            if (lp.height != altura) {
                lp.height = altura;
                imagem.setLayoutParams(lp);
            }
        }
        numero.setText(String.valueOf(posicao + 1));
        item.setActivated(posicao == atual);
        Bitmap b = cache.get(posicao);
        imagem.setImageBitmap(b);
        if (b != null) {
            imagem.setBackground(null);
        } else {
            imagem.setBackgroundColor(contexto.getColor(R.color.fundo_leitor));
        }
        imagem.setColorFilter(filtro);
        if (b == null) pedir(posicao, largura > 0 ? largura : 240);
        return item;
    }

    private void pedir(final int posicao, int largura) {
        if (pedidas.contains(posicao)) return;
        pedidas.add(posicao);
        final int g = geracao;
        pdf.pedirMiniatura(posicao, largura, new PdfView.PedidoMiniatura() {
            @Override
            public boolean aindaPrecisa() {
                return posicao >= primeiraVisivel - 4 && posicao <= ultimaVisivel + 4;
            }

            @Override
            public void receber(Bitmap miniatura) {
                if (g != geracao) return;
                pedidas.remove(posicao);
                if (miniatura != null) {
                    cache.put(posicao, miniatura);
                    notifyDataSetChanged();
                }
            }
        });
    }

    @Override
    public void onScrollStateChanged(AbsListView vista, int estado) {
        if (estado == SCROLL_STATE_IDLE) notifyDataSetChanged(); // pede o que ficou para trás
    }

    @Override
    public void onScroll(AbsListView vista, int primeira, int quantas, int totalItens) {
        primeiraVisivel = primeira;
        ultimaVisivel = primeira + quantas;
    }
}
