package br.com.mgeo.visitas

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.math.max

private const val TAXA = 16000

/**
 * Transcrição 100% offline com o modelo Vosk em português que vai dentro do APK.
 */
class Transcritor(private val ctx: Context) {
    @Volatile private var modelo: Model? = null
    private val trava = Mutex()

    suspend fun carregar(): Model = trava.withLock {
        modelo ?: withContext(Dispatchers.IO) {
            val dir = prepararModelo()
            Model(dir.absolutePath).also { modelo = it }
        }
    }

    /** Copia o modelo dos assets para a memória do app (só na primeira vez de cada versão). */
    private fun prepararModelo(): File {
        val destino = File(ctx.filesDir, "model-pt")
        val marca = File(destino, ".pronto-${BuildConfig.VERSION_CODE}")
        if (marca.exists()) return destino
        destino.deleteRecursively()
        val itens = ctx.assets.list("model-pt")
        if (itens.isNullOrEmpty()) error("Modelo de voz não encontrado no APK.")
        copiarAssets("model-pt", destino)
        marca.createNewFile()
        return destino
    }

    private fun copiarAssets(caminho: String, destino: File) {
        val itens = ctx.assets.list(caminho) ?: emptyArray()
        if (itens.isEmpty()) {
            destino.parentFile?.mkdirs()
            runCatching {
                ctx.assets.open(caminho).use { ent -> destino.outputStream().use { ent.copyTo(it) } }
            }
        } else {
            destino.mkdirs()
            itens.forEach { copiarAssets("$caminho/$it", File(destino, it)) }
        }
    }

