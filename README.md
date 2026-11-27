# Omnia Optimizer — Galaxy Universal Edition (`v6.0`)

Aplicativo nativo Android (`com.omnia.optimizer`, sem root e com **0% de consumo em segundo plano**) focado em análise rigorosa de ciclos de bateria, inspeção real de hardware/RAM, console ADB local via **Shizuku**, ativação da **API Vulkan (`skiavk`)**, controles rápidos de sistema, compilação ART sob demanda e detecção interativa de wakelocks nocivos para aparelhos **Samsung Galaxy (One UI)** e Android 8.0+.

## Novidades da Arquitetura `v6.0`
- **Visual Adaptativo Material 3 / One UI Material You (`DayNight`):** acompanha automaticamente o tema Claro/Escuro e a paleta dinâmica do papel de parede (`values-v31`) na interface principal, mantendo **Carvão Técnico** (`#0B0E11` / `#12161B`) nas ferramentas de densidade técnica (`RAM · MemHogs` e `ADB Shell`).
- **Navegação em 5 Abas Limpas (`Início | Sistema | Ciclos | Ajustes | Cofre`):** cabeçalho retrátil integrado ao fluxo de rolagem, ícones vetoriais monocromáticos em pílula Material 3, tipografia hierarquizada (`sans-serif-medium` para interface + `monospace` exclusivo para métricas numéricas e console) e resposta háptica sutil One UI.
- **Monitor de RAM Real (`MemHogs`) em `Sistema`:** lê `dumpsys meminfo` + `dumpsys activity lru` via Shizuku, associa renderers isolados (`WebView`/`Chrome`) ao app hospedeiro por UID, agrupa processos auxiliares por pacote, exibe barras proporcionais com alerta a partir de `>= 15%` da RAM e oferece **Liberar (Kill BG)** (`am kill`) e **Forçar Parada** confirmado (`am force-stop`, protegendo sistema e pacotes críticos), mantendo `0%` em 2º plano.
- **Console ADB Local (`aShell`) em `Sistema`:** streaming de saída `stdout/stderr` linha a linha via Shizuku Binder IPC com botão **Parar**, remoção automática do prefixo `adb shell `, suporte a `cd`, chips de atalho/histórico e exportação `.txt` em `/sdcard/Documents/OmniaOptimizer/ShellLogs`.
- **Divulgação Progressiva em `Ajustes`:** banner condicional de drift pós-reboot, hub Shizuku/permissões e **4 acordeões recolhíveis com resumo vivo em 1 linha** (*Energia, Carga & Rádio*, *Tela, Hz, AOD & Animações*, *ART, Freezer, Caches & Plano*, *Vulkan HWUI, Auditoria & Info*).

## Princípios do Projeto
- **Zero daemon em segundo plano:** não mantém serviços nem polling rodando fora da tomada. Widget nativo usa `updatePeriodMillis="0"`. Loops opcionais de CPU (`1s`) e RAM (`2.5s`, desligado por padrão) rodam exclusivamente enquanto a respectiva sub-aba está visível na tela.
- **Disparo passivo ao carregar (`JobScheduler`) + Salvamento pré-desligamento (`0%`):** coleta automaticamente quando o aparelho vai para o carregador (`setRequiresCharging(true)`) e captura o ciclo momentos antes de desligar por bateria crítica (`<= 4%` fora da tomada / `BATTERY_LOW` / `ACTION_SHUTDOWN`).
- **Medição real sem dados inventados:** lê diretamente `batterystats`, `power`, `thermalservice`, `BatteryManager`, `/proc/meminfo`, `dumpsys meminfo`, `cpufreq` e nós `sysfs` suportados pelo SoC (Snapdragon ou Exynos).

## Funcionalidades por Aba
- **`Início` + Widget (`Omnia HUD`):** Radar pentagonal comparativo (ciclo atual vs. média de até 7 ciclos limpos), badges reativos, **Sentinela Anti-Wakelock Interativo** (`Caçar Ladrão` + **`Doze Whitelist`** para remover pacotes não protegidos via `dumpsys deviceidle whitelist -<pkg>` + ações reversíveis `force-stop` / `appops RUN_ANY_IN_BACKGROUND ignore` / `Standby Bucket restricted` e botão `Desfazer`), cartões de CPU/Térmica e widget nativo na tela inicial.
- **`Sistema` (`CPU & Energia` | `RAM MemHogs` | `ADB Shell`):**
  - `CPU & Energia`: descoberta dinâmica de clusters (`policy*`), tetos ativos vs. HW, governor, sensores térmicos (`CPU/AP`, `DDR`, `Bateria`, `GPU`, `SKIN`, `PA`, `USB-C`), RAM + `ZRAM`, barramento DDR e energia instantânea (`V`, `mA`, `W`).
  - `RAM (MemHogs)`: inventário real de PSS agrupado por aplicativo com expansão de PIDs individuais e recuperação de memória sob demanda.
  - `ADB Shell`: terminal local Shizuku com chips rápidos, histórico persistente, cópia e salvamento `.txt`.
- **`Ciclos`:** Comparativo contra ciclo limpo de referência, diagnóstico causal (drift pós-reboot, 5G/sinal fraco, brilho, Hz, Raio-X de repouso com `AOD`, `Wi-Fi Multicast Lock`, `Top Wakeup Reason`, daemons `fg >= 1h` sem tela e wakelocks), gráfico de tendência (`mAh/h` ligada vs. apagada) e histórico separado em **`[CICLO LIMPO]`** / **`[JANELA MISTA]`**.
- **`Ajustes` (4 Acordeões):** Shizuku nativo (`moe.shizuku.privileged.api`) com saúde química real (`ASOC`, `BSOH`, ciclos e `FirstUseDate`), toggles adaptativos (`sem_low_heat_mode`, `protect_battery` 80%, AOD, Brilho Extra + %, **Hz Adaptativo `peak/min_refresh_rate`**, `4G/LTE` vs `5G NR`, MCF e Mosey/AirDrop), **3 barras deslizantes independentes (`0.0x–1.0x`, passo `0.1`)** para escalas de animação (`0.7 / 0.5 / 0.5`, `0.5x`, `1.0x`), compilação **`cmd package compile -m speed-profile`**, `cached_apps_freezer`, `pm trim-caches` e **Ativador Vulkan (`skiavk`)** com proteção de IMEs/Good Lock.
- **`Cofre`:** Notas de contexto por ciclo, exportação `.zip` para Google Drive (`BackupFileProvider`) e restauração sem duplicatas em `/sdcard/Documents/OmniaOptimizer`.

## Ativação das Permissões de Telemetria (Zero-PC via Shizuku ou 1x ADB)
Na aba **`Ajustes`**, com o **Shizuku** ativo, toque em **`Auto-Ativar Shizuku`**, ou execute via ADB / **ADB Shell** / PC:

```sh
pm grant com.omnia.optimizer android.permission.DUMP && \
pm grant com.omnia.optimizer android.permission.BATTERY_STATS && \
pm grant com.omnia.optimizer android.permission.PACKAGE_USAGE_STATS && \
cmd appops set com.omnia.optimizer GET_USAGE_STATS allow && \
cmd appops set com.omnia.optimizer MANAGE_EXTERNAL_STORAGE allow && \
cmd appops set com.omnia.optimizer RUN_ANY_IN_BACKGROUND allow && \
dumpsys deviceidle whitelist +com.omnia.optimizer
```

## Como Compilar (Windows + Android SDK)
Requer Android SDK Build-Tools + Platform + JDK 17 (ex.: JBR do Android Studio):

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```
