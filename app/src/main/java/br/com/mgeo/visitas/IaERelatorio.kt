package br.com.mgeo.visitas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max

fun temInternet(ctx: Context): Boolean {
    val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

object ClaudeApi {

    private fun prompt(v: Visita) = """
Você é um engenheiro que redige relatórios de visita técnica da empresa Mgeo a usinas.
A transcrição abaixo veio de reconhecimento de voz offline: está sem pontuação, em minúsculas e pode ter palavras trocadas. Pode ter vários trechos.

Produza:
1. "resumo": resumo organizado e fiel da visita, em português do Brasil, em parágrafos curtos, agrupado por assunto (não por trecho). Corrija erros óbvios de transcrição.
2. "parecer": o resumo do resumo, em até 6 frases: avaliação técnica geral da situação da usina, principais riscos ou pontos de atenção e uma recomendação clara.
3. "pendencias": lista de pendências citadas, cada uma {"item","responsavel","prazo"}; use "não informado" quando faltar.
4. "acoes": lista de próximas ações recomendadas (frases curtas).

Não invente dados, medições, nomes ou datas que não estejam no texto. Se algo estiver ambíguo, diga isso no parecer.
Responda somente com JSON: {"resumo":"","parecer":"","pendencias":[{"item":"","responsavel":"","prazo":""}],"acoes":[""]}

Usina: ${v.usina.ifBlank { "não informada" }}
Data: ${v.dataBr}
Técnico: ${v.tecnico.ifBlank { "não informado" }}
Participantes: ${v.participantes.ifBlank { "não informados" }}

TRANSCRIÇÃO:
${v.textoBruto}
""".trimIndent()

    /** Chama a API do Claude e devolve a visita preenchida com resumo, parecer, pendências e ações. */
    suspend fun gerar(v: Visita, chave: String, modelo: String): Visita = withContext(Dispatchers.IO) {
        val corpo = JSONObject()
            .put("model", modelo)
            .put("max_tokens", 4000)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt(v))))

        val con = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        con.requestMethod = "POST"
        con.connectTimeout = 20_000
        con.readTimeout = 240_000
        con.doOutput = true
        con.setRequestProperty("content-type", "application/json")
        con.setRequestProperty("x-api-key", chave)
        con.setRequestProperty("anthropic-version", "2023-06-01")
        con.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }

        val codigo = con.responseCode
        val resposta = (if (codigo in 200..299) con.inputStream else con.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        con.disconnect()
        if (codigo !in 200..299) {
            val msg = runCatching { JSONObject(resposta).getJSONObject("error").getString("message") }.getOrDefault(resposta.take(200))
            throw IllegalStateException(
                when (codigo) {
                    401 -> "Chave da API inválida. Confira em Configurações."
                    429 -> "Limite de uso da API atingido. Tente mais tarde."
                    else -> "Erro $codigo da API: $msg"
                }
            )
        }

        val conteudo = JSONObject(resposta).getJSONArray("content")
        val texto = buildString {
            for (i in 0 until conteudo.length()) {
                val b = conteudo.getJSONObject(i)
                if (b.optString("type") == "text") append(b.optString("text"))
            }
        }
        val ini = texto.indexOf('{')
        val fim = texto.lastIndexOf('}')
        if (ini < 0 || fim <= ini) throw IllegalStateException("A IA não devolveu um resultado válido. Tente de novo.")
        val r = JSONObject(texto.substring(ini, fim + 1))

        val pend = r.optJSONArray("pendencias") ?: JSONArray()
        val acoes = r.optJSONArray("acoes") ?: JSONArray()
        v.copy(
            resumo = r.optString("resumo"),
            parecer = r.optString("parecer"),
            pendencias = (0 until pend.length()).joinToString("\n") { i ->
                val p = pend.optJSONObject(i) ?: JSONObject()
                listOf("item", "responsavel", "prazo").joinToString(" | ") { k -> p.optString(k).ifBlank { "não informado" } }
            },
            acoes = (0 until acoes.length()).joinToString("\n") { i -> acoes.optString(i) },
            iaPendente = false,
            atualizadoEm = System.currentTimeMillis()
        )
    }
}

object Relatorio {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun linhas(s: String) = s.lines().map { it.trim() }.filter { it.isNotEmpty() }
    private fun para(s: String) = esc(s).replace("\n", "<br>")

    fun texto(v: Visita): String = buildString {
        appendLine("RELATÓRIO DE VISITA TÉCNICA – MGEO")
        appendLine("Usina: ${v.usina}")
        appendLine("Data: ${v.dataBr}")
        appendLine("Técnico: ${v.tecnico.ifBlank { "—" }}")
        appendLine("Participantes: ${v.participantes.ifBlank { "—" }}")
        appendLine()
        appendLine("PARECER TÉCNICO"); appendLine(v.parecer.ifBlank { "Ainda não gerado." }); appendLine()
        appendLine("RESUMO"); appendLine(v.resumo.ifBlank { "Ainda não gerado." }); appendLine()
        appendLine("PENDÊNCIAS (item | responsável | prazo)")
        linhas(v.pendencias).ifEmpty { listOf("Nenhuma") }.forEach { appendLine("- $it") }
        appendLine()
        appendLine("PRÓXIMAS AÇÕES")
        linhas(v.acoes).ifEmpty { listOf("Nenhuma") }.forEachIndexed { i, a -> appendLine("${i + 1}. $a") }
    }

