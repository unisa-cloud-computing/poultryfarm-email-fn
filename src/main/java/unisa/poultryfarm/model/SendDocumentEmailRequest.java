package unisa.poultryfarm.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SendDocumentEmailRequest {
    public String to;
    public String subject;
    public String blobUrl;
}
