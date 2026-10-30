@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.mgeo.visitas

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.OpenableColumns
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private val Grafite = Color(0xFF1E2B2F)
private val Ambar = Color(0xFFE8A317)
private val Vermelho = Color(0xFFC8372D)

class MainActivity : ComponentActivity() {
    lateinit var repo: Repositorio
    lateinit var prefs: Preferencias
    lateinit var transcritor: Transcritor
    private var webViewImpressao: WebView? = null
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repo = Repositorio(this)
        prefs = Preferencias(this)
        transcritor = Transcritor(applicationContext)
        setContent {
            val escuro = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (escuro)
                    darkColorScheme(primary = Ambar, onPrimary = Grafite, secondary = Ambar)
                else
                    lightColorScheme(primary = Grafite, onPrimary = Color.White, secondary = Ambar, onSecondary = Grafite)
            ) { App(this) }
        }
    }

    fun manterTelaLigada(sim: Boolean) {
        if (sim) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun uriDe(arq: File): Uri = FileProvider.getUriForFile(this, "$packageName.arquivos", arq)

    fun compartilhar(arq: File, tipo: String, texto: String? = null) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = tipo
            putExtra(Intent.EXTRA_STREAM, uriDe(arq))
            texto?.let { putExtra(Intent.EXTRA_TEXT, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Compartilhar"))
    }

    /** Abre a tela de impressão do Android, onde dá para escolher "Salvar como PDF". Funciona offline. */
    fun imprimir(html: String, nome: String) {
        val wv = WebView(this)
        webViewImpressao = wv
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val pm = getSystemService(PrintManager::class.java)
                pm.print(
                    nome, view.createPrintDocumentAdapter(nome),
                    PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build()
                )
            }
        }
        wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    fun tocar(arq: File) {
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(arq.absolutePath)
            prepare()
            start()
        }
    }

    fun pararAudio() {
        player?.release(); player = null
    }

    fun nomeArquivo(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "áudio importado"

    override fun onDestroy() {
        player?.release()
        super.onDestroy()
    }
}

private sealed interface Tela {
    data object Lista : Tela
    data object Config : Tela
    data class Editor(val id: String?) : Tela
}

@Composable
private fun App(act: MainActivity) {
    var tela by remember { mutableStateOf<Tela>(Tela.Lista) }
    var statusModelo by remember { mutableStateOf("Preparando o reconhecimento de voz offline…") }
    LaunchedEffect(Unit) {
        statusModelo = try {
            act.transcritor.carregar(); ""
        } catch (e: Throwable) {
            "Falha ao carregar o modelo de voz: ${e.message}"
        }
    }
    when (val t = tela) {
        Tela.Lista -> TelaLista(act, statusModelo, abrir = { tela = Tela.Editor(it) }, config = { tela = Tela.Config })
        Tela.Config -> TelaConfig(act, voltar = { tela = Tela.Lista })
        is Tela.Editor -> TelaEditor(act, t.id, statusModelo, voltar = { tela = Tela.Lista })
    }
}

/* ------------------------------------------------------------------ LISTA */

