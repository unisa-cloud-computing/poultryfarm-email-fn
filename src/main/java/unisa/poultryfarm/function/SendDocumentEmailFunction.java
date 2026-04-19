package unisa.poultryfarm.function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import com.sendgrid.*;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import unisa.poultryfarm.model.SendDocumentEmailRequest;
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
            HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.findAndRegisterModules();
            String rawBody = request.getBody().orElse(null);

            if (rawBody == null || rawBody.isBlank()) {
                return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                        .body("Request body is required")
                        .build();
            }

            SendDocumentEmailRequest body = objectMapper.readValue(rawBody, SendDocumentEmailRequest.class);

            String to = body.getTo();
            String subject = body.getSubject() != null
                    ? body.getSubject()
                    : "Documento ordine";
            String blobUrl = body.getBlobUrl();

            String textBody = """
                Buongiorno,

                in allegato il link per scaricare il documento relativo al suo ordine.
                
                Link: %s

                Se il link non dovesse aprirsi cliccando, copialo e incollalo nel browser.

                Cordiali saluti,
                PoultryFarm
                """.formatted(blobUrl);

            sendEmailWithSendGrid(context, to, subject, textBody);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            context.getLogger().severe("Errore nell'invio email: " + e.getMessage());
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Errore nell'invio email")
                    .build();
        }
        catch (Exception e) {
            context.getLogger().severe("Errore nel pargin" + e.getMessage());
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .body("Payload non valido")
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
            throw new IllegalStateException("EMAIL_FROM o SENDGRID_API_KEY non configurati nelle app settings");
        }

        Email from = new Email(fromAddress);
        Email toEmail = new Email(to);
        Content content = new Content("text/plain", body);
        Mail mail = new Mail(from, subject, toEmail, content);

        SendGrid sg = new SendGrid(sendGridApiKey);
        Request request = new Request();
        try {
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());
            Response response = sg.api(request);

            context.getLogger().info("SendGrid status: " + response.getStatusCode());
        } catch (IOException ex) {
            throw ex;
        }
    }
}
