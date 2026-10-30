# Visitas Mgeo

App Android para registrar visitas técnicas às usinas, mesmo sem sinal de internet.

## O que funciona offline

- Gravar áudio e transcrever na hora (modelo de voz em português que vai dentro do APK)
- Importar áudios já gravados (mp3, m4a, aac, ogg, opus, wav) e transcrever
- Ouvir de novo cada trecho gravado
- Fotos pela câmera ou galeria, com legenda
- Salvar os relatórios no celular (salvamento automático)
- Gerar o PDF do relatório (tela de impressão do Android → "Salvar como PDF")
- Compartilhar o relatório por WhatsApp, e-mail etc.
- Backup de todas as visitas em .zip

## O que precisa de internet

Só o **resumo, o parecer técnico, as pendências e as próximas ações**, que são gerados pela IA do Claude.
Na roça, toque em "Gerar resumo e parecer": o relatório fica marcado como **pendente**. Quando o celular
pegar sinal, abra o app e toque em **Gerar agora** na tela inicial. Todos os pendentes são processados de uma vez.

---

## Como gerar o APK (sem instalar nada no computador)

1. Crie uma conta em [github.com](https://github.com) (se ainda não tiver).
2. Crie um repositório novo (pode ser **privado**), por exemplo `visitas-mgeo`.
3. Envie todos os arquivos desta pasta para o repositório.
   Pelo site: **Add file → Upload files**, arraste o conteúdo da pasta (incluindo a pasta `.github`) e confirme em **Commit changes**.
   > Se o upload pelo site não levar a pasta `.github`, crie o arquivo manualmente em
   > **Add file → Create new file**, com o nome `.github/workflows/gerar-apk.yml`, e cole o conteúdo.
4. Abra a aba **Actions**. O build "Gerar APK" começa sozinho e leva de 5 a 10 minutos.
5. Quando terminar (ícone verde), vá em **Releases** (lado direito da página do repositório)
   e baixe o arquivo `VisitasMgeo-vN.apk`.

Cada alteração enviada ao repositório gera um APK novo automaticamente.
Para gerar de novo sem mudar nada: **Actions → Gerar APK → Run workflow**.

## Como instalar no celular

1. Baixe o `.apk` no celular (pelo navegador ou pelo app do GitHub, se o repositório for privado).
2. Toque no arquivo. Se o Android pedir, permita **instalar apps desta fonte**.
3. Abra o app **Visitas Mgeo**. Na primeira abertura ele prepara o modelo de voz (alguns segundos).

Versões novas instalam **por cima** da antiga sem apagar os relatórios, porque todos os APKs
são assinados com a mesma chave (`app/equipe.keystore`).

Para passar o app para outro celular sem internet, envie o `.apk` por cabo, cartão de memória ou Nearby Share.

## Configurar o resumo com IA

1. Crie uma chave de API em [console.anthropic.com](https://console.anthropic.com) (o uso é cobrado por consumo).
2. No app: **Configurações → Chave da API**, cole a chave e salve.
3. Também em Configurações: seu nome, que vem preenchido em cada visita nova.

A chave fica só no celular. Ela nunca vai para o GitHub.

## Dicas de uso em campo

- Durante a gravação a tela fica ligada. **Não bloqueie a tela nem troque de app enquanto grava**,
  porque o Android corta o microfone de apps em segundo plano.
- Grave em trechos curtos, um por assunto ou equipamento. Fica mais fácil revisar.
- Fale perto do celular e evite gravar ao lado de inversores, transformadores ou motores ligados.
- A transcrição offline sai sem pontuação e erra alguns nomes. Corrija nomes de equipamentos e números
  no "Texto bruto" antes de gerar o resumo, se forem importantes.
- Faça **Backup** com frequência (botão na tela inicial) e guarde o .zip no Drive ou no computador.
  Os relatórios ficam só no celular; se o app for desinstalado, eles são apagados.

## Detalhes técnicos

- Kotlin + Jetpack Compose, Android 8.0 (API 26) ou superior
- Transcrição: [Vosk](https://alphacephei.com/vosk/) com `vosk-model-small-pt-0.3` (cerca de 30 MB),
  baixado automaticamente pelo GitHub Actions no momento do build
- IA: API do Claude (`/v1/messages`); o modelo pode ser trocado em Configurações
- Dados: `files/visitas/<id>/` no armazenamento interno do app (`visita.json`, fotos `.jpg`, áudios `.wav`)

Para abrir no Android Studio: baixe o modelo de voz, descompacte em `app/src/main/assets/model-pt`
e abra a pasta do projeto (o Android Studio cria o Gradle Wrapper se pedir).

**Chave de assinatura:** `app/equipe.keystore` tem senha padrão `mgeo-visitas`. Serve para uso interno da equipe.
Se o repositório for público, ou se o app for para a Play Store, gere uma chave nova e coloque a senha no
secret `KEYSTORE_PASSWORD` do repositório.
