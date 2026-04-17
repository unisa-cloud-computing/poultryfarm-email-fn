# Azure Function `poultryfarm-email-fn`

Questa Azure Function HTTP riceve una richiesta JSON da `documento-service` e invia un'email al cliente contenente il **link al file salvato su Azure Blob Storage**.

La Function è pensata per essere invocata da un backend applicativo dopo la generazione del documento. Nel progetto PoultryFarm, il flusso previsto è: `ordine-service` pubblica un evento, `documento-service` genera il PDF e salva il blob, quindi chiama questa Function via HTTP passando email destinatario, oggetto e `blobUrl`

## Obiettivo

La Function ha un solo scopo: inviare una mail semplice con il link al documento già caricato su Blob Storage. Non genera documenti, non interroga database e non accede direttamente al Service Bus; riceve solo i dati necessari via richiesta HTTP

## Requisiti

Per usare correttamente la Function servono questi prerequisiti:

- Java 17 per build e runtime della Function
- Maven per packaging e deploy con `azure-functions-maven-plugin`.
- Una Function App Azure già deployata oppure deployabile tramite Maven plugin.
- Un account Twilio SendGrid con API Key valida.
- Un indirizzo mittente verificato in SendGrid tramite **Single Sender Verification**.
## Comportamento della Function

La Function espone un endpoint HTTP protetto con `AuthorizationLevel.FUNCTION`, quindi la chiamata deve includere la function key nella query string oppure in altro modo equivalente previsto dalla piattaforma. Quando riceve il payload JSON, valida i campi minimi, costruisce un testo email e usa il client Java di SendGrid per inviare il messaggio

Il corpo email contiene il nome del documento e il link pubblico o accessibile del blob.

## Payload richiesto

La Function si aspetta una richiesta `POST` con `Content-Type: application/json`.I campi minimi sono i seguenti:

| Campo | Obbligatorio | Descrizione |
|---|---|---|
| `to` | Sì | Email del destinatario finale. |
| `blobUrl` | Sì | URL del documento salvato su Blob Storage. |
| `subject` | No | Oggetto dell'email; se assente viene usato un default applicativo. |

Esempio payload:

```json
{
  "to": "cliente@example.com",
  "subject": "Documento ordine 123",
  "blobUrl": "https://storageaccount.blob.core.windows.net/documenti/ordine-123.pdf",
}
```

## Endpoint HTTP

L'endpoint esposto dalla Function è:

```text
POST /api/send-document-email
```

Poiché il trigger è configurato con `AuthorizationLevel.FUNCTION`, l'URL reale invocato dal chiamante sarà tipicamente di questo tipo:

```text
https://<function-app-name>.azurewebsites.net/api/send-document-email?code=<FUNCTION_KEY>
```

La function key può essere recuperata dal portale Azure nella sezione dedicata alla Function App.

## Variabili di ambiente

Per funzionare correttamente, la Function richiede almeno queste app settings configurate in Azure:

| Variabile | Obbligatoria | Descrizione                                                                                     |
|---|---|-------------------------------------------------------------------------------------------------|
| `SENDGRID_API_KEY` | Sì | API key di Twilio SendGrid usata per inviare le email.                                          |
| `EMAIL_FROM` | Sì | Indirizzo mittente usato nel campo From; deve corrispondere a un sender verificato in SendGrid. |

Esempio configurazione tramite Azure CLI:

```bash
az functionapp config appsettings set \
  --name poultryfarm-email-fn \
  --resource-group rg-poultryfarm \
  --settings \
    SENDGRID_API_KEY="<sendgrid-api-key>" \
    EMAIL_FROM="mittente-verificato@example.com"
```

## Configurazione SendGrid

E' sufficiente configurare un **Single Sender** e verificare l'indirizzo email che verrà usato come mittente. Se l'indirizzo specificato in `EMAIL_FROM` non corrisponde a un sender verificato, SendGrid può rifiutare l'invio con errore relativo alla sender identity.

Per la verifica rapida del mittente occorre:

1. Accedere alla dashboard Twilio SendGrid.
2. Aprire `Settings -> Sender Authentication`.
3. Scegliere `Verify a Single Sender`.
4. Inserire l'indirizzo che si vuole usare come mittente.
5. Confermare la mail di verifica ricevuta su quell'indirizzo.

Nel campo `Reply-To` si può usare lo stesso indirizzo del mittente per semplificare la configurazione.

## Struttura del progetto

Una struttura minima consigliata del progetto Maven è la seguente:

```text
poultryfarm-email-fn/
├── pom.xml
└── src/
    └── main/
        └── java/
            └── unisa/
                └── poultryfarm/
                    └── emailfn/
                        ├── SendDocumentEmailFunction.java
                        └── SendDocumentEmailRequest.java
```

## Dipendenze Maven minime

