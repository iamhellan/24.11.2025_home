package org.example;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.junit.jupiter.api.*;

import java.awt.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class v2_MOBI_phone_registration {
    static Playwright playwright;
    static Browser browser;
    static BrowserContext context;
    static Page page;
    static Properties creds = new Properties();

    // --- Вспомогательные методы ---

    /**
     * Ждём, пока document.readyState станет "complete".
     * Если за maxWaitMs не стало — перезагружаем страницу.
     * Перезагрузок не больше 3, чтобы не зависнуть навсегда.
     */
    static void waitForPageOrReload(int maxWaitMs) {
        int waited = 0;
        int reloads = 0;

        while (true) {
            try {
                String readyState = (String) page.evaluate("() => document.readyState");
                if ("complete".equals(readyState)) {
                    System.out.println("document.readyState=complete");
                    break;
                }
            } catch (Exception e) {
                System.out.println("Ошибка при проверке readyState: " + e.getMessage());
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            waited += 500;

            if (waited >= maxWaitMs) {
                if (reloads >= 3) {
                    System.out.println("⛔ Страница не загрузилась после " + (reloads + 1) + " попыток, прекращаем обновлять");
                    break;
                }
                System.out.println("Страница не загрузилась за " + maxWaitMs + " мс, обновляем! Попытка #" + (reloads + 1));
                page.reload();
                waited = 0;
                reloads++;
            }
        }
    }

    /**
     * Закрываем конкретный элемент, если он виден.
     */
    static void closeIfVisible(String selector, String description) {
        try {
            Locator popup = page.locator(selector);
            popup.waitFor(
                    new Locator.WaitForOptions()
                            .setTimeout(2000)
                            .setState(WaitForSelectorState.VISIBLE)
            );
            if (popup.isVisible()) {
                System.out.println("Закрываем: " + description);
                popup.click();
                page.waitForTimeout(500);
            } else {
                System.out.println("Элемент " + description + " не виден — пропускаем");
            }
        } catch (Exception e) {
            System.out.println("Элемент " + description + " не найден/не появился — пропускаем");
        }
    }

    /**
     * Универсально закрывает всплывашки с крестиком,
     * НО:
     *  - не трогает попап email (где вводим почту);
     *  - не трогает крест регистрации (popup-registration__close).
     */
    static void closeAllDialogsWithCross() {
        // 1) Исключение: попап email для отправки на почту
        try {
            Locator emailInput = page.locator("input.js-post-email-content-form__input");
            if (emailInput.isVisible()) {
                System.out.println("Открыт email-попап → крестики не трогаем");
                return;
            }
        } catch (Exception ignored) {
        }

        // 2) Универсальные крестики.
        String selectors = String.join(",",
                // приоритетно — модалки идентификации / привязки телефона
                "div.v-modal-user-identification button[aria-label='Закрыть']",
                "div.v-modal-user-identification button[class*='__close']",
                // затем все остальные
                "div[role='dialog'] button[aria-label='Закрыть']",
                "div[role='dialog'] button[class*='__close']",
                "div[role='dialog'] button[class*='-close']",
                "div.v--modal-box button[aria-label='Закрыть']"
        );

        for (int pass = 0; pass < 3; pass++) { // несколько проходов на случай нескольких окон
            Locator crosses = page.locator(selectors);
            int count = crosses.count();
            if (count == 0) {
                if (pass == 0) {
                    System.out.println("Крестики в модальных окнах не найдены");
                }
                break;
            }

            System.out.println("Попытка #" + (pass + 1) + " закрыть " + count + " крестиков в модалках");
            for (int i = 0; i < count; i++) {
                Locator cross = crosses.nth(i);
                try {
                    if (!cross.isVisible()) {
                        continue;
                    }

                    // Не трогаем крестик регистрационного окна
                    String cls = cross.getAttribute("class");
                    if (cls != null && cls.contains("popup-registration__close")) {
                        System.out.println("Пропускаем крест попапа регистрации (popup-registration__close)");
                        continue;
                    }

                    cross.click(new Locator.ClickOptions()
                            .setTimeout(2_000)
                            .setForce(true));
                    page.waitForTimeout(300);
                } catch (Exception e) {
                    System.out.println("Не удалось кликнуть по кресту: " + e.getMessage());
                }
            }
            page.waitForTimeout(200);
        }
    }

    static String generatePromoCode() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random rand = new Random();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 8; i++) code.append(chars.charAt(rand.nextInt(chars.length())));
        return code.toString();
    }

    // --- Google Messages session path resolver ---
    static Path resolveMessagesSessionPath() {
        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        Path[] possiblePaths = new Path[]{
                projectRoot.resolve("resources/sessions/messages-session.json"),
                projectRoot.resolve("src/test/resources/sessions/messages-session.json"),
                projectRoot.resolve("src/test/java/org/example/resources/sessions/messages-session.json")
        };

        for (Path p : possiblePaths) {
            if (p.toFile().exists()) {
                return p;
            }
        }
        return null;
    }

    /**
     * Открываем Google Messages с сохранённой сессией и вытаскиваем код из последнего сообщения.
     */
    static String getSmsCodeFromGoogleMessages() {
        Path sessionPath = resolveMessagesSessionPath();
        if (sessionPath == null) {
            throw new RuntimeException("❌ Файл сессии Google Messages не найден!");
        }

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
        if (chat.count() == 0) {
            chat = messagesPage.locator("mws-conversation-list-item").first();
        }
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
        if (count == 0) {
            messagesContext.close();
            throw new RuntimeException("❌ Сообщения не найдены в Google Messages!");
        }

        String lastMsg = msg.nth(count - 1).innerText().trim();
        System.out.println("📨 Последнее сообщение: " + lastMsg);

        Matcher matcher = Pattern.compile("\\b[a-zA-Z0-9]{4,8}\\b").matcher(lastMsg);
        String code = matcher.find() ? matcher.group() : null;
        if (code == null) {
            messagesContext.close();
            throw new RuntimeException("❌ Код не найден в сообщении!");
        }
        System.out.println("✅ Извлечённый код: " + code);

        messagesContext.close();
        return code;
    }

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
        page.setDefaultTimeout(30_000);

        // --- Проверяем сессию Google Messages ---
        Path sessionPath = resolveMessagesSessionPath();
        if (sessionPath != null) {
            try {
                BrowserContext messagesContext = browser.newContext(
                        new Browser.NewContextOptions().setStorageStatePath(sessionPath)
                );
                messagesContext.close();
                System.out.println("✅ Сессия Google Messages успешно загружена: " + sessionPath.toAbsolutePath());
            } catch (Exception e) {
                System.out.println("⚠️ Не удалось загрузить сохранённую сессию Google Messages. Проверь файл: "
                        + sessionPath.toAbsolutePath() + " / " + e.getMessage());
            }
        } else {
            System.out.println("⚠️ Файл сессии Google Messages не найден (проверены стандартные пути).");
        }
    }

    @Test
    void registrationByPhoneFullFlow() throws InterruptedException {
        long startMs = System.currentTimeMillis();
        LocalDateTime startDateTime = LocalDateTime.now();
        String startedAt = startDateTime.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"));

        String botToken = creds.getProperty("telegram.bot.token");
        String chatId = creds.getProperty("telegram.chat.id");

        String login = null;
        String password = null;
        String promoCode = null;
        String phone = null;

        Telegram.send("🚀 *Тест v2_MOBI_phone_registration* стартовал\n(Регистрация 'По телефону')",
                botToken, chatId);

        try {
            System.out.println("Открываем сайт (мобильная версия)...");
            page.navigate("https://1xbet.kz/?platform_type=mobile");
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            waitForPageOrReload(15_000);
            page.waitForTimeout(1000);

            System.out.println("Кликаем 'Регистрация'");
            page.waitForSelector("button.header-btn--registration");
            page.click("button.header-btn--registration");
            page.waitForTimeout(1000);

            System.out.println("Вводим номер телефона из конфига");
            phone = creds.getProperty("phone");
            if (phone == null || phone.isBlank()) {
                throw new IllegalStateException("phone не задан в config.properties");
            }
            page.fill("input.phone-input__field", phone.trim());
            page.waitForTimeout(500);

            System.out.println("Жмём 'Отправить sms'");
            page.waitForSelector("button#button_send_sms");
            page.click("button#button_send_sms");
            page.waitForTimeout(500);

            System.out.println("Реши капчу вручную — я жду появления окна 'SMS отправлено' (Ок)...");
            page.waitForSelector("button.swal2-confirm.swal2-styled",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(600_000));
            System.out.println("Окно 'SMS отправлено' появилось, жмём 'Ок'");
            page.click("button.swal2-confirm.swal2-styled");
            page.waitForTimeout(500);

            closeAllDialogsWithCross();

            System.out.println("Получаем код из Google Messages");
            String smsCode = getSmsCodeFromGoogleMessages();

            System.out.println("Вводим код подтверждения из SMS");
            page.waitForSelector("input#registration_phone_confirmation",
                    new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE));
            page.fill("input#registration_phone_confirmation", smsCode);
            page.waitForTimeout(500);

            System.out.println("Жмём 'Подтвердить'");
            page.waitForSelector("button.confirm_sms",
                    new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE));
            page.click("button.confirm_sms");
            page.waitForTimeout(1000);

            // --- Далее всё как в регистрации 'В 1 клик': промокод, бонусы, регистрация ---
            promoCode = generatePromoCode();
            System.out.println("Генерируем промокод: " + promoCode);
            page.fill("input#registration_ref_code", promoCode);
            page.waitForTimeout(1000);

            System.out.println("Отказываемся от бонусов → выбираем бонус снова");
            page.click("div.c-registration__block--bonus .multiselect__select");
            page.waitForSelector(".multiselect__option .c-registration-select--refuse-bonuses");
            page.click(".multiselect__option .c-registration-select--refuse-bonuses:has-text('Отказ от бонусов')");
            page.waitForTimeout(500);

            page.click("div.c-registration__block--bonus .multiselect__select");
            page.waitForSelector(".multiselect__option .c-registration-select--sport-bonus");
            page.click(".multiselect__option .c-registration-select--sport-bonus:has-text('Получать бонусы')");
            page.waitForTimeout(500);

            System.out.println("Жмём 'Зарегистрироваться'");
            page.click("div.submit_registration");

            System.out.println("Жду появление блока с кнопкой 'Копировать' (до 10 минут)...");
            try {
                page.waitForSelector(
                        "div#js-post-reg-copy-login-password",
                        new Page.WaitForSelectorOptions()
                                .setTimeout(600_000)
                                .setState(WaitForSelectorState.VISIBLE)
                );
                System.out.println("Блок с 'Копировать' появился ✅");
            } catch (PlaywrightException e) {
                throw new RuntimeException("Блок с 'Копировать' не появился — что-то пошло не так!");
            }

            System.out.println("Читаем логин и пароль...");
            Locator loginLocator = page.locator("p#account-info-id");
            Locator passwordLocator = page.locator("p#account-info-password");
            loginLocator.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(5_000));
            passwordLocator.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(5_000));
            login = loginLocator.innerText().trim();
            password = passwordLocator.innerText().trim();
            System.out.println("Логин: " + login + ", Пароль: " + password);

            System.out.println("Нажимаем 'Копировать' логин/пароль");
            page.click("div#js-post-reg-copy-login-password");
            page.waitForTimeout(500);

            page.waitForSelector("button.swal2-confirm.swal2-styled",
                    new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE));
            page.click("button.swal2-confirm.swal2-styled");
            page.waitForTimeout(500);

            System.out.println("Высылаем данные по SMS");
            page.waitForSelector("button#account-info-button-sms");
            page.click("button#account-info-button-sms");
            page.waitForTimeout(500);
            closeIfVisible("button.reset-password__close", "reset-password__close");
            closeAllDialogsWithCross();

            System.out.println("Сохраняем в файл");
            page.waitForSelector("a#account-info-button-file");
            page.click("a#account-info-button-file");
            page.waitForTimeout(500);
            closeAllDialogsWithCross();

            System.out.println("Сохраняем картинкой");
            page.waitForSelector("a#account-info-button-image");
            page.click("a#account-info-button-image");
            page.waitForTimeout(500);
            closeAllDialogsWithCross();

            System.out.println("Высылаем на e-mail");
            page.waitForSelector("a#form_mail_after_submit");
            page.click("a#form_mail_after_submit");
            page.waitForTimeout(500);

            page.waitForSelector("input.js-post-email-content-form__input",
                    new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE));
            page.fill("input.js-post-email-content-form__input", creds.getProperty("email"));
            page.waitForSelector("button.js-post-email-content-form__btn:not([disabled])",
                    new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE));
            page.click("button.js-post-email-content-form__btn:not([disabled])");
            page.waitForTimeout(500);

            closeAllDialogsWithCross();

            System.out.println("Жмём 'Продолжить'");
            page.waitForSelector("a#continue-button-after-reg",
                    new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE));
            page.click("a#continue-button-after-reg");
            page.waitForTimeout(500);

            System.out.println("Проверяем, появилось ли окно идентификации");
            try {
                page.waitForSelector(
                        "a.identification-popup-link[href='/office/identification']",
                        new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.VISIBLE)
                                .setTimeout(7_000)
                );
                System.out.println("Попап идентификации найден, жмём 'Пройти идентификацию'");
                page.click("a.identification-popup-link[href='/office/identification']");
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                waitForPageOrReload(15_000);
                page.waitForTimeout(1000);
            } catch (PlaywrightException ex) {
                System.out.println("Попап идентификации после 'Продолжить' не появился — продолжаем без него");
            }
            page.waitForTimeout(500);
            closeAllDialogsWithCross();

            System.out.println("Открываем меню (ЛК)");
            page.waitForSelector("button.user-header__link.header__reg_ico");
            page.click("button.user-header__link.header__reg_ico");
            page.waitForTimeout(1000);

            System.out.println("Выходим из аккаунта");
            page.waitForSelector("button.drop-menu-list__link_exit");
            page.click("button.drop-menu-list__link_exit");
            page.waitForTimeout(500);

            System.out.println("Подтверждаем выход (ОК)");
            page.waitForSelector("button.swal2-confirm.swal2-styled",
                    new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE));
            page.click("button.swal2-confirm.swal2-styled");
            page.waitForTimeout(1000);

            long durationSec = (System.currentTimeMillis() - startMs) / 1000;

            String summary = "✅ *Тест успешно:* v2_MOBI_phone_registration\n"
                    + "• Регистрация: 'По телефону'\n"
                    + "• Номер: `" + phone + "`\n"
                    + "• Промокод: `" + promoCode + "`\n"
                    + "🔑 *Данные аккаунта:*\n"
                    + "• Логин: `" + login + "`\n"
                    + "• Пароль: `" + password + "`\n"
                    + "🕒 Старт: " + startedAt + "\n"
                    + "⏱ Длительность: " + durationSec + " сек.\n"
                    + "🌐 [1xbet.kz](https://1xbet.kz)";

            summary = summary.replace("_", "\\_");

            System.out.println(summary);
            Telegram.send(summary, botToken, chatId);

        } catch (Exception e) {
            String err = "❌ *Тест v2_MOBI_phone_registration упал*\n"
                    + "Сообщение: `" + (e.getMessage() == null ? "null" : e.getMessage().replace("_", "\\_")) + "`";
            System.out.println(err);
            Telegram.send(err, botToken, chatId);
            throw new RuntimeException(e);
        }
    }

    @AfterAll
    static void tearDownAll() {
        System.out.println("Тест завершён ✅ (браузер остаётся открытым)");
    }

    // --- Telegram helper ---
    static class Telegram {
        static void send(String text, String botToken, String chatId) {
            sendInternal(text, botToken, chatId, true);
        }

        private static void sendInternal(String text, String botToken, String chatId, boolean markdown) {
            try {
                String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";

                StringBuilder data = new StringBuilder();
                data.append("chat_id=").append(chatId)
                        .append("&text=").append(URLEncoder.encode(text, "UTF-8"));
                if (markdown) {
                    data.append("&parse_mode=Markdown");
                }

                HttpResponse<String> resp = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder()
                                .uri(java.net.URI.create(url))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(HttpRequest.BodyPublishers.ofString(data.toString()))
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                );

                if (resp.statusCode() != 200) {
                    System.out.println("⚠️ Telegram HTTP " + resp.statusCode()
                            + " / body: " + resp.body());
                    if (markdown) {
                        System.out.println("→ Повторяем без Markdown");
                        sendInternal(text, botToken, chatId, false);
                    }
                } else {
                    System.out.println("📨 Сообщение отправлено в Telegram ("
                            + (markdown ? "Markdown" : "plain") + ")");
                }
            } catch (Exception e) {
                System.out.println("⚠️ Ошибка отправки в Telegram: " + e.getMessage());
            }
        }
    }
}
