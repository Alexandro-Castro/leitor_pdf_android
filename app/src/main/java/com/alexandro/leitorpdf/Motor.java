package com.alexandro.leitorpdf;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.ext.SdkExtensions;

import java.io.IOException;
import java.util.List;

/**
 * Acesso ao arquivo PDF. Há três versões, conforme o Android do aparelho:
 * <ul>
 *   <li>{@link MotorV}: Android 15 ou mais novo (busca, seleção, links e senha);</li>
 *   <li>{@link MotorPreV}: Android 11 a 14 com as atualizações do sistema em dia (os mesmos recursos);</li>
 *   <li>{@link MotorBasico}: os demais (só desenha as páginas).</li>
 * </ul>
 * Todos os métodos devem ser chamados na mesma linha de execução, um por vez.
 */
abstract class Motor implements AutoCloseable {

    /** Link de uma página: externo (uri) ou para outra página do documento. */
    static final class Link {
        final List<RectF> areas;  // em pontos da página
        final Uri uri;            // link externo; null se for interno
        final int destinoPagina;  // link interno; -1 se for externo
        final float destinoY;     // altura de destino em pontos; negativo se não informada

        Link(List<RectF> areas, Uri uri, int destinoPagina, float destinoY) {
            this.areas = areas;
            this.uri = uri;
            this.destinoPagina = destinoPagina;
            this.destinoY = destinoY;
        }
    }

    /** Trecho de texto selecionado numa página. */
    static final class Selecao {
        final int pagina;
        final String texto;
        final List<RectF> areas; // em pontos da página, na ordem de leitura

        Selecao(int pagina, String texto, List<RectF> areas) {
            this.pagina = pagina;
            this.texto = texto;
            this.areas = areas;
        }
    }

    /** O aparelho tem os recursos de texto (busca, seleção, links) e de senha? */
    static boolean temRecursosAvancados() {
        return Build.VERSION.SDK_INT >= 35 || temPreV();
    }

    private static boolean temPreV() {
        return Build.VERSION.SDK_INT >= 31
                && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13;
    }

    /** Abre o arquivo. A senha só é usada pelos motores que a aceitam. */
    static Motor abrir(ParcelFileDescriptor fd, String senha) throws IOException {
        if (Build.VERSION.SDK_INT >= 35) return new MotorV(fd, senha);
        if (temPreV()) return new MotorPreV(fd, senha);
        return new MotorBasico(fd);
    }

    abstract String nome();

    abstract int paginas();

    /** Largura e altura da página em pontos (1/72 de polegada). */
    abstract void tamanho(int pagina, float[] saida);

    /** Desenha a página no bitmap, com a transformação dada (de pontos para pixels). */
    abstract void desenhar(int pagina, Bitmap destino, Matrix transformacao);

    abstract boolean temTexto();

    /** Cada ocorrência é uma lista de retângulos (o termo pode quebrar de linha). */
    abstract List<List<RectF>> buscar(int pagina, String termo);

    abstract List<Link> links(int pagina);

    /** Seleciona o texto entre dois pontos; se forem iguais, a palavra naquele ponto. */
    abstract Selecao selecionar(int pagina, PointF inicio, PointF fim);

    abstract Selecao selecionarTudo(int pagina);

    @Override
    public abstract void close();
}