Le dipendenze fondamentali sono:

- `azure-functions-java-library` per il modello di Azure Functions in Java.
- `sendgrid-java` per l'invio email tramite Twilio SendGrid.
- opzionalmente `lombok` per ridurre boilerplate nel DTO richiesta.

Esempio:

```xml
<dependencies>
    <dependency>
        <groupId>com.microsoft.azure.functions</groupId>
        <artifactId>azure-functions-java-library</artifactId>
        <version>2.2.0</version>
    </dependency>

    <dependency>
        <groupId>com.sendgrid</groupId>
        <artifactId>sendgrid-java</artifactId>
        <version>4.10.2</version>
    </dependency>

    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <version>1.18.30</version>
    </dependency>
</dependencies>
```

## Esempio di implementazione

### DTO richiesta

```java
package unisa.poultryfarm.emailfn;

import lombok.Data;

@Data
public class SendDocumentEmailRequest {
    private String to;
    private String subject;
    private String blobUrl;
    private String fileName;
}
```

### Function HTTP

```java
package unisa.poultryfarm.emailfn;

import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import com.sendgrid.*;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;

import java.io.IOException;
import java.util.Optional;

public class SendDocumentEmailFunction {

    @FunctionName("send-document-email")
    public HttpResponseMessage run(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.FUNCTION,
                    route = "send-document-email")
            HttpRequestMessage<Optional<SendDocumentEmailRequest>> request,
            final ExecutionContext context) {

        SendDocumentEmailRequest body = request.getBody().orElse(null);

        if (body == null || body.getTo() == null || body.getBlobUrl() == null) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .body("Payload non valido: to e blobUrl sono obbligatori")
                    .build();
        }

        String to = body.getTo();
        String subject = body.getSubject() != null ? body.getSubject() : "Documento ordine";
        String blobUrl = body.getBlobUrl();
        String fileName = body.getFileName() != null ? body.getFileName() : "documento.pdf";

        String textBody = """
                Buongiorno,

                è stato generato il documento richiesto.

                Documento: %s
                Link: %s

                Cordiali saluti,
                PoultryFarm
                """.formatted(fileName, blobUrl);

        try {
            sendEmailWithSendGrid(context, to, subject, textBody);
        } catch (IOException e) {
            context.getLogger().severe("Errore nell'invio email: " + e.getMessage());
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Errore nell'invio email")
                    .build();
        }

        return request.createResponseBuilder(HttpStatus.OK)
                .body("Email inviata")
                .build();
    }

    private void sendEmailWithSendGrid(ExecutionContext context,
                                       String to,
                                       String subject,
                                       String body) throws IOException {

        String fromAddress = System.getenv("EMAIL_FROM");
        String sendGridApiKey = System.getenv("SENDGRID_API_KEY");

        if (fromAddress == null || sendGridApiKey == null) {
            throw new IllegalStateException("EMAIL_FROM o SENDGRID_API_KEY non configurati");
        }

        Email from = new Email(fromAddress);
        Email toEmail = new Email(to);
        Content content = new Content("text/plain", body);
        Mail mail = new Mail(from, subject, toEmail, content);

        SendGrid sg = new SendGrid(sendGridApiKey);
        Request request = new Request();
        request.setMethod(Method.POST);
        request.setEndpoint("mail/send");
        request.setBody(mail.build());

        Response response = sg.api(request);
        context.getLogger().info("SendGrid status: " + response.getStatusCode());
    }
}
```

## Build e deploy

Per il deploy si può usare `azure-functions-maven-plugin`, che crea o aggiorna la Function App e le risorse necessarie, tra cui storage account e Application Insights, se non vengono specificate risorse esistenti.Questo comportamento è normale e fa parte del provisioning automatico del plugin.

Comandi tipici:

```bash
mvn clean package
mvn azure-functions:deploy
```

Se il nome Function App indicato nel `pom.xml` punta a una Function già esistente con sistema operativo diverso, il deploy può fallire perché Azure non consente di cambiare OS a una Function App esistente. In quel caso occorre usare un nuovo nome app oppure allineare il runtime dichiarato a quello reale della Function già creata.


## Limitazioni note

Questa versione della Function **non allega** il documento all'email: invia solo il link al blob. Perché il destinatario possa scaricare il documento, il `blobUrl` deve essere accessibile, ad esempio perché il container è pubblico o perché l'URL include un meccanismo autorizzativo adeguato come SAS, se previsto dall'architettura applicativa.

L'uso di Single Sender Verification è adatto a test e demo, ma non è la soluzione ideale per ambienti produttivi ad alto volume o con esigenze forti di deliverability.

## Estensioni future

Questa Function può essere estesa in un secondo momento per:

- scaricare il blob e allegarlo realmente all'email invece di inviare il link;
- inviare email HTML oltre a quelle plain text;
