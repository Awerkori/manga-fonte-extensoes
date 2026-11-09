# Project Nox · Código-fonte das Extensões de Mangá

Repositório central de desenvolvimento, código-fonte em Kotlin e automação de compilação das extensões de mangá do **Project Nox** para **Mihon e forks**.

---

> [!IMPORTANT]
> **Procurando apenas instalar as extensões no seu aplicativo?**  
> Este repositório destina-se a desenvolvedores e contém o código-fonte em Kotlin para desenvolvimento e manutenção. Para instalar ou atualizar os catálogos prontos no seu leitor, utilize o repositório de distribuição oficial:  
> ➡️ [**Project Nox · Extensões de Mangá (Distribuição)**](https://github.com/Awerkori/manga-extensoes)

---

## 🎯 Sobre o Projeto

O ecossistema de mangás do **Project Nox** mantém fontes ativas e atualizadas com foco na comunidade brasileira (PT-BR) e fontes selecionadas. As principais responsabilidades deste projeto incluem:

- **Desenvolvimento e Manutenção Contínua:** Implementação de parsers em Kotlin baseados na API de extensões para **Mihon e forks**, contornando proteções anti-bot, atualizando seletores CSS/JSON e garantindo estabilidade no carregamento de capítulos.
- **Pipeline de Integração Contínua (CI):** Compilação automatizada via Gradle e GitHub Actions, com validação de tipos, testes sintéticos de catalogação e empacotamento em APKs assinados.
- **Sincronização Upstream com Guardião de Fontes:** Mecanismo autônomo que importa melhorias da base global upstream enquanto protege de forma determinística as fontes e customizações exclusivas desenvolvidas pelo Project Nox.
- **Publicação Automática:** Cada alteração aprovada na branch principal compila os pacotes e publica os novos artefatos e o índice Protobuf/JSON diretamente no repositório [`manga-extensoes`](https://github.com/Awerkori/manga-extensoes).

---

## 🏗️ Estrutura do Repositório

O projeto é organizado de forma modular em Gradle:

```
├── src/                    # Extensões separadas por idioma
│   ├── pt/                 # Extensões em Português (Brasil) — foco principal
│   ├── all/                # Extensões multisource globais
│   └── ...                 # Outros idiomas mantidos
├── lib/                    # Bibliotecas auxiliares compartilhadas entre extensões
├── lib-multisrc/           # Temas multisource compartilhados (Madara, MangaThemesia, etc.)
├── core/                   # Utilitários centrais de catalogação e testes unitários
├── .github/
│   ├── workflows/          # Workflows de CI (build_push.yml, sync_upstream.yml)
│   └── scripts/            # Scripts em Python para geração do catálogo e sincronização
└── build.gradle.kts        # Configurações globais de compilação Gradle
```

---

## 💻 Como Compilar e Desenvolver Localmente

### Pré-requisitos
- **JDK 17** ou superior instalado e configurado no ambiente (`JAVA_HOME`).
- **Android SDK** com Build-Tools e Platform-Tools atualizados (Android SDK 30+).

### Clonar o projeto
```bash
git clone https://github.com/Awerkori/manga-fonte-extensoes.git
cd manga-fonte-extensoes
```

### Comandos de Compilação

Para compilar uma extensão específica em modo debug:
```bash
# Exemplo: compilar uma extensão em português
./gradlew :src:pt:<nome-da-extensao>:assembleDebug
```
O arquivo APK compilado será gerado em:  
`src/pt/<nome-da-extensao>/build/outputs/apk/debug/`

### Verificação de Qualidade e Formatação
```bash
# Executar testes unitários do núcleo
./gradlew test

# Verificar e aplicar a formatação padrão do código (Spotless)
./gradlew spotlessApply
```

---

## 🤝 Como Contribuir

Contribuições da comunidade são muito bem-vindas:

1. **Consulte a Central de Pedidos:** Antes de iniciar o desenvolvimento, verifique na [Central de Pedidos](https://github.com/Awerkori/project-nox-requests) se já existe uma demanda aberta ou em andamento.
2. **Crie uma branch semântica:** Baseie-se na branch `main` e nomeie de forma clara (ex: `fix/nome-da-fonte` ou `feat/nova-fonte`).
3. **Mantenha os padrões técnicos:** Use seletores estáveis, evite dependências desnecessárias e garanta que `./gradlew spotlessApply` seja executado antes do commit.
4. **Envie seu Pull Request:** Descreva as alterações realizadas, os testes feitos no emulador ou dispositivo físico e anexe evidências se apropriado.

---

## 🛠️ Suporte e Reporte de Erros

Se encontrar uma extensão que parou de carregar imagens, apresentou erro de decodificação ou sofreu alteração de layout no site de origem:

- 🎫 **Abrir solicitação oficial:** [Project Nox · Central de Pedidos](https://github.com/Awerkori/project-nox-requests)
- 📊 **Acompanhar status em tempo real:** [Project Nox · Tracker](https://awerkori.github.io/project-nox-tracker/)

---

## 🔗 Projetos Relacionados

- 📦 [**Project Nox · Extensões de Mangá**](https://github.com/Awerkori/manga-extensoes) — Repositório de distribuição (APKs compilados e índice para o aplicativo).
- 🎬 [**Project Nox · Extensões de Anime**](https://github.com/Awerkori/anime-extensoes) — Catálogo de extensões de anime para Aniyomi e forks.
- 💻 [**Project Nox · Código-fonte das Extensões de Anime**](https://github.com/Awerkori/anime-fonte-extensoes) — Código-fonte e extractors de vídeo.
- 🌐 [**Project Nox · Plataforma Web de Mangá**](https://github.com/Awerkori/project-nox-manga) — Leitor web oficial do Project Nox.
- ⚙️ [**Project Nox · Importer**](https://github.com/Awerkori/project-nox-importer) — Serviço de ingestão contínua de catálogo 24/7.

---

## 🌟 Créditos e Atribuições

O **Project Nox** expressa seu reconhecimento e agradecimento a todos que tornaram este projeto possível:

- **Felipe GFA** ([@FelipeGFA](https://github.com/FelipeGFA)): Contribuição fundamental na concepção e engenharia do projeto, responsável pela criação e manutenção de dezenas de extensões da comunidade brasileira (Nexus, Manhastro, MangaLivre, Yomu, LycanToons, PlumaComics, MangoTheme, entre outras), pela implementação dos mecanismos avançados de sincronização upstream e pelo pipeline de publicação do catálogo Protobuf.
- **Keiyoushi** ([@keiyoushi](https://github.com/keiyoushi)): Pela base upstream e arquitetura de referência do ecossistema de extensões de leitura.
- **Javier Tomás** e colaboradores históricos do ecossistema open-source de mangás.

---

## ⚖️ Licença e Governança

O trabalho combinado deste repositório é distribuído sob os termos da **GNU General Public License versão 3.0** (`GPL-3.0-only`), respeitando a compatibilidade e preservando integralmente os direitos dos autores originais:

- **Código Upstream (Keiyoushi / Tachiyomi):** Licenciado sob a [Apache License 2.0](LICENSE-APACHE). Todos os avisos de direitos autorais originais foram integralmente mantidos.
- **Contribuições Originais do Project Nox:** Licenciadas sob a [GNU GPL v3.0](LICENSE) (`GPL-3.0-only`).
- **Trabalho Combinado:** Distribuído sob a **GNU GPL v3.0** em virtude da compatibilidade unidirecional Apache 2.0 → GPLv3.

Consulte o arquivo [NOTICE](NOTICE) para o inventário completo de atribuições, componentes incorporados e diretrizes jurídicas.

```
Copyright 2015 Javier Tomás
Copyright (c) Keiyoushi Contributors
Copyright (c) Felipe GFA
Copyright (C) 2026 Project Nox Contributors & Awerkori

Licensed under the GNU General Public License, Version 3.0 (for Project Nox contributions
and combined work) and the Apache License, Version 2.0 (for upstream Keiyoushi components).
```

### 🔄 Sincronização Upstream e Contribuições
- O Project Nox sincroniza periodicamente atualizações do ecossistema Keiyoushi mantendo os créditos e licenças Apache 2.0 originais intactos.
- Caso desenvolvedores desejem enviar PRs e contribuições deste repositório para o upstream oficial do Keiyoushi, tais contribuições devem ser disponibilizadas sob a licença **Apache 2.0** ou em **duplo licenciamento** (Apache 2.0 + GPL 3.0), visto que projetos estritamente Apache 2.0 não podem incorporar código exclusivamente GPLv3.

### 📌 Nota sobre GPL-3.0 e AGPL-3.0
O Project Nox adota oficialmente a licença **GNU GPL versão 3.0**. Diferentemente da licença GNU AGPLv3, a GPLv3 não contém a cláusula de interação remota por rede (Seção 13 da AGPL).

### 🛡️ Autenticidade e Marca
A concessão das licenças de software livre refere-se estritamente ao código. Ela **não concede** autorização ou licença de uso da marca, do nome "Project Nox" ou de logotipos oficiais para alegar endosso ou representação oficial do projeto.

### ⚖️ Isenção de Responsabilidade
O **Project Nox** desenvolve exclusivamente ferramentas de software livre para interoperabilidade técnica e pesquisa de agregação programática. O projeto não hospeda, gerencia nem armazena conteúdos protegidos por direitos autorais em seus repositórios de código. As extensões realizam apenas requisições diretas a fontes públicas da web.