    /** HTML completo, com fotos embutidas, pronto para PDF ou para compartilhar. */
    fun html(v: Visita, pasta: File): String {
        val pend = linhas(v.pendencias).map { l -> l.split("|").map { it.trim() } }
        val acoes = linhas(v.acoes)
        val fotos = v.fotos.mapNotNull { f ->
            val arq = File(pasta, f.arquivo)
            if (!arq.exists()) null else {
                val b64 = Base64.encodeToString(miniatura(arq, 1000), Base64.NO_WRAP)
                """<figure><img src="data:image/jpeg;base64,$b64"><figcaption>${esc(f.legenda.ifBlank { arq.nameWithoutExtension })}</figcaption></figure>"""
            }
        }
        return """<!DOCTYPE html><html lang="pt-BR"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Relatório ${esc(v.usina)} ${v.dataBr}</title>
<style>
body{font-family:sans-serif;color:#1E2B2F;margin:24px;line-height:1.5;font-size:14px}
h1{font-size:22px;margin:0}h2{font-size:16px;margin:22px 0 6px;border-bottom:2px solid #E8A317;padding-bottom:2px}
header{border-bottom:3px solid #1E2B2F;padding-bottom:8px}
dl{display:grid;grid-template-columns:auto 1fr;gap:2px 12px}dt{font-weight:bold;color:#5E6B6E}dd{margin:0}
.parecer{border:2px solid #1E2B2F;padding:10px 12px;background:#F6F7F4}
table{border-collapse:collapse;width:100%}th,td{border-bottom:1px solid #CDD3CF;text-align:left;padding:4px 6px;vertical-align:top}
.fotos{display:grid;grid-template-columns:1fr 1fr;gap:10px}figure{margin:0;break-inside:avoid}img{width:100%;border:1px solid #CDD3CF}
figcaption{font-size:12px;color:#5E6B6E}.bruto{font-size:11px;color:#5E6B6E}
</style></head><body>
<header><h1>Relatório de visita técnica</h1><div>Mgeo · ${esc(v.usina)}</div></header>
<dl><dt>Usina</dt><dd>${esc(v.usina)}</dd><dt>Data</dt><dd>${v.dataBr}</dd>
<dt>Técnico</dt><dd>${esc(v.tecnico.ifBlank { "—" })}</dd><dt>Participantes</dt><dd>${esc(v.participantes.ifBlank { "—" })}</dd></dl>
<h2>Parecer técnico</h2><div class="parecer">${para(v.parecer.ifBlank { "Ainda não gerado." })}</div>
<h2>Resumo da visita</h2><p>${para(v.resumo.ifBlank { "Ainda não gerado." })}</p>
<h2>Pendências</h2>${
            if (pend.isEmpty()) "<p>Nenhuma pendência registrada.</p>"
            else "<table><tr><th>Item</th><th>Responsável</th><th>Prazo</th></tr>" +
                pend.joinToString("") { p -> "<tr><td>${esc(p.getOrElse(0) { "" })}</td><td>${esc(p.getOrElse(1) { "—" })}</td><td>${esc(p.getOrElse(2) { "—" })}</td></tr>" } +
                "</table>"
        }
<h2>Próximas ações</h2>${
            if (acoes.isEmpty()) "<p>Nenhuma ação registrada.</p>"
            else "<ol>" + acoes.joinToString("") { "<li>${esc(it)}</li>" } + "</ol>"
        }
${if (fotos.isEmpty()) "" else "<h2>Registro fotográfico</h2><div class=\"fotos\">${fotos.joinToString("")}</div>"}
<h2>Texto bruto (transcrição)</h2><p class="bruto">${para(v.textoBruto.ifBlank { "—" })}</p>
</body></html>"""
    }

    /** Reduz a foto para caber no relatório. */
    fun miniatura(arq: File, lado: Int, girar: Int = 0): ByteArray {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(arq.absolutePath, opts)
        var amostra = 1
        while (max(opts.outWidth, opts.outHeight) / (amostra * 2) >= lado) amostra *= 2
        var bmp = BitmapFactory.decodeFile(arq.absolutePath, BitmapFactory.Options().apply { inSampleSize = amostra })
            ?: return ByteArray(0)
        if (girar != 0) {
            val m = android.graphics.Matrix().apply { postRotate(girar.toFloat()) }
            val girada = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (girada !== bmp) bmp.recycle()
            bmp = girada
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** Salva a foto (da câmera ou galeria) reduzida para 1600 px dentro da pasta da visita. */
    fun importarFoto(ctx: Context, origem: Uri, destino: File) {
        val tmp = File(ctx.cacheDir, "foto-tmp.jpg")
        ctx.contentResolver.openInputStream(origem)?.use { ent -> tmp.outputStream().use { ent.copyTo(it) } }
        val graus = runCatching {
            when (android.media.ExifInterface(tmp.absolutePath).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
        destino.writeBytes(miniatura(tmp, 1600, graus))
        tmp.delete()
    }

    /** Copia de segurança de todas as visitas num .zip. */
    fun backup(raiz: File, destino: File) {
        ZipOutputStream(destino.outputStream().buffered()).use { zip ->
            raiz.walkTopDown().filter { it.isFile }.forEach { f ->
                zip.putNextEntry(ZipEntry(f.relativeTo(raiz).path))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
