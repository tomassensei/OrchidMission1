# TVDE Contas — app Android

App para motoristas TVDE com:

- **Hoje**: contagem das horas de trabalho, descansos, limite das 10 h em 24 h, pausas e 11 h de descanso, com avisos de voz e vibração.
- **Oferta**: quanto a viagem paga por km e por hora, lucro e frase da voz ("Viagem boa", "Analisar", "Recusar").
- **Leitura automática Uber/Bolt**: quando cai uma oferta na Uber Driver ou na Bolt Driver, aparece por cima um cartão com o €/km, o €/h e o lucro, a verde ou vermelho conforme os seus mínimos. Só lê o ecrã: não carrega em nada e nada sai do telemóvel.
- **Fecho do dia**, **Objetivo** (mínimos €/h e €/km) e **Semana** (conferir pagamentos).

## Obter o APK — opção 1: GitHub (sem instalar nada no computador)

1. Crie uma conta grátis em github.com (se ainda não tem).
2. Crie um repositório novo (pode ser privado) e carregue para lá todo o conteúdo desta pasta
   ("Add file" › "Upload files", arraste tudo, incluindo a pasta `.github`).
3. Abra o separador **Actions**. A compilação começa sozinha ("Compilar APK") e demora uns 5 minutos.
4. Quando ficar com ✓ verde, abra-a e, em **Artifacts**, descarregue **TVDE-Contas-apk**.
   Dentro do zip está o `app-debug.apk`.

## Obter o APK — opção 2: Android Studio

1. Instale o Android Studio (grátis) e abra esta pasta (File › Open).
2. Aceite criar o "Gradle wrapper" se ele perguntar e espere a sincronização.
3. Build › Build App Bundle(s) / APK(s) › Build APK(s). O ficheiro fica em `app/build/outputs/apk/debug/`.

## Instalar no telemóvel

1. Envie o `app-debug.apk` para o telemóvel (WhatsApp, Drive…) e toque nele.
2. Permita instalar desta origem e, se o Play Protect avisar, "Mais detalhes" › "Instalar mesmo assim".

## Ligar a leitura Uber/Bolt

1. Abra a TVDE Contas › separador **Oferta** › **Ligar a leitura**.
2. Em Acessibilidade, escolha "TVDE Contas — leitura Uber/Bolt" e ligue.
3. **Android 13 ou mais recente:** como a app não vem da Play Store, o interruptor pode aparecer cinzento
   ("Definição restrita"). Volte à app e toque em **Definições restritas**: abre a informação da app;
   toque nos ⋮ (canto superior direito) › **Permitir definições restritas**, confirme, e volte a ligar.
4. Abra a app uma vez depois de configurar o **Objetivo**, para o cartão ter os seus mínimos.

Alguns telemóveis (Xiaomi, Samsung…) desligam a Acessibilidade para poupar bateria:
tire a TVDE Contas da poupança de bateria.

## Se uma oferta for mal lida

A Uber e a Bolt mudam os ecrãs com as atualizações. Em **Oferta › Última leitura** está o texto que a
app leu da última oferta. Copie-o e envie-o, junto com um print da oferta, para ajustar a leitura
(ficheiro `OfferParser.java`).
