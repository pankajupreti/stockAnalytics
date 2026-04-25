package com.example.alert_service.notification;

import com.example.alert_service.model.Alert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Service for sending alert notifications via various channels.
 */
@Service
@Slf4j
public class NotificationService {

    private final JavaMailSender mailSender;
    private final WebClient telegramClient;

    @Value("${alert.email.from:noreply@stockanalytics.com}")
    private String emailFrom;

    @Value("${alert.email.enabled:false}")
    private boolean emailEnabled;

    @Value("${alert.whatsapp.enabled:false}")
    private boolean whatsappEnabled;

    @Value("${alert.telegram.enabled:false}")
    private boolean telegramEnabled;

    @Value("${telegram.bot-token:}")
    private String telegramBotToken;

    public NotificationService(JavaMailSender mailSender, WebClient.Builder webClientBuilder) {
        this.mailSender = mailSender;
        this.telegramClient = webClientBuilder
                .baseUrl("https://api.telegram.org")
                .build();
    }

    /**
     * Send notification for a triggered alert.
     * Determines which channels to use based on alert configuration.
     */
    @Async
    public void sendAlertNotification(Alert alert, BigDecimal currentPrice) {
        String channels = alert.getNotificationChannels();
        if (channels == null || channels.isBlank()) {
            channels = "EMAIL";
        }

        log.info("Sending notification for alert {} via {}", alert.getId(), channels);

        if (channels.contains("EMAIL") && alert.getUserEmail() != null) {
            sendEmailNotification(alert, currentPrice);
        }

        if (channels.contains("WHATSAPP") && alert.getUserPhone() != null) {
            sendWhatsAppNotification(alert, currentPrice);
        }

        if (channels.contains("TELEGRAM")) {
            sendTelegramNotification(alert, currentPrice);
        }
    }

    /**
     * Send email notification.
     */
    private void sendEmailNotification(Alert alert, BigDecimal currentPrice) {
        if (!emailEnabled) {
            log.info("Email disabled - would send to {} for {}", alert.getUserEmail(), alert.getTicker());
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(emailFrom);
            message.setTo(alert.getUserEmail());
            message.setSubject(buildEmailSubject(alert));
            message.setText(buildEmailBody(alert, currentPrice));

            mailSender.send(message);
            log.info("Email sent to {} for alert {}", alert.getUserEmail(), alert.getId());
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", alert.getUserEmail(), e.getMessage());
            throw e;
        }
    }

    /**
     * Send WhatsApp notification via Twilio.
     */
    private void sendWhatsAppNotification(Alert alert, BigDecimal currentPrice) {
        if (!whatsappEnabled) {
            log.info("WhatsApp disabled - would send to {} for {}", alert.getUserPhone(), alert.getTicker());
            return;
        }

        // TODO: Implement Twilio WhatsApp integration
        // Requires twilio-java SDK and account setup
        /*
        try {
            Twilio.init(accountSid, authToken);
            Message message = Message.creator(
                new PhoneNumber("whatsapp:" + alert.getUserPhone()),
                new PhoneNumber(whatsappFrom),
                buildWhatsAppMessage(alert, currentPrice)
            ).create();
            log.info("WhatsApp sent to {} - SID: {}", alert.getUserPhone(), message.getSid());
        } catch (Exception e) {
            log.error("Failed to send WhatsApp to {}: {}", alert.getUserPhone(), e.getMessage());
            throw e;
        }
        */
        log.warn("WhatsApp integration not yet implemented");
    }

