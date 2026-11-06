package com.alexandro.leitorpdf;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.pdf.LoadParams;
import android.graphics.pdf.PdfRendererPreV;
import android.graphics.pdf.RenderParams;
import android.graphics.pdf.content.PdfPageGotoLinkContent;
import android.graphics.pdf.content.PdfPageLinkContent;
import android.graphics.pdf.models.PageMatchBounds;
import android.graphics.pdf.models.selection.PageSelection;
import android.graphics.pdf.models.selection.SelectionBoundary;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Motor completo do Android 11 a 14 (com as atualizações do sistema que trazem estes recursos). */
@SuppressLint("NewApi")
final class MotorPreV extends Motor {

    private final PdfRendererPreV renderer;
    private final RenderParams parametros =
            new RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY).build();

    MotorPreV(ParcelFileDescriptor fd, String senha) throws IOException {
        if (senha != null) {
            renderer = new PdfRendererPreV(fd, new LoadParams.Builder().setPassword(senha).build());
        } else {
            renderer = new PdfRendererPreV(fd);
        }
    }

    @Override
    String nome() {
        return "Android 11-14";
    }

    @Override
    int paginas() {
        return renderer.getPageCount();
    }

    @Override
    void tamanho(int pagina, float[] saida) {
        try (PdfRendererPreV.Page p = renderer.openPage(pagina)) {
            saida[0] = p.getWidth();
            saida[1] = p.getHeight();
        }
    }

    @Override
    void desenhar(int pagina, Bitmap destino, Matrix transformacao) {
        try (PdfRendererPreV.Page p = renderer.openPage(pagina)) {
            p.render(destino, null, transformacao, parametros);
        }
    }

    @Override
    boolean temTexto() {
        return true;
    }

    @Override
    List<List<RectF>> buscar(int pagina, String termo) {
        List<List<RectF>> resultado = new ArrayList<>();
        try (PdfRendererPreV.Page p = renderer.openPage(pagina)) {
            for (PageMatchBounds m : p.searchText(termo)) {
                resultado.add(new ArrayList<>(m.getBounds()));
            }
        }
        return resultado;
    }

    @Override
    List<Link> links(int pagina) {
        List<Link> resultado = new ArrayList<>();
        try (PdfRendererPreV.Page p = renderer.openPage(pagina)) {
            for (PdfPageLinkContent l : p.getLinkContents()) {
                resultado.add(new Link(new ArrayList<>(l.getBounds()), l.getUri(), -1, -1f));
            }
            for (PdfPageGotoLinkContent g : p.getGotoLinks()) {
                PdfPageGotoLinkContent.Destination d = g.getDestination();
                resultado.add(new Link(new ArrayList<>(g.getBounds()), null,
                        d.getPageNumber(), d.getYCoordinate()));
            }
        }
        return resultado;
    }

    @Override
    Selecao selecionar(int pagina, PointF inicio, PointF fim) {
        try (PdfRendererPreV.Page p = renderer.openPage(pagina)) {
            PageSelection s = p.selectContent(
                    new SelectionBoundary(new Point(Math.round(inicio.x), Math.round(inicio.y))),
                    new SelectionBoundary(new Point(Math.round(fim.x), Math.round(fim.y))));
            return s == null ? null : MotorV.juntar(pagina, s.getSelectedTextContents());
        }
    }

    @Override
    Selecao selecionarTudo(int pagina) {
        try (PdfRendererPreV.Page p = renderer.openPage(pagina)) {
            return MotorV.juntar(pagina, p.getTextContents());
        }
    }

    @Override
    public void close() {
        renderer.close();
    }
}
