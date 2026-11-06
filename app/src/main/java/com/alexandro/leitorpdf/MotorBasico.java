package com.alexandro.leitorpdf;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/** Motor para qualquer Android 8+: só desenha as páginas. */
final class MotorBasico extends Motor {

    private final PdfRenderer renderer;

    MotorBasico(ParcelFileDescriptor fd) throws IOException {
        renderer = new PdfRenderer(fd);
    }

    @Override
    String nome() {
        return "básico";
    }

    @Override
    int paginas() {
        return renderer.getPageCount();
    }

    @Override
    void tamanho(int pagina, float[] saida) {
        try (PdfRenderer.Page p = renderer.openPage(pagina)) {
            saida[0] = p.getWidth();
            saida[1] = p.getHeight();
        }
    }

    @Override
    void desenhar(int pagina, Bitmap destino, Matrix transformacao) {
        try (PdfRenderer.Page p = renderer.openPage(pagina)) {
            p.render(destino, null, transformacao, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        }
    }

    @Override
    boolean temTexto() {
        return false;
    }

    @Override
    List<List<RectF>> buscar(int pagina, String termo) {
        return Collections.emptyList();
    }

    @Override
    List<Link> links(int pagina) {
        return Collections.emptyList();
    }

    @Override
    Selecao selecionar(int pagina, PointF inicio, PointF fim) {
        return null;
    }

    @Override
    Selecao selecionarTudo(int pagina) {
        return null;
    }

    @Override
    public void close() {
        renderer.close();
    }
}
