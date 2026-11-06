package com.alexandro.leitorpdf;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Guarda as preferências, a página onde cada PDF parou e a lista de recentes. */
final class Armazenamento {

    /** Página marcada de um documento. */
    static final class Marcador {
        int pagina;
        String nome;
    }

    static final class Recente {
        String uri;
        String nome;
        String chave;
        int pagina;
        int total;
        long quando;
    }

    private static final int MAX_RECENTES = 30;
    private static final String NOTURNO = "noturno";
    private static final String RECENTES = "recentes";
    private static final String POSICAO = "pos:";
    private static final String MARCADORES = "marc:";
    static final String MODO_PAGINA = "modo_pagina";
    static final String CORTAR_MARGENS = "cortar_margens";
    static final String VOLUME = "virar_com_volume";
    static final String TRAVAR_ROTACAO = "travar_rotacao";
    private static final String ORIENTACAO = "orientacao_travada";

    private final SharedPreferences prefs;

    Armazenamento(Context contexto) {
        prefs = contexto.getSharedPreferences("leitor", Context.MODE_PRIVATE);
    }

    boolean noturno() {
        return prefs.getBoolean(NOTURNO, false);
    }

    void setNoturno(boolean valor) {
        prefs.edit().putBoolean(NOTURNO, valor).apply();
    }

    boolean opcao(String nome) {
        return prefs.getBoolean(nome, false);
    }

    void setOpcao(String nome, boolean valor) {
        prefs.edit().putBoolean(nome, valor).apply();
    }

    /** Orientação guardada quando a rotação foi travada (constante de ActivityInfo). */
    int orientacaoTravada(int padrao) {
        return prefs.getInt(ORIENTACAO, padrao);
    }

    void setOrientacaoTravada(int orientacao) {
        prefs.edit().putInt(ORIENTACAO, orientacao).apply();
    }

    /** Marcadores do documento, em ordem de página. */
    List<Marcador> marcadores(String chave) {
        List<Marcador> lista = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(MARCADORES + chave, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Marcador m = new Marcador();
                m.pagina = o.getInt("pagina");
                m.nome = o.optString("nome", "");
                lista.add(m);
            }
        } catch (JSONException e) {
            // lista corrompida: começa do zero
        }
        Collections.sort(lista, (a, b) -> Integer.compare(a.pagina, b.pagina));
        return lista;
    }

    void salvarMarcadores(String chave, List<Marcador> lista) {
        JSONArray arr = new JSONArray();
        try {
            for (Marcador m : lista) {
                JSONObject o = new JSONObject();
                o.put("pagina", m.pagina);
                o.put("nome", m.nome);
                arr.put(o);
            }
        } catch (JSONException e) {
            return;
        }
        if (lista.isEmpty()) {
            prefs.edit().remove(MARCADORES + chave).apply();
        } else {
            prefs.edit().putString(MARCADORES + chave, arr.toString()).apply();
        }
    }

    float posicao(String chave) {
        return prefs.getFloat(POSICAO + chave, 0f);
    }

    void salvarPosicao(String chave, float posicao) {
        prefs.edit().putFloat(POSICAO + chave, posicao).apply();
    }

    List<Recente> recentes() {
        List<Recente> lista = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(RECENTES, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Recente r = new Recente();
                r.uri = o.getString("uri");
                r.nome = o.optString("nome", "Documento.pdf");
                r.chave = o.optString("chave", r.uri);
                r.pagina = o.optInt("pagina", 0);
                r.total = o.optInt("total", 0);
                r.quando = o.optLong("quando", 0L);
                lista.add(r);
            }
        } catch (JSONException e) {
            // lista corrompida: começa do zero
        }
        return lista;
    }

    /**
     * Coloca o documento no topo da lista. Devolve os endereços que saíram da lista
     * (para o app liberar a permissão de acesso a eles).
     */
    List<String> registrarRecente(String uri, String nome, String chave, int pagina, int total) {
        List<Recente> lista = recentes();
        List<String> removidos = new ArrayList<>();
        Iterator<Recente> it = lista.iterator();
        while (it.hasNext()) {
            Recente r = it.next();
            if (r.uri.equals(uri) || r.chave.equals(chave)) {
                if (!r.uri.equals(uri)) removidos.add(r.uri);
                it.remove();
            }
        }
        Recente novo = new Recente();
        novo.uri = uri;
        novo.nome = nome;
        novo.chave = chave;
        novo.pagina = pagina;
        novo.total = total;
        novo.quando = System.currentTimeMillis();
        lista.add(0, novo);
        while (lista.size() > MAX_RECENTES) {
            removidos.add(lista.remove(lista.size() - 1).uri);
        }
        gravar(lista);
        return removidos;
    }

    void atualizarRecente(String chave, int pagina, int total) {
        List<Recente> lista = recentes();
        for (Recente r : lista) {
            if (r.chave.equals(chave)) {
                r.pagina = pagina;
                r.total = total;
                r.quando = System.currentTimeMillis();
                gravar(lista);
                return;
            }
        }
    }

    boolean removerRecente(String uri) {
        List<Recente> lista = recentes();
        Iterator<Recente> it = lista.iterator();
        boolean achou = false;
        while (it.hasNext()) {
            if (it.next().uri.equals(uri)) {
                it.remove();
                achou = true;
            }
        }
        if (achou) gravar(lista);
        return achou;
    }

    boolean usaUri(String uri) {
        for (Recente r : recentes()) {
            if (r.uri.equals(uri)) return true;
        }
        return false;
    }

    private void gravar(List<Recente> lista) {
        JSONArray arr = new JSONArray();
        try {
            for (Recente r : lista) {
                JSONObject o = new JSONObject();
                o.put("uri", r.uri);
                o.put("nome", r.nome);
                o.put("chave", r.chave);
                o.put("pagina", r.pagina);
                o.put("total", r.total);
                o.put("quando", r.quando);
                arr.put(o);
            }
        } catch (JSONException e) {
            return;
        }
        prefs.edit().putString(RECENTES, arr.toString()).apply();
    }
}
