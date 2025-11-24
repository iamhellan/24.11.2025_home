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

public class v2_MOBI_id_authorization_fastgames {
    static Playwright playwright;
    static Browser browser;
    static BrowserContext context;
    static Page page;
    static Properties creds = new Properties();

    // DEBUG для поиска фреймов (спам в логах отключён)
    private static final boolean DEBUG_FRAMES = false;
    private static final java.util.Set<String> DEBUG_FRAMES_LOGGED = new java.util.HashSet<>();

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

        // --- Проверяем и подгружаем сессию Google Messages отдельно ---
        Path sessionPath = resolveMessagesSessionPath();
        if (sessionPath != null) {
            try {
                BrowserContext messagesContext = browser.newContext(
                        new Browser.NewContextOptions().setStorageStatePath(sessionPath)
                );
                messagesContext.close(); // просто проверяем, что файл читается
                System.out.println("✅ Сессия Google Messages успешно загружена: " + sessionPath.toAbsolutePath());
            } catch (Exception e) {
                System.out.println("⚠️  Не удалось загрузить сохранённую сессию Google Messages. Проверь файл: " + sessionPath);
            }
        } else {
            System.out.println("⚠️ Файл сессии Google Messages не найден ни в одном из стандартных путей.");
        }
    }

    @AfterAll
    static void tearDownAll() {
        System.out.println("Тест завершён ✅ (браузер остаётся открытым)");
    }

    // ===== УТИЛИТЫ ============================================================

    private static Path resolveMessagesSessionPath() {
        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        Path[] possiblePaths = new Path[]{
                projectRoot.resolve("resources/sessions/messages-session.json"),
                projectRoot.resolve("src/test/resources/sessions/messages-session.json"),
                projectRoot.resolve("src/test/java/org/example/resources/sessions/messages-session.json")
        };
        for (Path p : possiblePaths) {
            if (p.toFile().exists()) return p;
        }
        return null;
    }

    private Frame findFrameWithSelector(Page p, String selector, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (Page pg : p.context().pages()) {
                for (Frame f : pg.frames()) {
                    try {
                        if (f.locator(selector).count() > 0) {
                            if (DEBUG_FRAMES && DEBUG_FRAMES_LOGGED.add(f.url())) {
                                System.out.println("[DEBUG] Нашли селектор в фрейме: " + f.url());
                            }
                            return f;
                        }
                    } catch (Throwable ignore) {
                    }
                }
            }
            p.waitForTimeout(300);
        }
        return null;
    }

    private Locator smartLocator(Page p, String selector, int timeoutMs) {
        Locator direct = p.locator(selector);
        if (direct.count() > 0) return direct;
        Frame f = findFrameWithSelector(p, selector, timeoutMs);
        if (f != null) return f.locator(selector);
        throw new RuntimeException("Элемент не найден: " + selector);
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
                try {
                    loc.first().click(new Locator.ClickOptions().setTimeout(2500).setForce(true));
                    return;
                } catch (RuntimeException e2) {
                    lastErr = e2;
                    try {
                        loc.first().evaluate("el => el.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true}))");
                        return;
                    } catch (RuntimeException e3) {
                        lastErr = e3;
                    }
                }
            }
            p.waitForTimeout(200);
        }
        if (lastErr != null) throw lastErr;
    }

    private void clickFirstEnabled(Page p, String selector, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Locator group;
            try {
                group = smartLocator(p, selector, 1500);
            } catch (RuntimeException e) {
                p.waitForTimeout(200);
                continue;
            }
            int count = group.count();
            for (int i = 0; i < count; i++) {
                Locator candidate = group.nth(i);
                boolean visible;
                try {
                    visible = candidate.isVisible();
                } catch (Throwable t) {
                    visible = false;
                }
                if (!visible) continue;
                boolean enabled;
                try {
                    enabled = (Boolean) candidate.evaluate("e => !(e.classList && e.classList.contains('pointer-events-none'))");
                } catch (Throwable t) {
                    enabled = true;
                }
                if (enabled) {
                    robustClick(p, candidate, 8000, selector + " [nth=" + i + "]");
                    return;
                }
            }
            p.waitForTimeout(200);
        }
        throw new RuntimeException("Не дождались активного элемента: " + selector);
    }

    private Page clickCardMaybeOpensNewTab(Locator card) {
        int before = context.pages().size();
        robustClick(page, card, 30000, "game-card");
        page.waitForTimeout(600);
        int after = context.pages().size();
        if (after > before) {
            Page newPage = context.pages().get(after - 1);
            newPage.bringToFront();
            return newPage;
        }
        return page;
    }

    private void passTutorialIfPresent(Page gamePage) {
        for (int i = 1; i <= 5; i++) {
            try {
                Locator nextBtn = smartLocator(gamePage, "div[role='button']:has-text('Далее')", 600);
                if (nextBtn.count() == 0 || !nextBtn.first().isVisible()) break;
                robustClick(gamePage, nextBtn.first(), 2000, "Далее");
                gamePage.waitForTimeout(150);
            } catch (RuntimeException ignore) {
                break;
            }
        }
        try {
            Locator understood = smartLocator(gamePage, "div[role='button']:has-text('Я всё понял')", 600);
            if (understood.count() > 0 && understood.first().isVisible()) {
                robustClick(gamePage, understood.first(), 2000, "Я всё понял");
            }
        } catch (RuntimeException ignore) {
        }
    }

    private void setStake50ViaChip(Page gamePage) {
        Locator chip50 = smartLocator(gamePage, "div.chip-text:has-text('50')", 2000);
        robustClick(gamePage, chip50.first(), 12000, "chip-50");
    }

    // старое универсальное ожидание (оставляем, но теперь используем только там, где нужно)
    private void waitRoundToSettle(Page gamePage, String debugName, int maxMs) {
        System.out.println("ℹ️  Ждём завершения раунда: " + debugName);
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

                    if (!enabled) {
                        roundStarted = true; // кнопка залочена – раунд идёт
                    } else if (roundStarted) {
                        System.out.println("✅ Раунд завершился: " + debugName);
                        return;
                    }
                }
            } catch (Throwable ignore) {
            }

            gamePage.waitForTimeout(300);
        }

        System.out.println("⚠️  Раунд не завершился за " + (maxMs / 1000) + " сек: " + debugName);
    }

    // --- умное определение "кликабельности" исхода ---
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

    // --- общее умное ожидание разблокировки исхода и клик ---
    private boolean tryBetButton(Page gamePage, String selector, int maxMs, String debugName) {
        long start = System.currentTimeMillis();
        System.out.println("ℹ️  Проверяем исход/кнопку для ставки: " + debugName);

        while (System.currentTimeMillis() - start < maxMs) {
            Locator button = null;
            try {
                button = smartLocator(gamePage, selector, 1000);
            } catch (Throwable ignore) {
            }

            if (button != null && button.count() > 0) {
                Locator btn = button.first();
                if (isClickable(btn)) {
                    System.out.println("✅ Исход '" + debugName + "' разблокирован — делаем ставку");
                    try {
                        btn.scrollIntoViewIfNeeded();
                        btn.click(new Locator.ClickOptions()
                                .setTimeout(5000)
                                .setForce(true));
                    } catch (Throwable e) {
                        System.out.println("⚠️ Обычный клик не сработал, пробуем через JS");
                        try {
                            gamePage.evaluate(
                                    "el => el.dispatchEvent(new MouseEvent('click', {bubbles:true}))",
                                    btn.elementHandle()
                            );
                        } catch (Throwable e2) {
                            System.out.println("❌ Ошибка при JS-клике: " + e2.getMessage());
                            return false;
                        }
                    }
                    gamePage.waitForTimeout(600);
                    return true;
                }
            }

            gamePage.waitForTimeout(500);
        }

        System.out.println("⚠️ Исход '" + debugName + "' так и не стал доступен за " + (maxMs / 1000) + " сек");
        return false;
    }

    // --- специализированное ожидание конца раунда по конкретному исходу (старый способ) ---
    private void waitRoundByOutcome(Page gamePage, String selector, int maxMs) {
        System.out.println("ℹ️  Ждём завершения раунда по исходу: " + selector);
        long start = System.currentTimeMillis();
        boolean seenLocked = false;

        while (System.currentTimeMillis() - start < maxMs) {
            Locator btn = null;
            try {
                btn = smartLocator(gamePage, selector, 1000);
            } catch (Throwable ignore) {
            }

            if (btn != null && btn.count() > 0) {
                boolean clickable = isClickable(btn);
                if (!clickable) {
                    seenLocked = true; // раунд идёт
                } else if (seenLocked) {
                    System.out.println("✅ Раунд завершился — исход снова доступен для ставки ✅");
                    return;
                }
            }

            gamePage.waitForTimeout(700);
        }

        System.out.println("⚠️ Не удалось отследить завершение раунда за " + (maxMs / 1000) +
                " сек (по исходу " + selector + ")");
    }

    // --- новый способ: ждём появления bet-bar второй раз (используем только для Нарды/Дартс/Фортуна/Больше-Меньше) ---
    private void waitNewRoundByBetBar(Page gamePage, String debugName, int maxMs) {
        System.out.println("ℹ️  Ждём новый раунд по bet-bar: " + debugName);
        long start = System.currentTimeMillis();

        boolean seenVisibleOnce = false;      // первое появление bet-bar
        boolean seenGoneAfterFirst = false;   // bet-bar исчез после первого появления

        boolean seenDisabledOnce = false;     // чипы хотя бы раз были в состоянии "заблокированы"

        while (System.currentTimeMillis() - start < maxMs) {
            Locator bar = null;
            boolean visible = false;

            try {
                bar = gamePage.locator(
                        "div.bet-bar-desktop, " +
                                "div.bet-bar-mobile, " +
                                "div.bet-bar"
                );
                if (bar.count() > 0 && bar.first().isVisible()) {
                    visible = true;
                }
            } catch (Throwable ignore) {
            }

            // --- старая логика: появился → исчез → снова появился ---
            if (!seenVisibleOnce) {
                if (visible) {
                    seenVisibleOnce = true;
                    System.out.println("ℹ️  Bet-bar появился первый раз: " + debugName);
                }
            } else if (!seenGoneAfterFirst) {
                if (!visible) {
                    seenGoneAfterFirst = true;
                    System.out.println("ℹ️  Bet-bar исчез после первой фазы — ждём повторное появление...");
                }
            } else {
                if (visible) {
                    System.out.println("✅ Bet-bar появился второй раз — новый раунд зафиксирован: " + debugName);
                    return;
                }
            }

            // --- новая логика: чипы были заблокированы, потом стали активными ---
            if (visible && bar != null && bar.count() > 0) {
                try {
                    Locator chips = bar.first().locator("div.bet-bar-chips");
                    if (chips.count() > 0) {
                        boolean disabled = (Boolean) chips.first().evaluate(
                                "el => {" +
                                        " const cls = el.className || '';" +
                                        " const style = window.getComputedStyle(el);" +
                                        " const byClass = cls.includes('pointer-events-none') || cls.includes('opacity-65');" +
                                        " const byStyle = style.pointerEvents === 'none';" +
                                        " return byClass || byStyle;" +
                                        "}"
                        );

                        if (disabled && !seenDisabledOnce) {
                            seenDisabledOnce = true;
                            System.out.println("ℹ️  Bet-bar: чипы заблокированы (идёт раунд): " + debugName);
                        } else if (seenDisabledOnce && !disabled) {
                            System.out.println("✅ Bet-bar: чипы снова активны — новый раунд зафиксирован: " + debugName);
                            return;
                        }
                    }
                } catch (Throwable ignore) {
                    // если не получилось прочитать состояние чипов — просто живём по старой логике
                }
            }

            gamePage.waitForTimeout(400);
        }

        System.out.println("⚠️  Не удалось зафиксировать новый раунд по bet-bar за " +
                (maxMs / 1000) + " сек: " + debugName);
    }

    // --- отдельная логика для Бокса: берём первый доступный исход ---
    private boolean tryBetFirstBoxingOutcome(Page boxingPage, int maxMs) {
        System.out.println("ℹ️  Ищем первый доступный исход в 'Бокс'");
        long start = System.currentTimeMillis();

        while (System.currentTimeMillis() - start < maxMs) {
            Locator all;

            try {
                all = smartLocator(
                        boxingPage,
                        "div.contest-panel-outcome-button, " +
                                "button.contest-panel-outcome-button, " +
                                "div[role='button'].contest-panel-outcome-button",
                        2000
                );
            } catch (Throwable ignore) {
                boxingPage.waitForTimeout(700);
                continue;
            }

            int count = all.count();
            for (int i = 0; i < count; i++) {
                Locator btn = all.nth(i);
                if (!btn.isVisible()) continue;

                if (isClickable(btn)) {
                    System.out.println("✅ Нашли доступный исход №" + (i + 1) + " — делаем ставку");
                    try {
                        btn.scrollIntoViewIfNeeded();
                        btn.click(new Locator.ClickOptions()
                                .setTimeout(5000)
                                .setForce(true));
                    } catch (Throwable e) {
                        System.out.println("⚠️ Обычный клик не сработал, пробуем через JS");
                        try {
                            boxingPage.evaluate(
                                    "el => el.dispatchEvent(new MouseEvent('click', {bubbles:true}))",
                                    btn.elementHandle()
                            );
                        } catch (Throwable e2) {
                            System.out.println("❌ Ошибка при JS-клике: " + e2.getMessage());
                            return false;
                        }
                    }
                    boxingPage.waitForTimeout(600);
                    return true;
                }
            }

            boxingPage.waitForTimeout(700);
        }

        System.out.println("⚠️ Не нашли ни одного доступного исхода в 'Бокс' за " + (maxMs / 1000) + " сек");
        return false;
    }

    private Page openGameByHrefContains(Page originPage, String hrefContains, String fallbackMenuText) {
        Frame f = findFrameWithSelector(originPage, "a[href*='" + hrefContains + "']", 5000);
        if (f == null && fallbackMenuText != null) {
            f = findFrameWithSelector(originPage, "span.text-hub-header-game-title:has-text('" + fallbackMenuText + "')", 5000);
        }
        if (f == null) throw new RuntimeException("Не нашли игру: " + hrefContains);
        Locator link = f.locator("a[href*='" + hrefContains + "']");
        link.first().scrollIntoViewIfNeeded();
        return clickCardMaybeOpensNewTab(link.first());
    }

    private Page openUniqueBoxingFromHub(Page originPage) {
        String innerSpan = "a.menu-sports-item-inner[href*='productId=boxing'] span.text-hub-header-game-title:has-text('Бокс')";
        Frame f = findFrameWithSelector(originPage, innerSpan, 8000);
        if (f == null) throw new RuntimeException("Не нашли уникальную кнопку 'Бокс'");
        Locator link = f.locator(innerSpan).first().locator("xpath=ancestor::a");
        return clickCardMaybeOpensNewTab(link.first());
    }

    // ===== ТЕСТ ===============================================================

    @Test
    void loginAndPlayFastGames() {
        long testStartTime = System.currentTimeMillis();
        String botToken = creds.getProperty("telegram.bot.token");
        String chatId = creds.getProperty("telegram.chat.id");

        String startMsg = "🚀 *Тест v2_MOBI_id_authorization_fastgames* стартовал " +
                "(авторизация через Google Messages)";
        Telegram.send(startMsg, botToken, chatId);

        boolean crashBetDone = false;
        boolean nardsBetDone = false;
        boolean dartsBetDone = false;
        boolean dartsFortuneBetDone = false;
        boolean hiloBetDone = false;
        boolean shootoutOk = false;
        boolean boxingOk = false;

        Exception globalError = null;

        try {
            // === Авторизация ===
            page.navigate("https://1xbet.kz/?platform_type=mobile");
            page.click("button#curLoginForm >> text=Войти");

            String login = creds.getProperty("login");
            String password = creds.getProperty("password");
            page.fill("input#auth_id_email", login);
            page.fill("input#auth-form-password", password);
            page.click("button.auth-button:has(span.auth-button__text:has-text('Войти'))");

            // ---- ЖДЁМ РЕШЕНИЯ КАПЧИ ----
            System.out.println("Теперь решай капчу вручную — я жду появление кнопки 'Выслать код' (до 10 минут)...");
            try {
                page.waitForSelector("button:has-text('Выслать код')",
                        new Page.WaitForSelectorOptions()
                                .setTimeout(600_000)
                                .setState(WaitForSelectorState.VISIBLE)
                );
                System.out.println("Кнопка 'Выслать код' появилась ✅");
            } catch (PlaywrightException e) {
                throw new RuntimeException("Кнопка 'Выслать код' не появилась — капча не решена или что-то пошло не так!");
            }

            // ---- ЖМЁМ "ВЫСЛАТЬ КОД" ----
            System.out.println("Жмём 'Выслать код'");
            page.click("button:has-text('Выслать код')");

            // ---- ЖДЁМ ПОЛЕ ДЛЯ ВВОДА КОДА ----
            System.out.println("Ждём поле для ввода кода (до 10 минут)...");
            page.waitForSelector("input.phone-sms-modal-code__input",
                    new Page.WaitForSelectorOptions()
                            .setTimeout(600_000)
                            .setState(WaitForSelectorState.VISIBLE)
            );
            System.out.println("Поле для ввода кода появилось ✅");

            // --- Google Messages: сессия + чтение кода ---
            Path sessionPath = resolveMessagesSessionPath();
            if (sessionPath == null) {
                throw new RuntimeException("❌ Файл сессии Google Messages не найден ни в одном из стандартных путей!");
            }
            System.out.println("📁 Используем файл сессии: " + sessionPath.toAbsolutePath());

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

            // закрываем messagesContext
            messagesContext.close();

            // --- Возврат на 1xbet и ввод кода ---
            page.bringToFront();
            page.fill("input.phone-sms-modal-code__input", code);
            page.click("button.phone-sms-modal-content__send:has-text('Подтвердить')");

            // --- Закрываем блокировку, если есть ---
            if (page.locator("a.pf-subs-btn-link__secondary:has-text('Блокировать')").isVisible()) {
                page.click("a.pf-subs-btn-link__secondary:has-text('Блокировать')");
            }

            // === Быстрые игры ===
            page.click("button.header__hamburger.hamburger");
            page.click("a.drop-menu-list__link[href*='fast-games']");

            Page gamePage = null;
            Page nardsPage = null;
            Page dartsPage = null;
            Page dartsFortunePage = null;
            Page hiloPage = null;
            Page shootoutPage = null;
            Page boxingPage = null;

            // ===== КРЭШ-БОКС (старый способ: ожидание по исходу) =====
            try {
                System.out.println("\n===== КРЭШ-БОКС =====");
                Locator crashTile = page.locator("div.tile__cell img[alt='Crash boxing']").first();
                gamePage = clickCardMaybeOpensNewTab(crashTile);
                passTutorialIfPresent(gamePage);

                clickFirstEnabled(
                        gamePage,
                        "div[role='button'][data-market='hit_met_condition'][data-outcome='yes']",
                        60_000
                );
                crashBetDone = true;

                waitRoundByOutcome(
                        gamePage,
                        "div[role='button'][data-market='hit_met_condition'][data-outcome='yes']",
                        60_000
                );
            } catch (Exception e) {
                System.out.println("⚠️ Ошибка в игре 'Крэш-Бокс': " + e.getMessage());
            }

            // ===== НАРДЫ (новый способ: bet-bar) =====
            if (gamePage != null) {
                try {
                    System.out.println("\n===== НАРДЫ =====");
                    nardsPage = openGameByHrefContains(gamePage, "nard", "Нарды");
                    passTutorialIfPresent(nardsPage);
                    setStake50ViaChip(nardsPage);
                    clickFirstEnabled(nardsPage,
                            "span[role='button'][data-market='dice'][data-outcome='blue']",
                            300_000);
                    nardsBetDone = true;
                    waitNewRoundByBetBar(nardsPage, "Нарды", 60_000);
                } catch (Exception e) {
                    System.out.println("⚠️ Ошибка в игре 'Нарды': " + e.getMessage());
                }
            }

            // ===== ДАРТС (новый способ: bet-bar) =====
            if (nardsPage != null) {
                try {
                    System.out.println("\n===== ДАРТС =====");
                    dartsPage = openGameByHrefContains(nardsPage, "darts?cid", "Дартс");
                    passTutorialIfPresent(dartsPage);
                    setStake50ViaChip(dartsPage);
                    clickFirstEnabled(dartsPage,
                            "span[role='button'][data-market='1-4-5-6-9-11-15-16-17-19']",
                            300_000);
                    dartsBetDone = true;
                    waitNewRoundByBetBar(dartsPage, "Дартс", 60_000);
                } catch (Exception e) {
                    System.out.println("⚠️ Ошибка в игре 'Дартс': " + e.getMessage());
                }
            }

            // ===== ДАРТС - ФОРТУНА (новый способ: bet-bar) =====
            if (dartsPage != null) {
                try {
                    System.out.println("\n===== ДАРТС - ФОРТУНА =====");
                    dartsFortunePage = openGameByHrefContains(dartsPage, "darts-fortune", "Дартс - Фортуна");
                    passTutorialIfPresent(dartsFortunePage);
                    setStake50ViaChip(dartsFortunePage);
                    clickFirstEnabled(dartsFortunePage,
                            "div[data-outcome='ONE_TO_EIGHT']",
                            300_000);
                    dartsFortuneBetDone = true;
                    waitNewRoundByBetBar(dartsFortunePage, "Дартс - Фортуна", 60_000);
                } catch (Exception e) {
                    System.out.println("⚠️ Ошибка в игре 'Дартс - Фортуна': " + e.getMessage());
                }
            }

            // ===== БОЛЬШЕ / МЕНЬШЕ (новый способ: bet-bar) =====
            if (dartsFortunePage != null) {
                try {
                    System.out.println("\n===== БОЛЬШЕ / МЕНЬШЕ =====");
                    hiloPage = openGameByHrefContains(dartsFortunePage, "darts-hilo", "Больше/Меньше");
                    passTutorialIfPresent(hiloPage);
                    setStake50ViaChip(hiloPage);
                    clickFirstEnabled(hiloPage,
                            "div[role='button'][data-market][data-outcome]:has-text('Больше')",
                            300_000);
                    hiloBetDone = true;
                    waitNewRoundByBetBar(hiloPage, "Больше/Меньше", 60_000);
                } catch (Exception e) {
                    System.out.println("⚠️ Ошибка в игре 'Больше/Меньше': " + e.getMessage());
                }
            }

            // ===== БУЛЛИТЫ NHL21 (через bet-bar) =====
            if (hiloPage != null) {
                try {
                    System.out.println("\n===== БУЛЛИТЫ NHL21 =====");
                    shootoutPage = openGameByHrefContains(hiloPage, "shootout", "Буллиты NHL21");
                    passTutorialIfPresent(shootoutPage);
                    setStake50ViaChip(shootoutPage);

                    boolean shootoutBetDone = tryBetButton(
                            shootoutPage,
                            "div[role='button'].market-button:has-text('Да')",
                            300000,
                            "Буллиты NHL21: Да"
                    );
                    if (shootoutBetDone) {
                        shootoutOk = true;
                        waitNewRoundByBetBar(shootoutPage, "Буллиты NHL21", 180000);
                    }
                } catch (Exception e) {
                    System.out.println("⚠️ Ошибка в игре 'Буллиты NHL21': " + e.getMessage());
                }
            }

            // ===== БОКС (ставка + ожидание нового раунда по bet-bar) =====
            if (shootoutPage != null) {
                try {
                    System.out.println("\n===== БОКС =====");
                    boxingPage = openUniqueBoxingFromHub(shootoutPage);
                    passTutorialIfPresent(boxingPage);
                    setStake50ViaChip(boxingPage);

                    boolean boxingBetDone = tryBetFirstBoxingOutcome(boxingPage, 300_000);
                    boxingOk = boxingBetDone;

                    if (boxingBetDone) {
                        // ждём новый раунд по bet-bar
                        waitNewRoundByBetBar(
                                boxingPage,
                                "Бокс",
                                300_000
                        );
                    } else {
                        System.out.println("⚠️ Не удалось сделать ставку в 'Бокс' — ни один исход так и не стал доступным.");
                    }
                } catch (Exception e) {
                    System.out.println("⚠️ Ошибка в игре 'Бокс': " + e.getMessage());
                }
            }

            System.out.println("\nВсе игры обработаны (насколько получилось) ✅");

        } catch (Exception e) {
            globalError = e;
            System.out.println("❌ Общая ошибка в тесте: " + e.getMessage());
        }

        // --- Итоговый отчёт в Telegram ---
        long duration = (System.currentTimeMillis() - testStartTime) / 1000;

        boolean allFastGamesOk =
                crashBetDone &&
                        nardsBetDone &&
                        dartsBetDone &&
                        dartsFortuneBetDone &&
                        hiloBetDone &&
                        shootoutOk &&
                        boxingOk;

        StringBuilder gamesReport = new StringBuilder();
        gamesReport.append("• Быстрые игры:\n");
        gamesReport.append("   - Крэш-Бокс — ").append(crashBetDone ? "ставка сделана\n" : "ставка НЕ сделана\n");
        gamesReport.append("   - Нарды — ").append(nardsBetDone ? "ставка сделана\n" : "ставка НЕ сделана\n");
        gamesReport.append("   - Дартс — ").append(dartsBetDone ? "ставка сделана\n" : "ставка НЕ сделана\n");
        gamesReport.append("   - Дартс - Фортуна — ").append(dartsFortuneBetDone ? "ставка сделана\n" : "ставка НЕ сделана\n");
        gamesReport.append("   - Больше/Меньше — ").append(hiloBetDone ? "ставка сделана\n" : "ставка НЕ сделана\n");
        gamesReport.append("   - Буллиты NHL21 — ").append(shootoutOk ? "ставка сделана\n" : "ставка НЕ сделана\n");
        gamesReport.append("   - Бокс — ").append(boxingOk ? "ставка сделана\n" : "ставка НЕ сделана\n");

        String statusEmoji = (globalError == null && allFastGamesOk) ? "✅" : "⚠️";

        String summary =
                statusEmoji + " *v2_MOBI_id_authorization_fastgames завершён*\n" +
                        "• Авторизация через Google Messages — " + (globalError == null ? "выполнена" : "с ошибками") + "\n" +
                        "• Код из Google Messages " + ((globalError == null) ? "получен или попытка была" : "мог быть не получен из-за ошибки") + "\n" +
                        gamesReport +
                        "\n🕒 Время выполнения: *" + duration + " сек.*\n" +
                        "🌐 Сайт: [1xbet.kz (mobile)](https://1xbet.kz/?platform_type=mobile)\n" +
                        "_Браузер остаётся открытым для ручной проверки._";

        System.out.println(summary);
        Telegram.send(summary, botToken, chatId);

        if (globalError != null) {
            String errorMsg = "🚨 Ошибка в тесте *v2_MOBI_id_authorization_fastgames*:\n" + globalError.getMessage();
            Telegram.send(errorMsg, botToken, chatId);
        }
    }

    // --- Telegram Helper ---
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
                System.out.println("⚠️ Ошибка при отправке в Telegram: " + e.getMessage());
            }
        }
    }
}
