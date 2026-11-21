<p align="center">
  <img src="https://raw.githubusercontent.com/ProjectNox-org/.github/main/assets/brand/nox-emblem.webp" alt="Emblema oficial do Project Nox" width="96" />
</p>

<h1 align="center">Project Nox · Código-fonte das Extensões de Mangá</h1>

<p align="center">
  Código-fonte em Kotlin e automação de compilação para <strong>Mihon e forks</strong> (base Keiyoushi / Tachiyomi).
</p>

> [!IMPORTANT]
> **Quer apenas instalar as extensões no seu leitor?**
> Você **não precisa baixar este repositório** — ele guarda apenas o código-fonte usado por desenvolvedores. As extensões prontas para instalar ficam no repositório oficial de distribuição:
> [**Project Nox · Extensões de Mangá**](https://github.com/ProjectNox-org/manga-extensoes) (`manga-extensoes`)
>
> Para pedir uma extensão nova ou relatar um problema, use a [Central de Pedidos](https://github.com/ProjectNox-org/project-nox-requests) e acompanhe no [Tracker](https://projectnox-org.github.io/project-nox-tracker/).

## Sobre o projeto

O ecossistema de mangás do **Project Nox** mantém extensões ativas com foco na comunidade brasileira (PT-BR), a partir da base upstream do **Keiyoushi / Tachiyomi**:

- **Desenvolvimento e manutenção:** parsers em Kotlin baseados na API `KeiSource`, com atualização de seletores, tratamento de requisições e compatibilidade com Mihon e forks.
- **Compilação e validação:** builds modulares em Gradle via GitHub Actions, com verificação de configuração, testes unitários do núcleo e empacotamento em APKs assinados.
- **Sincronização upstream:** incorporação contínua de melhorias do Keiyoushi preservando as extensões protegidas e modificações do Project Nox listadas em `.github/nox-protected.txt`.
- **Publicação automática:** alterações na branch `main` compilam os módulos afetados e publicam os APKs e os catálogos (`index.pb`, `index.json` e `index.min.json`) em [`manga-extensoes`](https://github.com/ProjectNox-org/manga-extensoes).

## Estrutura do repositório

| Caminho | Finalidade |
|---|---|
| `src/` | Extensões organizadas por idioma (`pt/`, `all/`, entre outros). |
| `lib/` | Bibliotecas compartilhadas (deobfuscação JS, decodificação de imagens, Cronet, etc.). |
| `lib-multisrc/` | Temas multisource reutilizados por várias fontes (Madara, MangaThemesia, FMReader, etc.). |
| `core/` | Utilitários centrais (`keiyoushi.utils`, `keiyoushi.network`) e testes unitários. |
| `common/`, `compiler/`, `lint-rules/` | Infraestrutura comum, geração de código via KSP e regras de lint. |
| `.github/` | Workflows (`build_push.yml`, `sync_upstream.yml`), scripts de publicação e `nox-protected.txt`. |
| `ext-bootstrap.py`, `build.gradle.kts`, `settings.gradle.kts` | Script para criar extensões e configuração modular do Gradle. |

## Desenvolvimento e compilação

### Pré-requisitos

**Ferramentas necessárias:**

- [JDK 17+](https://adoptium.net/) configurado no `JAVA_HOME` (exigido pelo Gradle e pelo Android Gradle Plugin).
- [Android SDK](https://developer.android.com/studio) com Platform-Tools e Build-Tools (recomendado via Android Studio).

**Configurações do projeto** (`gradle/kei.versions.toml`):

- `compileSdk` e `targetSdk`: `37` | `minSdk`: `26` | compatibilidade de bytecode Java: `11`.

### Clonar o repositório

```bash
git clone https://github.com/ProjectNox-org/manga-fonte-extensoes.git
cd manga-fonte-extensoes
```

Como o repositório possui mais de mil módulos, consulte em [`CONTRIBUTING.md`](CONTRIBUTING.md) as instruções de clone parcial e filtragem em `settings.gradle.kts` para agilizar o trabalho local.

### Compilar, testar e formatar

Os exemplos abaixo usam o módulo real `:src:pt:baozinho`. Substitua `:src:pt:baozinho` (e o caminho correspondente) pelo módulo da extensão em que estiver trabalhando:

```bash
# Compilar APK de debug (saída em src/pt/baozinho/build/outputs/apk/debug/)
./gradlew :src:pt:baozinho:assembleDebug

# Lint de release no módulo alterado (ou em :lib:<nome> / :lib-multisrc:<tema>)
./gradlew :src:pt:baozinho:lintRelease

# Testes unitários do núcleo (há também testes em :lib-multisrc:aurora:testDebugUnitTest)
./gradlew :core:testDebugUnitTest

# Aplicar ou verificar formatação padrão (Spotless / ktlint)
./gradlew spotlessApply
./gradlew spotlessCheck
```

Consulte [`CONTRIBUTING.md`](CONTRIBUTING.md) e [`AGENTS.md`](AGENTS.md) para detalhes da API `KeiSource`, criação de fontes com `ext-bootstrap.py` e convenções de código.

## Como contribuir

1. **Verifique pedidos existentes:** consulte a [Central de Pedidos](https://github.com/ProjectNox-org/project-nox-requests) antes de começar.
2. **Crie uma branch a partir da `main`:** use um nome descritivo (ex.: `fix/baozinho` ou `feat/nova-fonte`).
3. **Valide antes de enviar:** teste a extensão em emulador ou dispositivo, atualize `versionCode` (ou `baseVersionCode` em temas) e execute `spotlessApply` e `lintRelease` no módulo alterado.
4. **Envie o Pull Request aqui:** abra o PR exclusivamente em [`ProjectNox-org/manga-fonte-extensoes`](https://github.com/ProjectNox-org/manga-fonte-extensoes) — nunca nos repositórios upstream.

## Suporte e solicitações

- **Central de Pedidos ([`project-nox-requests`](https://github.com/ProjectNox-org/project-nox-requests)):** abrir pedidos de novas extensões e relatar fontes com erro.
- **Tracker ([Project Nox Tracker](https://projectnox-org.github.io/project-nox-tracker/)):** consultar e acompanhar o status das solicitações.

## Projetos relacionados

- [**Project Nox · Extensões de Mangá**](https://github.com/ProjectNox-org/manga-extensoes) — Distribuição oficial de extensões prontas para Mihon e forks.
- [**Project Nox · Código-fonte das Extensões de Anime**](https://github.com/ProjectNox-org/anime-fonte-extensoes) — Código-fonte e extractors das extensões de anime.
- [**Project Nox · Extensões de Anime**](https://github.com/ProjectNox-org/anime-extensoes) — Distribuição oficial das extensões de anime para Aniyomi e forks.

## Créditos e atribuições

- **Felipe GFA** ([@FelipeGFA](https://github.com/FelipeGFA)): concepção e engenharia do projeto, criação e manutenção de dezenas de extensões da comunidade brasileira (como Nexus, Manhastro, MangaLivre, Yomu, LycanToons, PlumaComics e MangoTheme), mecanismos de sincronização upstream e pipeline do catálogo Protobuf.
- **Keiyoushi** ([@keiyoushi](https://github.com/keiyoushi)): base upstream e arquitetura de referência do ecossistema de extensões.
- **Javier Tomás** e demais colaboradores históricos do ecossistema open-source (Tachiyomi/Mihon) e do Project Nox.

## Licenças e informações legais

Este repositório distribui um trabalho combinado sob a **GNU Affero General Public License v3.0** (`AGPL-3.0-only`), preservando os direitos e avisos das bases upstream:

- **Código upstream (Keiyoushi / Tachiyomi):** licenciado sob a [Apache License 2.0](LICENSE-APACHE).
- **Contribuições do Project Nox:** licenciadas sob a [GNU AGPL v3.0](LICENSE) (`AGPL-3.0-only`).
- **Trabalho combinado:** distribuído sob GNU AGPL v3.0 em compatibilidade com a Apache 2.0, preservando os avisos de copyright e atribuições originais, sem reivindicação de titularidade exclusiva sobre código de terceiros.
- **Sincronização e governança:** atualizações upstream são incorporadas downstream mantendo créditos e licenças originais; o desenvolvimento do Project Nox permanece exclusivo à organização `ProjectNox-org`, sem envio de PRs ao upstream.
- **Interação remota de rede (AGPLv3, Seção 13):** conforme os termos da licença, quem operar versão modificada acessível pela rede com interação remota de usuários deve disponibilizar o Código-Fonte Correspondente (*Corresponding Source*).
- **Identidade e marca (AGPLv3, Seção 7(e) / Apache 2.0, Seção 6):** as licenças de software não concedem direito de uso dos nomes ou marcas "Project Nox", "Awerkori" ou de terceiros para sugerir endosso ou representação oficial.
- **Isenção de responsabilidade:** software livre para interoperabilidade técnica; o projeto não hospeda nem armazena conteúdos protegidos por direitos autorais.

Consulte [`NOTICE`](NOTICE) para o inventário completo de atribuições e licenças específicas de terceiros (incluindo fontes tipográficas em `lib-multisrc/manhuarm/` e Cronet Runtime).

```text
Copyright 2015 Javier Tomás
Copyright (c) Keiyoushi Contributors
Copyright (c) Felipe GFA
Copyright (C) 2026 Project Nox Contributors & Awerkori

Licensed under the GNU Affero General Public License, Version 3.0 (for Project Nox contributions
and combined work) and the Apache License, Version 2.0 (for upstream Keiyoushi components).
```
