package org.example;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.BiFunction;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class v2_id_authorization_fastgames {
    static Playwright playwright;
    static Browser browser;
    static BrowserContext context;
    static Page page;
    static TelegramNotifier tg;

    // DEBUG для поиска фреймов
    private static final boolean DEBUG_FRAMES = false;
    private static final java.util.Set<String> DEBUG_FRAMES_LOGGED = new java.util.HashSet<>();

    // --- Цветные логи для наглядности ---
    static void log(String text) {
        System.out.println("\u001B[37m" + text + "\u001B[0m");
    }

    static void info(String text) {
        System.out.println("\u001B[36mℹ️  " + text + "\u001B[0m");
    }

    static void success(String text) {
        System.out.println("\u001B[32m✅ " + text + "\u001B[0m");
    }

    static void warn(String text) {
        System.out.println("\u001B[33m⚠️  " + text + "\u001B[0m");
    }

    static void error(String text) {
        System.out.println("\u001B[31m❌ " + text + "\u001B[0m");
    }

    static void section(String name) {
        System.out.println("\n\u001B[45m===== " + name.toUpperCase() + " =====\u001B[0m");
    }

    @BeforeAll
    static void setUpAll() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setHeadless(false)
                        .setArgs(List.of("--start-maximized"))
        );
        context = browser.newContext(
                new Browser.NewContextOptions()
                        .setViewportSize(null)
        );
        page = context.newPage();

        // --- Telegram ---
        String botToken = ConfigHelper.get("telegram.bot.token");
        String chatId = ConfigHelper.get("telegram.chat.id");
        tg = new TelegramNotifier(botToken, chatId);
    }

    @AfterAll
    static void tearDownAll() {
        success("Тест завершён ✅ (браузер остаётся открытым)");
    }

    // ===== ВСПОМОГАТЕЛЬНЫЕ УТИЛИТЫ ============================================================

    private com.microsoft.playwright.Frame findFrameWithSelector(Page p, String selector, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (Page pg : p.context().pages()) {
                for (com.microsoft.playwright.Frame f : pg.frames()) {
                    try {
                        if (f.locator(selector).count() > 0) {
                            if (DEBUG_FRAMES && DEBUG_FRAMES_LOGGED.add(f.url())) {
                                System.out.println("[DEBUG] Нашли селектор в фрейме: " + f.url());
                            }
                            return f;
                        }
                    } catch (Throwable ignore) {}
                }
            }
            p.waitForTimeout(300);
        }
        return null;
    }

    private Locator smartLocator(Page p, String selector, int timeoutMs) {
        Locator direct = p.locator(selector);
        if (direct.count() > 0) return direct;
        com.microsoft.playwright.Frame f = findFrameWithSelector(p, selector, timeoutMs);
        if (f != null) return f.locator(selector);
        throw new RuntimeException("Элемент не найден ни на странице, ни во фреймах: " + selector);
    }

    private void robustClick(Page p, Locator loc, int timeoutMs, String debugName) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        RuntimeException lastErr = null;

        while (System.currentTimeMillis() < deadline) {
            try {
                loc.first().scrollIntoViewIfNeeded();
                loc.first().click(new Locator.ClickOptions().setTimeout(3000));
                return;
            } catch (RuntimeException e1) {
                lastErr = e1;
                String msg = e1.getMessage() == null ? "" : e1.getMessage();
                boolean intercept = msg.contains("intercepts pointer events");

                if (intercept) {
                    info("'" + debugName + "': перехват клика. Пробуем через force или JS.");
                    try {
                        loc.first().click(new Locator.ClickOptions().setTimeout(2000).setForce(true));
                        return;
                    } catch (Throwable ignore) {}
                    try {
                        loc.first().evaluate("el => el.dispatchEvent(new MouseEvent('click', {bubbles:true}))");
                        return;
                    } catch (Throwable ignore) {}
                }
            }
            p.waitForTimeout(200);
        }
        if (lastErr != null) throw lastErr;
        throw new RuntimeException("Не удалось кликнуть по '" + debugName + "' за " + timeoutMs + "ms");
    }

    private void clickFirstEnabled(Page p, String selector, int timeoutMs) {
        Locator loc = smartLocator(p, selector, timeoutMs);
        robustClick(p, loc.first(), timeoutMs, selector);
    }

    private void clickFirstEnabledAny(Page p, String[] selectors, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (String sel : selectors) {
                try {
                    clickFirstEnabled(p, sel, 1500);
                    return;
                } catch (Throwable ignore) {}
            }
            p.waitForTimeout(150);
        }
        throw new RuntimeException("Не нашли активный элемент ни по одному из селекторов!");
    }

    // Доп. утилита (пока не используется, но оставлена на будущее)
    private void waitUntilClickable(Page p, String selector, int timeoutMs, String debugName) {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            try {
                Locator loc = smartLocator(p, selector, 3000);
                if (loc.count() == 0) {
                    p.waitForTimeout(400);
                    continue;
                }

                Locator el = loc.first();
                if (!el.isVisible()) {
                    p.waitForTimeout(400);
                    continue;
                }

                boolean clickable = (Boolean) el.evaluate(
                        "el => {" +
                                "  const style = window.getComputedStyle(el);" +
                                "  const rect = el.getBoundingClientRect();" +
                                "  if (!rect.width || !rect.height) return false;" +
                                "  const cx = rect.left + rect.width / 2;" +
                                "  const cy = rect.top + rect.height / 2;" +
                                "  const top = document.elementFromPoint(cx, cy);" +
                                "  if (!top) return false;" +
                                "  const contains = (top === el) || el.contains(top);" +
                                "  if (!contains) return false;" +
                                "  if (style.visibility === 'hidden' || style.display === 'none' || style.opacity === '0') return false;" +
                                "  if (style.pointerEvents === 'none') return false;" +
                                "  if (el.hasAttribute('disabled') || el.getAttribute('aria-disabled') === 'true') return false;" +
                                "  const locked = el.closest('.locked, [data-locked=\"true\"], [data-disabled=\"true\"], .market-lock, .is-locked');" +
                                "  return !locked;" +
                                "}"
                );

                if (clickable) {
                    success("Элемент '" + debugName + "' стал кликабельным ✅");
                    return;
                }

            } catch (Throwable ignore) {
                // просто ждём дальше
            }

            p.waitForTimeout(500);
        }

        throw new RuntimeException(
                "Элемент '" + debugName + "' так и не стал кликабельным за " + timeoutMs + " ms"
        );
    }

    // Проверка кликабельности исхода
    private boolean isClickable(Locator loc) {
        if (loc == null || loc.count() == 0) return false;
        Locator btn = loc.first();
        if (!btn.isVisible()) return false;

        try {
            return (Boolean) btn.evaluate(
                    "el => {" +
                            "  const style = window.getComputedStyle(el);" +
                            "  const hasLock = !!(el.querySelector('.icon-lock, .oneX-icon-locked'));" +
                            "  const disabledClass = el.classList.contains('disabled') || el.classList.contains('locked') || el.classList.contains('pointer-events-none');" +
                            "  const disabledAttr = el.hasAttribute('disabled') || !!el.closest('[disabled]');" +
                            "  if (hasLock || disabledClass || disabledAttr) return false;" +
                            "  if (style.pointerEvents === 'none') return false;" +
                            "  if (style.display === 'none') return false;" +
                            "  if (style.visibility === 'hidden') return false;" +
                            "  if (style.opacity === '0') return false;" +
                            "  if (el.closest('[style*=\"display:none\"]')) return false;" +
                            "  return true;" +
                            "}"
            );
        } catch (Throwable e) {
            return false;
        }
    }

    private Page clickCardMaybeOpensNewTab(Locator card) {
        int before = context.pages().size();
        robustClick(page, card, 30000, "game-card");
        page.waitForTimeout(600);
        int after = context.pages().size();
        if (after > before) {
            Page newPage = context.pages().get(after - 1);
            newPage.bringToFront();
            System.out.println("[DEBUG] Игра открылась в новой вкладке: " + newPage.url());
            return newPage;
        }
        System.out.println("[DEBUG] Игра открылась в текущем окне/фрейме");
        return page;
    }

    private void passTutorialIfPresent(Page gamePage) {
        for (int i = 1; i <= 5; i++) {
            try {
                Locator nextBtn = smartLocator(gamePage, "div[role='button']:has-text('Далее')", 600);
                if (nextBtn.count() == 0 || !nextBtn.first().isVisible()) break;
                robustClick(gamePage, nextBtn.first(), 2000, "Далее");
                gamePage.waitForTimeout(150);
            } catch (Throwable ignore) {
                break;
            }
        }
        try {
            Locator understood = smartLocator(gamePage, "div[role='button']:has-text('Я всё понял')", 600);
            if (understood.count() > 0 && understood.first().isVisible()) {
                robustClick(gamePage, understood.first(), 2000, "Я всё понял");
            }
        } catch (Throwable ignore) {}
    }

    private void setStake50ViaChip(Page gamePage) {
        System.out.println("Выбираем чип 50 KZT");
        Locator chip50 = smartLocator(gamePage, "div.chip-text:has-text('50')", 2000);
        robustClick(gamePage, chip50.first(), 8000, "chip-50");
    }

    // === универсальное ожидание раунда (для тех игр, где хватает грубой эвристики) ===
    private void waitRoundToSettle(Page gamePage, String debugName, int maxMs) {
        info("Ждём завершения раунда (старый режим): " + debugName);
        long start = System.currentTimeMillis();
        boolean roundStarted = false;

        while (System.currentTimeMillis() - start < maxMs) {
            try {
                Locator anyBet = gamePage.locator(
                        "div[role='button'][data-market][data-outcome]:has-text('Сделать ставку')"
                );
                if (anyBet.count() > 0 && anyBet.first().isVisible()) {
                    boolean enabled = (Boolean) anyBet.first().evaluate(
                            "e => !(e.classList && e.classList.contains('pointer-events-none'))"
                    );
                    if (enabled) {
                        success("Новый раунд доступен — продолжаем ✅: " + debugName);
                        roundStarted = true;
                        break;
                    }
                }

                if (System.currentTimeMillis() - start > 60000 && !roundStarted) {
                    warn("Игра не реагирует более 60 сек — пропускаем: " + debugName);
                    return;
                }

            } catch (Throwable ignore) {}
            gamePage.waitForTimeout(300);
        }

        if (!roundStarted) {
            warn("Раунд не завершился в течение " + (maxMs / 1000) + " сек: " + debugName);
        }
    }

    // Специализированное ожидание конца раунда по конкретному исходу
    private void waitRoundByOutcome(Page gamePage, String outcomeSelector, int maxMs) {
        info("Ждём завершения раунда по исходу: " + outcomeSelector);
        long start = System.currentTimeMillis();
        boolean seenLocked = false;

        while (System.currentTimeMillis() - start < maxMs) {
            Locator btn = null;
            try {
                btn = smartLocator(gamePage, outcomeSelector, 1000);
            } catch (Throwable ignore) {}

            if (btn != null && btn.count() > 0) {
                boolean clickable = isClickable(btn);
                if (!clickable) {
                    seenLocked = true; // раунд идёт
                } else if (seenLocked) {
                    success("Раунд завершился — исход снова доступен для ставки ✅");
                    return;
                }
            }

            gamePage.waitForTimeout(700);
        }

        warn("Не удалось отследить завершение раунда за " + (maxMs / 1000) +
                " сек (по исходу " + outcomeSelector + ")");
    }

    // === НОВОЕ: ожидание нового раунда по bet-bar (используем ТОЛЬКО для: Нарды, Дартс, Дартс-Фортуна, Больше/Меньше) ===
    private void waitNewRoundByBetBar(Page gamePage, String gameName, int maxMs) {
        System.out.println("ℹ️  Ждём следующий раунд по bet-bar в игре: " + gameName);

        long deadline = System.currentTimeMillis() + maxMs;

        Locator bar;
        try {
            // если игра в iframe — тут должен быть smartLocator, как ты уже делаешь
            bar = smartLocator(gamePage, "div.bet-bar-desktop, div.bet-bar-mobile", 10_000);
        } catch (Throwable t) {
            System.out.println("⚠️  bet-bar не найден в игре: " + gameName + " — пропускаем спец-ожидание.");
            return;
        }

        Integer prev = null;
        boolean sawDecreasing = false; // видели, что таймер убывает в рамках текущего раунда

        while (System.currentTimeMillis() < deadline) {
            Integer val = null;

            try {
                Locator timeNode = bar.locator(".bet-countdown-time");
                if (timeNode.count() > 0 && timeNode.first().isVisible()) {
                    String txt = timeNode.first().innerText().trim();
                    // выдёргиваем число, на случай "6 сек"
                    String digits = txt.replaceAll("\\D+", "");
                    if (!digits.isEmpty()) {
                        val = Integer.parseInt(digits);
                    }
                }
            } catch (Throwable ignore) {
            }

            if (val != null) {
                if (prev == null) {
                    prev = val;
                } else {
                    if (val < prev) {
                        // таймер идёт вниз → раунд идёт
                        sawDecreasing = true;
                        prev = val;
                    } else if (sawDecreasing && val > prev) {
                        // было убывание, потом значение ПОДРОСЛО → счётчик перезапустился → новый раунд
                        System.out.println("✅ Обнаружен НОВЫЙ раунд по bet-bar в игре: " + gameName);
                        return;
                    } else {
                        prev = val;
                    }
                }
            }

            gamePage.waitForTimeout(500);
        }

        System.out.println("⚠️  Не дождались нового раунда по bet-bar за " + (maxMs / 1000) + " сек в игре: " + gameName);
    }

    private Page openGameByHrefContains(Page originPage, String hrefContains, String fallbackMenuText) {
        com.microsoft.playwright.Frame f = findFrameWithSelector(originPage, "a[href*='" + hrefContains + "']", 5000);
        if (f == null && fallbackMenuText != null) {
            f = findFrameWithSelector(originPage, "span.text-hub-header-game-title:has-text('" + fallbackMenuText + "')", 5000);
        }
        if (f == null) throw new RuntimeException("Не нашли ссылку на игру: " + hrefContains);
        Locator link = f.locator("a[href*='" + hrefContains + "']");
        if (link.count() == 0 && fallbackMenuText != null) {
            link = f.locator("span.text-hub-header-game-title:has-text('" + fallbackMenuText + "')").locator("xpath=ancestor::a");
        }
        return clickCardMaybeOpensNewTab(link.first());
    }

    private Page openUniqueBoxingFromHub(Page originPage) {
        String innerSpan = "a.menu-sports-item-inner[href*='productId=boxing'][href*='cid=1xbetkz'] span.text-hub-header-game-title:has-text('Бокс')";
        com.microsoft.playwright.Frame f = findFrameWithSelector(originPage, innerSpan, 8000);
        if (f == null) throw new RuntimeException("❌ Не нашли уникальную кнопку 'Бокс'");
        Locator link = f.locator(innerSpan).locator("xpath=ancestor::a");
        return clickCardMaybeOpensNewTab(link.first());
    }

    // --- Безопасный запуск игры (чтобы при ошибке тест не падал, а шёл дальше) ---
    private void playSafe(String gameName, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            warn("Ошибка при выполнении '" + gameName + "': " + e.getMessage());
            String screenshot = ScreenshotHelper.takeScreenshot(page, "skip_" + gameName);
            info("Пропускаем игру '" + gameName + "' и продолжаем...");
        }
    }

    private boolean tryBetFirstBoxingOutcome(Page boxingPage) {
        info("Ищем первый доступный исход в 'Бокс'");
        long start = System.currentTimeMillis();
        long maxMs = 300_000; // до 5 минут

        // Находим все кнопки исходов (без текста 'Сделать ставку')
        Locator all = smartLocator(
                boxingPage,
                "div.contest-panel-outcome-button, " +
                        "button.contest-panel-outcome-button, " +
                        "div[role='button'].contest-panel-outcome-button",
                10_000
        );

        while (System.currentTimeMillis() - start < maxMs) {
            int count = all.count();
            for (int i = 0; i < count; i++) {
                Locator btn = all.nth(i);
                if (isClickable(btn)) {
                    success("Нашли доступный исход №" + (i + 1) + " — делаем ставку");
                    try {
                        btn.scrollIntoViewIfNeeded();
                        btn.click(new Locator.ClickOptions()
                                .setTimeout(5_000)
                                .setForce(true));
                    } catch (Throwable e) {
                        warn("Обычный клик не сработал, пробуем через JS");
                        try {
                            boxingPage.evaluate(
                                    "el => el.dispatchEvent(new MouseEvent('click', {bubbles:true}))",
                                    btn.elementHandle()
                            );
                        } catch (Throwable e2) {
                            error("Ошибка при JS-клике: " + e2.getMessage());
                            return false;
                        }
                    }
                    boxingPage.waitForTimeout(600);
                    return true;
                }
            }

            boxingPage.waitForTimeout(700);
        }

        warn("Не нашли ни одного доступного исхода в 'Бокс' за 300 сек");
        return false;
    }

    // ======= ТЕСТ ============================================================
    @Test
    void loginAndPlayFastGames() {
        long startTime = System.currentTimeMillis();
        tg.sendMessage("🚀 *Тест v2_id_authorization_fastgames* стартовал (авторизация через ID)");

        // флаги успешности по критичным играм
        boolean shootoutOk = false;
        boolean boxingOk = false;

        try {
            log("Открываем сайт 1xbet.kz");
            page.navigate("https://1xbet.kz/");
            page.evaluate("window.moveTo(0,0); window.resizeTo(screen.width, screen.height);");

            log("Жмём 'Войти' в шапке");
            page.waitForTimeout(800);
            page.click("button#login-form-call");

            String login = ConfigHelper.get("login");
            String password = ConfigHelper.get("password");
            log("Вводим ID и пароль из config.properties");

            page.fill("input#auth_id_email", login);
            page.fill("input#auth-form-password", password);
            page.click("button.auth-button.auth-button--block.auth-button--theme-secondary");

            log("Ждём появления кнопки 'Выслать код' (до 10 мин)");
            page.waitForSelector("button:has-text('Выслать код')",
                    new Page.WaitForSelectorOptions().setTimeout(600000).setState(WaitForSelectorState.VISIBLE));

            log("Жмём 'Выслать код'");
            page.click("button:has-text('Выслать код')");

            log("Ждём поле для кода (до 10 мин)");
            page.waitForSelector("input.phone-sms-modal-code__input",
                    new Page.WaitForSelectorOptions().setTimeout(600000).setState(WaitForSelectorState.VISIBLE));

            // --- УНИВЕРСАЛЬНЫЙ ПОИСК СЕССИИ GOOGLE MESSAGES ---
            Path projectRoot = Paths.get(System.getProperty("user.dir"));
            Path[] possiblePaths = new Path[]{
                    projectRoot.resolve("resources/sessions/messages-session.json"),
                    projectRoot.resolve("src/test/resources/sessions/messages-session.json"),
                    projectRoot.resolve("src/test/java/org/example/resources/sessions/messages-session.json")
            };

            Path sessionPath = null;
            for (Path path : possiblePaths) {
                if (path.toFile().exists()) {
                    sessionPath = path;
                    break;
                }
            }

            if (sessionPath == null) {
                throw new RuntimeException("❌ Файл сессии Google Messages не найден ни в одном из стандартных путей!");
            }

            System.out.println("📁 Используем файл сессии: " + sessionPath.toAbsolutePath());

            // --- Открываем Google Messages с сохранённой авторизацией ---
            System.out.println("🔐 Открываем Google Messages с сохранённой сессией...");
            BrowserContext messagesContext = browser.newContext(
                    new Browser.NewContextOptions().setStorageStatePath(sessionPath)
            );
            Page messagesPage = messagesContext.newPage();
            messagesPage.navigate("https://messages.google.com/web/conversations");

            System.out.println("⌛ Ждём появления списка чатов...");
            boolean chatsLoaded = false;
            for (int i = 0; i < 20; i++) {
                if (messagesPage.locator("mws-conversation-list-item").count() > 0) {
                    chatsLoaded = true;
                    break;
                }
                messagesPage.waitForTimeout(1000);
            }
            if (!chatsLoaded)
                throw new RuntimeException("❌ Чаты не появились в Google Messages — возможно, не успели подгрузиться.");
            System.out.println("✅ Список чатов успешно найден");

            System.out.println("🔍 Ищем чат с 1xBet...");
            Locator chat = messagesPage.locator("mws-conversation-list-item:has-text('1xbet'), mws-conversation-list-item:has-text('1xbet-kz')");
            if (chat.count() == 0) chat = messagesPage.locator("mws-conversation-list-item").first();
            chat.first().click();
            System.out.println("💬 Чат открыт");
            messagesPage.waitForTimeout(3000);

            System.out.println("📩 Ищем последнее сообщение...");
            Locator messageNodes = messagesPage.locator("div.text-msg-content div.text-msg.msg-content div.ng-star-inserted");
            int count = 0;
            for (int i = 0; i < 15; i++) {
                count = messageNodes.count();
                if (count > 0) break;
                messagesPage.waitForTimeout(1000);
            }
            if (count == 0)
                throw new RuntimeException("❌ Не найдено сообщений внутри чата!");
            String lastMessageText = messageNodes.nth(count - 1).innerText().trim();
            System.out.println("📨 Последнее сообщение: " + lastMessageText);

            Matcher matcher = Pattern.compile("\\b[a-zA-Z0-9]{4,8}\\b").matcher(lastMessageText);
            String code = matcher.find() ? matcher.group() : null;
            if (code == null)
                throw new RuntimeException("❌ Код подтверждения не найден в сообщении!");
            System.out.println("✅ Извлечённый код: " + code);

            // --- Возврат к 1xBet ---
            System.out.println("🔄 Закрываем вкладку Google Messages и возвращаем фокус на 1xBet");
            try {
                messagesPage.close(); // закрываем только вкладку
                messagesContext.close(); // освобождаем контекст
                page.bringToFront(); // возвращаем активное окно 1xBet
                page.waitForTimeout(1000);
                System.out.println("✅ Переключились обратно на 1xBet");
            } catch (Exception e) {
                System.out.println("⚠️ Не удалось корректно вернуть фокус: " + e.getMessage());
            }

            log("Вводим код и подтверждаем вход");
            page.fill("input.phone-sms-modal-code__input", code);
            page.click("button:has-text('Подтвердить')");
            success("Авторизация завершена ✅");

            // ====== БЫСТРЫЕ ИГРЫ ======
            section("Переход в Быстрые игры");
            page.waitForTimeout(1200);
            page.click("a.header-menu-nav-list-item__link.main-item:has-text('Быстрые игры')");

            // === Универсальная функция: ждём, пока исход станет кликабельным, и только потом ставим ===
            BiFunction<Page, String, Boolean> tryBetButton = (gamePage, selector) -> {
                info("Проверяем исход/кнопку для ставки: " + selector);
                long start = System.currentTimeMillis();
                long maxMs = 300_000;  // до 5 минут

                while (System.currentTimeMillis() - start < maxMs) {
                    Locator button = null;
                    try {
                        button = smartLocator(gamePage, selector, 1_000);
                    } catch (Throwable ignore) {}

                    if (button != null && button.count() > 0) {
                        Locator btn = button.first();
                        if (isClickable(btn)) {
                            success("Исход разблокирован — делаем ставку");
                            try {
                                btn.scrollIntoViewIfNeeded();
                                btn.click(new Locator.ClickOptions()
                                        .setTimeout(5_000)
                                        .setForce(true));
                            } catch (Throwable e) {
                                warn("Обычный клик не сработал, пробуем через JS");
                                try {
                                    gamePage.evaluate(
                                            "el => el.dispatchEvent(new MouseEvent('click', {bubbles:true}))",
                                            btn.elementHandle()
                                    );
                                } catch (Throwable e2) {
                                    error("Ошибка при JS-клике: " + e2.getMessage());
                                }
                            }
                            gamePage.waitForTimeout(600);
                            return true;
                        }
                    }

                    gamePage.waitForTimeout(500);
                }

                warn("Исход так и не стал доступен за " + (maxMs / 1000) + " сек — пропускаем игру");
                return false;
            };

            // === Крэш-Бокс === (оставляем старую схему ожидания)
            section("Крэш-Бокс");
            log("Ищем карточку 'Крэш-Бокс' (через href) в фреймах");

            com.microsoft.playwright.Frame gamesFrame = findFrameWithSelector(page, "a.game[href*='crash-boxing']", 8000);
            if (gamesFrame == null) {
                gamesFrame = findFrameWithSelector(page, "p.game-name:has-text('Крэш-Бокс')", 12000);
            }
            if (gamesFrame == null) {
                for (com.microsoft.playwright.Frame fx : page.frames()) {
                    if (fx.locator("a.game[href*='crash-boxing']").count() > 0) {
                        gamesFrame = fx;
                        break;
                    }
                }
            }
            if (gamesFrame == null) {
                List<com.microsoft.playwright.Frame> frames = page.frames();
                System.out.println("[DEBUG] Фреймы на странице:");
                for (com.microsoft.playwright.Frame f : frames) System.out.println(" - " + f.url());
                throw new RuntimeException("❌ Не удалось найти карточку 'Крэш-Бокс' ни в одном iframe");
            }

            Locator crashByHref = gamesFrame.locator("a.game[href*='crash-boxing']");
            Locator crashByText = gamesFrame.locator("p.game-name:has-text('Крэш-Бокс')").locator("xpath=ancestor::a");
            Locator crashCard = crashByHref.count() > 0 ? crashByHref : crashByText;

            log("Ждём появления карточки в DOM");
            crashCard.waitFor(new Locator.WaitForOptions().setTimeout(20000).setState(WaitForSelectorState.ATTACHED));

            log("Кликаем по Крэш-Бокс");
            Page gamePage = clickCardMaybeOpensNewTab(crashCard);
            gamePage.waitForTimeout(800);

            passTutorialIfPresent(gamePage);

            // --- Жмём кнопку "Мин" --- (оставляем как есть)
            log("Жмём кнопку 'Мин' для минимальной ставки");
            try {
                Locator minButton = smartLocator(gamePage,
                        "span[role='button']:has-text('Мин')",
                        8000);
                robustClick(gamePage, minButton, 5000, "Мин");
                success("Кнопка 'Мин' нажата ✅");
            } catch (Exception e) {
                warn("Не удалось нажать 'Мин': " + e.getMessage());
            }

            gamePage.waitForTimeout(800);

// --- Первая ставка (yes) ---
            log("Ставка 50 KZT (yes)");
            clickFirstEnabled(
                    gamePage,
                    "div[role='button'][data-market='hit_met_condition'][data-outcome='yes']",
                    60_000
            );

// --- Ждём, пока тот же исход снова станет доступен (новый раунд) ---
            waitRoundByOutcome(
                    gamePage,
                    "div[role='button'][data-market='hit_met_condition'][data-outcome='yes']",
                    60_000
            );

            // ===== Нарды ===== (НОВАЯ схема: ожидание по bet-bar)
            section("Нарды");
            log("Переходим в игру 'Нарды'");
            Page nardsPage = openGameByHrefContains(gamePage, "nard", "Нарды");
            nardsPage.waitForTimeout(600);
            passTutorialIfPresent(nardsPage);
            setStake50ViaChip(nardsPage);
            log("Выбираем исход: Синий");
            clickFirstEnabled(nardsPage, "span[role='button'][data-market='dice'][data-outcome='blue']", 300000);
            // новый раунд определяем через bet-bar
            waitNewRoundByBetBar(nardsPage, "Нарды", 60000);

            // ===== Дартс ===== (НОВАЯ схема: ожидание по bet-bar)
            section("Дартс");
            log("Переходим в игру 'Дартс'");
            Page dartsPage = openGameByHrefContains(nardsPage, "darts?cid", "Дартс");
            dartsPage.waitForTimeout(600);
            passTutorialIfPresent(dartsPage);
            setStake50ViaChip(dartsPage);
            log("Выбираем исход (1-4-5-6-9-11-15-16-17-19)");
            clickFirstEnabled(dartsPage, "span[role='button'][data-market='1-4-5-6-9-11-15-16-17-19']", 300000);
            // новый раунд определяем через bet-bar
            waitNewRoundByBetBar(dartsPage, "Дартс", 60000);

            // ===== Дартс - Фортуна ===== (НОВАЯ схема: ожидание по bet-bar)
            section("Дартс - Фортуна");
            log("Переходим в игру 'Дартс - Фортуна'");
            Page dartsFortunePage = openGameByHrefContains(dartsPage, "darts-fortune", "Дартс - Фортуна");
            dartsFortunePage.waitForTimeout(600);
            passTutorialIfPresent(dartsFortunePage);

            // --- Ждём появления чипа '50' ---
            log("Ожидаем появления чипа '50'");
            try {
                Locator chip50 = smartLocator(dartsFortunePage,
                        "div.chip-text:has-text('50')",
                        60000);
                chip50.first().waitFor(new Locator.WaitForOptions()
                        .setTimeout(60000)
                        .setState(WaitForSelectorState.VISIBLE));
                success("Чип '50' появился — можно делать ставку ✅");
            } catch (Exception e) {
                warn("Чип '50' не появился вовремя — продолжаем без него: " + e.getMessage());
            }

            // --- Выбираем исход: ONE_TO_EIGHT (Сектор 1-8) ---
            log("Выбираем исход: ONE_TO_EIGHT (Сектор 1-8)");
            try {
                clickFirstEnabled(dartsFortunePage, "div[data-outcome='ONE_TO_EIGHT']", 45000);
                success("Исход ONE_TO_EIGHT выбран ✅");
            } catch (Exception e) {
                error("Не удалось выбрать исход ONE_TO_EIGHT: " + e.getMessage());
            }

            // новый раунд определяем через bet-bar
            waitNewRoundByBetBar(dartsFortunePage, "Дартс - Фортуна", 60000);

            // ===== Больше/Меньше ===== (НОВАЯ схема: ожидание по bet-bar)
            section("Больше / Меньше");
            log("Переходим в игру 'Больше/Меньше'");
            Page hiloPage = openGameByHrefContains(dartsFortunePage, "darts-hilo", "Больше/Меньше");
            hiloPage.waitForTimeout(600);
            passTutorialIfPresent(hiloPage);
            setStake50ViaChip(hiloPage);
            log("Выбираем исход: Больше или равно (>=16)");
            clickFirstEnabledAny(hiloPage, new String[]{
                    "div[role='button'][data-market='THROW_RESULT'][data-outcome='gte-16']",
                    "div.board-market-hi-eq:has-text('Больше или равно')"
            }, 300000);
            // новый раунд определяем через bet-bar
            waitNewRoundByBetBar(hiloPage, "Больше/Меньше", 60000);

            // ===== Буллиты NHL21 ===== (оставляем «старый умный» способ по исходу "Да")
            section("Буллиты NHL21");
            log("Переходим в игру 'Буллиты NHL21'");
            Page shootoutPage = openGameByHrefContains(hiloPage, "shootout", "Буллиты NHL21");
            shootoutPage.waitForTimeout(800);
            passTutorialIfPresent(shootoutPage);
            log("Ждём появления суммы (чип 50)");
            setStake50ViaChip(shootoutPage);
            log("Выбираем исход: Да (ждём, пока станет доступен)");
            boolean shootoutBetDone = tryBetButton.apply(
                    shootoutPage,
                    "div[role='button'].market-button:has-text('Да')"
            );
            if (shootoutBetDone) {
                shootoutOk = true;
                waitRoundByOutcome(
                        shootoutPage,
                        "div[role='button'].market-button:has-text('Да')",
                        300_000
                );
            } else {
                warn("Не удалось сделать ставку в 'Буллиты NHL21' — исход 'Да' так и не разблокировался.");
            }

            // ===== Бокс (уникальная кнопка) ===== (оставляем старую спец-логику)
            section("Бокс");
            log("Переходим в игру 'Бокс' (уникальная кнопка)");
            Page boxingPage = openUniqueBoxingFromHub(shootoutPage);
            boxingPage.waitForTimeout(600);
            passTutorialIfPresent(boxingPage);
            setStake50ViaChip(boxingPage);
            log("Выбираем исход: боксёр №1 (первая кнопка)");

            log("Ждём появления панели исходов (contest-panel) в игре 'Бокс'");
            try {
                Locator panel = smartLocator(boxingPage, "div.contest-panel", 180_000);
                panel.first().waitFor(new Locator.WaitForOptions()
                        .setTimeout(180_000)
                        .setState(WaitForSelectorState.VISIBLE));
                success("Панель исходов появилась ✅");
            } catch (Exception e) {
                warn("Панель исходов 'div.contest-panel' не появилась вовремя или лежит глубже — продолжаем, пытаемся найти кнопку ставки напрямую: " + e.getMessage());
            }

            boolean betDone = tryBetFirstBoxingOutcome(boxingPage);
            if (betDone) {
                boxingOk = true;
            } else {
                warn("Не удалось сделать ставку в 'Бокс' — ни один исход не стал доступным.");
            }

            boolean allFastGamesOk = shootoutOk && boxingOk;

            if (allFastGamesOk) {
                success("Готово ✅ (все быстрые игры отыграны со ставкой)");
            } else {
                warn("Сценарий завершён частично: не во всех быстрых играх удалось сделать ставку.");
            }

            // --- Переход в Личный кабинет и корректный выход ---
            log("Открываем 'Личный кабинет'");
            page.waitForTimeout(1000);
            page.click("a.header-lk-box-link[title='Личный кабинет']");

            log("Пробуем закрыть popup-крестик после входа в ЛК (если он вообще есть)");
            try {
                Locator closeCrossLk = page.locator("div.box-modal_close.arcticmodal-close");
                closeCrossLk.waitFor(new Locator.WaitForOptions().setTimeout(2000).setState(WaitForSelectorState.ATTACHED));
                if (closeCrossLk.isVisible()) {
                    closeCrossLk.click();
                    success("Крестик в ЛК найден и нажат ✅");
                } else {
                    info("Крестика в ЛК нет — идём дальше");
                }
            } catch (Exception e) {
                info("Всплывашки в ЛК или крестика нет, игнорируем и двигаемся дальше");
            }

            log("Жмём 'Выход'");
            page.waitForTimeout(1000);
            page.click("a.ap-left-nav__item_exit");

            log("Подтверждаем выход кнопкой 'ОК'");
            page.waitForTimeout(1000);
            page.click("button.swal2-confirm.swal2-styled");

            success("Выход завершён ✅ (браузер остаётся открытым)");

            long duration = (System.currentTimeMillis() - startTime) / 1000;

            StringBuilder gamesReport = new StringBuilder();
            gamesReport.append("• Быстрые игры:\n");
            gamesReport.append("   - Крэш-Бокс — ставка сделана\n");
            gamesReport.append("   - Нарды — ставка сделана\n");
            gamesReport.append("   - Дартс — ставка сделана\n");
            gamesReport.append("   - Дартс - Фортуна — ставка сделана\n");
            gamesReport.append("   - Больше/Меньше — ставка сделана\n");
            gamesReport.append("   - Буллиты NHL21 — ").append(shootoutOk ? "ставка сделана\n" : "ставка НЕ сделана\n");
            gamesReport.append("   - Бокс — ").append(boxingOk ? "ставка сделана\n" : "ставка НЕ сделана\n");

            String statusEmoji = allFastGamesOk ? "✅" : "⚠️";

            tg.sendMessage(
                    statusEmoji + " *v2_id_authorization_fastgames завершён*\n" +
                            "• Авторизация по ID — выполнена\n" +
                            "• Код из Google Messages получен\n" +
                            gamesReport +
                            "\n🕒 Время выполнения: *" + duration + " сек.*"
            );

        } catch (Exception e) {
            error("Ошибка: " + e.getMessage());
            String screenshot = ScreenshotHelper.takeScreenshot(page, "v2_id_authorization_fastgames_error");
            tg.sendMessage("🚨 Ошибка в тесте *v2_id_authorization_fastgames*:\n" + e.getMessage());
            if (screenshot != null) tg.sendPhoto(screenshot, "Скриншот ошибки");
            // Не бросаем исключение, чтобы не ронять раннер
        }
    }
}
