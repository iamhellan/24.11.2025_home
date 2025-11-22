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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;
import java.util.Random;

public class v2_MOBI_social_registration {
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

    /**
     * Логин в Google: берёт email/пароль из параметров и отрабатывает стандартный флоу.
     */
    static void performGoogleLogin(Page googlePage, String email, String password) {
        System.out.println("=== Авторизация в Google ===");

        try {
            // 1. Пытаемся найти сохранённый аккаунт
            Locator savedAccount = googlePage.locator("div[data-identifier='" + email + "']");
            if (savedAccount.count() > 0 && savedAccount.first().isVisible()) {
                System.out.println("Нашли аккаунт в списке, кликаем по сохранённому: " + email);
                savedAccount.first().click();
                googlePage.waitForTimeout(1500);
            } else {
                System.out.println("Сохранённый аккаунт не найден, вводим email вручную...");

                Locator emailInput = googlePage.locator("input#identifierId, input[type='email']");
                emailInput.first().waitFor(new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(60_000));
                emailInput.first().fill(email);
                googlePage.waitForTimeout(500);

                // Сначала пробуем просто Enter
                try {
                    emailInput.first().press("Enter");
                    System.out.println("Нажали Enter после ввода email");
                } catch (Exception ex) {
                    System.out.println("Не удалось нажать Enter, пробуем кнопки 'Далее/Next'...");

                    Locator nextBtnEmail = googlePage.locator(
                            "#identifierNext, " +
                                    "button:has-text('Далее'), div[role='button']:has-text('Далее'), " +
                                    "button:has-text('Next'),  div[role='button']:has-text('Next')"
                    );
                    nextBtnEmail.first().waitFor(new Locator.WaitForOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(30_000));
                    nextBtnEmail.first().click();
                }
                googlePage.waitForTimeout(1500);
            }

            // 2. Ожидаем появление поля пароля и вводим его
            Locator passwordInput = googlePage.locator("input[name='Passwd'][type='password']");
            passwordInput.first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(60_000));   // ЖДЁМ до минуты, пока появится поле пароля

            passwordInput.first().fill(password);
            googlePage.waitForTimeout(500);

            // 3. Жмём "Далее" на шаге пароля
            Locator nextBtnPass = googlePage.locator(
                    "#passwordNext, " +
                            "button:has-text('Далее'), div[role='button']:has-text('Далее'), " +
                            "button:has-text('Next'),  div[role='button']:has-text('Next')"
            );
            nextBtnPass.first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(30_000));
            nextBtnPass.first().click();
            googlePage.waitForTimeout(2000);

