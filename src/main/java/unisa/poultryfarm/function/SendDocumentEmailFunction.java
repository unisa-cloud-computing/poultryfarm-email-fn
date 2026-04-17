package unisa.poultryfarm.function;

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
            HttpRequestMessage<Optional<SendDocumentEmailRequest>> request,
            final ExecutionContext context) {

        SendDocumentEmailRequest body = request.getBody().orElse(null);

        if (body == null || body.getTo() == null || body.getBlobUrl() == null) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .body("Payload non valido: to e blobUrl sono obbligatori")
                    .build();
        }

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
