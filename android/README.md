# Smart Ring Sync (Android)

App Android que conecta por Bluetooth LE nos anéis suportados pelo painel Python deste repositório e grava os dados no **Health Connect**. Com isso, Google Fit, Samsung Health e outros apps que leem do Health Connect recebem passos e frequência cardíaca do anel.

> Primeiro corte. Os comandos vêm de engenharia reversa da comunidade (tahnok/colmi_r02_client, colmi.puxtril.com) e foram validados no painel Python (`src/smartring/protocols/`). Não é documentação oficial dos fabricantes. O app não grava firmware nem envia comandos de reset; o único comando de escrita é o acerto de relógio.

## O que faz

| Anel | Protocolo (detectado pelo serviço GATT) | Lê | Grava no Health Connect |
|---|---|---|---|
| R11M e outros YuCheng | `be940000` | bateria, passos do dia (`0x02 0x0C`) | `StepsRecord` com o total do dia (00:00 até agora) |
| Colmi R02/R03/R06 (QRing) | `6e40fff0` | bateria (`0x03`), passos em blocos de 15 min (`0x43`), FC ao vivo (`0x69`/`0x6A`) | `StepsRecord` por bloco de 15 min, `HeartRateRecord` |

Em toda sincronização o app também acerta o relógio do anel (YuCheng `0x01 0x00`, Colmi `0x01`).

Todos os registros saem com `Metadata.autoRecorded` e `Device(type = TYPE_RING)`. Cada registro tem um `clientRecordId` estável (hash do endereço do anel + início do intervalo), então sincronizar de novo atualiza o registro em vez de duplicar.

FC ao vivo no R11M (YuCheng) ainda não está implementada neste corte.

## Aviso importante: uma conexão por vez

O anel aceita **uma conexão BLE por vez**. Se o app oficial (**QRing** para o Colmi, ou o app do R11M) estiver aberto ou conectado em segundo plano, este app não consegue conectar. Feche o app oficial e, se necessário, force a parada dele (ou desative o Bluetooth dele) antes de usar. O mesmo vale para o painel Python no PC.

Não deixe o anel pareado nas configurações de Bluetooth do Android: o app conecta direto por GATT, sem pareamento.

## Como compilar

Requisitos: JDK 17 e Android SDK com a plataforma 36 (o Android Studio instala os dois).

```powershell
cd android
.\gradlew.bat assembleDebug
```

No Linux/macOS: `./gradlew assembleDebug`. O APK fica em `app/build/outputs/apk/debug/app-debug.apk`.

Se o Gradle não achar o SDK, crie `android/local.properties` (não vai para o Git) com `sdk.dir=C:/caminho/do/Android/Sdk`, ou defina `ANDROID_HOME`.

Testes de unidade dos protocolos (montagem de pacotes, CRC, BCD, parsing):

```powershell
.\gradlew.bat testDebugUnitTest
```

Instalar no celular conectado por USB (depuração USB ligada):

```powershell
.\gradlew.bat installDebug
```

## Permissões

| Permissão | Por quê |
|---|---|
| `BLUETOOTH_SCAN` (Android 12+, `neverForLocation`) | procurar anéis |
| `BLUETOOTH_CONNECT` (Android 12+) | conectar e trocar comandos com o anel |
| `ACCESS_FINE_LOCATION` (só Android 9 a 11) | o sistema exige localização para varredura BLE nessas versões |
| `android.permission.health.WRITE_STEPS` | gravar passos no Health Connect |
| `android.permission.health.WRITE_HEART_RATE` | gravar FC no Health Connect |

O app só **escreve** no Health Connect; não lê dados de outros apps. A tela de explicação exigida pelo Health Connect é `PermissionsRationaleActivity`.

Health Connect: no Android 14+ já vem no sistema. No Android 9 a 13, instale o app **Health Connect** pela Play Store (o app mostra um botão para isso). Depois, na tela principal, toque em **Conceder permissões**.

Para Google Fit / Samsung Health verem os dados, abra o Health Connect e dê a eles permissão de **leitura** de passos e frequência cardíaca.

## Uso

1. Conceda as permissões do Health Connect.
2. **Procurar anéis**: lista aparelhos que anunciam um serviço conhecido ou têm nome de anel (`R0x`, `R11`, `COLMI`...).
3. **Conectar**: salva o anel, detecta o protocolo pelo GATT e faz a primeira sincronização.
4. **Sincronizar agora**: conecta, acerta o relógio, lê bateria e passos, mede a FC por 20 s (Colmi; use o anel no dedo, fora do carregador), desconecta e grava no Health Connect.

### Sincronização automática

Usa WorkManager (`RingSyncWorker`) com intervalo configurável: 15 min, 30 min, 1 h (padrão), 2 h ou 4 h. 15 min é o mínimo do WorkManager. Só roda com bateria do celular não baixa.

A cada ciclo o app conecta, lê e desconecta; não mantém conexão aberta. Por padrão a sincronização automática **não** mede FC (acender o LED do sensor é o que mais gasta a bateria do anel); há uma opção na tela para ligar. Prefira intervalos de 1 h ou mais.

## Estrutura

```
app/src/main/java/com/allsmartings/ring/
  protocol/   RingProtocol (detecção por GATT), Colmi, YuCheng: montagem e parsing de pacotes
  ble/        RingScanner (varredura), RingConnection (GATT em coroutines), RingClient (operações)
  health/     HealthConnectWriter (StepsRecord, HeartRateRecord, Metadata de anel)
  sync/       RingSync (uma sincronização completa), RingSyncWorker (WorkManager)
  data/       RingPrefs (anel escolhido, intervalo, última leitura; só no aparelho)
  ui/         MainActivity (Compose), RingViewModel, PermissionsRationaleActivity
```

## Privacidade

O endereço Bluetooth do anel fica só nas preferências locais do app (`allowBackup=false`). No Health Connect vai apenas um hash curto dele dentro do `clientRecordId`. O app não tem acesso à internet.