            System.out.println("Данные Google введены, ждём редирект обратно на 1xBet...");
        } catch (Exception e) {
            throw new RuntimeException("Ошибка при авторизации в Google: " + e.getMessage(), e);
        }
    }

    @BeforeAll
    static void setUpAll() throws IOException {
        // --- Загружаем креды ---
        creds.load(new FileInputStream("src/test/resources/config.properties"));

        playwright = Playwright.create();

        // --- полноэкранный мобильный браузер ---
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int width = (int) screenSize.getWidth();
        int height = (int) screenSize.getHeight();

        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setHeadless(false)
                        .setSlowMo(150)
                        .setArgs(List.of(
                                "--start-maximized",
                                "--window-size=" + width + "," + height
                        ))
        );

        context = browser.newContext(
                new Browser.NewContextOptions()
                        .setViewportSize(null)
                        .setUserAgent(
                                "Mozilla/5.0 (Linux; Android 11; SM-G998B) " +
                                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                        "Chrome/95.0.4638.74 Mobile Safari/537.36"
                        )
        );

        page = context.newPage();
        page.setDefaultTimeout(30_000);
    }

    @Test
    void registrationByGoogleSocialFullFlow() throws InterruptedException {
        long startMs = System.currentTimeMillis();
        LocalDateTime startDateTime = LocalDateTime.now();
        String startedAt = startDateTime.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"));

        String botToken = creds.getProperty("telegram.bot.token");
        String chatId = creds.getProperty("telegram.chat.id");

        String login = null;
        String password = null;
        String promoCode = null;

        Telegram.send("🚀 *Тест v2_MOBI_social_registration* стартовал\n(Регистрация через Google)",
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

            System.out.println("Переходим во вкладку 'Соцсети и мессенджеры'");
            // либо по классу soc_reg, либо по тексту
            Locator socTab = page.locator("button.c-registration__tab.soc_reg");
            if (socTab.count() == 0 || !socTab.first().isVisible()) {
                socTab = page.locator("button.c-registration__tab:has-text('Соцсети')");
            }
            socTab.first().click();
            page.waitForTimeout(1000);

            System.out.println("Выбираем способ регистрации через Google...");
            Locator googleOption = page.locator("div.c-registration__social-inner[name='google']");
            if (googleOption.count() == 0 || !googleOption.first().isVisible()) {
                throw new RuntimeException("Элемент Google-соцрегистрации не найден.");
            }
            googleOption.first().click();
            page.waitForTimeout(500);

            System.out.println("Отказываемся от бонусов → выбираем бонус снова");
            page.click("div.c-registration__block--bonus .multiselect__select");
            page.waitForSelector(".multiselect__option .c-registration-select--refuse-bonuses");
            page.click(".multiselect__option .c-registration-select--refuse-bonuses:has-text('Отказ от бонусов')");
            page.waitForTimeout(500);

            page.click("div.c-registration__block--bonus .multiselect__select");
            page.waitForSelector(".multiselect__option .c-registration-select--sport-bonus");
            page.click(".multiselect__option .c-registration-select--sport-bonus:has-text('Получать бонусы')");
            page.waitForTimeout(500);

            // --- Жмём 'Зарегистрироваться' и ждём сценарий ---
            System.out.println("Нажимаем 'Зарегистрироваться' (через JS) и ждём: Google / окно с логином и паролем / ЛК (до 5 минут)...");
            Locator regBtn = page.locator("div.c-registration__button.submit_registration:has-text('Зарегистрироваться')");
            if (regBtn.count() == 0 || !regBtn.first().isVisible()) {
                // fallback на общий submit
                regBtn = page.locator("div.submit_registration");
            }
            if (regBtn.count() == 0 || !regBtn.first().isVisible()) {
                throw new RuntimeException("Кнопка 'Зарегистрироваться' для соц.регистрации не найдена.");
            }

            page.evaluate("el => el.click()", regBtn.first().elementHandle());

            long waitStart = System.currentTimeMillis();
            long timeoutMs = 300_000L; // 5 минут
            long lastLog = waitStart;

            boolean googleDetected = false;
            boolean postRegDetected = false;
            boolean lkDetected = false;
            Page googleLoginPage = null;

            outer:
            while (System.currentTimeMillis() - waitStart < timeoutMs) {
                List<Page> pages = context.pages();

                for (Page p : pages) {
                    String url = "";
                    try {
                        url = p.url();
                    } catch (Exception ignored) {
                    }

                    // --- Google на любой вкладке ---
                    boolean urlLooksLikeGoogle =
                            url.contains("accounts.google.com") ||
                                    url.contains("consent.google.com") ||
                                    url.contains("myaccount.google.com") ||
                                    url.contains("://accounts.google.") ||
                                    url.contains("://www.google.");

                    boolean emailFieldVisible = false;
                    try {
                        Locator emailInput = p.locator("input[type='email']");
                        emailFieldVisible = emailInput.count() > 0 && emailInput.first().isVisible();
                    } catch (Exception ignored) {
                    }

                    if (urlLooksLikeGoogle || emailFieldVisible) {
                        googleDetected = true;
                        googleLoginPage = p;
                        System.out.println("Детектирован редирект на Google / форма логина Google ✅ (" + url + ")");
                        break outer;
                    }

                    // --- Окно кредов / ЛК ищем только на страницах 1xBet ---
                    boolean is1xBet =
                            url.contains("1xbet.kz") ||
                                    url.contains("1xbet.com") ||
                                    url.isEmpty(); // на всякий случай, если url ещё не успел обновиться

                    if (is1xBet) {
                        Locator idLocCheck = p.locator("p#account-info-id");
                        Locator passLocCheck = p.locator("p#account-info-password");
                        if (idLocCheck.count() > 0 && idLocCheck.first().isVisible()
                                && passLocCheck.count() > 0 && passLocCheck.first().isVisible()) {
                            postRegDetected = true;
                            System.out.println("Обнаружено окно с логином и паролем 1xBet после соц-регистрации ✅");
                            break outer;
                        }

                        Locator lkBtnCheck = p.locator("button.user-header__link.header__reg_ico");
                        if (lkBtnCheck.count() > 0 && lkBtnCheck.first().isVisible()) {
                            lkDetected = true;
                            System.out.println("Обнаружен ЛК — прямая авторизация без окна логин/пароль ✅");
                            break outer;
                        }
                    }
                }

                long now = System.currentTimeMillis();
                if (now - lastLog >= 10_000) {
                    System.out.println("Ждём решение капчи / Google / окно кредов / ЛК... прошло " +
                            ((now - waitStart) / 1000) + " сек.");
                    lastLog = now;
                }

                page.waitForTimeout(500);
            }

            if (!googleDetected && !postRegDetected && !lkDetected) {
                throw new RuntimeException("За 5 минут не дождались ни Google, ни окна с логином/паролем, ни ЛК. " +
                        "Возможно, капча не решена или флоу завис.");
            }

            // --- Если был Google — логинимся и ждём возврат на 1xBet ---
            boolean postRegAfterGoogle = false;
            boolean lkAfterGoogle = false;

            if (googleDetected) {
                System.out.println("Обнаружен переход на Google. Запускаем автоматическую авторизацию...");

                String googleEmail = creds.getProperty("google.email");
                String googlePassword = creds.getProperty("google.password");
                if (googleEmail == null || googlePassword == null ||
                        googleEmail.isBlank() || googlePassword.isBlank()) {
                    throw new IllegalStateException("google.email / google.password не заданы в config.properties");
                }

                Page gp = (googleLoginPage != null) ? googleLoginPage : page;
                performGoogleLogin(gp, googleEmail.trim(), googlePassword.trim());

                System.out.println("Ждём, пока после Google вернёмся на 1xBet и появится окно с логином/паролем или ЛК...");

                long backStart = System.currentTimeMillis();
                long backLastLog = backStart;

                while (System.currentTimeMillis() - backStart < timeoutMs) {
                    String url = "";
                    try {
                        url = page.url();
                    } catch (Exception ignored) {
                    }

                    boolean backTo1x = url.contains("1xbet.kz") || url.contains("1xbet.com");
                    if (backTo1x) {
                        Locator idLocCheck = page.locator("p#account-info-id");
                        Locator passLocCheck = page.locator("p#account-info-password");
                        if (idLocCheck.count() > 0 && idLocCheck.first().isVisible()
                                && passLocCheck.count() > 0 && passLocCheck.first().isVisible()) {
                            postRegAfterGoogle = true;
                            System.out.println("После Google появилось окно с логином и паролем 1xBet ✅");
                            break;
                        }

                        Locator lkBtnCheck = page.locator("button.user-header__link.header__reg_ico");
                        if (lkBtnCheck.count() > 0 && lkBtnCheck.first().isVisible()) {
                            lkAfterGoogle = true;
                            System.out.println("После Google сразу виден ЛК — авторизация без окна логин/пароль ✅");
                            break;
                        }
                    }

                    long now = System.currentTimeMillis();
                    if (now - backLastLog >= 10_000) {
                        System.out.println("Ждём возврат с Google на 1xBet... прошло " +
                                ((now - backStart) / 1000) + " сек.");
                        backLastLog = now;
                    }

                    page.waitForTimeout(1000);
                }

                if (!postRegAfterGoogle && !lkAfterGoogle) {
                    throw new RuntimeException("После Google за 5 минут не дождались ни окна с логином/паролем, ни ЛК.");
                }
            }

            boolean hasPostReg = postRegDetected || postRegAfterGoogle;
            boolean hasLk = lkDetected || lkAfterGoogle;

            if (!hasPostReg && !hasLk) {
                throw new RuntimeException("Не удалось определить конечное состояние после соц-регистрации.");
            }

            // --- Если есть окно с логином/паролем — работаем как в остальных регистрациях ---
            if (hasPostReg) {
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

                String email = creds.getProperty("email");
                if (email == null || email.isBlank()) {
                    throw new IllegalStateException("email не задан в config.properties");
                }
                page.fill("input.js-post-email-content-form__input", email);

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
            } else {
                System.out.println("Окно с логином/паролем не появилось — похоже, это авторизация через Google существующего аккаунта.");
            }

            // --- В любом случае: выходим из аккаунта через ЛК ---
            System.out.println("Открываем меню (ЛК)");
            page.waitForSelector("button.user-header__link.header__reg_ico",
                    new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE));
            page.click("button.user-header__link.header__reg_ico");
            page.waitForTimeout(1000);

            System.out.println("Выходим из аккаунта");
            page.waitForSelector("button.drop-menu-list__link_exit",
                    new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE));
            page.click("button.drop-menu-list__link_exit");
            page.waitForTimeout(500);

            System.out.println("Подтверждаем выход (ОК)");
            page.waitForSelector("button.swal2-confirm.swal2-styled",
                    new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE));
            page.click("button.swal2-confirm.swal2-styled");
            page.waitForTimeout(1000);

            long durationSec = (System.currentTimeMillis() - startMs) / 1000;

            String summary;
            if (hasPostReg) {
                summary = "✅ *Тест успешно:* v2_MOBI_social_registration\n"
                        + "• Регистрация: 'Соцсети и мессенджеры' (Google)\n"
                        + "• Промокод: `" + promoCode + "`\n"
                        + "🔑 *Данные аккаунта:*\n"
                        + "• Логин: `" + login + "`\n"
                        + "• Пароль: `" + password + "`\n"
                        + "🕒 Старт: " + startedAt + "\n"
                        + "⏱ Длительность: " + durationSec + " сек.\n"
                        + "🌐 [1xbet.kz](https://1xbet.kz)";
            } else {
                summary = "✅ *Тест успешно:* v2_MOBI_social_registration\n"
                        + "• Авторизация через Google (существующий аккаунт)\n"
                        + "• Окно с логином/паролем не появилось\n"
                        + "🕒 Старт: " + startedAt + "\n"
                        + "⏱ Длительность: " + durationSec + " сек.\n"
                        + "🌐 [1xbet.kz](https://1xbet.kz)";
            }

            summary = summary.replace("_", "\\_");

            System.out.println(summary);
            Telegram.send(summary, botToken, chatId);

        } catch (Exception e) {
            String err = "❌ *Тест v2_MOBI_social_registration упал*\n"
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