    /**
     * Send Telegram notification.
     * Uses simple HTTP POST to Telegram Bot API - no external library needed.
     */
    private void sendTelegramNotification(Alert alert, BigDecimal currentPrice) {
        if (!telegramEnabled) {
            log.info("Telegram disabled - would send for {}", alert.getTicker());
            return;
        }

        if (telegramBotToken == null || telegramBotToken.isBlank()) {
            log.warn("Telegram bot token not configured");
            return;
        }

        String chatId = alert.getTelegramChatId();
        if (chatId == null || chatId.isBlank()) {
            log.warn("No Telegram chat ID for alert {} - user needs to start chat with bot first", alert.getId());
            return;
        }

        try {
            String message = buildTelegramMessage(alert, currentPrice);

            // Telegram Bot API: POST /bot{token}/sendMessage
            Map<String, Object> response = telegramClient.post()
                    .uri("/bot{token}/sendMessage", telegramBotToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "chat_id", chatId,
                            "text", message,
                            "parse_mode", "Markdown"
                    ))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null && Boolean.TRUE.equals(response.get("ok"))) {
                log.info("Telegram notification sent to chat {} for alert {}", chatId, alert.getId());
            } else {
                log.warn("Telegram API returned error: {}", response);
            }
        } catch (Exception e) {
            log.error("Failed to send Telegram notification: {}", e.getMessage());
        }
    }

    private String buildTelegramMessage(Alert alert, BigDecimal currentPrice) {
        String emoji = switch (alert.getAlertType()) {
            case STOP_LOSS -> "\u26A0\uFE0F"; // warning
            case PRICE_ABOVE -> "\uD83D\uDCC8"; // chart up
            case PRICE_BELOW -> "\uD83D\uDCC9"; // chart down
        };

        String action = switch (alert.getAlertType()) {
            case STOP_LOSS -> "STOP LOSS TRIGGERED";
            case PRICE_ABOVE -> "Price Above Target";
            case PRICE_BELOW -> "Price Below Target";
        };

        StringBuilder sb = new StringBuilder();
        sb.append(emoji).append(" *").append(action).append("*\n\n");
        sb.append("*Ticker:* `").append(alert.getTicker()).append("`\n");
        sb.append("*Target:* Rs. ").append(alert.getTargetPrice()).append("\n");
        sb.append("*Current:* Rs. ").append(currentPrice).append("\n");

        if (alert.getBuyPrice() != null) {
            sb.append("*Buy Price:* Rs. ").append(alert.getBuyPrice()).append("\n");
            BigDecimal lossPct = alert.getBuyPrice().subtract(currentPrice)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(alert.getBuyPrice(), 2, java.math.RoundingMode.HALF_UP);
            sb.append("*Change:* ").append(lossPct.negate()).append("%\n");
        }

        sb.append("\n_Check your portfolio!_");
        return sb.toString();
    }

    private String buildEmailSubject(Alert alert) {
        String action = switch (alert.getAlertType()) {
            case STOP_LOSS -> "STOP LOSS TRIGGERED";
            case PRICE_ABOVE -> "Price Target Reached (Above)";
            case PRICE_BELOW -> "Price Target Reached (Below)";
        };
        return String.format("[Stock Alert] %s - %s", alert.getTicker(), action);
    }

    private String buildEmailBody(Alert alert, BigDecimal currentPrice) {
        StringBuilder sb = new StringBuilder();
        sb.append("Stock Alert Notification\n");
        sb.append("========================\n\n");

        sb.append("Ticker: ").append(alert.getTicker()).append("\n");
        if (alert.getCompanyName() != null) {
            sb.append("Company: ").append(alert.getCompanyName()).append("\n");
        }
        sb.append("\n");

        sb.append("Alert Type: ").append(alert.getAlertType()).append("\n");
        sb.append("Target Price: Rs. ").append(alert.getTargetPrice()).append("\n");
        sb.append("Current Price: Rs. ").append(currentPrice).append("\n");

        if (alert.getBuyPrice() != null) {
            sb.append("Your Buy Price: Rs. ").append(alert.getBuyPrice()).append("\n");

            BigDecimal loss = alert.getBuyPrice().subtract(currentPrice);
            BigDecimal lossPct = loss.multiply(BigDecimal.valueOf(100))
                    .divide(alert.getBuyPrice(), 2, java.math.RoundingMode.HALF_UP);
            sb.append("Loss from Buy: Rs. ").append(loss).append(" (").append(lossPct).append("%)\n");
        }

        sb.append("\n");
        sb.append("Triggered At: ").append(alert.getTriggeredAt()).append("\n");

        if (alert.getNotes() != null) {
            sb.append("\nNotes: ").append(alert.getNotes()).append("\n");
        }

        sb.append("\n");
        sb.append("---\n");
        sb.append("This is an automated alert from Stock Analytics.\n");
        sb.append("Please review your portfolio and take appropriate action.\n");

        return sb.toString();
    }

    private String buildWhatsAppMessage(Alert alert, BigDecimal currentPrice) {
        return String.format(
                "*Stock Alert*\n\n" +
                        "*%s* %s\n\n" +
                        "Target: Rs. %s\n" +
                        "Current: Rs. %s\n" +
                        "%s\n\n" +
                        "Check your portfolio!",
                alert.getTicker(),
                alert.getAlertType(),
                alert.getTargetPrice(),
                currentPrice,
                alert.getBuyPrice() != null ? "Buy: Rs. " + alert.getBuyPrice() : ""
        );
    }
}
