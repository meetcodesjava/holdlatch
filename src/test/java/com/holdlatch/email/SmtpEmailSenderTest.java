package com.holdlatch.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** Sends a real message over SMTP to an embedded server and reads it back, not a mock. */
class SmtpEmailSenderTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("holdlatch", "secret"));

    @Test
    void deliversASignedInEmailThatCanBeReadBack() throws Exception {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("localhost");
        mailSender.setPort(greenMail.getSmtp().getPort());
        mailSender.setUsername("holdlatch");
        mailSender.setPassword("secret");
        mailSender.getJavaMailProperties().put("mail.smtp.auth", "true");

        SmtpEmailSender sender = new SmtpEmailSender(mailSender, "noreply@holdlatch.example");
        sender.send("buyer@example.com", "Your HoldLatch order is confirmed", "Total: 25.00 USD");

        MimeMessage[] received = greenMail.getReceivedMessages();
        assertEquals(1, received.length);
        assertEquals("Your HoldLatch order is confirmed", received[0].getSubject());
        assertEquals("buyer@example.com", received[0].getAllRecipients()[0].toString());
        assertTrue(GreenMailUtil.getBody(received[0]).contains("25.00 USD"));
    }
}
