# Omnia Optimizer — Galaxy Universal Edition (`v4.4.1`)

Aplicativo nativo Android (`com.omnia.optimizer`, sem root e com **0% de consumo em segundo plano**) focado em análise rigorosa de ciclos de bateria, telemetria de hardware sob demanda e detecção de wakelocks nocivos para aparelhos **Samsung Galaxy (linha S / Fold / Flip / One UI)** e Android 8.0+.

## Princípios do Projeto
- **Zero daemon em segundo plano:** não mantém serviços nem polling rodando fora da tomada.
- **Disparo passivo ao carregar (`JobScheduler`):** coleta automaticamente somente quando o aparelho está conectado ao carregador (`setRequiresCharging(true)`), identificando mudanças reais de `Start clock time` e descarga para evitar registros repetidos.
- **Medição real sem dados inventados:** lê diretamente `batterystats`, `power`, `thermalservice`, `BatteryManager`, `/proc/meminfo`, `cpufreq` e nós `sysfs` suportados pelo SoC (Snapdragon ou Exynos). Quando um sensor é bloqueado pelo SELinux, o app informa explicitamente.

## Funcionalidades
- **`📡 HUD`:** Radar pentagonal comparativo (ciclo atual vs. média de até 7 ciclos limpos), badges reativos em tempo real (`ContentObserver` ativo apenas em tela aberta para Modo Leve, Brilho Extra Reduzido e Vulkan/HWUI), resumo do último ciclo e **Sentinela Anti-Wakelock** (`🕵️ CAÇAR LADRÃO DE BATERIA AGORA`).
- **`⚡ PERF` (somente ao vivo com a aba visível):** Descobre dinamicamente todos os clusters (`policy*`) e núcleos (`cpu0..cpuN`), tetos ativos vs. hardware, governor, sensores térmicos (`CPU/AP`, `DDR`, `Bateria`, `GPU`, `SKIN`, `PA`, `USB-C`), RAM + `ZRAM`, barramento DDR e GPU/energia instantânea (`V`, `mA`, `W`).
- **`📈 DIAG`:** Comparativo contra ciclo limpo de referência, diagnóstico causal (5G/sinal fraco, brilho, taxa de atualização, carga intensa, Raio-X de repouso com `AOD / Screen doze` e principais wakelocks), gráfico nativo de tendência (`mAh/h` ligada vs. apagada) e separação automática entre **`[CICLO LIMPO]`** e **`[JANELA MISTA]`**.
- **`⚙️ OTIM`:** Assistente de ativação de permissões (`DUMP` / `BATTERY_STATS`), auditor de configurações com pontuação, atalhos para ajustes do sistema e checklist de rotina One UI salvo no backup.
- **`☁️ COFRE`:** Notas de contexto por ciclo, exportação em `.zip` para o Google Drive (`BackupFileProvider`) e restauração sem duplicatas em `/sdcard/Documents/OmniaOptimizer`.

## Ativação das Permissões de Telemetria (1 única vez)
O Android não libera `DUMP` e `BATTERY_STATS` pelo menu comum de configurações. Após instalar o APK, execute **uma única vez** via **ADB (PC)** ou **aShell (com Shizuku no próprio celular)** — o botão **`📋 COPIAR COMANDO ADB / ASHELL`** dentro da aba **`⚙️ OTIM`** já copia o comando pronto:

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
