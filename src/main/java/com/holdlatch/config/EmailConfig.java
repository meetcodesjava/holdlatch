package com.holdlatch.config;

import com.holdlatch.email.DisabledEmailSender;
import com.holdlatch.email.EmailSender;
import com.holdlatch.email.SmtpEmailSender;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Configuration
@EnableConfigurationProperties(MailProperties.class)
public class EmailConfig {

    private static final Logger log = LoggerFactory.getLogger(EmailConfig.class);

    @Bean
    EmailSender emailSender(MailProperties props) {
        if (props.configured()) {
            JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
            mailSender.setHost(props.host());
            mailSender.setPort(props.port());
            mailSender.setUsername(props.username());
            mailSender.setPassword(props.password());
            Properties javaMailProperties = mailSender.getJavaMailProperties();
            javaMailProperties.put("mail.smtp.auth", "true");
            javaMailProperties.put("mail.smtp.starttls.enable", String.valueOf(props.starttls()));
            return new SmtpEmailSender(mailSender, props.from());
        }
        log.warn("SMTP is not configured: order-confirmation emails will only be logged until "
                + "SMTP_HOST, SMTP_USERNAME, SMTP_PASSWORD and MAIL_FROM are provided");
        return new DisabledEmailSender();
    }
}