@Composable
private fun TelaLista(act: MainActivity, statusModelo: String, abrir: (String?) -> Unit, config: () -> Unit) {
    var visitas by remember { mutableStateOf(act.repo.listar()) }
    var busca by remember { mutableStateOf("") }
    var gerando by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val escopo = rememberCoroutineScope()
    val pendentes = visitas.filter { it.iaPendente }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Visitas Mgeo", fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(onClick = {
                        val dia = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        val zip = File(act.cacheDir, "backup-visitas-mgeo-$dia.zip")
                        escopo.launch {
                            withContext(Dispatchers.IO) { Relatorio.backup(act.repo.pastaRaiz(), zip) }
                            act.compartilhar(zip, "application/zip")
                        }
                    }) { Text("Backup") }
                    TextButton(onClick = config) { Text("Configurações") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { abrir(null) }, containerColor = Ambar, contentColor = Grafite) {
                Text("Nova visita", fontWeight = FontWeight.Bold)
            }
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
            if (statusModelo.isNotEmpty()) item {
                Text(statusModelo, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            }
            if (pendentes.isNotEmpty()) item {
                Card(Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = Ambar.copy(alpha = 0.18f))) {
                    Column(Modifier.padding(14.dp)) {
                        Text("${pendentes.size} relatório(s) aguardando o resumo da IA", fontWeight = FontWeight.Bold)
                        Text("Gere quando o celular tiver internet.", fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Button(enabled = !gerando, onClick = {
                            if (act.prefs.chaveApi.isBlank()) { escopo.launch { snack.showSnackbar("Cadastre a chave da API em Configurações.") }; return@Button }
                            if (!temInternet(act)) { escopo.launch { snack.showSnackbar("Ainda sem internet.") }; return@Button }
                            gerando = true
                            escopo.launch {
                                var ok = 0
                                for (p in pendentes) {
                                    try {
                                        val nova = ClaudeApi.gerar(p, act.prefs.chaveApi, act.prefs.modelo)
                                        withContext(Dispatchers.IO) { act.repo.salvar(nova) }
                                        ok++
                                    } catch (e: Exception) {
                                        snack.showSnackbar("${p.usina}: ${e.message}")
                                        break
                                    }
                                }
                                visitas = act.repo.listar()
                                gerando = false
                                if (ok > 0) snack.showSnackbar("$ok relatório(s) atualizado(s).")
                            }
                        }) { Text(if (gerando) "Gerando…" else "Gerar agora") }
                    }
                }
            }
            item {
                OutlinedTextField(busca, { busca = it }, label = { Text("Buscar por usina ou técnico") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }
            val filtradas = visitas.filter { busca.isBlank() || "${it.usina} ${it.tecnico}".contains(busca, ignoreCase = true) }
            if (filtradas.isEmpty()) item {
                Text(
                    if (busca.isBlank()) "Nenhum relatório ainda. Toque em Nova visita para gravar a primeira."
                    else "Nenhum relatório encontrado.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp)
                )
            }
            items(filtradas, key = { it.id }) { v ->
                Card(onClick = { abrir(v.id) }, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Row {
                        Box(Modifier.width(6.dp).height(78.dp).background(if (v.iaPendente) Ambar else MaterialTheme.colorScheme.primary))
                        Column(Modifier.padding(12.dp)) {
                            Text(v.usina.ifBlank { "Usina sem nome" }, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("${v.dataBr} · ${v.tecnico.ifBlank { "técnico não informado" }} · ${v.fotos.size} foto(s)", fontSize = 14.sp)
                            if (v.iaPendente) Text("Resumo da IA pendente", fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}

/* ------------------------------------------------------------------ CONFIG */

@Composable
private fun TelaConfig(act: MainActivity, voltar: () -> Unit) {
    var chave by remember { mutableStateOf(act.prefs.chaveApi) }
    var modelo by remember { mutableStateOf(act.prefs.modelo) }
    var tecnico by remember { mutableStateOf(act.prefs.tecnico) }
    BackHandler(onBack = voltar)
    Scaffold(topBar = {
        TopAppBar(title = { Text("Configurações") }, navigationIcon = { TextButton(onClick = voltar) { Text("Voltar") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).imePadding().verticalScroll(rememberScrollState())) {
            OutlinedTextField(tecnico, { tecnico = it }, label = { Text("Seu nome (técnico)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Text("Resumo com IA", fontWeight = FontWeight.Bold)
            Text(
                "A gravação, a transcrição, as fotos e o relatório funcionam sem internet. Só o resumo e o parecer precisam de sinal, " +
                    "usando a API do Claude. Crie a chave em console.anthropic.com.", fontSize = 14.sp
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(chave, { chave = it }, label = { Text("Chave da API (sk-ant-…)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(modelo, { modelo = it }, label = { Text("Modelo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                act.prefs.chaveApi = chave; act.prefs.modelo = modelo; act.prefs.tecnico = tecnico; voltar()
            }, modifier = Modifier.fillMaxWidth()) { Text("Salvar configurações") }
            Spacer(Modifier.height(16.dp))
            Text("Versão ${BuildConfig.VERSION_NAME}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/* ------------------------------------------------------------------ EDITOR */

@Composable
private fun Secao(titulo: String, conteudo: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(titulo, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            Spacer(Modifier.height(8.dp))
            conteudo()
        }
    }
}

@Composable
private fun TelaEditor(act: MainActivity, idInicial: String?, statusModelo: String, voltar: () -> Unit) {
    var v by remember {
        mutableStateOf(idInicial?.let { act.repo.carregar(it) } ?: Visita(tecnico = act.prefs.tecnico))
    }
    val pasta = remember(v.id) { act.repo.pasta(v.id) }
    val escopo = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    fun aviso(msg: String) { escopo.launch { snack.showSnackbar(msg) } }

    // salvamento automático: nada se perde se a bateria acabar
    LaunchedEffect(v) {
        if (!v.vazia) {
            delay(700)
            withContext(Dispatchers.IO) { act.repo.salvar(v.copy(atualizadoEm = System.currentTimeMillis())) }
        }
    }

    val ativo = remember { AtomicBoolean(false) }
    var gravando by remember { mutableStateOf(false) }
    var segundos by remember { mutableIntStateOf(0) }
    var aoVivo by remember { mutableStateOf("") }
    var importando by remember { mutableStateOf(false) }
    var progresso by remember { mutableStateOf(0f) }
    var gerando by remember { mutableStateOf(false) }
    var confirmarExclusao by remember { mutableStateOf(false) }

    fun sair() {
        ativo.set(false)
        act.pararAudio()
        if (!v.vazia) act.repo.salvar(v.copy(atualizadoEm = System.currentTimeMillis()))
        voltar()
    }
    BackHandler { sair() }
    DisposableEffect(Unit) { onDispose { ativo.set(false); act.manterTelaLigada(false) } }

    fun iniciarGravacao() {
        if (statusModelo.isNotEmpty()) { aviso(statusModelo); return }
        val nomeWav = "audio-${System.currentTimeMillis()}.wav"
        val arq = File(pasta, nomeWav)
        val hora = horaAgora()
        ativo.set(true); gravando = true; aoVivo = "Ouvindo…"; act.manterTelaLigada(true)
        escopo.launch {
            val inicio = System.currentTimeMillis()
            val relogio = launch { while (isActive) { segundos = ((System.currentTimeMillis() - inicio) / 1000).toInt(); delay(500) } }
            try {
                val texto = act.transcritor.gravar(arq, ativo) { p -> act.runOnUiThread { aoVivo = p.ifBlank { "Ouvindo…" } } }
                v = v.comTrecho(Trecho("Gravação", hora, texto.ifBlank { "(nenhuma fala reconhecida)" }, nomeWav))
                aoVivo = "Trecho ${v.trechos.size} adicionado ao texto bruto."
            } catch (e: Exception) {
                aviso("Erro na gravação: ${e.message}")
            } finally {
                relogio.cancel(); gravando = false; act.manterTelaLigada(false)
            }
        }
    }

    val pedirMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) iniciarGravacao() else aviso("Sem permissão do microfone não é possível gravar.")
    }

    val importarAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (statusModelo.isNotEmpty()) { aviso(statusModelo); return@rememberLauncherForActivityResult }
        importando = true; act.manterTelaLigada(true)
        escopo.launch {
            for ((n, uri) in uris.withIndex()) {
                val nome = act.nomeArquivo(uri)
                try {
                    progresso = 0f; aoVivo = "Transcrevendo ${n + 1} de ${uris.size}: $nome"
                    val texto = act.transcritor.transcreverArquivo(uri) { p, t ->
                        act.runOnUiThread { progresso = p; if (t.isNotBlank()) aoVivo = "…$t" }
                    }
                    v = v.comTrecho(Trecho(nome, horaAgora(), texto.ifBlank { "(nenhuma fala reconhecida)" }))
                } catch (e: Exception) {
                    aviso("Não foi possível transcrever $nome: ${e.message}")
                }
            }
            aoVivo = "Importação concluída."
            importando = false; act.manterTelaLigada(false)
        }
    }

    fun adicionarFotos(uris: List<Uri>) {
        escopo.launch {
            val novas = withContext(Dispatchers.IO) {
                uris.mapNotNull { u ->
                    runCatching {
                        val nome = "foto-${System.currentTimeMillis()}-${(100..999).random()}.jpg"
                        Relatorio.importarFoto(act, u, File(pasta, nome))
                        Foto(nome)
                    }.getOrNull()
                }
            }
            if (novas.size < uris.size) aviso("Uma ou mais fotos não puderam ser adicionadas.")
            v = v.copy(fotos = v.fotos + novas)
        }
    }
    val arqCamera = remember { File(act.cacheDir, "camera.jpg") }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) adicionarFotos(listOf(act.uriDe(arqCamera)))
    }
    val galeria = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) adicionarFotos(uris)
    }

    fun gerarIa() {
        if (v.textoBruto.isBlank()) { aviso("Grave ou importe o áudio primeiro."); return }
        if (act.prefs.chaveApi.isBlank()) {
            v = v.copy(iaPendente = true)
            aviso("Cadastre a chave da API em Configurações. O relatório ficou marcado como pendente."); return
        }
        if (!temInternet(act)) {
            v = v.copy(iaPendente = true)
            aviso("Sem internet. O relatório ficou marcado e você gera o resumo na tela inicial quando tiver sinal."); return
        }
        gerando = true
        escopo.launch {
            try {
                v = ClaudeApi.gerar(v, act.prefs.chaveApi, act.prefs.modelo)
                aviso("Resumo e parecer prontos. Revise antes de enviar.")
            } catch (e: Exception) {
                v = v.copy(iaPendente = true)
                aviso(e.message ?: "Falha ao gerar. Tente de novo.")
            } finally { gerando = false }
        }
    }

    fun nomeRelatorio() = "Relatorio_${v.usina.ifBlank { "usina" }}_${v.data}".replace(Regex("[^\\w\\-]+"), "_")

    if (confirmarExclusao) {
        AlertDialog(
            onDismissRequest = { confirmarExclusao = false },
            title = { Text("Excluir relatório?") },
            text = { Text("O relatório, os áudios e as fotos desta visita serão apagados do celular.") },
            confirmButton = {
                TextButton(onClick = { act.repo.excluir(v.id); confirmarExclusao = false; voltar() }) { Text("Excluir", color = Vermelho) }
            },
            dismissButton = { TextButton(onClick = { confirmarExclusao = false }) { Text("Cancelar") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (idInicial == null) "Nova visita" else "Visita", fontWeight = FontWeight.Bold) },
                navigationIcon = { TextButton(onClick = { sair() }) { Text("Voltar") } },
                actions = { if (idInicial != null) TextButton(onClick = { confirmarExclusao = true }) { Text("Excluir", color = Vermelho) } }
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp).padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = {
                    escopo.launch {
                        val html = withContext(Dispatchers.IO) { Relatorio.html(v, pasta) }
                        act.imprimir(html, nomeRelatorio())
                    }
                }, modifier = Modifier.weight(1f)) { Text("PDF") }
                Button(onClick = {
                    escopo.launch {
                        val arq = File(act.cacheDir, nomeRelatorio() + ".html")
                        withContext(Dispatchers.IO) { arq.writeText(Relatorio.html(v, pasta)) }
                        act.compartilhar(arq, "text/html", Relatorio.texto(v))
                    }
                }, modifier = Modifier.weight(1f)) { Text("Compartilhar") }
            }
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)
        ) {
            Secao("Dados da visita") {
                OutlinedTextField(v.usina, { v = v.copy(usina = it) }, label = { Text("Usina") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    val c = Calendar.getInstance()
                    runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(v.data) }.getOrNull()?.let { c.time = it }
                    DatePickerDialog(act, { _, a, m, d ->
                        v = v.copy(data = "%04d-%02d-%02d".format(a, m + 1, d))
                    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                }, modifier = Modifier.fillMaxWidth()) { Text("Data: ${v.dataBr}") }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(v.tecnico, { v = v.copy(tecnico = it) }, label = { Text("Técnico responsável") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(v.participantes, { v = v.copy(participantes = it) }, label = { Text("Participantes") }, modifier = Modifier.fillMaxWidth())
            }

            Secao("Gravação") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            if (gravando) ativo.set(false)
                            else if (ContextCompat.checkSelfPermission(act, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) iniciarGravacao()
                            else pedirMic.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        enabled = !importando,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = if (gravando) Vermelho else Grafite, contentColor = Color.White),
                        modifier = Modifier.size(84.dp)
                    ) { Text(if (gravando) "Parar" else "Gravar", fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("%02d:%02d".format(segundos / 60, segundos % 60), fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        Text(if (gravando) "Gravando. A tela fica ligada." else "Cada gravação vira um trecho.", fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { importarAudio.launch(arrayOf("audio/*", "application/ogg", "video/mp4")) },
                    enabled = !gravando && !importando, modifier = Modifier.fillMaxWidth()) { Text("Importar áudio já gravado") }
                if (importando) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progresso }, modifier = Modifier.fillMaxWidth())
                }
                if (aoVivo.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(aoVivo, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp))
                }
                v.trechos.forEachIndexed { i, t ->
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Trecho ${i + 1} · ${t.origem} · ${t.hora}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("${t.texto.split(Regex("\\s+")).size} palavras", fontSize = 13.sp)
                        }
                        val audio = t.audio?.let { File(pasta, it) }
                        if (audio != null && audio.exists()) TextButton(onClick = {
                            runCatching { act.tocar(audio) }.onFailure { aviso("Não foi possível tocar o áudio.") }
                        }) { Text("Ouvir") }
                    }
                }
            }

            Secao("Texto bruto") {
                OutlinedTextField(v.textoBruto, { v = v.copy(textoBruto = it) }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    placeholder = { Text("Os trechos gravados e importados aparecem aqui. Você pode corrigir ou digitar.") })
                Text("A transcrição offline sai sem pontuação. A IA organiza isso no resumo.", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }

            Secao("Fotos") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { camera.launch(act.uriDe(arqCamera)) }, modifier = Modifier.weight(1f)) { Text("Tirar foto") }
                    OutlinedButton(onClick = {
                        galeria.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, modifier = Modifier.weight(1f)) { Text("Da galeria") }
                }
                v.fotos.chunked(2).forEach { par ->
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        par.forEach { f ->
                            Column(Modifier.weight(1f)) {
                                Miniatura(File(pasta, f.arquivo))
                                OutlinedTextField(f.legenda, { nova ->
                                    v = v.copy(fotos = v.fotos.map { if (it.arquivo == f.arquivo) it.copy(legenda = nova) else it })
                                }, placeholder = { Text("Legenda") }, textStyle = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                                TextButton(onClick = {
                                    File(pasta, f.arquivo).delete()
                                    v = v.copy(fotos = v.fotos.filter { it.arquivo != f.arquivo })
                                }) { Text("Remover", color = Vermelho) }
                            }
                        }
                        if (par.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            Secao("Resumo e parecer") {
                Button(onClick = { gerarIa() }, enabled = !gerando && !gravando && !importando, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Ambar, contentColor = Grafite)) {
                    Text(if (gerando) "Gerando… pode levar até um minuto" else "Gerar resumo e parecer", fontWeight = FontWeight.Bold)
                }
                if (v.iaPendente) Text("Pendente: será gerado quando houver internet.", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(v.parecer, { v = v.copy(parecer = it) }, label = { Text("Parecer técnico") }, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(v.resumo, { v = v.copy(resumo = it) }, label = { Text("Resumo") }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(v.pendencias, { v = v.copy(pendencias = it) }, label = { Text("Pendências: item | responsável | prazo") }, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(v.acoes, { v = v.copy(acoes = it) }, label = { Text("Próximas ações (uma por linha)") }, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Miniatura(arq: File) {
    val img by produceState<ImageBitmap?>(null, arq) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val b = Relatorio.miniatura(arq, 500)
                BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val m = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(6.dp))
    val atual = img
    if (atual != null) Image(atual, contentDescription = null, contentScale = ContentScale.Crop, modifier = m)
    else Box(m.background(MaterialTheme.colorScheme.surfaceVariant))
}
