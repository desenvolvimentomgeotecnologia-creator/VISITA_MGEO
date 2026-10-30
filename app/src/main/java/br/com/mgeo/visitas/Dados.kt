package br.com.mgeo.visitas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Trecho(
    val origem: String,   // "Gravação" ou nome do arquivo importado
    val hora: String,
    val texto: String,
    val audio: String? = null // nome do arquivo .wav dentro da pasta da visita
)

data class Foto(val arquivo: String, val legenda: String = "")

data class Visita(
    val id: String = UUID.randomUUID().toString(),
    val usina: String = "",
    val data: String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
    val tecnico: String = "",
    val participantes: String = "",
    val trechos: List<Trecho> = emptyList(),
    val textoBruto: String = "",
    val resumo: String = "",
    val parecer: String = "",
    val pendencias: String = "",  // uma por linha: item | responsável | prazo
    val acoes: String = "",       // uma por linha
    val fotos: List<Foto> = emptyList(),
    val iaPendente: Boolean = false,
    val atualizadoEm: Long = System.currentTimeMillis()
) {
    val vazia: Boolean
        get() = usina.isBlank() && textoBruto.isBlank() && fotos.isEmpty() && trechos.isEmpty()

    val dataBr: String
        get() = data.split("-").let { if (it.size == 3) "${it[2]}/${it[1]}/${it[0]}" else data }

    fun comTrecho(t: Trecho): Visita {
        val n = trechos.size + 1
        val cab = "[Trecho $n – ${t.origem} – ${t.hora}]"
        val novo = if (textoBruto.isBlank()) "$cab\n${t.texto}" else "${textoBruto.trim()}\n\n$cab\n${t.texto}"
        return copy(trechos = trechos + t, textoBruto = novo)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("usina", usina); put("data", data); put("tecnico", tecnico)
        put("participantes", participantes); put("textoBruto", textoBruto); put("resumo", resumo)
        put("parecer", parecer); put("pendencias", pendencias); put("acoes", acoes)
        put("iaPendente", iaPendente); put("atualizadoEm", atualizadoEm)
        put("trechos", JSONArray().apply {
            trechos.forEach { t ->
                put(JSONObject().put("origem", t.origem).put("hora", t.hora).put("texto", t.texto).put("audio", t.audio ?: ""))
            }
        })
        put("fotos", JSONArray().apply {
            fotos.forEach { f -> put(JSONObject().put("arquivo", f.arquivo).put("legenda", f.legenda)) }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): Visita {
            val tr = o.optJSONArray("trechos") ?: JSONArray()
            val fo = o.optJSONArray("fotos") ?: JSONArray()
            return Visita(
                id = o.getString("id"),
                usina = o.optString("usina"),
                data = o.optString("data"),
                tecnico = o.optString("tecnico"),
                participantes = o.optString("participantes"),
                textoBruto = o.optString("textoBruto"),
                resumo = o.optString("resumo"),
                parecer = o.optString("parecer"),
                pendencias = o.optString("pendencias"),
                acoes = o.optString("acoes"),
                iaPendente = o.optBoolean("iaPendente"),
                atualizadoEm = o.optLong("atualizadoEm"),
                trechos = (0 until tr.length()).map { i ->
                    val t = tr.getJSONObject(i)
                    Trecho(t.optString("origem"), t.optString("hora"), t.optString("texto"),
                        t.optString("audio").ifBlank { null })
                },
                fotos = (0 until fo.length()).map { i ->
                    val f = fo.getJSONObject(i)
                    Foto(f.optString("arquivo"), f.optString("legenda"))
                }
            )
        }
    }
}

/** Guarda cada visita em uma pasta própria: visita.json + fotos + áudios. Tudo no celular, sem internet. */
class Repositorio(context: Context) {
    private val raiz = File(context.filesDir, "visitas").apply { mkdirs() }

    fun pasta(id: String): File = File(raiz, id).apply { mkdirs() }

    fun listar(): List<Visita> = raiz.listFiles()?.mapNotNull { dir ->
        val f = File(dir, "visita.json")
        if (f.exists()) runCatching { Visita.fromJson(JSONObject(f.readText())) }.getOrNull() else null
    }?.sortedWith(compareByDescending<Visita> { it.data }.thenByDescending { it.atualizadoEm }) ?: emptyList()

    fun carregar(id: String): Visita? {
        val f = File(File(raiz, id), "visita.json")
        return if (f.exists()) runCatching { Visita.fromJson(JSONObject(f.readText())) }.getOrNull() else null
    }

    fun salvar(v: Visita) {
        val dir = pasta(v.id)
        val tmp = File(dir, "visita.json.tmp")
        tmp.writeText(v.toJson().toString(2))
        tmp.renameTo(File(dir, "visita.json"))
    }

    fun excluir(id: String) {
        File(raiz, id).deleteRecursively()
    }

    fun pastaRaiz(): File = raiz
}

class Preferencias(context: Context) {
    private val sp = context.getSharedPreferences("config", Context.MODE_PRIVATE)
    var chaveApi: String
        get() = sp.getString("chave", "") ?: ""
        set(v) = sp.edit().putString("chave", v.trim()).apply()
    var modelo: String
        get() = sp.getString("modelo", MODELO_PADRAO) ?: MODELO_PADRAO
        set(v) = sp.edit().putString("modelo", v.trim().ifBlank { MODELO_PADRAO }).apply()
    var tecnico: String
        get() = sp.getString("tecnico", "") ?: ""
        set(v) = sp.edit().putString("tecnico", v.trim()).apply()

    companion object {
        const val MODELO_PADRAO = "claude-sonnet-5-5"
    }
}

fun horaAgora(): String = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date())
