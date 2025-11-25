package org.example;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class v2_MOBI_number_authorization {
    static Playwright playwright;
    static Browser browser;
    static BrowserContext context;
    static Page page;
    static Properties creds = new Properties();

    @BeforeAll
    static void setUpAll() throws IOException {
        // --- Загружаем креды ---
        creds.load(new FileInputStream("src/test/resources/config.properties"));

        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(false)
                .setArgs(List.of("--start-maximized"))
        );
        context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(null)
        );
        page = context.newPage();

        // --- Проверяем сессию Google Messages ---
        Path sessionPath = Paths.get("src/test/resources/sessions/messages-session.json");
        try {
            BrowserContext messagesContext = browser.newContext(
                    new Browser.NewContextOptions().setStorageStatePath(sessionPath)
            );
            messagesContext.close();
            System.out.println("✅ Сессия Google Messages успешно загружена.");
        } catch (Exception e) {
            System.out.println("⚠️ Не удалось загрузить сохранённую сессию Google Messages. Проверь файл: " + sessionPath);
        }
    }

    @AfterAll
    static void tearDownAll() {
        System.out.println("Тест завершён ✅ (браузер остаётся открытым)");
    }

    @Test
    void loginAndLogout() {
        long start = System.currentTimeMillis();
        String botToken = creds.getProperty("telegram.bot.token");
        String chatId = creds.getProperty("telegram.chat.id");

        Telegram.send("🚀 *Тест v2_MOBI_number_authorization* стартовал\n(авторизация по номеру телефона + SMS)", botToken, chatId);

        String phone = creds.getProperty("phone");
        String password = creds.getProperty("password");

        System.out.println("Открываем мобильную версию сайта 1xbet.kz");
        page.navigate("https://1xbet.kz/?platform_type=mobile");

        System.out.println("Открываем форму входа");
        page.click("button#curLoginForm span.auth-btn__label:has-text('Вход')");

        System.out.println("Выбираем способ входа по номеру телефона");
        page.click("button.c-input-material__custom.custom-functional-button");

        System.out.println("Вводим номер телефона");
        page.fill("input.phone-input__field[type='tel']", phone);

        System.out.println("Вводим пароль");
        page.fill("input#auth-form-password", password);

        System.out.println("Жмём 'Войти'");
        page.click("button.auth-button span.auth-button__text:has-text('Войти')");

        // ---- ЖДЁМ РЕШЕНИЯ КАПЧИ ----
        System.out.println("Теперь решай капчу вручную — я жду кнопку 'Выслать код' (до 10 минут)");
        try {
            page.waitForSelector("button:has-text('Выслать код')",
                    new Page.WaitForSelectorOptions()
                            .setTimeout(600_000)
                            .setState(WaitForSelectorState.VISIBLE)
            );
            System.out.println("Кнопка 'Выслать код' появилась ✅");
        } catch (PlaywrightException e) {
            throw new RuntimeException("❌ Кнопка 'Выслать код' не появилась — капча не решена!");
        }

        System.out.println("Жмём 'Выслать код'");
        page.click("button:has-text('Выслать код')");

        System.out.println("Ждём поле для ввода кода...");
        page.waitForSelector("input.phone-sms-modal-code__input",
                new Page.WaitForSelectorOptions()
                        .setTimeout(600_000)
                        .setState(WaitForSelectorState.VISIBLE)
        );
        System.out.println("Поле для кода появилось ✅");

        // --- Google Messages ---
        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        Path[] possiblePaths = new Path[]{
                projectRoot.resolve("resources/sessions/messages-session.json"),
                projectRoot.resolve("src/test/resources/sessions/messages-session.json"),
                projectRoot.resolve("src/test/java/org/example/resources/sessions/messages-session.json")
        };

        Path sessionPath = null;
        for (Path p : possiblePaths) {
            if (p.toFile().exists()) {
                sessionPath = p;
                break;
            }
        }
        if (sessionPath == null)
            throw new RuntimeException("❌ Файл сессии Google Messages не найден!");

        System.out.println("📁 Используем сессию: " + sessionPath.toAbsolutePath());

        System.out.println("🔐 Открываем Google Messages...");
        BrowserContext messagesContext = browser.newContext(
                new Browser.NewContextOptions().setStorageStatePath(sessionPath)
        );
        Page messagesPage = messagesContext.newPage();
        messagesPage.navigate("https://messages.google.com/web/conversations");

        System.out.println("⌛ Ждём список чатов...");
        for (int i = 0; i < 20; i++) {
            if (messagesPage.locator("mws-conversation-list-item").count() > 0) break;
            messagesPage.waitForTimeout(1000);
        }

        System.out.println("🔍 Ищем чат с 1xBet...");
        Locator chat = messagesPage.locator("mws-conversation-list-item:has-text('1xbet'), mws-conversation-list-item:has-text('1xbet-kz')");
        if (chat.count() == 0) chat = messagesPage.locator("mws-conversation-list-item").first();
        chat.first().click();
        messagesPage.waitForTimeout(2000);

        System.out.println("📩 Ищем последнее сообщение...");
        Locator msg = messagesPage.locator("div.text-msg-content div.text-msg.msg-content div.ng-star-inserted");
        int count = 0;
        for (int i = 0; i < 15; i++) {
            count = msg.count();
            if (count > 0) break;
            messagesPage.waitForTimeout(1000);
        }
        if (count == 0)
            throw new RuntimeException("❌ Сообщения не найдены в Google Messages!");

        String lastMsg = msg.nth(count - 1).innerText().trim();
        System.out.println("📨 Последнее сообщение: " + lastMsg);

        Matcher matcher = Pattern.compile("\\b[a-zA-Z0-9]{4,8}\\b").matcher(lastMsg);
        String code = matcher.find() ? matcher.group() : null;
        if (code == null)
            throw new RuntimeException("❌ Код не найден в сообщении!");
        System.out.println("✅ Извлечённый код: " + code);

        // --- Вводим код на сайте ---
        page.bringToFront();
        page.fill("input.phone-sms-modal-code__input", code);
        page.click("button.phone-sms-modal-content__send:has-text('Подтвердить')");
        page.waitForTimeout(3000);

        // --- Личный кабинет и выход ---
        System.out.println("Открываем 'Личный кабинет'");
        page.click("button.user-header__link.header__link.header__reg.header__reg_ico.ion-android-person");
        page.waitForTimeout(2000);

        System.out.println("Жмём 'Выход'");
        page.click("button.drop-menu-list__link_exit:has-text('Выход')");
        page.waitForTimeout(1000);

        System.out.println("Подтверждаем выход 'ОК'");
        page.click("button.swal2-confirm.swal2-styled:has-text('ОК')");
        page.waitForTimeout(2000);

        System.out.println("✅ Выход выполнен");

        long duration = (System.currentTimeMillis() - start) / 1000;
        String summary = "✅ *Тест v2_MOBI_number_authorization завершён успешно*\n"
                + "• Авторизация — выполнена\n"
                + "• Код подтверждения — *" + code + "*\n"
                + "• Выход — произведён\n"
                + "🕒 Время выполнения: *" + duration + " сек.*\n"
                + "🌐 [1xbet.kz](https://1xbet.kz)\n"
                + "_Браузер остаётся открытым._";

        System.out.println(summary);
        Telegram.send(summary, botToken, chatId);
    }

    // --- Telegram helper ---
    static class Telegram {
        static void send(String text, String botToken, String chatId) {
            try {
                String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
                String data = "chat_id=" + chatId
                        + "&text=" + java.net.URLEncoder.encode(text, "UTF-8")
                        + "&parse_mode=Markdown";
                java.net.http.HttpClient.newHttpClient().send(
                        java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create(url))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(data))
                                .build(),
                        java.net.http.HttpResponse.BodyHandlers.discarding()
                );
                System.out.println("📨 Сообщение отправлено в Telegram");
            } catch (Exception e) {
                System.out.println("⚠️ Ошибка Telegram: " + e.getMessage());
            }
        }
    }
}