    /**
     * Grava pelo microfone, salva o .wav e transcreve ao mesmo tempo.
     * Roda até [ativo] virar false. [parcial] recebe o texto ao vivo.
     */
    @SuppressLint("MissingPermission")
    suspend fun gravar(wav: File, ativo: AtomicBoolean, parcial: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val rec = Recognizer(carregar(), TAXA.toFloat())
            val minimo = AudioRecord.getMinBufferSize(TAXA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val ar = AudioRecord(
                MediaRecorder.AudioSource.MIC, TAXA,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minimo, 16000)
            )
            val escritor = EscritorWav(wav)
            val texto = StringBuilder()
            val buf = ShortArray(4000)
            try {
                ar.startRecording()
                while (ativo.get()) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    escritor.escrever(buf, n)
                    if (rec.acceptWaveForm(buf, n)) {
                        juntar(texto, campo(rec.result, "text"))
                        parcial(texto.toString())
                    } else {
                        val p = campo(rec.partialResult, "partial")
                        parcial((texto.toString() + " " + p).trim())
                    }
                }
            } finally {
                runCatching { ar.stop() }
                ar.release()
                escritor.fechar()
            }
            juntar(texto, campo(rec.finalResult, "text"))
            rec.close()
            texto.toString().trim()
        }

    /** Transcreve um arquivo de áudio já gravado (mp3, m4a, aac, ogg, wav, opus…). */
    suspend fun transcreverArquivo(uri: Uri, progresso: (Float, String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val rec = Recognizer(carregar(), TAXA.toFloat())
            val ex = MediaExtractor()
            ex.setDataSource(ctx, uri, null)
            val faixa = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: run { ex.release(); rec.close(); error("O arquivo não tem áudio.") }
            ex.selectTrack(faixa)
            val formato = ex.getTrackFormat(faixa)
            val duracao = if (formato.containsKey(MediaFormat.KEY_DURATION)) formato.getLong(MediaFormat.KEY_DURATION) else 0L
            var taxa = formato.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var canais = formato.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false

            val codec = MediaCodec.createDecoderByType(formato.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(formato, null, null, 0)
            codec.start()

            val reamostrador = Reamostrador()
            val info = MediaCodec.BufferInfo()
            val texto = StringBuilder()
            var fimEntrada = false
            var fimSaida = false
            try {
                while (!fimSaida) {
                    if (!fimEntrada) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val b = codec.getInputBuffer(i)!!
                            val n = ex.readSampleData(b, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                fimEntrada = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        taxa = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        canais = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    } else if (o >= 0) {
                        val b = codec.getOutputBuffer(o)!!
                        b.position(info.offset)
                        b.limit(info.offset + info.size)
                        b.order(ByteOrder.LITTLE_ENDIAN)
                        val amostras: ShortArray = if (pcmFloat) {
                            val fb = b.asFloatBuffer()
                            ShortArray(fb.remaining()) { (fb.get().coerceIn(-1f, 1f) * 32767).toInt().toShort() }
                        } else {
                            val sb = b.asShortBuffer()
                            ShortArray(sb.remaining()).also { sb.get(it) }
                        }
                        codec.releaseOutputBuffer(o, false)

                        val mono = paraMono(amostras, canais)
                        val pronto = reamostrador.processar(mono, taxa)
                        if (pronto.isNotEmpty() && rec.acceptWaveForm(pronto, pronto.size)) {
                            juntar(texto, campo(rec.result, "text"))
                        }
                        if (duracao > 0) {
                            progresso((info.presentationTimeUs.toFloat() / duracao).coerceIn(0f, 1f), texto.takeLast(160).toString())
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) fimSaida = true
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
                ex.release()
            }
            juntar(texto, campo(rec.finalResult, "text"))
            rec.close()
            progresso(1f, "")
            texto.toString().trim()
        }

    private fun campo(json: String, nome: String): String =
        runCatching { JSONObject(json).optString(nome) }.getOrDefault("")

    private fun juntar(sb: StringBuilder, parte: String) {
        if (parte.isBlank()) return
        if (sb.isNotEmpty()) sb.append(' ')
        sb.append(parte.trim())
    }

    private fun paraMono(a: ShortArray, canais: Int): ShortArray {
        if (canais <= 1) return a
        val n = a.size / canais
        return ShortArray(n) { i ->
            var s = 0
            for (c in 0 until canais) s += a[i * canais + c]
            (s / canais).toShort()
        }
    }
}

/** Converte qualquer taxa de amostragem para 16 kHz (interpolação linear, contínua entre blocos). */
private class Reamostrador {
    private var pos = 0.0
    private var ultimo: Short = 0

    fun processar(ent: ShortArray, taxa: Int): ShortArray {
        if (ent.isEmpty()) return ent
        if (taxa == TAXA) return ent
        val passo = taxa.toDouble() / TAXA
        val saida = ShortArray(((ent.size - pos) / passo).toInt() + 2)
        var k = 0
        var p = pos
        while (p < ent.size - 1 && k < saida.size) {
            val i = floor(p).toInt()
            val f = p - i
            val a = if (i < 0) ultimo.toDouble() else ent[i].toDouble()
            val b = ent[i + 1].toDouble()
            saida[k++] = (a + (b - a) * f).toInt().toShort()
            p += passo
        }
        pos = p - ent.size
        ultimo = ent.last()
        return saida.copyOf(k)
    }
}

/** Grava PCM 16 bits mono 16 kHz em .wav. */
private class EscritorWav(private val arquivo: File) {
    private val saida = arquivo.outputStream().buffered()
    private var bytes = 0L

    init {
        saida.write(ByteArray(44))
    }

    fun escrever(buf: ShortArray, n: Int) {
        val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) bb.putShort(buf[i])
        saida.write(bb.array())
        bytes += n * 2
    }

    fun fechar() {
        saida.flush(); saida.close()
        RandomAccessFile(arquivo, "rw").use { raf ->
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt((36 + bytes).toInt()); h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
            h.putInt(TAXA); h.putInt(TAXA * 2); h.putShort(2); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(bytes.toInt())
            raf.seek(0); raf.write(h.array())
        }
    }
}
