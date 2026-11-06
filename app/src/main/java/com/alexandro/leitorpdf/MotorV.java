package com.alexandro.leitorpdf;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.pdf.LoadParams;
import android.graphics.pdf.PdfRenderer;
import android.graphics.pdf.content.PdfPageGotoLinkContent;
import android.graphics.pdf.content.PdfPageLinkContent;
import android.graphics.pdf.content.PdfPageTextContent;
import android.graphics.pdf.models.PageMatchBounds;
import android.graphics.pdf.models.selection.PageSelection;
import android.graphics.pdf.models.selection.SelectionBoundary;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Motor completo do Android 15 ou mais novo. */
@TargetApi(35)
final class MotorV extends Motor {

    private final PdfRenderer renderer;

    MotorV(ParcelFileDescriptor fd, String senha) throws IOException {
        if (senha != null) {
            renderer = new PdfRenderer(fd, new LoadParams.Builder().setPassword(senha).build());
        } else {
            renderer = new PdfRenderer(fd);
        }
    }

    @Override
    String nome() {
        return "Android 15";
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
        return true;
    }

    @Override
    List<List<RectF>> buscar(int pagina, String termo) {
        List<List<RectF>> resultado = new ArrayList<>();
        try (PdfRenderer.Page p = renderer.openPage(pagina)) {
            for (PageMatchBounds m : p.searchText(termo)) {
                resultado.add(new ArrayList<>(m.getBounds()));
            }
        }
        return resultado;
    }

    @Override
    List<Link> links(int pagina) {
        List<Link> resultado = new ArrayList<>();
        try (PdfRenderer.Page p = renderer.openPage(pagina)) {
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
        try (PdfRenderer.Page p = renderer.openPage(pagina)) {
            PageSelection s = p.selectContent(
                    new SelectionBoundary(new Point(Math.round(inicio.x), Math.round(inicio.y))),
                    new SelectionBoundary(new Point(Math.round(fim.x), Math.round(fim.y))));
            return s == null ? null : juntar(pagina, s.getSelectedTextContents());
        }
    }

    @Override
    Selecao selecionarTudo(int pagina) {
        try (PdfRenderer.Page p = renderer.openPage(pagina)) {
            return juntar(pagina, p.getTextContents());
        }
    }

    static Selecao juntar(int pagina, List<PdfPageTextContent> partes) {
        if (partes == null || partes.isEmpty()) return null;
        StringBuilder texto = new StringBuilder();
        List<RectF> areas = new ArrayList<>();
        for (PdfPageTextContent parte : partes) {
            texto.append(parte.getText());
            areas.addAll(parte.getBounds());
        }
        if (texto.toString().trim().isEmpty() || areas.isEmpty()) return null;
        return new Selecao(pagina, texto.toString(), areas);
    }

    @Override
    public void close() {
        renderer.close();
    }
}
